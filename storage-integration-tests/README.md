# Storage integration tests

Reproduces the storage failover conditions a rolling node replacement produces, and asserts that
the MaaS client libraries recover.

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
(`SpringStorageITBase`, `GoStorageITBase`) and decides which application is
port-forwarded; the storage comes from `StorageProfile` and carries the probe name, the thresholds
and the faults. Everything else — the scenarios, the workload shape, the assertions — is shared.

| Test | Application | Libraries under test |
|---|---|---|
| `MaasStorageIT` | spring | Java MaaS client |
| `MaasGoStorageIT` | go | Go MaaS client and maas-core |
| `MaasAgentStorageIT` | spring | Java MaaS client, with maas-agent losing an instance |
| `MaasAgentGoStorageIT` | go | Go MaaS client, with maas-agent losing an instance |
| `MaasRabbitStorageIT` | spring | Java MaaS client obtaining a vhost |
| `MaasRabbitGoStorageIT` | go | Go MaaS client obtaining a vhost — disabled, see below |
| `MaasWatchStorageIT` | spring | Java MaaS client watch subscription |
| `MaasWatchGoStorageIT` | go | Go MaaS client watch subscription |

The `MaasAgent` classes exercise the other half of the retry logic. When the database behind
maas-service moves its leader the client gets 405 with a `MAAS-0600` body; when maas-agent itself
loses a pod the client gets a reset connection. Those are different branches, and the second one
was only covered by unit tests before. The controller scales maas-agent to two instances first,
since losing one of one is a full outage rather than the endpoint change being measured.

The `MaasWatch` classes cover the long poll the client holds open against maas-agent, which no
other class touches: everywhere else the client opens a connection per call.

One subscription is outstanding at a time, on one client that lives for the whole scenario. An
operation creates the watched topic, collects the callback if it has already arrived, and arms the
next subscription; it never blocks. A callback that never arrives fails one operation and the probe
re-arms. Two properties force that shape:

- The client restarts its poll on every registration, and in per-call mode it would also open a new
  client each time. Subscribing per operation left hundreds of polls against maas-agent and it
  stopped answering, taking the *next* test class down with it.
- maas-service delivers a create event over an unbuffered channel shared by all watchers, so an
  event with nobody waiting is simply dropped. The topic is then reported by the first pass of the
  next poll instead, which is why the deadline for a callback is longer than the 120-second poll
  maas-service holds open. Anything shorter fails operations for a watch that is working as
  designed — the freshly armed subscription in particular, whose topic is created before its poll
  has reached the server.

## A defect this suite found

`MaasRabbitGoStorageIT` is `@Disabled`. The Go client posts the bare classifier to
`/api/v1/rabbit/vhost`:

```go
request := d.HttpClient.R().SetContext(ctx).SetBody(classifier)
```

maas-service reads that body as `VHostRegistrationReqDto`, where the classifier is a nested field,
so every call is rejected with 400 and `Field validation for 'Name' failed on the 'required' tag`.
The Java client wraps it — `post(new VHostRequest(classifier))` — and works. Only get-or-create is
affected; the neighbouring `GetVhost` posts to `/rabbit/vhost/get-by-classifier`, which does expect
a bare classifier.

The client's own unit test does not catch it because its fake server answers 200 to any POST on
that path without looking at the request body. Re-enable the class once a client carrying the fix
is released.

## What is out of scope

The suite covers the MaaS client against maas-agent and maas-service: get-or-create topic,
get-or-create vhost, and the watch subscription. Producing to a topic and consuming from it are the
data plane and are not here; neither is the DBaaS client. Kafka and RabbitMQ still have to be
deployed, because maas-service reaches into the broker to create and describe the resource — they
are dependencies of the scenario rather than its subject.

There is no Quarkus application. The Quarkus extension wraps the same Java client the Spring
application uses, so a third deployment would run the same retry code against the same faults.

Each profile runs one fault, `ABRUPT_LEADER_LOSS`, except `MAAS_KAFKA`, which also runs
`GRACEFUL_SWITCHOVER`. The two are not the same event: a planned handover demotes the old leader,
which answers 405 while it is read-only, where an abrupt loss shows up first as reset connections
and an election. One profile carrying both is enough to keep that distinction covered; repeating it
on the others adds runtime, not coverage.

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
mvn -f storage-test-apps-java/pom.xml package -DskipTests
docker build -t storage-test-service-spring:it -f storage-test-service-spring/Dockerfile storage-test-service-spring
docker build -t storage-test-service-go:it -f storage-test-service-go/Dockerfile storage-test-service-go
kind load docker-image storage-test-service-spring:it storage-test-service-go:it --name kind

./storage-test-install/storage-test-apps.sh install --namespace core --tag it --local-images
./run-it/run-integration-tests.sh kind-kind core kind-control-plane:10.244.0.1 \
    storage-test-apps:storage-integration-tests
```

The images are built here rather than pulled. A published image is tagged from the branch, so a
change touching only the suite would run against whatever was published last, and a change touching
an application would race its own image build. `--local-images` points the manifest at the loaded
image and sets `imagePullPolicy: Never`, so nothing reaches a registry.

The applications are installed from a single manifest,
`storage-test-install/manifests/storage-test-app.yaml`, rendered per platform with `envsubst`. They
need a deployment, a service and a service account, and they are reached by port-forward rather
than through the gateway, so there is nothing for a chart to add — and a chart would fetch a
library chart over the network on every run. The service account keeps its `type: m2m` label: that
is what identifies it once the client talks to maas-service directly instead of through maas-agent.

| Property | Default | Meaning |
|---|---|---|
| `storage.namespace` | `postgres` | where the database under test runs |
| `storage.leaderService` | `pg-patroni` | service whose endpoints point at the primary |
| `storage.memberPrefix` | `pg-patroni-node` | pod-name prefix of the cluster members |
| `storage.maasAgentDeployment` | `maas-agent` | deployment whose instances are killed |
| `storage.maasAgentReplicas` | `2` | instances the scenario needs running |

The vhost classes need a registered RabbitMQ instance, which the bootstrap skips by default. The
workflow passes `rabbit-instances: rabbitmq-1` when the storage suite is selected; locally, install
MaaS with `RABBIT_INSTANCES=rabbitmq-1`.
