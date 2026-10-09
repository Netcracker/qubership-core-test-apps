"""Summarizes how the core services authenticated to the stand-in for DBaaS and MaaS, and checks it.

usage: report.py --log mock.log --pods pods.json [--basic any|require|forbid] [--bearer any|require|forbid]
                 [--fallback] [--callers a,b,c]

mock.log   the log of the stand-in (lines that start with "REQ ")
pods.json  `kubectl get pods -A -o json`, to name the sender of a Basic credential by its address

Prints a Markdown table, a line per sender, target and kind of credential, and exits 1 when a check
fails:
  --basic / --bearer  the kind of credential must (require), or must not (forbid), be seen at all
  --fallback          some sender is refused with a bearer token and then comes back with Basic
  --callers           these senders must all have been seen
Whatever the options, a bearer token must be the Kubernetes token that sender is meant to send:
issued for the service account of the sender, in the namespace of the core, for the audience of the
target (dbaas or maas).
"""
import argparse
import collections
import json
import re
import sys

CORE_NAMESPACE = "core"
POD_HASH = re.compile(r"-[a-z0-9]{8,10}-[a-z0-9]{5}$|-[a-z0-9]{5}$")


def load_requests(path):
    requests = []
    for line in open(path, encoding="utf-8", errors="replace"):
        # `kubectl logs` may put a timestamp or prefix ahead of the marker
        marker = line.find("REQ {")
        if marker >= 0:
            requests.append(json.loads(line[marker + 4:]))
    return requests


def load_pod_names(path):
    pods = {}
    for item in json.load(open(path, encoding="utf-8")).get("items", []):
        ip = item.get("status", {}).get("podIP")
        if ip:
            pods[ip] = POD_HASH.sub("", item["metadata"]["name"])
    return pods


def target_of(host):
    host = (host or "").split(":")[0]
    if host.startswith("dbaas-aggregator"):
        return "dbaas"
    if host.startswith("maas-service"):
        return "maas"
    return host or "?"


def sender_of(request, pods):
    if request["auth"] == "bearer":
        claims = request.get("claims") or {}
        account = ((claims.get("kubernetes.io") or {}).get("serviceaccount") or {}).get("name")
        if account:
            return account
        return (claims.get("sub") or "?").rsplit(":", 1)[-1]
    return pods.get(request["src"], request["src"])


def token_problems(request, sender):
    claims = request.get("claims")
    if not claims:
        return ["the token has no readable claims"]
    problems = []
    target = target_of(request["host"])
    audience = claims.get("aud")
    audiences = audience if isinstance(audience, list) else [audience]
    if target not in audiences:
        problems.append(f"audience {audience} is not '{target}'")
    expected_subject = f"system:serviceaccount:{CORE_NAMESPACE}:{sender}"
    if claims.get("sub") != expected_subject:
        problems.append(f"subject {claims.get('sub')} is not {expected_subject}")
    return problems


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True)
    parser.add_argument("--pods", required=True)
    parser.add_argument("--basic", choices=["any", "require", "forbid"], default="any")
    parser.add_argument("--bearer", choices=["any", "require", "forbid"], default="any")
    parser.add_argument("--fallback", action="store_true")
    parser.add_argument("--callers", default="")
    args = parser.parse_args()

    requests = load_requests(args.log)
    pods = load_pod_names(args.pods)

    rows = collections.OrderedDict()
    problems = []
    refused_with_bearer = set()
    fell_back = set()
    for request in requests:
        sender = sender_of(request, pods)
        key = (sender, target_of(request["host"]), request["auth"])
        row = rows.setdefault(key, {"ok": 0, "refused": 0, "details": set()})
        row["ok" if request["status"] < 400 else "refused"] += 1
        if request["auth"] == "basic":
            row["details"].add(f"user {request.get('user')}")
            if sender in refused_with_bearer:
                fell_back.add(sender)
        if request["auth"] == "bearer":
            if request["status"] >= 400:
                refused_with_bearer.add(sender)
            for problem in token_problems(request, sender):
                row["details"].add(problem)
                problems.append(f"{sender} -> {key[1]}: {problem}")

    print("### Credentials the core services sent")
    print()
    if not requests:
        print("No request reached the stand-in.")
    else:
        print("| Sender | Target | Credential | Accepted | Refused | Notes |")
        print("| --- | --- | --- | --- | --- | --- |")
        for (sender, target, auth), row in sorted(rows.items()):
            print(f"| {sender} | {target} | {auth} | {row['ok']} | {row['refused']} | {'; '.join(sorted(row['details']))} |")
    print()

    kinds = {auth for (_, _, auth) in rows}
    for kind, want in (("basic", args.basic), ("bearer", args.bearer)):
        if want == "require" and kind not in kinds:
            problems.append(f"no {kind} credential was seen, and one is expected")
        if want == "forbid" and kind in kinds:
            senders = sorted({s for (s, _, a) in rows if a == kind})
            problems.append(f"a {kind} credential was seen from {', '.join(senders)}, and none is expected")
    if args.fallback and not fell_back:
        problems.append("no sender was refused with a bearer token and then sent Basic")
    if args.callers:
        seen = {s for (s, _, _) in rows}
        for caller in args.callers.split(","):
            if caller and caller not in seen:
                problems.append(f"{caller} sent no request")

    if problems:
        print("### Failed checks")
        print()
        for problem in problems:
            print(f"- {problem}")
        sys.exit(1)
    print("All checks passed.")


if __name__ == "__main__":
    main()
