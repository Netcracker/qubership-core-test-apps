"""Builds the map of interactions from the access logs of the waypoints and the recorder.

usage: traffic_map.py --access-log FILE [FILE...] --mirror FILE --pods pods.json --pod-ips pod-ips.txt
                      [--out-md map.md] [--out-json map.json] [--expect RULE [RULE...]]

access log  lines of JSON with "kind":"capture", one per request that passed a waypoint: who sent it
            (the identity of the peer, or its address), to which service, which request, which answer,
            and the header and claims of a bearer token (without its signature)
mirror      lines "MIRROR {...}" of the recorder: the credential of the same request (same id), with
            the user of a Basic credential, which the access log does not have
pods        `kubectl get pods -A -o json`
pod-ips     lines of "<address> <pod name>" taken while the services ran

Prints the map in Markdown: a diagram, a table of the interactions, and the requests that were
refused with a Kubernetes token and then made another way (a fallback). The tunnels of the mesh
(CONNECT) and the configuration streams of the gateways (xDS) are counted but left off the map.

--expect checks the map. A rule is one of

  edge:CALLER>TARGET                        the caller sent a request to the target
  no-edge:CALLER>TARGET                     it did not
  credential:CALLER>TARGET=KIND             the caller sent a request with that credential
  no-credential:CALLER>TARGET=KIND          it did not
  refused:CALLER>TARGET=KIND                a request with that credential was answered 401 or 403
  fallback:CALLER                           a request of the caller was refused with a Kubernetes
                                            token and then made again, to the target it first used
                                            or to another
  no-fallback:CALLER                        no request of the caller was

CALLER and TARGET may be * or have wildcards (dbaas-*). KIND is basic, k8s-token or legacy-token (a
bearer token that is not a Kubernetes token, whether it is a JWT or not). Exit status 1 when a rule
fails.

Whatever the rules, every Kubernetes token the recorder saw must be the token its sender is meant to
send: issued for the service account of the sender, in the namespace of the sender, and for the
audience of DBaaS (dbaas) or of MaaS (maas) where it goes to one of them. A token that is not is
listed under "Tokens" and fails the run.
"""
import argparse
import base64
import collections
import fnmatch
import json
import re
import sys

POD_HASH = re.compile(r"-[a-z0-9]{8,10}-[a-z0-9]{5}$|-[a-z0-9]{5}$")
SPIFFE = re.compile(r"spiffe://[^/]+/ns/([^/]+)/sa/([^,;\s]+)")
ID_PART = re.compile(r"^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{16,}|\d+)$")
ADDRESS = re.compile(r"^\d+\.\d+\.\d+\.\d+$")
DATABASES = ("dbaas-aggregator", "dbaas-agent", "maas-service", "maas-agent")
# the audience a Kubernetes token has to carry for these targets
AUDIENCES = {"dbaas-aggregator": "dbaas", "maas-service": "maas"}


def load_access_log(paths):
    """The access log of a waypoint: lines of JSON that Envoy writes, among the other lines of the proxy."""
    records = []
    for path in paths:
        for line in open(path, encoding="utf-8", errors="replace"):
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                record = json.loads(line)
            except ValueError:
                continue
            if record.get("kind") == "capture":
                records.append(record)
    return records


def load_mirror(path):
    """The records of the recorder, by request id."""
    decoder = json.JSONDecoder()
    records = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        at = line.find("MIRROR {")
        if at < 0:
            continue
        try:
            record, _ = decoder.raw_decode(line, at + len("MIRROR "))
        except ValueError:
            continue
        if record.get("id"):
            records[record["id"]] = record
    return records


def load_pod_names(pods_path, pod_ips_path):
    names = collections.defaultdict(set)
    try:
        for item in json.load(open(pods_path, encoding="utf-8")).get("items", []):
            ip = item.get("status", {}).get("podIP")
            if ip:
                names[ip].add(POD_HASH.sub("", item["metadata"]["name"]))
    except (OSError, ValueError):
        pass
    try:
        for line in open(pod_ips_path, encoding="utf-8", errors="replace"):
            fields = line.split()
            if len(fields) == 2:
                names[fields[0]].add(POD_HASH.sub("", fields[1]))
    except OSError:
        pass
    return {ip: "/".join(sorted(found)) for ip, found in names.items()}


def target_of(authority, pods):
    host = (authority or "").split(":")[0]
    if ADDRESS.match(host):
        return pods.get(host, host)
    return host.split(".")[0] or "?"


def caller_namespace(record):
    match = SPIFFE.search(record.get("src_san") or "")
    return match.group(1) if match else None


def token_problems(request):
    """What is wrong with the Kubernetes token of a request: not its sender's, or not for its target."""
    claims = request.get("claims") or {}
    problems = []
    if request["namespace"]:
        subject = f"system:serviceaccount:{request['namespace']}:{request['caller']}"
        if claims.get("sub") != subject:
            problems.append(f"subject {claims.get('sub')} is not {subject}")
    audience = AUDIENCES.get(request["target"])
    if audience:
        found = claims.get("aud")
        if audience not in (found if isinstance(found, list) else [found]):
            problems.append(f"audience {found} is not '{audience}'")
    return problems


def caller_of(record, pods):
    match = SPIFFE.search(record.get("src_san") or "")
    if match:
        return match.group(2)
    for field in ("src_addr", "src_direct"):
        address = (record.get(field) or "").rsplit(":", 1)[0]
        if address in pods:
            return pods[address]
    return record.get("src_addr") or "unknown"


def path_template(path):
    path = (path or "").split("?")[0]
    return "/".join("{id}" if ID_PART.match(part) else part for part in path.split("/")) or "/"


def claims_of(record, mirror):
    """The claims of the bearer token of the request: from the access log, or from the mirrored copy."""
    jwt = record.get("jwt")
    if jwt and jwt != "-":
        try:
            payload = jwt.split(".")[1]
            payload += "=" * (-len(payload) % 4)
            return json.loads(base64.urlsafe_b64decode(payload))
        except (IndexError, ValueError):
            pass
    return (mirror or {}).get("claims")


def credential_of(record, mirror, claims):
    """The kind of the credential of the request."""
    scheme = (record.get("auth") or "").strip().lower()
    if mirror and mirror["auth"] != "none":
        scheme = mirror["auth"]
    if scheme.startswith("basic"):
        return "basic"
    if scheme.startswith("bearer"):
        # A Kubernetes token is a JWT, and its claims are known. Any other bearer token, one the
        # claims of which are unknown because it is not a JWT, is the legacy token.
        if claims and ("kubernetes.io" in claims or (claims.get("sub") or "").startswith("system:serviceaccount:")):
            return "k8s-token"
        return "legacy-token"
    return "none" if scheme in ("", "-", "none") else scheme


def is_infrastructure(request):
    """The tunnels of the mesh and the configuration streams of the gateways, not an interaction of services."""
    return (request["method"] == "CONNECT" or request["target"] == "xds_cluster"
            or request["path"].startswith("/envoy.service."))


def check(rules, requests, edges, fallbacks):
    problems = []
    for rule in rules:
        kind, _, spec = rule.partition(":")
        fallback_callers = {c for (c, _t, _m, _p) in fallbacks}
        if kind == "fallback":
            if not any(fnmatch.fnmatch(c, spec) for c in fallback_callers):
                problems.append(f"{rule}: no request of {spec} was refused with a Kubernetes token and made again")
            continue
        if kind == "no-fallback":
            fell_back = sorted(c for c in fallback_callers if fnmatch.fnmatch(c, spec))
            if fell_back:
                problems.append(f"{rule}: {', '.join(fell_back)} made a refused request again")
            continue
        edge, _, wanted = spec.partition("=")
        caller_pattern, _, target_pattern = edge.partition(">")
        matching = [r for r in requests if fnmatch.fnmatch(r["caller"], caller_pattern)
                    and fnmatch.fnmatch(r["target"], target_pattern) and not is_infrastructure(r)]
        if kind in ("credential", "no-credential", "refused"):
            matching = [r for r in matching if r["credential"] == wanted]
            if kind == "refused":
                matching = [r for r in matching if r["code"] in ("401", "403")]
        elif kind not in ("edge", "no-edge"):
            problems.append(f"{rule}: unknown rule")
            continue
        if kind in ("edge", "credential", "refused") and not matching:
            problems.append(f"{rule}: not seen")
        if kind in ("no-edge", "no-credential") and matching:
            problems.append(f"{rule}: seen {len(matching)} times, first {matching[0]['caller']} > {matching[0]['target']} "
                            f"{matching[0]['method']} {matching[0]['path']} ({matching[0]['credential']})")
    return problems


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--access-log", nargs="+", required=True)
    parser.add_argument("--mirror", required=True)
    parser.add_argument("--pods", required=True)
    parser.add_argument("--pod-ips", required=True)
    parser.add_argument("--out-md")
    parser.add_argument("--out-json")
    parser.add_argument("--expect", nargs="*", default=[])
    args = parser.parse_args()

    accesses = load_access_log(args.access_log)
    mirrors = load_mirror(args.mirror)
    pods = load_pod_names(args.pods, args.pod_ips)

    requests = []
    for record in accesses:
        mirror = mirrors.get(record.get("id"))
        claims = claims_of(record, mirror)
        requests.append({
            "start": record.get("start"),
            "caller": caller_of(record, pods),
            "target": target_of(record.get("authority"), pods),
            "method": record.get("method"),
            "path": path_template(record.get("path")),
            "code": str(record.get("code")),
            "credential": credential_of(record, mirror, claims),
            "mirrored": bool(mirror),
            "namespace": caller_namespace(record),
            "claims": claims,
        })
    infrastructure = [r for r in requests if is_infrastructure(r)]
    interactions = [r for r in requests if not is_infrastructure(r)]

    edges = collections.OrderedDict()
    for request in interactions:
        edge = edges.setdefault((request["caller"], request["target"]), {
            "requests": 0, "codes": collections.Counter(), "credentials": collections.Counter(),
            "paths": collections.Counter()})
        edge["requests"] += 1
        edge["codes"][request["code"]] += 1
        edge["credentials"][request["credential"]] += 1
        edge["paths"][f"{request['method']} {request['path']}"] += 1

    # A request that a Kubernetes token did not get through, and that the same caller then makes
    # again, to the same target with another credential or to another target (the agent).
    fallbacks = collections.OrderedDict()
    refused = {}
    for request in sorted(interactions, key=lambda r: r["start"] or ""):
        key = (request["caller"], request["method"], request["path"])
        if request["credential"] == "k8s-token" and request["code"] in ("401", "403"):
            refused[key] = request
        elif key in refused and (request["target"] != refused[key]["target"] or request["credential"] != "k8s-token"):
            first = refused[key]
            fallbacks.setdefault((request["caller"], first["target"], request["method"], request["path"]),
                                 collections.Counter())[(request["target"], request["credential"], request["code"])] += 1

    out = ["### Interactions", ""]
    if not requests:
        out.append("No request passed a waypoint.")
    else:
        mirrored = sum(r["mirrored"] for r in interactions)
        out.append(f"{len(interactions)} requests between services passed a waypoint, {mirrored} of them were also mirrored with "
                   f"their credential. {len(infrastructure)} more are tunnels of the mesh and configuration streams of the gateways.")
        out.append("")
        out.append("```mermaid")
        out.append("flowchart LR")
        names = {}

        def node(name):
            names.setdefault(name, f"n{len(names)}")
            return names[name]

        for (caller, target), edge in edges.items():
            credentials = ", ".join(sorted(edge["credentials"]))
            codes = ", ".join(f"{c}x{n}" for c, n in sorted(edge["codes"].items()))
            out.append(f'  {node(caller)}["{caller}"] -->|"{edge["requests"]} req, {credentials}, {codes}"| {node(target)}["{target}"]')
        out.append("```")
        out.append("")
        out.append("| Caller | Target | Requests | Credentials | Answers | Requests (most frequent) |")
        out.append("| --- | --- | --- | --- | --- | --- |")
        for (caller, target), edge in sorted(edges.items(), key=lambda e: (e[0][1] not in DATABASES, e[0])):
            credentials = ", ".join(f"{c} x{n}" for c, n in sorted(edge["credentials"].items()))
            codes = ", ".join(f"{c} x{n}" for c, n in sorted(edge["codes"].items()))
            paths = "<br>".join(f"`{p}` x{n}" for p, n in edge["paths"].most_common(4))
            out.append(f"| {caller} | {target} | {edge['requests']} | {credentials} | {codes} | {paths} |")
        out.append("")
        out.append("### Fallbacks")
        out.append("")
        if fallbacks:
            out.append("| Caller | Request | Refused by | Then made to | With | Answer |")
            out.append("| --- | --- | --- | --- | --- | --- |")
            for (caller, first_target, method, path), then in fallbacks.items():
                for (target, credential, code), count in then.items():
                    out.append(f"| {caller} | `{method} {path}` | {first_target} | {target} | {credential} | {code} (x{count}) |")
        else:
            out.append("No request was refused with a Kubernetes token and then made again.")

    problems = check(args.expect, interactions, edges, list(fallbacks))
    wrong_tokens = collections.Counter()
    for request in interactions:
        if request["credential"] == "k8s-token" and request["claims"]:
            for problem in token_problems(request):
                wrong_tokens[(request["caller"], request["target"], problem)] += 1
    out.append("")
    out.append("### Tokens")
    out.append("")
    checked = sum(1 for r in interactions if r["credential"] == "k8s-token" and r["claims"])
    if wrong_tokens:
        out.append("| Caller | Target | Problem | Requests |")
        out.append("| --- | --- | --- | --- |")
        for (caller, target, problem), count in sorted(wrong_tokens.items()):
            out.append(f"| {caller} | {target} | {problem} | {count} |")
            problems.append(f"token of {caller} to {target}: {problem}")
    else:
        out.append(f"All {checked} Kubernetes tokens seen are their sender's, in its namespace, for the audience of their target.")
    if args.expect:
        out.append("")
        out.append("### Expectations")
        out.append("")
        if problems:
            out.extend(f"- failed: {problem}" for problem in problems)
        else:
            out.append(f"All {len(args.expect)} expectations hold.")
    out.append("")
    text = "\n".join(out)
    print(text)
    if args.out_md:
        open(args.out_md, "w", encoding="utf-8").write(text + "\n")
    if args.out_json:
        json.dump({
            "requests": requests,
            "edges": [{"caller": c, "target": t, "requests": e["requests"], "codes": dict(e["codes"]),
                       "credentials": dict(e["credentials"]), "paths": dict(e["paths"])}
                      for (c, t), e in edges.items()],
            "fallbacks": [{"caller": k[0], "refused_by": k[1], "method": k[2], "path": k[3],
                           "then": [{"target": t, "credential": c, "code": code, "count": n} for (t, c, code), n in v.items()]}
                          for k, v in fallbacks.items()],
        }, open(args.out_json, "w", encoding="utf-8"), indent=1)
    if problems:
        sys.exit(1)


if __name__ == "__main__":
    main()
