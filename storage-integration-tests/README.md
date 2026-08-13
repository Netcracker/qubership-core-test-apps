# Storage integration tests

Reproduces the storage failover conditions a rolling node replacement produces, and asserts that
the DBaaS/MaaS client libraries recover.

## How it works

The suite starts a workload inside `storage-test-service-spring`, injects a fault through the
Kubernetes API, then reads the timeline the application recorded next to the library under test. A
port-forward stalling on the runner would otherwise look identical to a storage that stopped
answering — which is the measurement being made.

```
 test (runner) ──drives──► storage-test-service-spring ──uses──► DBaaS client ──► Patroni
       │                            │
       └──injects fault via K8s API─┘  then reads /api/v1/workload/stats
```

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
./run-it/run-integration-tests.sh kind-kind core kind-control-plane:10.244.0.1 \
    storage-test-service-spring:storage-integration-tests
```

| Property | Default |
|---|---|
| `storage.postgres.namespace` | `postgres` |
| `storage.postgres.labelKey` | `application` |
| `storage.postgres.labelValue` | `patroni` |
