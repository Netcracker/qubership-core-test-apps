"""Summarizes how the core services authenticated to the stand-in for DBaaS and MaaS, and checks it.

usage: report.py --log mock.log --pods pods.json [--k8s-token any|require|forbid] [--basic ...]
                 [--other-bearer ...] [--basic-by-design a,b] [--callers a,b,c]

mock.log   the log of the stand-in (lines that contain "REQ {")
pods.json  `kubectl get pods -A -o json`, to name the sender of a Basic credential by its address

Prints a Markdown table, a line per sender, target and kind of credential, and exits 1 when a check
fails. The kinds of credential are

  k8s token     a bearer token issued by Kubernetes for a service account
  basic         a user name and password
  other bearer  any other bearer token, such as the legacy M2M token of the identity provider

and each can be required (seen from some sender), forbidden (seen from none), or left alone (any).
Senders named in --basic-by-design send a Basic credential in every mode, whatever M2M_AUTH_MODE is,
and are left out when the Basic credentials are counted. --callers lists the senders that must all
have been seen.

Whatever the options, a k8s token must be the token that sender is meant to send: issued for the
service account of the sender, in the namespace of the core, for the audience of the target (dbaas
or maas).
"""
import argparse
import collections
import json
import re
import sys

CORE_NAMESPACE = "core"
POD_HASH = re.compile(r"-[a-z0-9]{8,10}-[a-z0-9]{5}$|-[a-z0-9]{5}$")
K8S_TOKEN, BASIC, OTHER_BEARER = "k8s token", "basic", "other bearer"


def load_requests(path):
    """Reads the records of the stand-in, which may follow a timestamp or other prefix of a log line."""
    decoder = json.JSONDecoder()
    requests = []
    for line in open(path, encoding="utf-8", errors="replace"):
        start = 0
        while True:
            marker = line.find("REQ {", start)
            if marker < 0:
                break
            try:
                record, end = decoder.raw_decode(line, marker + 4)
            except ValueError:
                break
            requests.append(record)
            start = end
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


def service_account_of(claims):
    account = ((claims.get("kubernetes.io") or {}).get("serviceaccount") or {}).get("name")
    if account:
        return account
    subject = claims.get("sub") or ""
    return subject.rsplit(":", 1)[-1] if subject.startswith("system:serviceaccount:") else None


def kind_of(request):
    if request["auth"] == "basic":
        return BASIC
    if request["auth"] == "bearer":
        claims = request.get("claims") or {}
        if (claims.get("sub") or "").startswith("system:serviceaccount:") or "kubernetes.io" in claims:
            return K8S_TOKEN
        return OTHER_BEARER
    return request["auth"]


def sender_of(request, kind, pods):
    if kind == K8S_TOKEN:
        return service_account_of(request.get("claims") or {}) or "?"
    return pods.get(request["src"], request["src"])


def token_problems(request, sender):
    claims = request.get("claims") or {}
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
    for option in ("k8s-token", "basic", "other-bearer"):
        parser.add_argument("--" + option, choices=["any", "require", "forbid"], default="any")
    parser.add_argument("--basic-by-design", default="")
    parser.add_argument("--callers", default="")
    args = parser.parse_args()

    requests = load_requests(args.log)
    pods = load_pod_names(args.pods)
    by_design = {s for s in args.basic_by_design.split(",") if s}

    rows = collections.OrderedDict()
    problems = []
    for request in requests:
        kind = kind_of(request)
        sender = sender_of(request, kind, pods)
        key = (sender, target_of(request["host"]), kind)
        row = rows.setdefault(key, {"ok": 0, "refused": 0, "notes": set()})
        row["ok" if request["status"] < 400 else "refused"] += 1
        if kind == BASIC:
            note = f"user {request.get('user')}"
            row["notes"].add(note + (", in every mode" if sender in by_design else ""))
        if kind == K8S_TOKEN:
            for problem in token_problems(request, sender):
                row["notes"].add(problem)
                problems.append(f"{sender} -> {key[1]}: {problem}")

    print("### Credentials the core services sent")
    print()
    if not requests:
        print("No request reached the stand-in.")
    else:
        print("| Sender | Target | Credential | Accepted | Refused | Notes |")
        print("| --- | --- | --- | --- | --- | --- |")
        for (sender, target, kind), row in sorted(rows.items()):
            print(f"| {sender} | {target} | {kind} | {row['ok']} | {row['refused']} | {'; '.join(sorted(row['notes']))} |")
    print()

    senders_by_kind = collections.defaultdict(set)
    for (sender, _, kind) in rows:
        if kind == BASIC and sender in by_design:
            continue
        senders_by_kind[kind].add(sender)
    for kind, want in ((K8S_TOKEN, args.k8s_token), (BASIC, args.basic), (OTHER_BEARER, args.other_bearer)):
        if want == "require" and not senders_by_kind[kind]:
            problems.append(f"no {kind} credential was seen, and one is expected")
        if want == "forbid" and senders_by_kind[kind]:
            problems.append(f"a {kind} credential was seen from {', '.join(sorted(senders_by_kind[kind]))}, and none is expected")
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
