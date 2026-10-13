"""Builds the map of interactions from the access logs of the waypoints, and checks the credentials.

usage: traffic_map.py --access-log FILE [FILE...] --pods pods.json --pod-ips pod-ips.txt
                      [--out-md map.md] [--out-json map.json]
                      [--core-mode MODE --dependency-mode MODE] [--core-namespace NS]

access log  lines of JSON with "kind":"capture", one per request that passed a waypoint: who sent it
            (the identity of the peer, or its address), to which service, which request, which answer,
            the header and claims of a bearer token (without its signature) or the user of a Basic
            credential
pods        `kubectl get pods -A -o json`
pod-ips     lines of "<address> <pod name>" taken while the services ran

Prints the map in Markdown: a diagram and a table of the interactions, the requests that were refused
with a Kubernetes token and then made another way (a fallback), and the credentials of each sender.
The tunnels of the mesh (CONNECT) and the configuration streams of the gateways (xDS) are counted but
left off the map.

The checks look at credentials, not at who calls whom: with the DBaaS Operator, for one, the core
services make no call to DBaaS at all. Each sender is one of

  core     a service in the core namespace, other than an agent or a hook
  agent    dbaas-agent and maas-agent
  shown    anything else, such as dbaas-operator and the install hooks: on the map, not checked

and with --core-mode and --dependency-mode (legacy, hybrid or k8s) these must hold:

  - the bearer tokens of the core services fit the mode of the core: legacy tokens only for a legacy
    core, Kubernetes tokens only for a k8s core, and for a hybrid core Kubernetes tokens, and legacy
    tokens only in a fallback. A Basic credential of a core service is shown, not checked;
  - the agents call dbaas-aggregator and maas-service with a Basic credential;
  - a core service falls back when a hybrid core meets a legacy dependency, and in no other case.

A bearer token that is not a Kubernetes token, whether it is a JWT or not, is the legacy token.
Whatever the modes, every Kubernetes token seen must be the token its sender is meant to send: issued
for the service account of the sender, in the namespace of the sender, and for the audience of DBaaS
(dbaas) or of MaaS (maas) where it goes to one of them. Exit status 1 when a check fails.
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
DEPENDENCIES = ("dbaas-aggregator", "dbaas-agent", "maas-service", "maas-agent")
MODES = ("legacy", "hybrid", "k8s")
# the agents carry the legacy calls of the core to DBaaS and MaaS, with a Basic credential
AGENTS = ("dbaas-agent", "maas-agent")
AGENT_TARGETS = ("dbaas-aggregator", "maas-service")
# senders that are on the map but not checked, wherever they run: the install hooks
SHOWN_ONLY = ("*-pre-hook", "*-post-hook", "*-job-*", "dbaas-operator")
GROUP_ORDER = {"core": 0, "agent": 1, "shown": 2}
GROUP_LABELS = {"core": "core service", "agent": "agent", "shown": "shown only"}
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


def load_pod_namespaces(pods_path):
    """The namespace of each pod, by the name the map gives its sender."""
    namespaces = {}
    try:
        for item in json.load(open(pods_path, encoding="utf-8")).get("items", []):
            namespaces[POD_HASH.sub("", item["metadata"]["name"])] = item["metadata"].get("namespace")
    except (OSError, ValueError):
        pass
    return namespaces


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


def claims_of(record):
    """The claims of the bearer token of the request, from the header and claims the access log keeps."""
    jwt = record.get("jwt")
    if jwt and jwt != "-":
        try:
            payload = jwt.split(".")[1]
            payload += "=" * (-len(payload) % 4)
            return json.loads(base64.urlsafe_b64decode(payload))
        except (IndexError, ValueError):
            pass
    return None


def credential_of(record, claims):
    """The kind of the credential of the request."""
    scheme = (record.get("auth") or "").strip().lower()
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


def group_of(caller, namespace, core_namespace):
    """Which rules apply to the sender of a request: core, agent, or shown (no rule)."""
    if caller in AGENTS:
        return "agent"
    if any(fnmatch.fnmatch(caller, pattern) for pattern in SHOWN_ONLY):
        return "shown"
    return "core" if namespace == core_namespace else "shown"


def token_verdict(request, core_mode):
    """Why the bearer token of a request of a core service does not fit the mode of the core, or None."""
    credential = request["credential"]
    if core_mode == "legacy" and credential == "k8s-token":
        return "a Kubernetes token from a legacy core"
    if core_mode == "k8s" and credential == "legacy-token":
        return "a legacy token from a k8s core"
    if core_mode == "hybrid" and credential == "legacy-token" and not request.get("fallback"):
        return "a legacy token from a hybrid core, not in a fallback"
    return None


def check(interactions, fallbacks, core_mode, dependency_mode):
    """The rules of the modes. Returns the problems, one line each."""
    problems = collections.Counter()
    for request in interactions:
        if request["group"] == "core":
            verdict = token_verdict(request, core_mode)
            if verdict:
                problems[f"{request['caller']} > {request['target']}: {verdict}"] += 1
        elif (request["group"] == "agent" and request["target"] in AGENT_TARGETS
              and request["credential"] != "basic"):
            problems[f"{request['caller']} > {request['target']}: {request['credential']}, not Basic"] += 1
    lines = [f"{problem} (x{count})" for problem, count in problems.items()]
    groups = {request["caller"]: request["group"] for request in interactions}
    core_fallbacks = sorted({caller for (caller, _t, _m, _p) in fallbacks if groups.get(caller) == "core"})
    if core_mode == "hybrid" and dependency_mode == "legacy":
        if not core_fallbacks:
            lines.append("no core service fell back, although a hybrid core is refused by a legacy dependency")
    elif core_fallbacks:
        lines.append(f"{', '.join(core_fallbacks)} fell back, which only a hybrid core against a legacy dependency does")
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--access-log", nargs="+", required=True)
    parser.add_argument("--pods", required=True)
    parser.add_argument("--pod-ips", required=True)
    parser.add_argument("--out-md")
    parser.add_argument("--out-json")
    parser.add_argument("--core-mode", choices=MODES)
    parser.add_argument("--dependency-mode", choices=MODES)
    parser.add_argument("--core-namespace", default="core")
    args = parser.parse_args()

    accesses = load_access_log(args.access_log)

    pods = load_pod_names(args.pods, args.pod_ips)
    namespaces = load_pod_namespaces(args.pods)

    requests = []
    for record in accesses:
        claims = claims_of(record)
        user = record.get("user")
        caller = caller_of(record, pods)
        namespace = caller_namespace(record) or namespaces.get(caller)
        requests.append({
            "start": record.get("start"),
            "caller": caller,
            "target": target_of(record.get("authority"), pods),
            "method": record.get("method"),
            "path": path_template(record.get("path")),
            "code": str(record.get("code")),
            "credential": credential_of(record, claims),
            "user": user if user and user != "-" else None,
            "agent": record.get("agent"),
            "bytes": record.get("bytes"),
            "namespace": namespace,
            "group": group_of(caller, namespace, args.core_namespace),
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
        edge["credentials"][credential_label(request)] += 1
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
            request["fallback"] = True
            fallbacks.setdefault((request["caller"], first["target"], request["method"], request["path"]),
                                 collections.Counter())[(request["target"], request["credential"], request["code"])] += 1

    out = ["### Interactions", ""]
    if not requests:
        out.append("No request passed a waypoint.")
    else:
        out.append(f"{len(interactions)} requests between services passed a waypoint. {len(infrastructure)} more are tunnels of the "
                   "mesh and configuration streams of the gateways.")
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
        for (caller, target), edge in sorted(edges.items(), key=lambda e: (e[0][1] not in DEPENDENCIES, e[0])):
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

    # The credentials of each sender, and the rule that applies to it.
    senders = collections.OrderedDict()
    for request in interactions:
        if request["credential"] == "none":
            continue
        sender = senders.setdefault(request["caller"], {"group": request["group"], "credentials": collections.Counter()})
        sender["credentials"][credential_label(request) + (" (fallback)" if request.get("fallback") else "")] += 1
    out.append("")
    out.append("### Credentials")
    out.append("")
    if senders:
        out.append("| Sender | Checked as | Credentials |")
        out.append("| --- | --- | --- |")
        for caller, sender in sorted(senders.items(), key=lambda s: (GROUP_ORDER[s[1]["group"]], s[0])):
            credentials = ", ".join(f"{c} x{n}" for c, n in sorted(sender["credentials"].items()))
            out.append(f"| {caller} | {GROUP_LABELS[sender['group']]} | {credentials} |")
    else:
        out.append("No request carried a credential.")

    problems = []
    if args.core_mode and args.dependency_mode:
        problems = check(interactions, list(fallbacks), args.core_mode, args.dependency_mode)
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
    if args.core_mode and args.dependency_mode:
        out.append("")
        out.append(f"### Checks (core {args.core_mode}, dependencies {args.dependency_mode})")
        out.append("")
        if problems:
            out.extend(f"- failed: {problem}" for problem in problems)
        else:
            out.append("The credentials of the core services and the agents fit the modes.")
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
            "problems": problems,
        }, open(args.out_json, "w", encoding="utf-8"), indent=1)
    if problems:
        sys.exit(1)


def credential_label(request):
    return request["credential"] + (f" ({request['user']})" if request["user"] else "")


if __name__ == "__main__":
    main()
