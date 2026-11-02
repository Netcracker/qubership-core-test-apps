# Config-server migration and core-legacy-api tests

Integration tests of core-legacy-api (from [qubership-mini-core](https://github.com/Netcracker/qubership-mini-core)),
which serves the config-server API under the Service `config-server`, in two kinds, told apart by JUnit tags:

| Tag | Classes | Runs on |
| --- | --- | --- |
| `migration` | `BeforeMigrationIT`, `AfterMigrationIT` | A running Cloud Core where config-server is replaced: config-server is uninstalled, core-legacy-api is installed on the same Consul, and the properties config-server stored stay readable through the same Service |
| `core-legacy-api` | `CoreLegacyApiIT` | Any stand where core-legacy-api serves the Service: installed in place of config-server from the start, or after the migration |

## Migration scenarios

The migration tests run in two phases, one on each side of the replacement, each in a Maven run of its own selected
with `-Dtest`.

| Phase | Scenario | What it establishes |
| --- | --- | --- |
| `before-migration` | `BeforeMigrationIT` | config-server on Consul stores the test properties in the Consul KV; its answers to a fixed list of requests and the KV under `config/core/` are recorded as the baseline |
| `after-migration` | `AfterMigrationIT` | Every key config-server stored keeps its value, including a changed default; core-legacy-api answers the baseline requests as config-server did |

The answers are compared after the normalizations the core-legacy-api migration guide allows: the order of applications
and profiles in `/applications`, the order of keys in JSON, the order of lines in `.properties`, and the formatting of
`.yaml`. Any other difference fails the comparison. The KV check allows keys core-legacy-api adds and prints them; the
answer comparison does not, so a property core-legacy-api adds to `global` or a new application fails it.

The baseline is kept in the ConfigMap `config-server-migration-baseline` in the namespace `core`. The
`before-migration` phase deletes it first, so `after-migration` fails with a message naming it when no baseline was
recorded.

## core-legacy-api scenarios

`CoreLegacyApiIT` checks that a default core-legacy-api writes on startup is served from `global` and stored in Consul;
that the first property source of `GET /{application}/default`, the only one the Go and Quarkus config clients read,
has the properties of the application over those of `global`; and that a POST, a PUT, and a `properties-delete` reach
the API and Consul. Each test writes its own data under the
application `core-legacy-api-it`, which the migration data and requests do not use. Run them with
`-Dgroups=core-legacy-api`.

## Running

The workflow `core-legacy-api-integration-tests.yml` installs Cloud Core with core-legacy-api in place of
config-server and runs the `core-legacy-api` tests. The nightly report includes it.

The workflow `config-server-migration-integration-tests.yml` runs both migration phases on a Kind cluster, then the
`core-legacy-api` tests on the migrated stand, after the after-migration phase has read the answers it compares. It is
started by hand only; the nightly report does not include it. Its inputs:

| Input | Default | Sets |
| --- | --- | --- |
| `core-bootstrap-branch` | `main` | The branch of [qubership-core-bootstrap](https://github.com/Netcracker/qubership-core-bootstrap) that installs the stand |
| `core-legacy-api-branch` | `main` | The branch of qubership-mini-core whose chart and image replace config-server |
| `config-server-branch` | `main` | The branch of qubership-core-config-server whose chart and image the stand starts with |
