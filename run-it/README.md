# Integration Tests Runner

This directory contains the integration tests runner script:
- **run-integration-tests.sh** - Run integration tests sequentially for all services

## Requirements

Integration Tests run script is configured to run on cluster with default cluster domain - *cluster.local*. Hostname *cluster.local* should be resolved to ip address of cluster api server.

## Prerequisites

### For Integration Tests (run-integration-tests.sh)
- [Maven](https://maven.apache.org/install.html) must be installed
- Access to a Kubernetes cluster with deployed services
- Access to GitHub Packages (maven.pkg.github.com) - automatically checked by script

## Usage

### Integration Tests (run-integration-tests.sh)

Run integration tests sequentially for all mesh test services:

#### Basic Usage (with default test folder)

```bash
# Run with all required parameters (uses default mesh-integration-tests folder and integration-test profile)
./run-integration-tests.sh minikube core minikube:10.244.0.1

# Run with different context and namespace
./run-integration-tests.sh docker-desktop test-ns docker-desktop:10.0.0.1

# Run with custom Maven profile
./run-integration-tests.sh --profile custom-profile minikube core minikube:10.244.0.1

# Run with custom Maven profile (short form)
./run-integration-tests.sh -p test-profile docker-desktop test-ns docker-desktop:10.0.0.1
```

**Note**: use node ip that belongs to pods network

#### Advanced Usage (with custom test folders and service names)

You can specify multiple test folders and service names as additional arguments:

```bash
# Single custom test folder
./run-integration-tests.sh minikube core minikube:10.244.0.1 my-service:my-test-folder

# Multiple test folders
./run-integration-tests.sh minikube core minikube:10.244.0.1 service1:test-folder1 service2:test-folder2

# Mix of relative and absolute paths
./run-integration-tests.sh minikube core minikube:10.244.0.1 service1:relative-path service2:/absolute/path/to/tests

# Combine default and custom tests
./run-integration-tests.sh minikube core minikube:10.244.0.1 mesh-integration-tests:mesh-integration-tests custom-service:custom-tests

# With custom profile and test folders
./run-integration-tests.sh --profile integration-test minikube core minikube:10.244.0.1 service1:test-folder1 service2:test-folder2
```

#### Show Help

```bash
./run-integration-tests.sh --help
```

#### Integration Tests Configuration

**Required Arguments:**
- **Kube Context**: Kubernetes context for tests
- **Namespace**: Kubernetes namespace for tests
- **Node IP Mapping**: Node IP mapping for tests

**Options:**
- **--profile PROFILE, -p PROFILE**: Maven profile to use (default: `integration-test`)
  - If not specified, uses `integration-test` profile
  - Must be specified before the required arguments

**Optional Arguments:**
- **Service Name:Test Folder**: One or more pairs in format `service-name:test-folder`
  - Service name: Name used for reporting and identification
  - Test folder: Relative path from project root or absolute path
  - If not provided, defaults to `mesh-integration-tests:mesh-integration-tests`

**Other Configuration:**
- **Maven Profile**: Defaults to `integration-test` profile (configurable via `--profile` option)
- **Connectivity Check**: Automatically verifies GitHub Packages access before running tests

## Integration Tests Results

The script automatically generates a comprehensive final report that includes:

### Individual Service Results
- Tests run, passed, failed, errors, and skipped for each service
- Success/failure status for each service

### Overall Summary  
- Total tests across all services
- Aggregated pass/fail/error/skip counts
- Service execution summary

### Success Rate
- Percentage of services that completed successfully
- Clear indication of which services succeeded or failed

### Sample Report Output
```
🎯 FINAL INTEGRATION TESTS REPORT
========================================

📊 Individual Service Results:
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

🏆 SERVICE EXECUTION SUMMARY
========================================
Successful Services:          1
Failed Services:              0
Total Services:               1

📊 SUCCESS RATE: 100% (1/1 services)
```
