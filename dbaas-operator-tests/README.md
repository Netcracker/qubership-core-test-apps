# DBaaS Operator integration tests

These tests check that Cloud Core services get their databases from the DBaaS Operator, through the Secrets it
mounts, and never fall back to provisioning over REST. The services covered are control-plane, config-server, and
site-management.

## How a fallback is caught

Cloud Core is installed with `CORE_DBAAS_SERVICE_NAME` set to a service name that does not resolve. Only the service
name in `API_DBAAS_ADDRESS` changes; the namespace label stays real, so each chart still renders the right
`spec.operatorNamespace` and the `dbaas-operator-secrets` RoleBinding. Any REST call from a Cloud Core service then
fails, whether it goes to dbaas-aggregator directly or through dbaas-agent, which forwards to the same address. A
service that reaches Ready has therefore resolved its database from the operator's Secret.

The DBaaS installation, Patroni, and MaaS keep using the real aggregator name through `DBAAS_SERVICE_NAME`.

## Scenarios

| Job | What it proves |
|---|---|
| `clean-install` | Cloud Core installed onto a cluster that already runs the operator comes up, every CR is `Ready`, and core-bootstrap no longer writes `control-plane-db-credentials` |
| `upgrade` | Cloud Core installed as today, then upgraded after the operator is added, keeps the databases it already had: each claim resolves to the database recorded before the upgrade |

## Reports

Each job publishes a Surefire report, as the Java suites in this repository do, so the results can join the
integration tests report. With `REPORT_DIR` set, every check is recorded, and `checks.sh report` writes
`surefire-reports/TEST-<suite>.xml` and `reports/surefire.html` there. The workflow uploads them as
`surefire-reports-dbaas-operator-<scenario>`.

The report runs even when an earlier step failed. If the cluster setup failed and no check ran, or a step failed
outside the checks, it records a `setup` error, so a scenario that never ran shows as failed rather than disappearing
from the report.

## Running

The workflow is `DBaaS Operator integration tests`. The core-bootstrap branch and the core-bootstrap and
control-plane images are inlined in it, because they exist only while this work is open: those two carry code
changes, while config-server and site-management only change their charts. Once this merges, drop them from the
workflow and the action's defaults, `main` and `latest`, apply. `test-branch` stays an input, so the service charts
can be taken from another branch.

To run the checks against your own cluster, for example one installed with `cloud-core-local-dev`:

```bash
./checks.sh assert --expect-no-legacy-secret
```

The namespaces default to those of `cloud-core-local-dev`; set `CORE_NAMESPACE`, `PG_NAMESPACE`, or
`DBAAS_NAMESPACE` only if yours differ. `checks.sh` without arguments prints the other subcommands. It needs
`kubectl` and `jq`.
