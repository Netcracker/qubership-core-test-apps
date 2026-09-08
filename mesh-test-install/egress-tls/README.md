# Egress TLS tests

Egress TLS origination is the one part of the mesh that behaves differently on Cloud-Core Mesh and on Istio Ambient
Mesh while having to look identical to a caller. The tests here pin that down: the same requests, the same assertions,
run against both meshes.

A caller reaches the egress gateway over plain HTTP on a path prefix. The gateway opens the HTTPS connection to the
external host, presenting whatever the TLS profile says. Cloud-Core Mesh describes that profile with a `TlsDef`; Istio
describes it with a `ServiceEntry`, a `Secret` and a `DestinationRule`. The migration rules that connect the two live
in the [`core-mesh-crs-to-istio`][skill] skill (`tls-def-mapping.md`).

[skill]: https://github.com/Netcracker/qubership-core-control-plane/tree/main/agent-packages/core-mesh-crs-to-istio

## What gets deployed

| Piece | Where | Purpose |
|---|---|---|
| `egress-tls-echo` (nginx) | `templates/EgressTlsEcho.yaml` | Stands in for the external HTTPS site. Mesh-agnostic. |
| its nginx config | `templates/EgressTlsEchoConfig.yaml` | Separate file so the Deployment can hash it and roll the pod. |
| `TlsDef` + `RouteConfiguration` | `templates/EgressTls.yaml` | Cloud-Core Mesh side, guarded by `SERVICE_MESH_TYPE: Core`. |
| `HTTPRoute` + `ServiceEntry` + `Secret` + `DestinationRule` | `templates/EgressTls-istio.yaml` | Istio side, guarded by `SERVICE_MESH_TYPE: Istio`. |
| `EgressTlsIT` | `mesh-integration-tests/.../spring/EgressTlsIT.java` | Assertions, identical for both meshes. |
| `egress-tls-dns.sh` | `mesh-test-install/egress-tls/` | CoreDNS rewrites for the `*.external.test` host names. |

All of these live in the `mesh-test-service-spring` chart and are switched off by setting `EGRESS_TLS_TESTS_ENABLED`
to `false`.

## The simulated external site

A real external host makes the test depend on the internet and on someone else's certificate rotation. Instead,
`egress-tls-echo` is an nginx in the same namespace that answers on port 8443 and picks its server block — and
therefore its server certificate — by SNI. A gateway that originates TLS with the wrong SNI gets a `421` rather than a
body, so SNI handling is checked rather than assumed.

Each request comes back as JSON reporting what the site saw:

```json
{
  "sni": "verified.external.test",
  "host": "verified.external.test",
  "uri": "/hello",
  "clientVerify": "NONE",
  "clientDn": "",
  "origin": "",
  "authorization": "",
  "tenantId": "cloud-common"
}
```

That covers SNI, the authority after host rewrite, the path after prefix rewrite, the client certificate, and the
headers the egress route was supposed to strip.

### Host names and DNS

The external hosts are named under `.external.test`, a TLD [RFC 6761][rfc6761] reserves and no public resolver answers
for. `egress-tls-dns.sh` adds a CoreDNS rewrite mapping each name onto the `egress-tls-echo` Service:

```text
rewrite name verified.external.test egress-tls-echo.<namespace>.svc.cluster.local
```

`mesh-test-apps.sh` runs that script on install and again on uninstall, so the rewrites arrive and leave with the test
apps. Run it directly to add or drop them without touching the Helm releases:

```bash
./mesh-test-install/egress-tls/egress-tls-dns.sh install <namespace>
./mesh-test-install/egress-tls/egress-tls-dns.sh uninstall <namespace>
```

The rewrites go into the CoreDNS Corefile between two marker comments, applied as a merge patch. Installing twice
replaces the block rather than duplicating it, and uninstalling restores the Corefile to what it was.

Both meshes then resolve the same names: Cloud-Core Mesh in the egress gateway's Envoy, Istio through the
`ServiceEntry`'s `resolution: DNS`. Naming the hosts after an existing Kubernetes Service instead would put the
`ServiceEntry` in conflict with the service registry, and Istio resolves that conflict unpredictably.

[rfc6761]: https://www.rfc-editor.org/rfc/rfc6761#section-6.2

### Two CAs

`gen-certs.sh` produces a trusted CA and a rogue CA. The mesh is given only the trusted one. The host
`insecure.external.test` is served by a certificate from the rogue CA, so the "skip verification" scenario fails
unless verification really is off. A shared CA would let it pass for the wrong reason.

That only holds if the gateway offers SNI. nginx picks the certificate during the handshake, so without SNI it serves
the default server's certificate — the trusted one — and the scenario passes whether verification is on or off. This
is why `egress-insecure-cert` sets `tls.sni` even though it verifies nothing: the SNI is what makes the site present
the untrusted certificate. The cluster run that caught this returned `200` with `sni: ""` before the field was added.

## Scenarios

Every scenario uses its own external host name. Istio allows one `DestinationRule` per host, so scenarios that differ
only in TLS settings cannot share a host.

| Path prefix | External host | Cloud-Core Mesh | Istio |
|---|---|---|---|
| `/egress-tls/verified` | `verified.external.test` | cluster-level `TlsDef`, `trustedCA`, explicit `sni` | `Secret` with `ca.crt`, `DestinationRule` `mode: SIMPLE` + `credentialName` |
| `/egress-tls/insecure` | `insecure.external.test` | `TlsDef` with `insecure: true` and explicit `sni` | `DestinationRule` `mode: SIMPLE` + `insecureSkipVerify: true`, no `Secret` |
| `/egress-tls/mtls` | `mtls.external.test` | `TlsDef` with `clientCert` and `privateKey` | `Secret` with `ca.crt` / `tls.crt` / `tls.key`, `DestinationRule` `mode: MUTUAL` |
| `/egress-tls/gwdefault` | `gwdefault.external.test` | gateway-level `TlsDef` (`trustedForGateways`), route names no profile | per-host `DestinationRule` named `<TlsDef>-<host-with-dashes>`, SNI taken from the host |
| `/egress-tls/implicit` | `implicit.external.test` | gateway-level `TlsDef`, and the rule sets no `hostRewrite` | same, plus a `URLRewrite` hostname the source never asked for |

The `verified` route also matches on a `tenant-id: cloud-common` header, which exercises the `headerMatchers` →
`matches[].headers` mapping.

Every rule strips `Origin` and `Authorization` at **rule level**, not on the virtual service. Four RouteConfigurations
in this chart — the two pre-existing egress ones, the Quarkus one, and this one — all name the `egress-gw` virtual
service on `egress-gateway`, so they merge into one Envoy virtual host and its `removeHeaders` resolves to a single
list. This chart's `["Origin", "Authorization"]` lost to the others' `["Origin"]`, and `Authorization` reached the
external host. Rule-level `removeHeaders` belongs to one route and cannot collide, and it matches the Istio sibling,
where every rule already carries its own `RequestHeaderModifier`.

## Where the two meshes diverge

Two things an egress route controls are not portable, and the tests are written around that rather than pretending
otherwise.

### The authority

Four of the five rules set `hostRewrite` explicitly. nginx picks its server block by the `Host` header, so pinning the
authority keeps those assertions on the configuration under test rather than on gateway defaults.

The fifth, `/egress-tls/implicit`, deliberately sets none. The migration rules give every egress destination a
`URLRewrite` hostname *even when the source has no `hostRewrite`*, reasoning that Cloud-Core Mesh already uses the
cluster endpoint as the upstream authority. That does not hold for the `Host` header: `NewEgressRouteBuilder` sets
`hostRewrite = true`, but the builder only applies a rewrite when the route carries `HostRewrite` or `HostAutoRewrite`,
and nothing populates either for a declarative egress route. With both empty, Envoy sets no rewrite specifier and
forwards the caller's `Host` unchanged.

Migrating a chart whose egress routes omit `hostRewrite` therefore changes the `Host` the external host receives. An
upstream that routes by `Host` — a shared CDN, or an nginx with several `server_name` blocks — can start answering
differently after migration.

### SNI on a gateway-level profile

Cloud-Core Mesh forbids `tls.sni` on a gateway-level `TlsDef`, and derives one from the endpoint only when the control
plane runs with `SNI_PROPAGATION_ENABLED=true`:

```go
// control-plane/envoy/cache/builder/cluster/cluster.go
if propagateSni && aggregatedTlsConfig.SNI == "" {
    aggregatedTlsConfig.SNI = cluster.Endpoints[0].Address
}
```

That flag defaults to `false` (`control-plane/values.yaml`), so a gateway-level profile originates TLS with **no SNI**.
Istio has no gateway-wide profile, so the migration expands it into a per-host `DestinationRule` whose `sni` is the
destination host — meaning Istio always sends one.

The same applies to a cluster-level profile that omits `tls.sni`: Cloud-Core Mesh sends SNI only when the field is
set, while the migration gives every `DestinationRule` an `sni` regardless — `tls.sni` when present, the destination
host otherwise. So all three cluster-level profiles here set `tls.sni` explicitly, which makes their SNI assertable on
both meshes.

That leaves `/egress-tls/gwdefault` and `/egress-tls/implicit` as the two routes that see SNI on Istio and none on
Cloud-Core Mesh, because a gateway-level profile is the one case where `tls.sni` cannot be set.

### What the tests do about it

Each test asserts what both meshes must agree on and logs the rest. A route reaching the external host at all already
proves TLS origination worked, because the site serves a certificate signed by a CA that only the profile under test
carries.

| Route | Asserted on both meshes | Logged, not asserted |
|---|---|---|
| `verified`, `insecure`, `mtls` | SNI, path rewrite, client cert | — |
| `gwdefault` | authority, path rewrite, no client cert | SNI |
| `implicit` | path rewrite, no client cert | authority, SNI |

The echo server matches: it answers normally when SNI is absent, since that is ordinary Cloud-Core Mesh behavior, and
returns `421` only for a non-empty SNI naming a host it does not serve. That keeps the guard against originating TLS
to the wrong host without failing the legitimate no-SNI case.

The table is what a Cloud-Core Mesh cluster actually reports, not what the mapping rules predict:

```text
verified   sni=verified.external.test   host=verified.external.test   origin=""  tenantId=cloud-common
insecure   sni=insecure.external.test   host=insecure.external.test
mtls       sni=mtls.external.test       clientVerify=SUCCESS  clientDn=...CN=egress-gateway-client
gwdefault  sni=""                       host=gwdefault.external.test
implicit   sni=""                       host=implicit.external.test
```

## Verifying against the skill

The Istio manifests were written by hand from the mapping rules, then checked against the skill itself. Install it with:

```bash
apm install "Netcracker/qubership-core-control-plane/agent-packages/core-mesh-crs-to-istio#feature/egress-routes-migration"
```

The check was a blind reproduction: a session with no knowledge of `EgressTls-istio.yaml` was given only
`EgressTls.yaml` and the skill, and asked to convert it. Its output and this file agree on all 14 resources — same
names, same TLS modes, credentials, SNI, ServiceEntry hosts and ports, Secret keys, backendRefs and labels.

Two deviations came out of that run:

- **Rule order.** Five prefixes share two segments, so `path-specificity-sorting` rule 4 breaks the tie
  lexicographically. The rules had been left in source order. Matching was unaffected — the prefixes are disjoint — but
  regenerating would have produced churn. Fixed here.
- **`namespace: {{ .Release.Namespace }}`.** The `ServiceEntry`, `Secret` and `DestinationRule` templates in
  `tls-def-mapping.md` set it, while the `HTTPRoute` template does not. Helm already installs into the release
  namespace, so the field is redundant and the inconsistency across kinds is not deliberate. This chart leaves it out,
  and the skill is being changed to match rather than the other way round.

Re-run that comparison after any change to `EgressTls.yaml`, and again once PR #398 merges, so this file stays what the
migration actually produces rather than a hand-written lookalike.

## Running them

```bash
./mesh-test-install/mesh-test-apps.sh install <namespace> Core     # or Istio
./run-it/run-integration-tests.sh <kube-context> <namespace> <node-ip-mapping>
./mesh-test-install/mesh-test-apps.sh uninstall <namespace> Core
```

`EgressTlsIT` port-forwards the `egress-gateway` Service and calls it directly, so it needs no application endpoint
and behaves the same whichever mesh is installed.

## Regenerating the PKI

The certificates are valid for ten years and are committed under
`mesh-test-service-spring/helm-templates/mesh-test-service-spring/files/egress-tls`. Regenerate them only when the
host names change:

```bash
./mesh-test-install/egress-tls/gen-certs.sh
```

The script needs OpenSSL 1.1.1 or later. On Windows, run it from Git Bash with `/usr/bin` ahead of `/mingw64/bin` on
`PATH`, because the MinGW OpenSSL build does not accept POSIX paths.

## Things to check when a scenario fails

- **`credentialName` resolves in the gateway's namespace.** Istio reads the Secret from the namespace the egress
  gateway pod runs in. When `core-mesh-config` places `egress-gateway` outside the application namespace, the Secrets
  in `EgressTls-istio.yaml` have to move with it.
- **The CoreDNS rewrite survived, and covers every host.** `kubectl -n kube-system get cm coredns -o yaml` should
  contain the marked block with one `rewrite name` per host. A cluster rebuild between install and test run drops the
  block; a host added to the chart but not to `EGRESS_TLS_HOSTS` in `egress-tls-dns.sh` is missing from it, which
  Envoy reports as `503 no healthy upstream` rather than a DNS error. **Adding a scenario means editing three places:**
  the chart, the certificate SANs in `gen-certs.sh`, and that host list.
- **The echo pod is serving the config you think it is.** `kubectl -n core exec deploy/egress-tls-echo -- cat
  /etc/nginx/egress/nginx.conf`. The Deployment hashes `EgressTlsEchoConfig.yaml` into an annotation so a config change
  rolls the pod; a mounted ConfigMap on its own would update in place and leave nginx running the old configuration.
- **`subKind: TlsDef` reaches the control plane.** The chart wraps `TlsDef` the way it wraps every other Cloud-Core
  Mesh CR, as `core.netcracker.com/v1` `Mesh` with a `subKind`. Confirmed working against Cloud-Core Mesh —
  `kubectl -n core get mesh` lists each profile with `subKind=TlsDef`. A `TlsDef` that never arrives shows up as a
  handshake failure rather than a deployment error.
