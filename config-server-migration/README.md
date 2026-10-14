# Config-server migration tests

Integration tests for replacing config-server with core-legacy-api (from
[qubership-mini-core](https://github.com/Netcracker/qubership-mini-core)) on a running Cloud Core: config-server is
uninstalled, core-legacy-api is installed on the same Consul, and the properties config-server stored stay readable and
writable through the same Service `config-server`.

## Scenarios

The tests run in two phases, one on each side of the replacement.

| Phase | Scenario | What it establishes |
| --- | --- | --- |
| `before-migration` | `BeforeMigrationIT` | config-server on Consul stores the test properties in the Consul KV; its answers to a fixed list of requests and the KV under `config/core/` are recorded as the baseline |
| `after-migration` | `AfterMigrationIT` | Every key config-server stored keeps its value, including a changed default; core-legacy-api answers the baseline requests as config-server did; a POST, a PUT, and a `properties-delete` through core-legacy-api reach the API and Consul |

The answers are compared after the normalizations the core-legacy-api migration guide allows: the order of applications
and profiles in `/applications`, the order of keys in JSON, the order of lines in `.properties`, and the formatting of
`.yaml`. Any other difference fails the comparison. The KV check allows keys core-legacy-api adds and prints them; the
answer comparison does not, so a property core-legacy-api adds to `global` or a new application fails it.

The baseline is kept in the ConfigMap `config-server-migration-baseline` in the namespace `core`. The
`before-migration` phase deletes it first, so `after-migration` fails with a message naming it when no baseline was
recorded.

## Running

The workflow `config-server-migration-integration-tests.yml` runs both phases on a Kind cluster and is started by
hand only; the nightly report does not include it. Its inputs:

| Input | Default | Sets |
| --- | --- | --- |
| `core-bootstrap-branch` | `main` | The branch of [qubership-core-bootstrap](https://github.com/Netcracker/qubership-core-bootstrap) that installs the stand |
| `core-legacy-api-branch` | `main` | The branch of qubership-mini-core whose chart and image replace config-server |
| `config-server-tag` | `latest` | The image tag of config-server the stand starts with |

To run the phases against a stand of your own, from the current kubeconfig context:

1. In `cloud-core-local-dev` of qubership-core-bootstrap, install Cloud Core with config-server on Consul:
   `make install CONFIG_SERVER_CONSUL_ENABLED=true`.
2. In this repository, run the `before-migration` phase:
   `mvn -f config-server-migration/integration-tests/pom.xml test -P before-migration`.
3. In `cloud-core-local-dev`, replace config-server with core-legacy-api:
   `make uninstall-config-server deploy-core-legacy-api INSTALL_CONFIG_SERVER=false INSTALL_CORE_LEGACY_API=true`.
4. In this repository, run the `after-migration` phase:
   `mvn -f config-server-migration/integration-tests/pom.xml test -P after-migration`.
   It changes the properties it compares, so it runs once per stand: to repeat it, start again from step 1.

The tests reach config-server, core-legacy-api, and Consul through port forwards.
