#!/usr/bin/env bash
# Puts the access log of capture-access-log.yaml on the waypoints of the core, DBaaS and MaaS
# namespaces, between `make install-dependencies` and `make deploy-core`, so that what the services
# and the hooks do while Cloud Core installs is seen.
#
# The bootstrap makes the waypoints: MESH_DEPENDENCY_NAMESPACES=dbaas,maas puts DBaaS and MaaS in the
# mesh behind one at the end of install-dependencies, and CORE_MESH_EARLY=true puts the core
# namespace behind the waypoint of core-mesh-config first thing in deploy-core. The filter targets
# the Gateway by name, so it takes effect on the core waypoint when that is created.
#
# usage: apply-capture.sh   (kubectl on the path, a context for the cluster)
set -euo pipefail

CORE_NS="${CORE_NS:-core}"
DBAAS_NS="${DBAAS_NS:-dbaas}"
MAAS_NS="${MAAS_NS:-maas}"
manifest="$(dirname "$0")/capture-access-log.yaml"

# the core namespace does not exist yet; the bootstrap takes one that does
kubectl create namespace "$CORE_NS" --dry-run=client -o yaml | kubectl apply -f -
for ns in "$CORE_NS" "$DBAAS_NS" "$MAAS_NS"; do
  kubectl -n "$ns" apply -f "$manifest"
done
kubectl get envoyfilter.networking.istio.io,gateway.gateway.networking.k8s.io -A -o wide || true
