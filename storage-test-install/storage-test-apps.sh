#!/bin/bash

# Installs the storage test applications into a namespace, mirroring mesh-test-apps.sh.
# Usage: ./storage-test-apps.sh [install|uninstall] [--namespace NS] [--apps LIST] [--tag TAG]

set -e

NAMESPACE="core"
APPS="spring,go,quarkus"
TAG="latest"
SPRING_TAG=""
GO_TAG=""
QUARKUS_TAG=""
DBAAS_AGENT_URL="http://dbaas-agent:8080"
MAAS_AGENT_URL="http://maas-agent:8080"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

show_usage() {
    echo "Usage: $0 [install|uninstall] [options]"
    echo ""
    echo "Options:"
    echo "  --namespace NS       Target namespace (default: core)"
    echo "  --apps LIST          Comma-separated platforms to install (default: spring,go,quarkus)"
    echo "  --tag TAG            Image tag for every application (default: latest)"
    echo "  --spring-tag TAG     Image tag for storage-test-service-spring, overrides --tag"
    echo "  --go-tag TAG         Image tag for storage-test-service-go, overrides --tag"
    echo "  --quarkus-tag TAG    Image tag for storage-test-service-quarkus, overrides --tag"
    echo "  --dbaas-agent URL    DBaaS agent address (default: http://dbaas-agent:8080)"
    echo "  --maas-agent URL     MaaS agent address (default: http://maas-agent:8080)"
    echo ""
    echo "Examples:"
    echo "  $0 install"
    echo "  $0 install --namespace core --tag feat-x-snapshot"
    echo "  $0 install --apps go --go-tag pr-123"
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
        --apps)        APPS="$2"; shift 2 ;;
        --tag)         TAG="$2"; shift 2 ;;
        --spring-tag)  SPRING_TAG="$2"; shift 2 ;;
        --go-tag)      GO_TAG="$2"; shift 2 ;;
        --quarkus-tag) QUARKUS_TAG="$2"; shift 2 ;;
        --dbaas-agent) DBAAS_AGENT_URL="$2"; shift 2 ;;
        --maas-agent)  MAAS_AGENT_URL="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; echo ""; show_usage; exit 1 ;;
    esac
done

service_name_of() {
    echo "storage-test-service-$1"
}

tag_of() {
    case "$1" in
        spring)  echo "${SPRING_TAG:-$TAG}" ;;
        go)      echo "${GO_TAG:-$TAG}" ;;
        quarkus) echo "${QUARKUS_TAG:-$TAG}" ;;
        *)       echo "$TAG" ;;
    esac
}

# Values each platform's chart understands; passing an unknown one is only noise in the release.
values_of() {
    case "$1" in
        spring)  echo "--set DBAAS_AGENT_URL=$DBAAS_AGENT_URL" ;;
        go|quarkus) echo "--set MAAS_AGENT_URL=$MAAS_AGENT_URL" ;;
        *)       echo "" ;;
    esac
}

check_helm() {
    if ! command -v helm &> /dev/null; then
        echo "Error: helm is not installed"
        exit 1
    fi
    echo "Helm version: $(helm version --short)"
}

install_app() {
    local platform=$1
    local service_name chart_path tag
    service_name="$(service_name_of "$platform")"
    chart_path="$PROJECT_ROOT/$service_name/helm-templates/$service_name"
    tag="$(tag_of "$platform")"

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
    # shellcheck disable=SC2046 # the values are deliberately word-split into separate flags
    helm upgrade --install "$service_name" "$chart_path" \
        --namespace "$NAMESPACE" \
        --set TAG="$tag" \
        $(values_of "$platform") \
        --wait \
        --timeout=300s

    echo "✅ $service_name installed"
}

uninstall_app() {
    local service_name
    service_name="$(service_name_of "$1")"
    if helm list -n "$NAMESPACE" | grep -q "$service_name"; then
        echo "Uninstalling $service_name..."
        helm uninstall "$service_name" --namespace "$NAMESPACE" --wait --timeout=300s
    else
        echo "$service_name is not installed, nothing to do"
    fi
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
        check_helm
        for_each_app install_app
        echo ""
        echo "Installed into namespace $NAMESPACE. Check with:"
        echo "  helm list -n $NAMESPACE"
        ;;
    uninstall)
        check_helm
        for_each_app uninstall_app
        ;;
    *)
        echo "Unknown action: $ACTION"
        echo ""
        show_usage
        exit 1
        ;;
esac
