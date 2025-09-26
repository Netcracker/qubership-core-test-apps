# Mesh Test Applications Management

This directory contains a bash script to manage (install/uninstall) the mesh test services helm packages in the correct order.

## Prerequisites

- [Helm](https://helm.sh/docs/intro/install/) must be installed
- [kubectl](https://kubernetes.io/docs/tasks/tools/) must be installed and configured
- Access to a Kubernetes cluster

## Usage

Run the script with required operation, namespace, and optional tag arguments:

### Install Services

```bash
# Install in mesh-test namespace with default 'latest' tag
./mesh-test-apps.sh install mesh-test

# Install in mesh-test namespace with specific tag
./mesh-test-apps.sh install mesh-test v1.2.3

```

### Uninstall Services

```bash
# Uninstall from mesh-test namespace
./mesh-test-apps.sh uninstall mesh-test
```

### Alternative Execution

Or if you need to run with bash directly:

```bash
# Install with default tag
bash mesh-test-apps.sh install mesh-test


# Uninstall services
bash mesh-test-apps.sh uninstall mesh-test
```

### Show Help

```bash
./mesh-test-apps.sh --help
```

## Service Order

### Installation Order
The script installs the services in the following order:
1. **mesh-test-service-spring** - Spring Boot service
2. **mesh-test-service-quarkus** - Quarkus service  
3. **mesh-test-service-go** - Go service

### Uninstallation Order
The script uninstalls the services in reverse order:
1. **mesh-test-service-go** - Go service
2. **mesh-test-service-quarkus** - Quarkus service
3. **mesh-test-service-spring** - Spring Boot service

## Configuration

- **Operation**: Required first argument - must be 'install' or 'uninstall'
- **Namespace**: Required second argument - must be specified
- **TAG**: Optional third argument for Docker image tag (only used for install, defaults to 'latest')
- **Timeout**: Each operation has a 300-second timeout
- **Dependencies**: The script automatically handles helm dependencies for install operations

## Verification

### After Installation
Verify the deployments (replace `<namespace>` with your chosen namespace):

```bash
# Check helm releases
helm list -n <namespace>

# Check pod status
kubectl get pods -n <namespace>

# Check services
kubectl get services -n <namespace>
```

### After Uninstallation
Verify the services have been removed:

```bash
# Check that helm releases are gone
helm list -n <namespace>

# Check that pods are terminated
kubectl get pods -n <namespace>
```

## Troubleshooting

### Installation Issues
If an installation fails:
1. Check the error message in the script output
2. Verify your Kubernetes cluster is accessible
3. Ensure helm charts are valid
4. Check if the namespace has sufficient resources

### Uninstallation Issues
If an uninstallation fails:
1. Check the error message in the script output
2. Verify your Kubernetes cluster is accessible
3. Check if the releases exist: `helm list -n <namespace>`

### Manual Cleanup
If you need to manually uninstall all services:

```bash
helm uninstall mesh-test-service-spring mesh-test-service-quarkus mesh-test-service-go -n <namespace>
```

### Force Cleanup
If services are stuck, you may need to force cleanup:

```bash
# Force uninstall with longer timeout
helm uninstall mesh-test-service-spring --namespace <namespace> --timeout=600s --wait

# If still stuck, you may need to manually delete resources
kubectl delete all -l app.kubernetes.io/instance=mesh-test-service-spring -n <namespace>
```
