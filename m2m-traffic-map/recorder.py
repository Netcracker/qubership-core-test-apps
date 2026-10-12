"""Receives the requests that the waypoints mirror, and records how each of them authenticated.

A waypoint copies a request to this server and goes on with the original, so the answer is never
used: it is always 200 and empty. What is recorded is one line per request,

    MIRROR {"id": ..., "authority": ..., "method": ..., "path": ..., "auth": ..., ...}

id         the x-request-id of the request, which the access log of the waypoint carries as well, so
           that the two can be joined (the access log knows the caller and the answer, the mirror
           knows the credential)
authority  the host the caller used, without the "-shadow" that the mirror adds
auth       "basic", "bearer", "none" or "other", with the user of a Basic credential or the decoded
           claims of a bearer token (not verified: there is no key to verify with)
"""
import base64
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8080"))
KEPT_CLAIMS = ("iss", "aud", "sub", "azp", "client_id", "exp", "iat", "kubernetes.io")
LOG_LOCK = threading.Lock()


def decode_claims(token):
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        claims = json.loads(base64.urlsafe_b64decode(payload))
    except (IndexError, ValueError):
        return None
    return {k: claims[k] for k in KEPT_CLAIMS if k in claims}


def describe_auth(header):
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

        if not self.path.startswith(("/health", "/__recorder")):
            kind, detail = describe_auth(self.headers.get("Authorization"))
            authority = (self.headers.get("Host") or "").replace("-shadow", "", 1)
            record = {
                "id": self.headers.get("X-Request-Id"),
                "authority": authority,
                "method": self.command,
                "path": self.path,
                "auth": kind,
                "size": length,
                "agent": self.headers.get("User-Agent"),
            }
            record.update(detail)
            with LOG_LOCK:
                sys.stdout.write("MIRROR " + json.dumps(record, separators=(",", ":")) + "\n")
                sys.stdout.flush()

        body = b"{}"
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = handle_any

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"recorder listening on :{PORT}", flush=True)
    try:
        ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)
