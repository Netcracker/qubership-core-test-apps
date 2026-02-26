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

Run the script with required operation, namespace, mesh type, and optional per-service tag arguments:

#### Mesh type

The **mesh type** selects which service mesh implementation the test services use:

- **ISTIO** — Use Istio service mesh
- **CORE** — Use Cloud-Core mesh

This value is passed to Helm as `SERVICE_MESH_TYPE` and affects how routes and sidecars are configured.

#### Install Services

```bash
# Install in mesh-test namespace with ISTIO mesh and default 'latest' tag for all services
./mesh-test-apps.sh install mesh-test ISTIO

# Install with CORE mesh type
./mesh-test-apps.sh install mesh-test CORE

# Install with specific image tags (use named flags per service)
./mesh-test-apps.sh install mesh-test ISTIO --spring-tag v1.2.3
./mesh-test-apps.sh install mesh-test CORE --spring-tag v1.2.3 --quarkus-tag v2.0.0 --go-tag v3.1.0
```

#### Uninstall Services

```bash
# Uninstall from mesh-test namespace (mesh type is still required)
./mesh-test-apps.sh uninstall mesh-test ISTIO
./mesh-test-apps.sh uninstall mesh-test CORE
```

#### Show Help

```bash
./mesh-test-apps.sh --help
```

#### Configuration
- **Operation**: Required first argument — `install` or `uninstall`
- **Namespace**: Required second argument — Kubernetes namespace to operate on
- **Mesh type**: Required third argument — `ISTIO` or `CORE` (see [Mesh type](#mesh-type) above)
- **Tags**: Optional named flags for install: `--spring-tag`, `--quarkus-tag`, `--go-tag` (each defaults to `latest`)
- **Timeout**: Each operation has a 300-second timeout

## Troubleshooting

### Manual Cleanup
If you need to manually uninstall all services:

```bash
helm uninstall mesh-test-service-spring mesh-test-service-quarkus mesh-test-service-go -n <namespace>
```