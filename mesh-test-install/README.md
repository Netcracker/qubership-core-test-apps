# Mesh Test Applications Management

This directory contains bash scripts to manage the mesh test services:
- **mesh-test-apps.sh** - Install/uninstall helm packages in the correct order
- **run-integration-tests.sh** - Run integration tests sequentially for all services

## Prerequisites

### For Service Management (mesh-test-apps.sh)
- [Helm](https://helm.sh/docs/intro/install/) must be installed
- Access to a Kubernetes cluster

### For Integration Tests (run-integration-tests.sh)
- [Maven](https://maven.apache.org/install.html) must be installed
- Access to a Kubernetes cluster with deployed services
- Internet connectivity to GitHub Packages (maven.pkg.github.com) - automatically checked by script


## Usage

### Service Management (mesh-test-apps.sh)

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

### Integration Tests (run-integration-tests.sh)

Run integration tests sequentially for all mesh test services (all parameters required):

```bash
# Run with all required parameters
./run-integration-tests.sh minikube core minikube:10.244.0.1

# Run with different context and namespace
./run-integration-tests.sh docker-desktop test-ns docker-desktop:10.0.0.1
```

Or with bash directly:

```bash
# All parameters required
bash run-integration-tests.sh minikube core minikube:10.244.0.1
```

**Note**: use node ip that belongs to pod's network

Show help:

```bash
./run-integration-tests.sh --help
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

### Integration Tests Execution Order
The integration tests are executed sequentially in this order:
1. **mesh-test-service-spring** - Spring Boot integration tests
2. **mesh-test-service-quarkus** - Quarkus integration tests
3. **mesh-test-service-go** - Go service integration tests

**Note**: The script continues running all tests even if one service fails, then provides a comprehensive final report with detailed statistics from all modules.

## Configuration

### Service Management Configuration
- **Operation**: Required first argument - must be 'install' or 'uninstall'
- **Namespace**: Required second argument - must be specified
- **TAG**: Optional third argument for Docker image tag (only used for install, defaults to 'latest')
- **Timeout**: Each operation has a 300-second timeout
- **Dependencies**: The script automatically handles helm dependencies for install operations

### Integration Tests Configuration
- **Kube Context**: Required first argument - Kubernetes context for tests
- **Namespace**: Required second argument - Kubernetes namespace for tests
- **Node IP Mapping**: Required third argument - Node IP mapping for tests
- **Test Execution**: Sequential execution with fail-fast behavior
- **Maven Profiles**: Uses 'integration-test' profile with skipIT=false
- **Connectivity Check**: Automatically verifies GitHub Packages access before running tests

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

### Integration Tests Results

The script automatically generates a comprehensive final report that includes:

#### Individual Service Results
- Tests run, passed, failed, errors, and skipped for each service
- Success/failure status for each service

#### Overall Summary  
- Total tests across all services
- Aggregated pass/fail/error/skip counts
- Service execution summary

#### Success Rate
- Percentage of services that completed successfully
- Clear indication of which services succeeded or failed

#### Sample Report Output
```
🎯 FINAL INTEGRATION TESTS REPORT
========================================

📊 Individual Service Results:
----------------------------------------
mesh-test-service-spring       ✅
  Tests Run:              15
  Passed:                 14
  Failed:                  1
  Errors:                  0
  Skipped:                 0

📈 OVERALL SUMMARY
========================================
Total Tests Run:           45
Total Passed:              42
Total Failed:               3
Total Errors:               0
Total Skipped:              0

🏆 SERVICE EXECUTION SUMMARY
========================================
Successful Services:         2
Failed Services:             1
Total Services:              3

📊 SUCCESS RATE: 67% (2/3 services)
```

You can also manually check individual service reports:

```bash
# Check Maven surefire reports (example for Spring service)
ls -la mesh-test-service-spring/test-service-spring-integration-tests/target/surefire-reports/

# View test results
cat mesh-test-service-spring/test-service-spring-integration-tests/target/surefire-reports/TEST-*.xml
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

Common integration test fixes:

```bash
# Check if services are accessible
kubectl get services -n <namespace>

# Test Maven dependency resolution
mvn dependency:resolve -U

# Run tests for individual service (example)
cd mesh-test-service-spring
mvn clean verify -P integration-test -DskipIT=false -Dclouds.cloud.name=minikube -Dclouds.cloud.namespaces.namespace=core
```
