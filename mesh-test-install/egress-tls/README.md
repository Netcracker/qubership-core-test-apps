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

## Scenarios

Every scenario uses its own external host name. Istio allows one `DestinationRule` per host, so scenarios that differ
only in TLS settings cannot share a host.

| Path prefix | External host | Cloud-Core Mesh | Istio |
|---|---|---|---|
| `/egress-tls/verified` | `verified.external.test` | cluster-level `TlsDef`, `trustedCA`, explicit `sni` | `Secret` with `ca.crt`, `DestinationRule` `mode: SIMPLE` + `credentialName` |
| `/egress-tls/insecure` | `insecure.external.test` | `TlsDef` with `insecure: true` | `DestinationRule` `mode: SIMPLE` + `insecureSkipVerify: true`, no `Secret` |
| `/egress-tls/mtls` | `mtls.external.test` | `TlsDef` with `clientCert` and `privateKey` | `Secret` with `ca.crt` / `tls.crt` / `tls.key`, `DestinationRule` `mode: MUTUAL` |
| `/egress-tls/gwdefault` | `gwdefault.external.test` | gateway-level `TlsDef` (`trustedForGateways`), route names no profile | per-host `DestinationRule` named `<TlsDef>-<host-with-dashes>`, SNI taken from the host |
| `/egress-tls/implicit` | `implicit.external.test` | gateway-level `TlsDef`, and the rule sets no `hostRewrite` | same, plus a `URLRewrite` hostname the source never asked for |

The `verified` route also matches on a `tenant-id: cloud-common` header, which exercises the `headerMatchers` →
`matches[].headers` mapping, and every route strips `Origin` and `Authorization`.

## The authority divergence

Four of the five rules set `hostRewrite` explicitly. nginx picks its server block by the `Host` header, so pinning the
authority keeps those assertions on the configuration under test rather than on gateway defaults.

The fifth, `/egress-tls/implicit`, deliberately sets none, because that is where the two meshes part company. The
migration rules give every egress destination a `URLRewrite` hostname *even when the source has no `hostRewrite`*,
reasoning that Cloud-Core Mesh already uses the cluster endpoint as the upstream authority. That does not hold for the
`Host` header: `NewEgressRouteBuilder` sets `hostRewrite = true`, but the builder only applies a rewrite when the route
carries `HostRewrite` or `HostAutoRewrite`, and nothing populates either for a declarative egress route. With both
empty, Envoy sets no rewrite specifier and forwards the caller's `Host` unchanged.

So on that route Istio sends `implicit.external.test` as the authority and Cloud-Core Mesh sends whatever the caller
used. `testEgressTlsWithoutExplicitHostRewrite` asserts SNI and the path rewrite, which both meshes must agree on, and
logs the authority instead of asserting it. Two consequences worth knowing:

- Migrating a chart whose egress routes omit `hostRewrite` changes the `Host` the external host receives. An upstream
  that routes by `Host` — a shared CDN or an nginx with several `server_name` blocks — can start answering differently
  after migration.
- The echo server treats that as legitimate: its default server answers normally for any SNI it recognizes and returns
  `421` only for an unrecognized one, so an unrewritten authority is not mistaken for an SNI fault.

## Verifying against the skill

The Istio manifests here were written by hand from the mapping rules. The package carrying them
(`core-mesh-crs-to-istio` with `tls-def-mapping.md`) is not installed in this repo — `.agents/skills/` has the older
`core-mesh-crs-to-gatewayapi`, whose `route-configuration-mapping.md` still says `tlsConfigName ... ignore` — and
PR #398 is open. So these tests prove the configuration works at runtime; they do not prove the skill emits it.

Once #398 merges, point `apm.yml` at the merged commit, run the skill against `EgressTls.yaml`, and diff its output
against `EgressTls-istio.yaml`. Any divergence is a bug in one or the other.

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
- **`subKind: TlsDef` reaches the control plane.** The chart wraps `TlsDef` the way it wraps every other Cloud-Core
  Mesh CR, as `core.netcracker.com/v1` `Mesh` with a `subKind`. A `TlsDef` that never arrives shows up as a handshake
  failure rather than a deployment error.
- **The CoreDNS rewrite survived.** `kubectl -n kube-system get cm coredns -o yaml` should contain the marked block.
  A cluster rebuild between install and test run drops it.
