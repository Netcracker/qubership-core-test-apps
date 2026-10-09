"""A stand-in for dbaas-aggregator and maas-service that records how each request authenticates.

It answers every request with an empty JSON object, so a client that gets past authentication goes
on to ask for more, and never uses the answer. What matters is the log: one line per request,

    REQ {"host": ..., "method": ..., "path": ..., "auth": "basic|bearer|none", ...}

with the user name of a Basic credential, or the claims of a bearer token (decoded, not verified:
the stand-in has no key to verify with, and the claims are what a test looks at).

ACCEPT selects what the stand-in lets through, the other kind of credential gets a 401:
  all     Basic and bearer (default)
  basic   Basic only, like a DBaaS or MaaS in the legacy M2M mode
  bearer  bearer only
"""
import base64
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ACCEPT = os.environ.get("ACCEPT", "all")
PORT = int(os.environ.get("PORT", "8080"))
KEPT_CLAIMS = ("iss", "aud", "sub", "exp", "kubernetes.io")


def decode_claims(token):
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        claims = json.loads(base64.urlsafe_b64decode(payload))
    except (IndexError, ValueError):
        return None
    return {k: claims[k] for k in KEPT_CLAIMS if k in claims}


def describe_auth(header):
    """Returns the kind of credential in an Authorization header and what identifies its sender."""
    if not header:
        return "none", {}
    scheme, _, value = header.partition(" ")
    if scheme.lower() == "basic":
        try:
            user = base64.b64decode(value).decode("utf-8", "replace").split(":", 1)[0]
        except ValueError:
            user = None
        return "basic", {"user": user}
    if scheme.lower() == "bearer":
        return "bearer", {"claims": decode_claims(value)}
    return "other", {"scheme": scheme}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def handle_any(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)

        kind, detail = describe_auth(self.headers.get("Authorization"))
        # the probes of the pod itself, and of anyone checking that it is up: always answered, never logged
        probe = self.path.startswith(("/health", "/probes", "/__mock"))
        accepted = probe or ACCEPT == "all" or (ACCEPT == "basic" and kind == "basic") or (ACCEPT == "bearer" and kind == "bearer")
        status = 200 if accepted else 401

        if not probe:
            record = {
                "host": self.headers.get("Host"),
                "method": self.command,
                "path": self.path,
                "auth": kind,
                "status": status,
                "src": self.client_address[0],
            }
            record.update(detail)
            print("REQ " + json.dumps(record, separators=(",", ":")), flush=True)

        body = b"{}" if accepted else b'{"error":"unauthorized"}'
        self.send_response(status)
        if not accepted:
            self.send_header("WWW-Authenticate", "Basic realm=mock" if ACCEPT == "basic" else "Bearer")
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = handle_any

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"mock listening on :{PORT}, accepting {ACCEPT}", flush=True)
    try:
        ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)
