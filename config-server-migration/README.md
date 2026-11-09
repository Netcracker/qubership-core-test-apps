# Config-server migration and core-legacy-api tests

Integration tests of core-legacy-api (from [qubership-mini-core](https://github.com/Netcracker/qubership-mini-core)),
which serves the config-server API under the Service `config-server`, told apart by JUnit tags:

| Tag | Classes | Runs on |
| --- | --- | --- |
| `migration` | `BeforeMigrationIT`, `AfterMigrationIT` | A running Cloud Core where config-server is replaced: config-server is uninstalled, core-legacy-api is installed on the same Consul, and the properties config-server stored stay readable through the same Service |
| `core-legacy-api` | `CoreLegacyApiIT` | Any stand where core-legacy-api serves the Service: installed in place of config-server from the start, or after the migration |
| `core-legacy-api-consul-acl` | The classes in `consulacl` but `ConsulAclsDisabledIT` | A stand with only the dependencies of Cloud Core, Consul with ACLs among them; the tests install core-legacy-api themselves |
| `core-legacy-api-consul-acls-disabled` | `ConsulAclsDisabledIT` | The same stand with Consul ACLs disabled |

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

## Consul ACL scenarios

These tests check how core-legacy-api gets its Consul token in each Consul auth mode. Each class installs
core-legacy-api from scratch with the make targets of `cloud-core-local-dev`, in the mode it tests:
`deploy-cloud-core-configuration` runs the core-bootstrap job, whose `configserver` task issues or deletes the secret
`config-server-consul-token`, and `deploy-core-legacy-api` installs the service. The checkout of
qubership-core-bootstrap is read from `-Dcore-bootstrap.dir`, by default `core-bootstrap-repo` at the root of this
repository.

| Class | Mode | What it establishes |
| --- | --- | --- |
| `KubernetesModeIT` | `k8s` | The operator applies the `ConsulACL` of the chart; the bootstrap deletes the secret a `legacy` deployment left; the service writes to Consul with the token of its login through `applications-k8s-m2m`; a login as its ServiceAccount may write under `config/<namespace>/` and nowhere else |
| `HybridModeAcceptedAudienceIT` | `hybrid` | With the audience `netcracker` accepted by the auth method, the bootstrap deletes the secret and the service logs in |
| `HybridModeRejectedAudienceIT` | `hybrid` | With the audience removed from the auth method, the bootstrap issues a static token with the policy `<namespace>_config-edit`, and the service writes with it |
| `StartBeforeConsulAclIT` | `k8s` | Started while the Consul ACL operator is stopped, the service is not ready, and becomes ready once the operator applies its `ConsulACL` |
| `ConsulAclsDisabledIT` | `k8s` | On Consul with ACLs disabled, the service writes without a token |

Cloud Core as a whole does not run on Consul with ACLs, so the stand has only the dependencies of Cloud Core and the
service under test.

## Running

The workflow `core-legacy-api-integration-tests.yml` installs Cloud Core with core-legacy-api in place of
config-server and runs the `core-legacy-api` tests. Its second job installs only the dependencies of Cloud Core, once
with Consul ACLs and once without, and runs the Consul ACL scenarios. The nightly report includes both. The input
`test-branch` selects the branch of qubership-mini-core and the core-bootstrap image, and `core-bootstrap-branch` the
checkout whose make targets install the stand.

The workflow `config-server-migration-integration-tests.yml` runs both migration phases on a Kind cluster, then the
`core-legacy-api` tests on the migrated stand, after the after-migration phase has read the answers it compares. It is
started by hand only; the nightly report does not include it. Its inputs:

| Input | Default | Sets |
| --- | --- | --- |
| `core-bootstrap-branch` | `main` | The branch of [qubership-core-bootstrap](https://github.com/Netcracker/qubership-core-bootstrap) that installs the stand |
| `core-legacy-api-branch` | `main` | The branch of qubership-mini-core whose chart and image replace config-server |
| `config-server-branch` | `main` | The branch of qubership-core-config-server whose chart and image the stand starts with |
