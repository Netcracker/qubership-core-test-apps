#!/bin/bash

# Installs the failover test applications into a namespace, mirroring mesh-test-apps.sh.
# Usage: ./storage-test-apps.sh [install|uninstall] [--namespace NS] [--spring-tag TAG]

set -e

NAMESPACE="core"
SPRING_TAG="latest"
DBAAS_AGENT_URL="http://dbaas-agent:8080"
# Empty means the scheduler places the pod. Set it when the drain scenario needs the application
# on a known node.
NODE_NAME=""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SPRING_ROOT="$PROJECT_ROOT/storage-test-service-spring"
SPRING_CHART="$SPRING_ROOT/helm-templates/storage-test-service-spring"

show_usage() {
    echo "Usage: $0 [install|uninstall] [options]"
    echo ""
    echo "Options:"
    echo "  --namespace NS       Target namespace (default: core)"
    echo "  --spring-tag TAG     Image tag for storage-test-service-spring (default: latest)"
    echo "  --dbaas-agent URL    DBaaS agent address (default: http://dbaas-agent:8080)"
    echo "  --node-name NODE     Pin the application to a node, for the drain scenario"
    echo ""
    echo "Examples:"
    echo "  $0 install"
    echo "  $0 install --namespace core --spring-tag pr-123"
    echo "  $0 uninstall"
}

ACTION="${1:-install}"
if [[ "$ACTION" == "-h" || "$ACTION" == "--help" ]]; then
    show_usage
    exit 0
fi
shift || true

while [[ $# -gt 0 ]]; do
    case $1 in
        --namespace)   NAMESPACE="$2"; shift 2 ;;
        --spring-tag)  SPRING_TAG="$2"; shift 2 ;;
        --dbaas-agent) DBAAS_AGENT_URL="$2"; shift 2 ;;
        --node-name)   NODE_NAME="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; echo ""; show_usage; exit 1 ;;
    esac
done

check_helm() {
    if ! command -v helm &> /dev/null; then
        echo "Error: helm is not installed"
        exit 1
    fi
    echo "Helm version: $(helm version --short)"
}

install_helm_package() {
    local service_name=$1
    local chart_path=$2
    local tag=$3

    echo ""
    echo "===================================="
    echo "Installing $service_name..."
    echo "===================================="

    if [ ! -d "$chart_path" ]; then
        echo "Error: Chart directory $chart_path does not exist"
        exit 1
    fi

    # Pulls the coretpl library chart the templates include; without it the chart does not render.
    echo "Updating helm dependencies for $service_name..."
    helm dependency update "$chart_path"

    echo "Installing/upgrading $service_name with tag: $tag..."
    helm upgrade --install "$service_name" "$chart_path" \
        --namespace "$NAMESPACE" \
        --set TAG="$tag" \
        --set DBAAS_AGENT_URL="$DBAAS_AGENT_URL" \
        --set NODE_NAME="$NODE_NAME" \
        --wait \
        --timeout=300s

    echo "✅ $service_name installed"
}

uninstall_helm_package() {
    local service_name=$1
    if helm list -n "$NAMESPACE" | grep -q "$service_name"; then
        echo "Uninstalling $service_name..."
        helm uninstall "$service_name" --namespace "$NAMESPACE" --wait --timeout=300s
    else
        echo "$service_name is not installed, nothing to do"
    fi
}

case "$ACTION" in
    install)
        check_helm
        install_helm_package "storage-test-service-spring" "$SPRING_CHART" "$SPRING_TAG"
        echo ""
        echo "Installed into namespace $NAMESPACE. Check with:"
        echo "  helm list -n $NAMESPACE"
        ;;
    uninstall)
        check_helm
        uninstall_helm_package "storage-test-service-spring"
        ;;
    *)
        echo "Unknown action: $ACTION"
        echo ""
        show_usage
        exit 1
        ;;
esac
