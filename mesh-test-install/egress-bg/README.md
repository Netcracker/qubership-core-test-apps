# Egress Blue/Green tests

An application deployed in a Blue/Green domain sometimes needs different outbound integrations per version: a
`candidate` should talk to a stub, `active` to the real thing. The convention that makes this possible is context
propagation — every business application carries the `x-version-name` header across all of its calls — and the egress
gateway is where the header is turned into a routing decision:

```text
if headers(x-version-name) == "candidate" or headers(x-version-name) == "legacy" {
    forward to "http://stub-endpoint"
} else {
    forward to "http://real-integration.com"
}
```

The tests here pin that down for both meshes: the same requests, the same assertions, run against the Cloud-Core Mesh
`RouteConfiguration` and against the Istio `HTTPRoute` it migrates to.

## What gets deployed

| Piece | Where | Purpose |
|---|---|---|
| `egress-bg-echo` (nginx) + Services `bg-prod`, `bg-stub` | `templates/EgressBgEcho.yaml` | The two endpoints. Mesh-agnostic. |
| its nginx config | `templates/EgressBgEchoConfig.yaml` | Separate file so the Deployment can hash it and roll the pod. |
| `RouteConfiguration` | `templates/EgressBg.yaml` | Cloud-Core Mesh side, guarded by `SERVICE_MESH_TYPE: Core`. |
| `HTTPRoute` | `templates/EgressBg-istio.yaml` | Istio side, guarded by `SERVICE_MESH_TYPE: Istio`. |
| `EgressBgIT` | `mesh-integration-tests/.../spring/EgressBgIT.java` | Assertions, identical for both meshes. |

All of these live in the `mesh-test-service-spring` chart and are switched off by setting `EGRESS_BG_TESTS_ENABLED` to
`false`.

## The two endpoints

One nginx pod listens twice, on 8081 as the production integration and on 8082 as its stub, and a Service named after
each endpoint points at one listener. Every request comes back as JSON naming the endpoint that was reached:

```json
{"endpoint":"bg-stub","host":"egress-gateway","uri":"/hello","xVersionName":"candidate"}
```

`endpoint` is what tells a test which of the two the gateway chose; `xVersionName` shows the header reached it rather
than only having steered the route. `host` is logged, not asserted: Cloud-Core Mesh rewrites it to the endpoint
address, Istio forwards the caller's, and the endpoint's identity is decided by the listener, not by the header. The
echo meets the container security baseline `CoreMicroservicesSecurityIT` enforces — read-only root filesystem,
non-root, all capabilities dropped — so it needs no exclusion there.

## The configuration under test

Applications call the egress gateway by its explicit address — `http://egress-gateway:8080/egress-bg/integration/...` —
and the gateway forwards to one of the two endpoints:

| `x-version-name` | Endpoint | Cloud-Core Mesh rule | Istio rule |
|---|---|---|---|
| `candidate`, `legacy` | `bg-stub` | `safeRegexMatch: "candidate\|legacy"` | `headers[].type: RegularExpression`, same value |
| `active` | `bg-prod` | `exactMatch: active` | `headers[].value: active` |
| absent | `bg-prod` | `presentMatch: true` + `invertMatch: true` | bare path match — see below |

The endpoints are in-cluster Services rather than external hosts, so the destination side of the migration is the
plain `endpoint` → Service `backendRef` mapping.

### Field names in the Cloud-Core Mesh rule

The `Mesh` CR spec goes to the control plane unchanged and is decoded into its `HeaderMatcher` type by field name, so
the specifiers are spelt as in the [control-plane API](https://github.com/Netcracker/qubership-core-control-plane/blob/main/docs/api/control-plane-api.md):
`presentMatch`, `exactMatch`, `safeRegexMatch`, `invertMatch`.

## What the tests assert

| Test | Path | Asserts |
|---|---|---|
| `testEgressGatewaySelectsEndpointByVersionName` | egress gateway, `/egress-bg/integration/hello` | endpoint per header value, prefix rewrite to `/hello`, header reached the endpoint |
| `testVersionNameSurvivesTheApplicationHop` | public gateway → Spring `proxy` → egress gateway | same endpoint per header value after an application carried the context |
| `testProductionTrafficIsRejectedWhenProductionEndpointIsOff` | `/egress-bg/stub-only/hello`, `active` or no header | not `200`, neither endpoint answered |
| `testStubStaysReachableWhenProductionEndpointIsOff` | `/egress-bg/stub-only/hello`, `candidate` or `legacy` | stub endpoint |

The application hop goes through `mesh-test-service-spring`'s `/spring/proxy`, whose m2m client serialises the
`x-version-name` context it captured from the incoming request. A business application built on the same libraries
propagates it the same way; one that does not is exactly what step 3 of the workflow above is meant to catch.

## Running them

```bash
./mesh-test-install/mesh-test-apps.sh install <namespace> Core     # or Istio
./run-it/run-integration-tests.sh <kube-context> <namespace> <node-ip-mapping>
./mesh-test-install/mesh-test-apps.sh uninstall <namespace> Core
```


## Things to check when a scenario fails

- **The echo pod is serving the config you think it is.** `kubectl -n <ns> exec deploy/egress-bg-echo -- cat
  /etc/nginx/egress-bg/nginx.conf` should show the two `listen` blocks; the Deployment hashes
  `EgressBgEchoConfig.yaml` into an annotation so a config change rolls the pod.
- **Every request landed on production.** On Cloud-Core Mesh, check the header matcher spelling first — a dropped
  specifier degrades to "present" and reorders which rule wins. On Istio, check that the stub rule still carries its
  header match; without it the stub and production rules are equally specific and the first one listed wins.
- **The application hop lost the header.** `testEgressGatewaySelectsEndpointByVersionName` passing while
  `testVersionNameSurvivesTheApplicationHop` sends everything to production means the Spring service is not propagating
  `x-version-name`; the egress configuration is fine.
