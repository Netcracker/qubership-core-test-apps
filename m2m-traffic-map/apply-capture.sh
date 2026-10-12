#!/usr/bin/env bash
# Puts the capture into a cluster where Cloud Core runs in the Istio ambient mesh, with DBaaS and
# MaaS installed:
#
#   1. a waypoint in the DBaaS and MaaS namespaces, which are not part of the mesh, so that the calls
#      to dbaas-aggregator and maas-service pass a waypoint as the calls inside the core namespace do;
#   2. an access log on every waypoint, one JSON line per request: the caller, the destination, the
#      request, the answer and the credential (see below).
#
# usage: apply-capture.sh   (kubectl and jq on the path, a context for the cluster)
set -euo pipefail

CORE_NS="${CORE_NS:-core}"
DBAAS_NS="${DBAAS_NS:-dbaas}"
MAAS_NS="${MAAS_NS:-maas}"
CORE_WAYPOINT="${CORE_WAYPOINT:-waypoint}"
CAPTURE_WAYPOINT=capture-waypoint

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

# The access log of a waypoint, one JSON line per request. The caller is the identity in the
# certificate of the peer; the address is there for what carries no identity.
#
# The credential is not logged as it is. A small filter puts what identifies it into headers of the
# request, which the log writes:
#   x-capture-jwt   the header and the claims of a bearer token, without its signature, which a token
#                   cannot be used without
#   x-capture-user  the user name of a Basic credential, not the password
# Anything else of the Authorization header is the scheme, which is all the log keeps of it.
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
    - applyTo: HTTP_FILTER
      match:
        listener:
          filterChain:
            filter:
              name: envoy.filters.network.http_connection_manager
              subFilter:
                name: envoy.filters.http.router
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.lua
          typed_config:
            '@type': type.googleapis.com/envoy.extensions.filters.http.lua.v3.Lua
            default_source_code:
              inline_string: |
                local alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

                -- the first characters of a base64 text; Lua here has no decoder of its own
                local function decode(text)
                  local bits = ""
                  for i = 1, #text do
                    local at = string.find(alphabet, string.sub(text, i, i), 1, true)
                    if at == nil then
                      break
                    end
                    local value = at - 1
                    for bit = 5, 0, -1 do
                      bits = bits .. (math.floor(value / 2 ^ bit) % 2 == 1 and "1" or "0")
                    end
                  end
                  local decoded = ""
                  for i = 1, #bits - 7, 8 do
                    local byte = 0
                    for bit = 0, 7 do
                      byte = byte * 2 + (string.sub(bits, i + bit, i + bit) == "1" and 1 or 0)
                    end
                    decoded = decoded .. string.char(byte)
                  end
                  return decoded
                end

                function envoy_on_request(request_handle)
                  local headers = request_handle:headers()
                  headers:remove("x-capture-jwt")
                  headers:remove("x-capture-user")
                  local auth = headers:get("authorization")
                  if auth == nil then
                    return
                  end
                  local scheme, rest = string.match(auth, "^(%a+)%s+(.*)$")
                  if scheme == nil then
                    return
                  end
                  scheme = string.lower(scheme)
                  if scheme == "bearer" then
                    local jose, claims = string.match(rest, "^([%w%-_]+)%.([%w%-_]+)%.")
                    if jose ~= nil then
                      headers:add("x-capture-jwt", jose .. "." .. claims)
                    end
                  elseif scheme == "basic" then
                    -- only the first characters are decoded, and only what comes before the colon is kept
                    local user = string.match(decode(string.sub(rest, 1, 96)), "^([^:]+)")
                    if user ~= nil then
                      headers:add("x-capture-user", user)
                    end
                  end
                end
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
                      auth: "%REQ(AUTHORIZATION):6%"
                      jwt: "%REQ(X-CAPTURE-JWT)%"
                      user: "%REQ(X-CAPTURE-USER)%"
                      agent: "%REQ(USER-AGENT)%"
                      bytes: "%BYTES_RECEIVED%"
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

# the services of DBaaS and MaaS go through the waypoint made above
echo "::group::Services"
DBAAS_SERVICE="${DBAAS_SERVICE_NAME:-dbaas-aggregator}"
kubectl -n "$DBAAS_NS" label svc "$DBAAS_SERVICE" istio.io/use-waypoint=$CAPTURE_WAYPOINT --overwrite
kubectl -n "$MAAS_NS" label svc maas-service istio.io/use-waypoint=$CAPTURE_WAYPOINT --overwrite
echo "::endgroup::"

# the proxies take the new configuration within seconds
sleep 20
kubectl get envoyfilter.networking.istio.io,gateway.gateway.networking.k8s.io -A -o wide || true
