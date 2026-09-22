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
| `negative-control` | A claim for a role the service never requests stops that service. If it did not, the other jobs could not detect a fallback |

## Running

The workflow is `DBaaS Operator integration tests`. It takes the branch of the core-bootstrap and service repositories
and the images built from them. core-bootstrap and control-plane carry code changes, so they need images built from
those branches; config-server and site-management only change their charts.

To run the checks against your own cluster, for example one installed with `cloud-core-local-dev`:

```bash
./checks.sh assert --expect-no-legacy-secret
```

The namespaces default to those of `cloud-core-local-dev`; set `CORE_NAMESPACE`, `PG_NAMESPACE`, or
`DBAAS_NAMESPACE` only if yours differ. `checks.sh` without arguments prints the other subcommands. It needs
`kubectl` and `jq`.
