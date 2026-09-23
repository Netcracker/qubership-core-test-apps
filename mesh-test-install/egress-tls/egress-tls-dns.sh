#!/bin/bash

# CoreDNS rewrites for the egress TLS test host names.
#
# The egress TLS tests need external-looking host names that resolve inside the
# cluster. *.external.test is reserved by RFC 6761 and never resolves publicly,
# so CoreDNS is given a rewrite mapping each name onto the in-cluster nginx that
# simulates the external site. Both mesh types then reach the same host names:
# Cloud-Core Mesh resolves them in the egress gateway's Envoy, Istio in the
# ServiceEntry's DNS resolution.
#
# Called by mesh-test-apps.sh around install and uninstall; run it directly to
# add or drop the rewrites without touching the Helm releases.
#
# Usage: ./egress-tls-dns.sh <operation> <namespace>
# Operation: install/uninstall, Namespace is the one the test apps run in.

set -e

EGRESS_TLS_HOSTS=(verified.external.test insecure.external.test mtls.external.test gwdefault.external.test implicit.external.test)
COREDNS_MARKER="# BEGIN qubership-core-test-apps egress-tls"
COREDNS_END_MARKER="# END qubership-core-test-apps egress-tls"

# Function to show usage
show_usage() {
    echo "Usage: $0 <operation> <namespace>"
    echo ""
    echo "Arguments:"
    echo "  operation       Operation to perform: 'install' or 'uninstall' (required)"
    echo "  namespace       Namespace the egress-tls-echo Service runs in (required)"
    echo ""
    echo "Examples:"
    echo "  $0 install mesh-test      # Resolve ${EGRESS_TLS_HOSTS[0]} and friends to egress-tls-echo"
    echo "  $0 uninstall mesh-test    # Drop the rewrites again"
    echo ""
}

# Function to strip the marked block out of the Corefile given as $1
remove_egress_tls_rewrites() {
    awk -v begin="$COREDNS_MARKER" -v end="$COREDNS_END_MARKER" '
        index($0, begin) { skip = 1 }
        !skip { print }
        index($0, end) { skip = 0 }
    ' <<< "$1"
}

# Function to write a Corefile back and restart CoreDNS
apply_corefile() {
    local patch
    patch=$(mktemp)
    # A merge patch rather than a recreated ConfigMap, so that the labels and any
    # other keys CoreDNS carries survive.
    {
        echo "data:"
        echo "  Corefile: |"
        printf '%s\n' "$1" | sed 's/^/    /'
    } > "$patch"
    kubectl -n kube-system patch configmap coredns --type merge --patch-file "$patch"
    rm -f "$patch"
    kubectl -n kube-system rollout restart deployment coredns
    kubectl -n kube-system rollout status deployment coredns --timeout=120s
}

# Function to add the *.external.test rewrites to the CoreDNS Corefile
install_egress_tls_dns() {
    echo ""
    echo "===================================="
    echo "Configuring CoreDNS for egress TLS test hosts..."
    echo "===================================="

    local corefile
    corefile=$(kubectl -n kube-system get configmap coredns -o jsonpath='{.data.Corefile}')
    if [[ -z "$corefile" ]]; then
        echo "❌ Could not read the coredns ConfigMap in kube-system"
        exit 1
    fi

    if [[ "$corefile" == *"$COREDNS_MARKER"* ]]; then
        echo "Rewrites already present, refreshing them..."
        corefile=$(remove_egress_tls_rewrites "$corefile")
    fi

    local rewrites="        $COREDNS_MARKER"
    local host
    for host in "${EGRESS_TLS_HOSTS[@]}"; do
        rewrites+=$'\n'"        rewrite name $host egress-tls-echo.$NAMESPACE.svc.cluster.local"
    done
    rewrites+=$'\n'"        $COREDNS_END_MARKER"

    # Inserted after "ready", which the default Corefile of every supported
    # distribution carries inside the server block.
    local patched
    patched=$(awk -v block="$rewrites" '
        { print }
        !done && $1 == "ready" { print block; done = 1 }
    ' <<< "$corefile")

    if [[ "$patched" != *"$COREDNS_MARKER"* ]]; then
        echo "❌ Found no 'ready' directive in the Corefile; add the rewrites manually:"
        echo "$rewrites"
        exit 1
    fi

    apply_corefile "$patched"
    echo "✅ CoreDNS now resolves ${EGRESS_TLS_HOSTS[*]} to egress-tls-echo.$NAMESPACE"
}

# Function to drop the *.external.test rewrites from the CoreDNS Corefile
uninstall_egress_tls_dns() {
    echo ""
    echo "===================================="
    echo "Removing egress TLS test hosts from CoreDNS..."
    echo "===================================="

    local corefile
    corefile=$(kubectl -n kube-system get configmap coredns -o jsonpath='{.data.Corefile}' 2>/dev/null || true)
    if [[ "$corefile" != *"$COREDNS_MARKER"* ]]; then
        echo "⚠️  No egress TLS rewrites found, skipping..."
        return 0
    fi

    apply_corefile "$(remove_egress_tls_rewrites "$corefile")"
    echo "✅ CoreDNS rewrites removed"
}

# Show help if requested
if [[ "$1" == "-h" || "$1" == "--help" ]]; then
    show_usage
    exit 0
fi

# Check if operation argument is provided
if [[ -z "$1" ]]; then
    echo "Error: Operation argument is required!"
    echo ""
    show_usage
    exit 1
fi

# Check if namespace argument is provided
if [[ -z "$2" ]]; then
    echo "Error: Namespace argument is required!"
    echo ""
    show_usage
    exit 1
fi

# Validate operation
OPERATION="$1"
if [[ "$OPERATION" != "install" && "$OPERATION" != "uninstall" ]]; then
    echo "Error: Operation must be 'install' or 'uninstall', got: $OPERATION"
    echo ""
    show_usage
    exit 1
fi

NAMESPACE="$2"

# Main function
main() {
    if [[ "$OPERATION" == "install" ]]; then
        install_egress_tls_dns
    else
        uninstall_egress_tls_dns
    fi
}

# Run main function
main "$@"
