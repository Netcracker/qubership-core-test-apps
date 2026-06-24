## Mesh Integration Tests — How To

This module contains unified integration tests for the Spring, Quarkus, and Go services.

### Prerequisites
- kubectl configured to access your cluster
- Maven 3.8+ and JDK 21
- GitHub Packages access (maven.pkg.github.com)
- Hostname cluster.local must resolve to the cluster API server IP (e.g., via hosts)

### Quick start
Run the entire integration test suite:

```bash
mvn clean verify -P integration-test \
  -Dclouds.cloud.name=<kube-context> \
  -Dclouds.cloud.namespaces.namespace=<test-namespace> \
  -DNODE_IP_MAPPING=<node-name>:<pod-network-ip> \
  -DORIGIN_NAMESPACE=<test-namespace> \
  -Denv.cloud-namespace=<test-namespace> \
  -Dkubernetes.master=https://cluster.local:${API_SERVER_PORT}
```

### Example (minikube)

```bash
API_SERVER_PORT=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}' | sed -E 's|.*:([0-9]+)$|\1|')

mvn clean verify -P integration-test \
  -Dclouds.cloud.name=minikube \
  -Dclouds.cloud.namespaces.namespace=core \
  -DNODE_IP_MAPPING=minikube:10.244.0.1 \
  -DORIGIN_NAMESPACE=core \
  -Denv.cloud-namespace=core \
  -Dkubernetes.master=https://cluster.local:${API_SERVER_PORT}
```

### Run a single test

```bash
# By class name
mvn -Dtest=HttpIT clean verify -P integration-test ...

# By pattern (all *IT classes)
mvn -Dtest=*IT clean verify -P integration-test ...
```

### Security tests

The `com.netcracker.it.security` package checks that deployed workloads meet the container security
baseline. `CoreMicroservicesSecurityIT` scans every Deployment, StatefulSet, and DaemonSet in the test
namespace and asserts the full baseline: non-root, no privilege escalation, a read-only root filesystem,
all capabilities dropped, the RuntimeDefault seccomp profile, a tagged image, and no host network, PID,
IPC, or path. `IstioSecurityIT` holds istiod to the same baseline and verifies that the CNI agent and
ztunnel stay within their documented exception — never fully privileged, still dropping all capabilities,
and adding only the capabilities they require by design.

Run only the security tests:

```bash
mvn -Dtest='*SecurityIT' clean test -P integration-test \
  -Dsecurity.test.namespace=<test-namespace> \
  -Dsecurity.test.istio.namespace=istio-system \
  -Dkubernetes.master=https://cluster.local:${API_SERVER_PORT}
```

Options:
- `-Dsecurity.test.namespace` — namespace holding the core microservices. Defaults to the runner's
  `ORIGIN_NAMESPACE`, then `core`.
- `-Dsecurity.test.istio.namespace` — namespace holding Istio. Defaults to `istio-system`.
- `-Dsecurity.test.exclude=name1,name2` — workload names to skip in the core-microservices scan.

A failure lists the offending workload, container, and the specific requirement it violates.

### Where to find reports
After execution, JUnit/Surefire reports are written to:

```bash
ls -la target/surefire-reports/
cat target/surefire-reports/TEST-*.xml
```

### Troubleshooting
- cluster.local doesn’t resolve:
  - Add a hosts entry mapping cluster.local to the API server IP, or ensure DNS resolves it.
- Cannot reach API server at cluster.local:PORT:
  - Validate connectivity:
    ```bash
    kubectl --request-timeout=5s \
      --server="https://cluster.local:${API_SERVER_PORT}" \
      --insecure-skip-tls-verify=true version
    ```
- Maven dependency issues:
  - Ensure GitHub Packages access (token provided to maven), then run:
    ```bash
    mvn dependency:resolve -U
    ```