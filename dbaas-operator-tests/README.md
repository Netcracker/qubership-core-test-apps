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
