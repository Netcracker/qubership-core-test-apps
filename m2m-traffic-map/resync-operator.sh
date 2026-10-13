#!/usr/bin/env bash
# Makes core-operator reconcile its resources again, so that it calls DBaaS and MaaS: it skips a
# resource whose status.observedGeneration is the generation of the resource, and clears the steps it
# has done and starts over when they differ.
# usage: resync-operator.sh   (in the namespace core)
NS="${1:-core}"
for kind in composites.core.netcracker.com dbaases.core.netcracker.com maases.core.netcracker.com; do
  for name in $(kubectl -n "$NS" get "$kind" -o name 2>/dev/null); do
    kubectl -n "$NS" patch "$name" --subresource=status --type=merge -p '{"status":{"observedGeneration":0}}' >/dev/null &&
      echo "reconcile again: $name"
  done
done
