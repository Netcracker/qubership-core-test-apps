#!/usr/bin/env bash
# Records, until it is stopped, what the map is built from:
#
#   <out>/pod-ips.txt              "<address> <pod name>" of every pod, every two seconds. The hooks are
#                                  deleted when they succeed, and their addresses are needed to name
#                                  the senders of what the access log shows without an identity.
#   <out>/waypoint/<ns>.<pod>.<n>  the log of each waypoint pod, container start n, followed from its
#                                  start. A waypoint pod can be replaced (core-mesh-config changes its
#                                  Gateway, the autoscaler scales it) or restarted, and a log read at
#                                  the end would not have what such a pod wrote.
#
# Each log is read with `kubectl logs -f`, which prints the log from the start of the container and
# follows it until the container stops. One that stopped before it wrote anything (the container was
# not ready to be read) is started again; one that wrote something is not, so that no line is written
# twice, and so that a pod that is gone does not leave an empty file in place of its log.
#
# usage: record-traffic.sh OUT_DIR NAMESPACE...   (in the background; stop it with SIGTERM)
set -uo pipefail

out="$1"
shift
mkdir -p "$out/waypoint"

declare -A followers   # file -> pid of the kubectl logs that writes it

stop() {
  for pid in "${followers[@]}"; do
    kill "$pid" 2>/dev/null
  done
  wait
  exit 0
}
trap stop TERM INT

while true; do
  kubectl get pods -A -o jsonpath='{range .items[*]}{.status.podIP}{" "}{.metadata.name}{"\n"}{end}' \
    >> "$out/pod-ips.txt" 2>/dev/null

  for ns in "$@"; do
    # name, restarts, and the start of the container when it runs
    while read -r pod restarts started; do
      [ -n "$started" ] || continue
      file="$out/waypoint/$ns.$pod.$restarts"
      pid="${followers[$file]:-}"
      if [ -n "$pid" ] && { kill -0 "$pid" 2>/dev/null || [ -s "$file" ]; }; then
        continue
      fi
      kubectl -n "$ns" logs -f "$pod" -c istio-proxy > "$file" 2>/dev/null &
      followers[$file]=$!
    done < <(kubectl -n "$ns" get pods -l gateway.networking.k8s.io/gateway-name \
      -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[0].restartCount}{" "}{.status.containerStatuses[0].state.running.startedAt}{"\n"}{end}' 2>/dev/null)
  done

  sleep 2
done
