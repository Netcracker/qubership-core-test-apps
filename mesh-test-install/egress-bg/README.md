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
`false`. Nothing here is shared with the egress TLS tests, and nothing outside the chart is touched: the endpoints are
plain in-cluster Services, so no DNS setup is needed.

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
plain `endpoint` → Service `backendRef` mapping. The external-host side — `ServiceEntry`, `Hostname` backendRefs, TLS
origination — is covered by the egress TLS tests; what this suite adds is the header matching and the gate.

### Field names in the Cloud-Core Mesh rule

The `Mesh` CR spec goes to the control plane unchanged and is decoded into its `HeaderMatcher` type by field name, so
the specifiers are spelt as in the [control-plane API](https://github.com/Netcracker/qubership-core-control-plane/blob/main/docs/api/control-plane-api.md):
`presentMatch`, `exactMatch`, `safeRegexMatch`, `invertMatch`. The design page for this feature writes `present: true`
and `regexMatch:` instead. Those keys are unknown to the decoder and dropped without an error, and a header matcher
with no specifier left degrades to "header present" — so the stub rule as written there would catch **every** request
carrying `x-version-name`, `active` included. The chart uses the API names; the tests are what would catch a
regression to the wrong ones.

### `EGRESS_PROD_INTEGRATION_ENABLED`

The production rules are wrapped in this flag. The intended workflow:

1. Warm up the Blue/Green domain.
2. Roll the candidate out with `EGRESS_PROD_INTEGRATION_ENABLED=false`, so the production endpoint is absent from the
   rendered configuration entirely, not just unrouted.
3. Test the candidate. A request that lost its `x-version-name` on the way matches nothing and fails — that is the
   signal, and it beats a quiet call to production.
4. Fix whatever dropped the context.
5. Right before `promote`, roll out again with the flag on.

Two things to keep in mind when copying the gated templates:

- **The gate produces an error, not a fallback to production.** On Cloud-Core Mesh that error is a 404. On Istio it
  is whatever the catch-all answers — see [An unmatched path](#an-unmatched-path) below.
- **Never gate every rule.** The stub rule stays unconditional so a candidate's own traffic still has somewhere to
  go, and so the `HTTPRoute` is never left with an empty `rules` list, which fails to apply.

The tests cannot redeploy the chart between cases, so `/egress-bg/stub-only` is rendered alongside `/egress-bg/integration`
as what the latter becomes with the flag off: the stub rule only. `testProductionTrafficIsRejectedWhenProductionEndpointIsOff`
asserts that `active` and no header reach neither endpoint on it, `testStubStaysReachableWhenProductionEndpointIsOff`
that the stub is still there.

## Where the two meshes diverge

### Negated header matches

Gateway API has no negated header match. Cloud-Core Mesh expresses "header absent" as `presentMatch: true` +
`invertMatch: true`; the [migration rules](https://github.com/Netcracker/qubership-core-control-plane/tree/main/agent-packages/core-mesh-crs-to-istio)
drop that matcher, flag the rule for review, and leave a bare path match behind. Gateway API precedence is decided by
specificity rather than list order, so the two rules with a header match still win for `candidate`, `legacy` and
`active`, and the bare rule takes the rest.

"The rest" is where the meshes differ. Cloud-Core Mesh answers 404 to a value that is neither `active` nor
`candidate|legacy`, because nothing matches it; Istio sends it to production, because the bare rule does. Nothing in a
Blue/Green domain sends such a value — `x-version-name` is `active`, `candidate` or `legacy`, or absent — so the tests
do not cover it. A chart that has to reject unknown values on Istio would need an explicit rule for them, which Gateway
API cannot express without listing the accepted ones.

### An unmatched path

Cloud-Core Mesh has nothing to match and returns `404`. The Istio egress gateway that `core-mesh-config` installs
carries a catch-all `HTTPRoute` to `egress-fallback-service`, which is the Cloud-Core Mesh egress gateway, so an
unmatched path is handed to that instead. What comes back depends on whether the Cloud-Core Mesh gateway has any
routes of its own: with the test apps deployed in `Core` mode it does, and answers `404`; in `Istio` mode nothing
registers routes on it, Envoy opens no listener, and the Istio gateway reports the refused connection as `503`:

```text
"GET /egress-bg/stub-only/hello HTTP/1.1" 503 UC upstream_reset_before_response_started{connection_termination}
```

`testProductionTrafficIsRejectedWhenProductionEndpointIsOff` therefore asserts that the response is not `200` and
names neither endpoint, rather than a status. It uses a client without the shared retry-on-503 interceptor, which
would otherwise spend two minutes on each of these cases.

### One regular expression, not two exact matches

The Istio route matches `candidate|legacy` with a single `RegularExpression` header match, which is what
`safeRegexMatch` migrates to. Two `Exact` entries under separate `matches` items work as well; two `headers` entries
under **one** match do not, because per the Gateway API spec only the first of several equivalent header names is
considered, so `legacy` would silently fall through to the production rule.

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

`EgressBgIT` port-forwards the `egress-gateway` and `public-gateway-service` Services and calls them directly, so it
behaves the same whichever mesh is installed. All twelve cases pass against both Cloud-Core Mesh and Istio.

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
