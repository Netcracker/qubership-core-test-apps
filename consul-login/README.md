# Consul login tests

Integration tests for the way a service logs in to Consul: with the projected service account token of its pod
(the kubernetes way), with a JWT of its own that Consul validates against an auth method named after the namespace
of the service (the m2m way), and with the fallback that carries a service from one to the other. Each stack carries
the login library through its own wiring, so every way is checked on Spring, Quarkus and Go separately, against a
real Consul with ACLs on.

## Scenarios

| Scenario | What it establishes |
| --- | --- |
| `ConsulKubernetesLoginIT` | Consul issues a token to a pod that presents its projected token, and the probe prefix stays unreadable without one |
| `SpringServiceKubernetesLoginIT` | The Spring service logs in with its projected token, and logs in again when the token expires |
| `QuarkusServiceKubernetesLoginIT` | The Quarkus service logs in with its projected token |
| `GoServiceKubernetesLoginIT` | The Go service logs in with its projected token |
| `SpringServiceM2MLoginIT` | The Spring service logs in the m2m way |
| `QuarkusServiceM2MLoginIT` | The Quarkus service logs in the m2m way |
| `GoServiceM2MLoginIT` | The Go service logs in the m2m way |
| `SpringServiceMigrationIT` | A Spring pod started before its kubernetes auth method exists serves properties over m2m and moves to the kubernetes way without a restart |
| `QuarkusServiceMigrationIT` | A Quarkus pod started before its kubernetes auth method exists serves properties over m2m and moves to the kubernetes way without a restart, which also covers the relogin on this stack |
| `GoServiceMigrationIT` | A Go pod started before its kubernetes auth method exists serves properties over m2m and moves to the kubernetes way without a restart |

## Layout

| Directory | Holds |
| --- | --- |
| `integration-tests/` | The scenarios and the stand they run on; `.../consullogin/stand/README.md` describes the stand |
| `test-service-spring/` | The Spring service under test, and its `/login-status` endpoint |
| `test-service-quarkus/` | The Quarkus service under test, and its `/login-status` endpoint |
| `test-service-go/` | The Go service under test, and its `/login-status` endpoint |

The workflows in `.github/workflows/`:

| Workflow | Runs |
| --- | --- |
| `consul-login-integration-tests.yml` | The set, on a Kind cluster with Consul and no Cloud Core. Called by `update-integration-tests-report.yml` nightly, and by hand through `workflow_dispatch` |
| `consul-login-test-service-spring-on-commit.yaml` | Builds and analyses the Spring service on a change to it |
| `consul-login-test-service-quarkus-on-commit.yaml` | Builds and analyses the Quarkus service on a change to it |
| `consul-login-test-service-go-on-commit.yaml` | Builds and analyses the Go service on a change to it |

The images are built by the run itself, from the same ref as the scenarios, and loaded into the cluster under the
tag `it`; none of them is published. To run the scenarios by hand, build each service with
`docker build -t consul-login-test-service-<stack>:it consul-login/test-service-<stack>` (the Java ones after
`mvn package`) and load it with `kind load docker-image`, or point the set at another image with
`-Dconsul.login.test.service.<stack>.image`.

The stand itself comes from `.github/actions/setup-kind-with-consul`, which installs Consul with
`global.acls.manageSystemACLs=true` through the `deploy-consul` target of `cloud-core-local-dev` in
[qubership-core-bootstrap](https://github.com/Netcracker/qubership-core-bootstrap).

## When these tests can be removed

The m2m way is what the kubernetes way replaces, so the scenarios that cover it are temporary. Once the m2m way is
removed from the login library, remove with it:

- the m2m scenarios: `SpringServiceM2MLoginIT`, `QuarkusServiceM2MLoginIT`, `GoServiceM2MLoginIT`;
- the migration scenarios: `SpringServiceMigrationIT`, `QuarkusServiceMigrationIT`, `GoServiceMigrationIT`;
- the m2m stand-in of each service and the wiring around it, which signs the tokens those scenarios log in with:
  `StandInM2MManager` on Spring and Quarkus, `standin_token_provider.go` on Go, and `SigningKey` in the stand.

What stays is the kubernetes way: `ConsulKubernetesLoginIT` and the three `*ServiceKubernetesLoginIT` scenarios.
