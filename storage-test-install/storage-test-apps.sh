#!/bin/bash

# Installs the storage test applications into a namespace.
#
# Plain manifests rather than Helm: what these applications need is a deployment, a service and a
# service account, they are reached by port-forward rather than through the gateway, and a chart
# would pull a library chart over the network on every run.
#
# Usage: ./storage-test-apps.sh [install|uninstall] [--namespace NS] [--apps LIST] [--tag TAG]

set -e

NAMESPACE="core"
APPS="spring,go"
TAG="latest"
SPRING_TAG=""
GO_TAG=""
MAAS_AGENT_URL="http://maas-agent:8080"
LOCAL_IMAGES="false"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$SCRIPT_DIR/manifests/storage-test-app.yaml"

show_usage() {
    echo "Usage: $0 [install|uninstall] [options]"
    echo ""
    echo "Options:"
    echo "  --namespace NS       Target namespace (default: core)"
    echo "  --apps LIST          Comma-separated platforms to install (default: spring,go)"
    echo "  --tag TAG            Image tag for every application (default: latest)"
    echo "  --spring-tag TAG     Image tag for storage-test-service-spring, overrides --tag"
    echo "  --go-tag TAG         Image tag for storage-test-service-go, overrides --tag"
    echo "  --maas-agent URL     MaaS agent address (default: http://maas-agent:8080)"
    echo "  --local-images       Images were loaded into the cluster, do not pull a registry"
    echo ""
    echo "Examples:"
    echo "  $0 install"
    echo "  $0 install --namespace core --tag feat-x-snapshot"
    echo "  $0 install --apps go --go-tag pr-123"
    echo "  $0 install --tag it --local-images"
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
        --namespace)    NAMESPACE="$2"; shift 2 ;;
        --apps)         APPS="$2"; shift 2 ;;
        --tag)          TAG="$2"; shift 2 ;;
        --spring-tag)   SPRING_TAG="$2"; shift 2 ;;
        --go-tag)       GO_TAG="$2"; shift 2 ;;
        --maas-agent)   MAAS_AGENT_URL="$2"; shift 2 ;;
        --local-images) LOCAL_IMAGES="true"; shift ;;
        *) echo "Unknown option: $1"; echo ""; show_usage; exit 1 ;;
    esac
done

service_name_of() {
    echo "storage-test-service-$1"
}

tag_of() {
    case "$1" in
        spring) echo "${SPRING_TAG:-$TAG}" ;;
        go)     echo "${GO_TAG:-$TAG}" ;;
        *)      echo "$TAG" ;;
    esac
}

# A loaded image is referenced by its bare name; a published one lives in the registry.
image_of() {
    local service_name
    service_name="$(service_name_of "$1")"
    if [ "$LOCAL_IMAGES" = "true" ]; then
        echo "$service_name:$(tag_of "$1")"
    else
        echo "ghcr.io/netcracker/qubership-core-$service_name:$(tag_of "$1")"
    fi
}

# Spring Boot answers on the actuator, the Go service on /health.
health_path_of() {
    case "$1" in
        spring) echo "/actuator/health/$2" ;;
        *)      echo "/health" ;;
    esac
}

check_tools() {
    for tool in kubectl envsubst; do
        if ! command -v "$tool" &> /dev/null; then
            echo "Error: $tool is not installed"
            exit 1
        fi
    done
}

render() {
    local platform=$1
    SERVICE="$(service_name_of "$platform")" \
    IMAGE="$(image_of "$platform")" \
    IMAGE_PULL_POLICY="$([ "$LOCAL_IMAGES" = "true" ] && echo Never || echo IfNotPresent)" \
    MAAS_AGENT_URL="$MAAS_AGENT_URL" \
    READINESS_PATH="$(health_path_of "$platform" readiness)" \
    LIVENESS_PATH="$(health_path_of "$platform" liveness)" \
        envsubst < "$MANIFEST"
}

install_app() {
    local service_name
    service_name="$(service_name_of "$1")"

    echo ""
    echo "===================================="
    echo "Installing $service_name..."
    echo "===================================="

    render "$1" | kubectl apply -n "$NAMESPACE" -f -
    kubectl rollout status "deployment/$service_name" -n "$NAMESPACE" --timeout=300s

    echo "✅ $service_name installed"
}

uninstall_app() {
    local service_name
    service_name="$(service_name_of "$1")"
    echo "Uninstalling $service_name..."
    render "$1" | kubectl delete -n "$NAMESPACE" --ignore-not-found -f -
}

for_each_app() {
    local action=$1
    IFS=',' read -ra platforms <<< "$APPS"
    for platform in "${platforms[@]}"; do
        "$action" "$(echo "$platform" | tr -d '[:space:]')"
    done
}

case "$ACTION" in
    install)
        check_tools
        for_each_app install_app
        echo ""
        echo "Installed into namespace $NAMESPACE. Check with:"
        echo "  kubectl get pods -n $NAMESPACE -l app.kubernetes.io/part-of=storage-test-app"
        ;;
    uninstall)
        check_tools
        for_each_app uninstall_app
        ;;
    *)
        echo "Unknown action: $ACTION"
        echo ""
        show_usage
        exit 1
        ;;
esac
