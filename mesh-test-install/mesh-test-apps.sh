#!/bin/bash

# Mesh test applications management script
# Manages mesh test services in order: spring, quarkus, go
# Usage: ./mesh-test-apps.sh <operation> <namespace> [tag]
# Operation: install/uninstall, Namespace is required, TAG is optional (defaults to 'latest' for install)

set -e  # Exit on any error

# Function to show usage
show_usage() {
    echo "Usage: $0 <operation> <namespace> [tag]"
    echo ""
    echo "Arguments:"
    echo "  operation    Operation to perform: 'install' or 'uninstall' (required)"
    echo "  namespace    Kubernetes namespace to operate on (required)"
    echo "  tag          Docker image tag to use for install (optional, defaults to 'latest')"
    echo ""
    echo "Examples:"
    echo "  $0 install mesh-test                    # Install in 'mesh-test' namespace with 'latest' tag"
    echo "  $0 install mesh-test v1.2.3             # Install in 'mesh-test' namespace with 'v1.2.3' tag"
    echo "  $0 uninstall mesh-test                  # Uninstall from 'mesh-test' namespace"
    echo ""
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

# Parse command line arguments
NAMESPACE="$2"
TAG="${3:-latest}"

echo "Starting mesh test applications $OPERATION..."
echo "Operation: $OPERATION"
echo "Target namespace: $NAMESPACE"
if [[ "$OPERATION" == "install" ]]; then
    echo "Docker image tag: $TAG"
fi

# Function to check if helm is installed
check_helm() {
    if ! command -v helm &> /dev/null; then
        echo "Error: Helm is not installed. Please install Helm first."
        exit 1
    fi
    echo "Helm version: $(helm version --short)"
}

# Function to install a helm package
install_helm_package() {
    local service_name=$1
    local chart_path=$2
    local namespace=$3
    
    echo ""
    echo "===================================="
    echo "Installing $service_name..."
    echo "===================================="
    
    # Check if chart directory exists
    if [ ! -d "$chart_path" ]; then
        echo "Error: Chart directory $chart_path does not exist"
        exit 1
    fi
    
    # Update helm dependencies
    echo "Updating helm dependencies for $service_name..."
    helm dependency update "$chart_path"
    
    # Install or upgrade the helm chart
    echo "Installing/upgrading $service_name with tag: $TAG..."
    helm upgrade --install "$service_name" "$chart_path" \
        --namespace "$namespace" \
        --set TAG="$TAG" \
        --wait \
        --timeout=300s || true

    if [ $? -eq 0 ]; then
        echo "✅ $service_name installed successfully"
    else
        echo "❌ Failed to install $service_name"
        exit 1
    fi
}

# Function to uninstall a helm package
uninstall_helm_package() {
    local service_name=$1
    local namespace=$2
    
    echo ""
    echo "===================================="
    echo "Uninstalling $service_name..."
    echo "===================================="
    
    # Check if the release exists
    if helm list -n "$namespace" | grep -q "$service_name"; then
        echo "Uninstalling $service_name from namespace $namespace..."
        helm uninstall "$service_name" --namespace "$namespace" --wait --timeout=300s
        
        if [ $? -eq 0 ]; then
            echo "✅ $service_name uninstalled successfully"
        else
            echo "❌ Failed to uninstall $service_name"
            exit 1
        fi
    else
        echo "⚠️  $service_name not found in namespace $namespace, skipping..."
    fi
}

# Main installation process
install_services() {
    echo "Checking prerequisites..."
    check_helm
    
    # Get the script directory to determine the project root
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
    
    echo "Project root: $PROJECT_ROOT"
    
    # Define chart paths relative to project root
    SPRING_CHART="$PROJECT_ROOT/mesh-test-service-spring/helm-templates/mesh-test-service-spring"
    QUARKUS_CHART="$PROJECT_ROOT/mesh-test-service-quarkus/helm-templates/mesh-test-service-quarkus"
    GO_CHART="$PROJECT_ROOT/mesh-test-service-go/helm-templates/mesh-test-service-go"
    
    echo ""
    echo "Chart paths:"
    echo "Spring:  $SPRING_CHART"
    echo "Quarkus: $QUARKUS_CHART"
    echo "Go:      $GO_CHART"
    echo "Namespace: $NAMESPACE"
    
    # Install packages in specified order
    echo ""
    echo "🚀 Starting installation process..."
    
    # 1. Install Spring service first
    install_helm_package "mesh-test-service-spring" "$SPRING_CHART" "$NAMESPACE"
    
    # 2. Install Quarkus service second
    install_helm_package "mesh-test-service-quarkus" "$QUARKUS_CHART" "$NAMESPACE"
    
    # 3. Install Go service third
    install_helm_package "mesh-test-service-go" "$GO_CHART" "$NAMESPACE"
    
    echo ""
    echo "🎉 All mesh test services installed successfully!"
    echo ""
    echo "Installed services:"
    echo "1. mesh-test-service-spring"
    echo "2. mesh-test-service-quarkus" 
    echo "3. mesh-test-service-go"
    echo ""
    echo "To check the status of your deployments, run:"
    echo "  helm list -n $NAMESPACE"
    echo "  kubectl get pods -n $NAMESPACE"
}

# Main uninstallation process
uninstall_services() {
    echo "Checking prerequisites..."
    check_helm
    
    echo ""
    echo "🗑️  Starting uninstallation process..."
    echo "Namespace: $NAMESPACE"
    
    # Uninstall packages in reverse order (Go, Quarkus, Spring)
    # 1. Uninstall Go service first
    uninstall_helm_package "mesh-test-service-go" "$NAMESPACE"
    
    # 2. Uninstall Quarkus service second
    uninstall_helm_package "mesh-test-service-quarkus" "$NAMESPACE"
    
    # 3. Uninstall Spring service third
    uninstall_helm_package "mesh-test-service-spring" "$NAMESPACE"
    
    echo ""
    echo "🎉 All mesh test services uninstalled successfully!"
    echo ""
    echo "To verify removal, run:"
    echo "  helm list -n $NAMESPACE"
    echo "  kubectl get pods -n $NAMESPACE"
}

# Main function
main() {
    if [[ "$OPERATION" == "install" ]]; then
        install_services
    elif [[ "$OPERATION" == "uninstall" ]]; then
        uninstall_services
    fi
}

# Handle script interruption
if [[ "$OPERATION" == "install" ]]; then
    trap 'echo ""; echo "Installation interrupted. Some packages may be partially installed."; exit 1' INT TERM
else
    trap 'echo ""; echo "Uninstallation interrupted. Some packages may be partially uninstalled."; exit 1' INT TERM
fi

# Run main function
main "$@"
