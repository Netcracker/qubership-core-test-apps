# Mesh Test Applications Management

This directory contains bash scripts to manage the mesh test services:
- **mesh-test-apps.sh** - Install/uninstall helm packages in the correct order

## Prerequisites

### For Service Management (mesh-test-apps.sh)
- [Helm](https://helm.sh/docs/intro/install/) must be installed
- Access to a Kubernetes cluster

## Usage

### Test services deployment (mesh-test-apps.sh)

Installs / uninstalls helm packages for

* Mesh-test-service-go
* Mesh-test-service-quarkus
* Mesh-test-service-spring

Run the script with required operation, namespace, and optional tag arguments:

#### Install Services

```bash
# Install in mesh-test namespace with default 'latest' tag
./mesh-test-apps.sh install mesh-test

# Install in mesh-test namespace with specific tag
./mesh-test-apps.sh install mesh-test v1.2.3

```

#### Uninstall Services

```bash
# Uninstall from mesh-test namespace
./mesh-test-apps.sh uninstall mesh-test
```

#### Show Help

```bash
./mesh-test-apps.sh --help
```

#### Configuration
- **Operation**: Required first argument - must be 'install' or 'uninstall'
- **Namespace**: Required second argument - must be specified
- **TAG**: Optional third argument for Docker image tag (only used for install, defaults to 'latest')
- **Timeout**: Each operation has a 300-second timeout

## Troubleshooting

### Manual Cleanup
If you need to manually uninstall all services:

```bash
helm uninstall mesh-test-service-spring mesh-test-service-quarkus mesh-test-service-go -n <namespace>
```