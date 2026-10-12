"""Builds the map of interactions from the access logs of the waypoints and the recorder.

usage: traffic_map.py --access-log FILE [FILE...] --mirror FILE --pods pods.json --pod-ips pod-ips.txt
                      [--out-md map.md] [--out-json map.json]

access log  lines of JSON with "kind":"capture", one per request that passed a waypoint: who sent it
            (the identity of the peer, or its address), to which service, which request, which answer
mirror      lines "MIRROR {...}" of the recorder: the credential of the same request (same id)
pods        `kubectl get pods -A -o json`
pod-ips     lines of "<address> <pod name>" taken while the services ran

Prints the map in Markdown: a diagram, a table of interactions between services, the interactions
with DBaaS and MaaS and their agents, and the requests where a Kubernetes token was refused and the
sender tried another credential.
"""
import argparse
import collections
import json
import re
import sys

POD_HASH = re.compile(r"-[a-z0-9]{8,10}-[a-z0-9]{5}$|-[a-z0-9]{5}$")
SPIFFE = re.compile(r"spiffe://[^/]+/ns/([^/]+)/sa/([^,;\s]+)")
ID_PART = re.compile(r"^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{16,}|\d+)$")
DATABASES = ("dbaas-aggregator", "dbaas-agent", "maas-service", "maas-agent")


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


def service_of(authority):
    host = (authority or "").split(":")[0]
    return host.split(".")[0] or "?"


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


def credential_of(record, mirror):
    """The credential of the request, from the mirrored copy when there is one."""
    if mirror:
        if mirror["auth"] == "basic":
            return f"basic ({mirror.get('user')})"
        if mirror["auth"] == "bearer":
            claims = mirror.get("claims") or {}
            if "kubernetes.io" in claims or (claims.get("sub") or "").startswith("system:serviceaccount:"):
                return "k8s token"
            return "legacy token"
        return mirror["auth"]
    prefix = (record.get("auth") or "").strip().lower()
    if prefix.startswith("basic"):
        return "basic"
    if prefix.startswith("bearer"):
        return "bearer"
    return "none" if not prefix or prefix == "-" else "other"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--access-log", nargs="+", required=True)
    parser.add_argument("--mirror", required=True)
    parser.add_argument("--pods", required=True)
    parser.add_argument("--pod-ips", required=True)
    parser.add_argument("--out-md")
    parser.add_argument("--out-json")
    args = parser.parse_args()

    accesses = load_access_log(args.access_log)
    mirrors = load_mirror(args.mirror)
    pods = load_pod_names(args.pods, args.pod_ips)

    requests = []
    for record in accesses:
        mirror = mirrors.get(record.get("id"))
        requests.append({
            "start": record.get("start"),
            "caller": caller_of(record, pods),
            "target": service_of(record.get("authority")),
            "method": record.get("method"),
            "path": path_template(record.get("path")),
            "code": str(record.get("code")),
            "credential": credential_of(record, mirror),
            "mirrored": bool(mirror),
            "claims": (mirror or {}).get("claims"),
        })

    edges = collections.OrderedDict()
    for request in requests:
        edge = edges.setdefault((request["caller"], request["target"]), {
            "requests": 0, "codes": collections.Counter(), "credentials": collections.Counter(),
            "paths": collections.Counter(), "mirrored": 0})
        edge["requests"] += 1
        edge["codes"][request["code"]] += 1
        edge["credentials"][request["credential"]] += 1
        edge["paths"][f"{request['method']} {request['path']}"] += 1
        edge["mirrored"] += request["mirrored"]

    # a Kubernetes token that was refused, followed by another credential for the same request
    fallbacks = collections.Counter()
    last_refused = {}
    for request in sorted(requests, key=lambda r: r["start"] or ""):
        key = (request["caller"], request["target"], request["method"], request["path"])
        if request["credential"] == "k8s token" and request["code"] == "401":
            last_refused[key] = True
        elif key in last_refused and request["credential"] not in ("k8s token", "none"):
            fallbacks[(key, request["credential"], request["code"])] += 1

    out = []
    out.append("### Interactions")
    out.append("")
    if not requests:
        out.append("No request passed a waypoint.")
    else:
        mirrored = sum(r["mirrored"] for r in requests)
        out.append(f"{len(requests)} requests passed a waypoint, {mirrored} of them were also mirrored with their credential.")
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
            out.append(f'  {node(caller)}["{caller}"] -->|"{edge["requests"]} · {credentials} · {codes}"| {node(target)}["{target}"]')
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
            out.append("| Caller | Target | Request | Then sent | Answer |")
            out.append("| --- | --- | --- | --- | --- |")
            for ((caller, target, method, path), credential, code), count in sorted(fallbacks.items()):
                out.append(f"| {caller} | {target} | `{method} {path}` | {credential} | {code} (x{count}) |")
        else:
            out.append("No request was refused with a Kubernetes token and then repeated with another credential.")
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
            "fallbacks": [{"caller": k[0][0], "target": k[0][1], "method": k[0][2], "path": k[0][3],
                           "then": k[1], "code": k[2], "count": n} for k, n in fallbacks.items()],
        }, open(args.out_json, "w", encoding="utf-8"), indent=1)


if __name__ == "__main__":
    main()
