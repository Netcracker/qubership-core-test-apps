# DBaaS Operator integration tests

These tests check that Cloud Core services get their databases from the DBaaS Operator, through the Secrets it
mounts, and never fall back to provisioning over REST. The services covered are control-plane, config-server, and
site-management.

## How a fallback is caught

The tests run Cloud Core in the Istio ambient mesh. `checks.sh arm` puts a waypoint in front of dbaas-aggregator with
an `AuthorizationPolicy` that denies `/api/v3/dbaas/*` to the Cloud Core namespace (`assets/rest-fallback-trap.yaml`).
Every database lookup or creation over REST from a Cloud Core service is then denied with 403, whether it goes to
dbaas-aggregator directly or through dbaas-agent, and the waypoint counts it by source workload. The rest of the
aggregator stays open, so core-operator still registers the composite structure through dbaas-agent, and the DBaaS
Operator and the database adapters are not affected: they call from their own namespaces.

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 320}}}%%
flowchart LR
    subgraph core["Cloud Core namespace (ambient mesh)"]
        services["control-plane<br/>config-server<br/>site-management"]
        agent["dbaas-agent"]
        operatorCo["core-operator"]
        probe["probe pod<br/>(checks.sh arm)"]
        secret[("operator Secrets<br/>metadata.json<br/>connectionProperties.json")]
    end

    subgraph dbaas["DBaaS namespace (ambient mesh)"]
        waypoint{{"waypoint<br/>DENY Cloud Core<br/>/api/v3/dbaas/* on 8080<br/>counts denials per workload"}}
        aggregator["dbaas-aggregator"]
        dbaasOperator["DBaaS Operator"]
    end

    subgraph pg["Postgres namespace"]
        adapter["postgres adapter"]
    end

    services -- "REST fallback: denied, 403" --> waypoint
    services -. "REST fallback via agent" .-> agent
    agent -- "/api/v3/dbaas/*: denied, 403" --> waypoint
    operatorCo -- "composite registration" --> agent
    agent -- "/api/composite/*: allowed" --> waypoint
    probe -- "/api/v3/dbaas/*: must get 403<br/>/health: must answer" --> waypoint
    waypoint --> aggregator
    dbaasOperator -- "own namespace: allowed" --> aggregator
    adapter -- "own namespace: allowed" --> aggregator
    dbaasOperator -- "creates" --> secret
    secret -- "mounted, read at startup" --> services

    classDef denied fill:#fde2e1,stroke:#cf222e,color:#1f2328
    classDef allowed fill:#dafbe1,stroke:#1a7f37,color:#1f2328
    class waypoint denied
    class secret allowed
```

A DENY rule that does not apply blocks nothing and reports nothing, so the tests prove it applies:

- `arm` calls the denied path from a probe pod in the Cloud Core namespace and requires `403 RBAC: access denied`,
  and calls `/health` and requires an answer.
- The waypoint sees only pods in the mesh. `arm` puts the Cloud Core namespace in the mesh before Cloud Core is
  installed, and `assert` requires every pod of the services and of dbaas-agent to be captured.
- `assert` reads the waypoint's request metrics and requires no denied request from any service or dbaas-agent.
  Readiness alone would miss a call that failed and was then retried from the Secret. The probe's own denied
  requests show that the metrics are there to read.

The tests need nothing from core-bootstrap beyond what an install already does: Cloud Core keeps its real
`API_DBAAS_ADDRESS`.

## Scenarios

| Job | What it proves |
|---|---|
| `clean-install` | Cloud Core installed onto a cluster that already runs the operator comes up, every CR is `Ready`, and core-bootstrap no longer writes `control-plane-db-credentials` |
| `upgrade` | Cloud Core installed as today, then upgraded after the operator is added, keeps the databases it already had: each claim resolves to the database recorded before the upgrade |

`clean-install` installs the dependencies and Cloud Core in two runs, through the `make-targets` input of the setup
action, so the trap is in place before the first Cloud Core pod starts. `upgrade` arms it after the baseline install
and the database snapshot, which itself asks dbaas-aggregator from the Cloud Core namespace.

### Clean install

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 320}}}%%
flowchart TD
    A["Kind cluster"] --> B["make … deploy-dependencies<br/>Istio ambient, Postgres, DBaaS,<br/>DBaaS Operator"]
    B --> C["checks.sh arm"]

    subgraph arm["arm: set the trap and prove it works"]
        C1["wait for istiod, ztunnel, istio-cni"] --> C2["put the Cloud Core and DBaaS<br/>namespaces in the ambient mesh"]
        C2 --> C3["apply the waypoint, the DENY policy,<br/>and the probe pod"]
        C3 --> C4{"probe: /api/v3/dbaas/* gets 403?<br/>/health answers?"}
    end

    C --> C1
    C4 -- "no" --> F1["fail: the trap does not apply"]
    C4 -- "yes" --> D["make deploy-core<br/>core-bootstrap skips REST creation;<br/>charts declare InternalDatabase,<br/>DatabaseSecretClaim, DatabaseAccessPolicy"]
    D --> E["DBaaS Operator reconciles the CRs<br/>and writes a Secret per service"]
    E --> G["services start on the mounted Secret"]
    G --> H["checks.sh assert --expect-no-legacy-secret"]

    subgraph assert["assert: 28 checks"]
        H1["every service and dbaas-agent pod<br/>is in the mesh"] --> H2["every CR Ready,<br/>every Secret materialized"]
        H2 --> H3["every service ready;<br/>control-plane in operator mode,<br/>no control-plane-db-credentials"]
        H3 --> H4["waypoint metrics: no denied request<br/>from any service or dbaas-agent"]
    end

    H --> H1
    H4 --> R["Surefire report"]

    classDef trap fill:#fde2e1,stroke:#cf222e,color:#1f2328
    classDef proof fill:#dafbe1,stroke:#1a7f37,color:#1f2328
    class C4,F1 trap
    class H4 proof
```

### Upgrade

Yellow steps run Cloud Core from `main`, before the operator.

```mermaid
%%{init: {"flowchart": {"wrappingWidth": 320}}}%%
flowchart TD
    A["Kind cluster"] --> B["setup action: install from main<br/>Istio ambient, DBaaS without the operator,<br/>Cloud Core in legacy mode"]
    B --> C["services create their databases over REST"]
    C --> D["checks.sh snapshot<br/>ask dbaas-aggregator for each service's database<br/>and record its name"]
    D --> E["switch core-bootstrap to the feature branch"]
    E --> F["install only the DBaaS Operator<br/>into the running DBaaS"]
    F --> G["checks.sh arm<br/>(after the snapshot, which itself calls REST<br/>from the Cloud Core namespace)"]
    G --> H["make … deploy-core<br/>upgrade Cloud Core onto the operator"]
    H --> I["DBaaS Operator adopts the existing databases:<br/>same classifier, so no new database"]
    I --> J["checks.sh assert --snapshot"]

    subgraph assert["assert: 33 checks"]
        J1["every service and dbaas-agent pod<br/>is in the mesh"] --> J2["every CR Ready,<br/>every Secret materialized,<br/>every service ready"]
        J2 --> J3{"Secret's database name<br/>= recorded name?"}
        J3 -- "yes" --> J4["adopted"]
        J3 -- "no" --> J5["fail: replaced by a new database"]
        J4 --> J6["waypoint metrics: no denied request<br/>from any service or dbaas-agent"]
    end

    J --> J1
    J6 --> R["Surefire report"]

    classDef legacy fill:#fff8c5,stroke:#9a6700,color:#1f2328
    classDef trap fill:#fde2e1,stroke:#cf222e,color:#1f2328
    classDef proof fill:#dafbe1,stroke:#1a7f37,color:#1f2328
    class B,C,D legacy
    class G,J5 trap
    class J4,J6 proof
```

## Reports

Each job publishes a Surefire report, as the Java suites in this repository do, so the results can join the
integration tests report. With `REPORT_DIR` set, every check is recorded, and `checks.sh report` writes
`surefire-reports/TEST-<suite>.xml` and `reports/surefire.html` there. The workflow uploads them as
`surefire-reports-dbaas-operator-<scenario>`.

The report runs even when an earlier step failed. If the cluster setup failed and no check ran, or a step failed
outside the checks, it records a `setup` error, so a scenario that never ran shows as failed rather than disappearing
from the report.

## Running

The workflow is `DBaaS Operator integration tests`. The core-bootstrap branch is inlined in it as
`OPERATOR_BOOTSTRAP_BRANCH`, because it exists only while this work is open; the bootstrap derives the core-bootstrap
image and the Core service tags from the branches. `CORETPL_CHART_DIR` is inlined for the same reason: the charts need
`coretpl` 0.2.4, which is packaged from the core-bootstrap branch until it is published. Once this merges, drop both,
and the defaults, `main` and `latest`, apply. `test-branch` stays an input, so the service charts can be taken from
another branch.

To run the checks against your own cluster, for example one installed with `cloud-core-local-dev`:

```bash
./checks.sh arm                              # before Cloud Core is installed or upgraded; needs ambient Istio
./checks.sh assert --expect-no-legacy-secret
```

The namespaces default to those of `cloud-core-local-dev`; set `CORE_NAMESPACE`, `PG_NAMESPACE`, or
`DBAAS_NAMESPACE` only if yours differ. `checks.sh` without arguments prints the other subcommands. It needs
`kubectl`, `jq`, and `envsubst`.
