# Storage integration tests

Reproduces the storage failover conditions a rolling node replacement produces, and asserts that
the DBaaS/MaaS client libraries recover.

## How it works

The suite starts a workload inside a test application, injects a fault through the Kubernetes API,
then reads the timeline the application recorded next to the library under test. A port-forward
stalling on the runner would otherwise look identical to a storage that stopped answering — which
is the measurement being made.

```
 test (runner) ──drives──► storage-test-service-* ──uses──► client library ──► storage
       │                            │
       └──injects fault via K8s API─┘  then reads /api/v1/workload/stats
```

A test class is one platform crossed with one storage. The platform comes from the base class
(`SpringStorageITBase`, `GoStorageITBase`, `QuarkusStorageITBase`) and decides which application is
port-forwarded; the storage comes from `StorageProfile` and carries the probe name, the thresholds
and the faults. Everything else — the scenarios, the workload shape, the assertions — is shared.

| Test | Application | Libraries under test |
|---|---|---|
| `PostgresStorageIT` | spring | Java DBaaS PostgreSQL client |
| `MaasStorageIT` | spring | Java MaaS client |
| `KafkaStorageIT` | spring | Java MaaS client plus kafka-clients |
| `MaasGoStorageIT` | go | Go MaaS client and maas-core |
| `KafkaGoStorageIT` | go | Go MaaS client plus segmentio |
| `MaasQuarkusStorageIT` | quarkus | Quarkus MaaS extension over the Java MaaS client |
| `KafkaQuarkusStorageIT` | quarkus | Quarkus MaaS extension plus kafka-clients |

## What passes

`StorageAssertions.assertContract` checks all four at once:

| Assertion | Meaning |
|---|---|
| recovered | a success occurred within the storage's recovery budget after the fault cleared |
| errors stopped | no failures once the storage was healthy again plus the recovery allowance |
| nothing hung | every operation returned, success or error, within the per-operation limit |
| no leak | threads and descriptors back to baseline after repeated fault cycles |

Zero-error failover is explicitly **not** the contract. A leader change produces errors; what is
asserted is that they are bounded and recoverable. Thresholds live in `scenario/Thresholds.java`,
one record per storage.

## Running

```bash
./storage-test-install/storage-test-apps.sh install --namespace core --tag latest
./run-it/run-integration-tests.sh kind-kind core kind-control-plane:10.244.0.1 \
    storage-test-apps:storage-integration-tests
```

| Property | Default | Meaning |
|---|---|---|
| `storage.namespace` | `postgres` | where the database under test runs |
| `storage.leaderService` | `pg-patroni` | service whose endpoints point at the primary |
| `storage.memberPrefix` | `pg-patroni-node` | pod-name prefix of the cluster members |
| `storage.kafkaNamespace` | `kafka` | where the broker under test runs |
| `storage.kafkaInstance` | `kafka-1` | Helm release of the broker to disturb |
