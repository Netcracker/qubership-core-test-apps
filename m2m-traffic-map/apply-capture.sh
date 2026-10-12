#!/usr/bin/env bash
# Puts the capture into a cluster where Cloud Core runs in the Istio ambient mesh, with DBaaS and
# MaaS installed:
#
#   1. the recorder, which the waypoints mirror requests to;
#   2. a waypoint in the DBaaS and MaaS namespaces, which are not part of the mesh, so that the calls
#      to dbaas-aggregator and maas-service pass a waypoint as the calls inside the core namespace do;
#   3. an access log on every waypoint: caller, destination, request and answer of each request;
#   4. a route per service port that mirrors the requests of the service to the recorder.
#
# The access log is complete, the mirror holds the credentials. They share the x-request-id.
#
# usage: apply-capture.sh   (kubectl and jq on the path, a context for the cluster)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_NS="${CORE_NS:-core}"
DBAAS_NS="${DBAAS_NS:-dbaas}"
MAAS_NS="${MAAS_NS:-maas}"
CORE_WAYPOINT="${CORE_WAYPOINT:-waypoint}"
CAPTURE_WAYPOINT=capture-waypoint

echo "::group::Recorder"
kubectl apply -f "$HERE/recorder.yaml"
kubectl -n capture create configmap traffic-recorder-script \
  --from-file=recorder.py="$HERE/recorder.py" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n capture rollout status deploy/traffic-recorder --timeout=180s
echo "::endgroup::"

echo "::group::Waypoints for $DBAAS_NS and $MAAS_NS"
for ns in "$DBAAS_NS" "$MAAS_NS"; do
  kubectl label namespace "$ns" istio.io/dataplane-mode=ambient --overwrite
  kubectl apply -f - <<EOF
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: $CAPTURE_WAYPOINT
  namespace: $ns
  labels:
    istio.io/waypoint-for: service
spec:
  gatewayClassName: istio-waypoint
  listeners:
    - name: mesh
      port: 15008
      protocol: HBONE
EOF
done
for ns in "$DBAAS_NS" "$MAAS_NS"; do
  # gateways.core.netcracker.com is a resource of the core with the same short name
  kubectl -n "$ns" wait --for=condition=Programmed "gateway.gateway.networking.k8s.io/$CAPTURE_WAYPOINT" --timeout=180s
done
echo "::endgroup::"

# the access log of a waypoint, one JSON line per request. The caller is the identity in the
# certificate of the peer; the address is there for what carries no identity.
access_log_filter() { # $1 namespace, $2 gateway
  cat <<EOF
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: capture-access-log
  namespace: $1
spec:
  targetRefs:
    - kind: Gateway
      group: gateway.networking.k8s.io
      name: $2
  configPatches:
    - applyTo: NETWORK_FILTER
      match:
        listener:
          filterChain:
            filter:
              name: envoy.filters.network.http_connection_manager
      patch:
        operation: MERGE
        value:
          typed_config:
            '@type': type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
            access_log:
              - name: envoy.access_loggers.file
                typed_config:
                  '@type': type.googleapis.com/envoy.extensions.access_loggers.file.v3.FileAccessLog
                  path: /dev/stdout
                  log_format:
                    json_format:
                      kind: capture
                      start: "%START_TIME%"
                      id: "%REQ(X-REQUEST-ID)%"
                      src_san: "%DOWNSTREAM_PEER_URI_SAN%"
                      src_addr: "%DOWNSTREAM_REMOTE_ADDRESS%"
                      src_direct: "%DOWNSTREAM_DIRECT_REMOTE_ADDRESS%"
                      authority: "%REQ(:AUTHORITY)%"
                      method: "%REQ(:METHOD)%"
                      path: "%REQ(X-ENVOY-ORIGINAL-PATH?:PATH)%"
                      code: "%RESPONSE_CODE%"
                      flags: "%RESPONSE_FLAGS%"
                      auth: "%REQ(AUTHORIZATION):12%"
                      upstream: "%UPSTREAM_HOST%"
                      route: "%ROUTE_NAME%"
                      duration: "%DURATION%"
EOF
}

echo "::group::Access logs"
{
  access_log_filter "$CORE_NS" "$CORE_WAYPOINT"
  echo "---"
  access_log_filter "$DBAAS_NS" "$CAPTURE_WAYPOINT"
  echo "---"
  access_log_filter "$MAAS_NS" "$CAPTURE_WAYPOINT"
} | kubectl apply -f -
echo "::endgroup::"

# a waypoint treats a port as HTTP when the service says so, and the services of the core name
# their port "web" and say nothing: every port that does not look like another protocol is marked
echo "::group::Services"
mark_http() { # $1 namespace
  kubectl -n "$1" get svc -o json | jq -r '
    .items[]
    | select(.spec.clusterIP != "None" and (.spec.selector // null) != null)
    | select((.metadata.labels["gateway.networking.k8s.io/gateway-name"] // null) == null)
    | . as $svc
    | [.spec.ports | to_entries[]
       | select((.value.appProtocol // null) == null
                and (((.value.name // "") | test("^(tcp|udp|grpc|tls|https|mongo|redis|mysql|postgres)")) | not))]
    | select(length > 0)
    | "\($svc.metadata.name) " + (map(.key | tostring) | join(","))' |
  while read -r name indexes; do
    patch="["
    for i in ${indexes//,/ }; do
      patch+="{\"op\":\"add\",\"path\":\"/spec/ports/$i/appProtocol\",\"value\":\"http\"},"
    done
    patch="${patch%,}]"
    kubectl -n "$1" patch svc "$name" --type=json -p "$patch" >/dev/null && echo "$1/$name: appProtocol http"
  done
}
for ns in "$CORE_NS" "$DBAAS_NS" "$MAAS_NS"; do mark_http "$ns"; done

# the services of DBaaS and MaaS go through the waypoint made above
DBAAS_SERVICE="${DBAAS_SERVICE_NAME:-dbaas-aggregator}"
kubectl -n "$DBAAS_NS" label svc "$DBAAS_SERVICE" istio.io/use-waypoint=$CAPTURE_WAYPOINT --overwrite
kubectl -n "$MAAS_NS" label svc maas-service istio.io/use-waypoint=$CAPTURE_WAYPOINT --overwrite
echo "::endgroup::"

echo "::group::Mirror routes"
mirror_routes() { # $1 namespace
  kubectl -n "$1" get svc -o json | jq -r --arg ns "$1" '
    .items[]
    | select(.spec.clusterIP != "None" and (.spec.selector // null) != null)
    | select((.metadata.labels["gateway.networking.k8s.io/gateway-name"] // null) == null)
    | select((.metadata.labels["istio.io/use-waypoint"] // "") != "none")
    | . as $svc
    | .spec.ports[]
    | select((.appProtocol // "") | test("^http"))
    | "\($svc.metadata.name) \(.port)"' |
  while read -r name port; do
    cat <<EOF
---
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: capture-$name-$port
  namespace: $1
  labels:
    app.kubernetes.io/part-of: m2m-traffic-map
spec:
  parentRefs:
    - group: ""
      kind: Service
      name: $name
      port: $port
  rules:
    - backendRefs:
        - name: $name
          port: $port
      filters:
        - type: RequestMirror
          requestMirror:
            backendRef:
              name: traffic-recorder
              namespace: capture
              port: 8080
EOF
    echo "$1/$name:$port" >&2
  done
}
{ mirror_routes "$CORE_NS"; mirror_routes "$DBAAS_NS"; mirror_routes "$MAAS_NS"; } | kubectl apply -f -
echo "::endgroup::"

# the proxies take the new configuration within seconds
sleep 20

# The routes above mirror the traffic of the services they are made for. A route that other
# configuration brings, such as the routes control-plane makes for its services, comes before them
# and is not mirrored. The recorder is added to every route of the waypoints, under the name its
# cluster has in the proxy, which the routes above have made it create.
recorder_cluster() { # $1 namespace, $2 gateway: the name of the cluster of the recorder in the waypoint
  local pod
  pod="$(kubectl -n "$1" get pod -l "gateway.networking.k8s.io/gateway-name=$2" -o jsonpath='{.items[0].metadata.name}')"
  kubectl -n "$1" exec "$pod" -c istio-proxy -- pilot-agent request GET clusters 2>/dev/null |
    awk -F'::' '/traffic-recorder/ { print $1 }' | sort -u | head -1
}

mirror_all_filter() { # $1 namespace, $2 gateway, $3 cluster
  cat <<EOF
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: capture-mirror-all
  namespace: $1
spec:
  targetRefs:
    - kind: Gateway
      group: gateway.networking.k8s.io
      name: $2
  configPatches:
    - applyTo: HTTP_ROUTE
      match:
        routeConfiguration:
          vhost:
            route:
              action: ROUTE
      patch:
        operation: MERGE
        value:
          route:
            request_mirror_policies:
              - cluster: "$3"
EOF
}

echo "::group::Mirror of every route"
for target in "$CORE_NS:$CORE_WAYPOINT" "$DBAAS_NS:$CAPTURE_WAYPOINT" "$MAAS_NS:$CAPTURE_WAYPOINT"; do
  ns="${target%%:*}"; gw="${target##*:}"
  cluster="$(recorder_cluster "$ns" "$gw")"
  if [ -z "$cluster" ]; then
    echo "::warning::The waypoint $ns/$gw has no cluster for the recorder, so only the routes made above are mirrored"
    continue
  fi
  echo "$ns/$gw mirrors every route to the cluster $cluster"
  mirror_all_filter "$ns" "$gw" "$cluster" | kubectl apply -f -
done
echo "::endgroup::"
sleep 15
kubectl get httproute.gateway.networking.k8s.io,envoyfilter.networking.istio.io,gateway.gateway.networking.k8s.io -A -o wide || true
