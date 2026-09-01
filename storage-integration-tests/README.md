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
| `MaasAgentStorageIT` | spring | Java MaaS client, with maas-agent losing an instance |
| `MaasAgentGoStorageIT` | go | Go MaaS client, with maas-agent losing an instance |
| `MaasRabbitStorageIT` | spring | Java MaaS client obtaining a vhost |
| `MaasRabbitGoStorageIT` | go | Go MaaS client obtaining a vhost |
| `MaasWatchStorageIT` | spring | Java MaaS client watch subscription |
| `MaasWatchGoStorageIT` | go | Go MaaS client watch subscription |

The `MaasAgent` classes exercise the other half of the retry logic. When the database behind
maas-service moves its leader the client gets 405 with a `MAAS-0600` body; when maas-agent itself
loses a pod the client gets a reset connection. Those are different branches, and the second one
was only covered by unit tests before. The controller scales maas-agent to two instances first,
since losing one of one is a full outage rather than the endpoint change being measured.

The `MaasWatch` classes cover the long poll the client holds open against maas-agent, which no
other class touches: everywhere else the client opens a connection per call. One operation
subscribes to a name that does not exist, creates the topic and waits for the notification. Names
have to be unique because a watch fires once, and the Java client has no delete, so registrations
accumulate — hence one operation per second rather than the usual rate.

## What the Kafka fault really is

The local-dev chart runs one single-node KRaft broker per Helm release with no volume, so killing
its pod destroys the log directory. The broker returns without a single topic while MaaS still has
them registered, and get-or-create then fails with `MAAS-0600` until the registry is reconciled.
The suite therefore calls `POST /api/v2/kafka/recovery/{namespace}` through maas-agent before each
scenario — the same operation an operator would run after such an outage.

There is no Quarkus counterpart on purpose: the Quarkus extension wraps the same Java client the
Spring application uses, so a third run of the same code would cost time without adding coverage.
For the same reason the Quarkus classes run one fault instead of the whole sweep and skip the leak
scenario — what is new on that platform is the CDI wiring, not the retry logic.

## What passes

`StorageAssertions.assertContract` checks all four at once:

| Assertion | Meaning |
|---|---|
| recovered | a success occurred within the storage's recovery allowance after the fault cleared |
| errors stopped | no failures once the client had settled, measured from its first success |
| nothing hung | every operation returned, success or error, within the per-operation limit |
| no leak | threads and descriptors back to baseline after repeated fault cycles |

A scenario waits for the client to answer again rather than sitting out the whole allowance, so a
storage that recovers in a second costs a second. The allowance stays the limit that fails the test.

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
| `storage.maasAgentDeployment` | `maas-agent` | deployment whose instances are killed |
| `storage.maasAgentReplicas` | `2` | instances the scenario needs running |
