# Mesh Test Applications Management

This directory contains bash scripts to manage the mesh test services:
- **mesh-test-apps.sh** - Install/uninstall helm packages in the correct order
- **run-integration-tests.sh** - Run integration tests sequentially for all services

## Requirments
- Integration Tests run script is configured to run on cluster with default cluster domain - *cluster.local*. Hostname *cluster.local* should be resolved to ip address of cluster api server 

## Prerequisites

### For Service Management (mesh-test-apps.sh)
- [Helm](https://helm.sh/docs/intro/install/) must be installed
- Access to a Kubernetes cluster

### For Integration Tests (run-integration-tests.sh)
- [Maven](https://maven.apache.org/install.html) must be installed
- Access to a Kubernetes cluster with deployed services
- Access to GitHub Packages (maven.pkg.github.com) - automatically checked by script


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

**Note**: use node ip that belongs to pods network

Show help:

```bash
./run-integration-tests.sh --help
```

## Configuration

### Service Management Configuration
- **Operation**: Required first argument - must be 'install' or 'uninstall'
- **Namespace**: Required second argument - must be specified
- **TAG**: Optional third argument for Docker image tag (only used for install, defaults to 'latest')
- **Timeout**: Each operation has a 300-second timeout

### Integration Tests Configuration
- **Kube Context**: Required first argument - Kubernetes context for tests
- **Namespace**: Required second argument - Kubernetes namespace for tests
- **Node IP Mapping**: Required third argument - Node IP mapping for tests
- **Maven Profiles**: Uses 'integration-test' profile with skipIT=false
- **Connectivity Check**: Automatically verifies GitHub Packages access before running tests

### Integration Tests Results

The script automatically generates a comprehensive final report that includes:

#### Individual Module Results
- Tests run, passed, failed, errors, and skipped for the module
- Success/failure status for the module

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

📊 Individual Module Results:
----------------------------------------
mesh-integration-tests        ✅
  Tests Run:              45
  Passed:                 42
  Failed:                  3
  Errors:                  0
  Skipped:                 0

📈 OVERALL SUMMARY
========================================
Total Tests Run:           45 
Total Passed:              42 
Total Failed:               3 
Total Errors:               0
Total Skipped:              0

🏆 MODULE EXECUTION SUMMARY
========================================
Successful Modules:          1
Failed Modules:              0
Total Modules:               1

📊 SUCCESS RATE: 100% (1/1 modules)
```

## Troubleshooting

### Manual Cleanup
If you need to manually uninstall all services:

```bash
helm uninstall mesh-test-service-spring mesh-test-service-quarkus mesh-test-service-go -n <namespace>
```