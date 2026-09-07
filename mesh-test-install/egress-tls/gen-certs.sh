#!/bin/bash

# Generates the PKI used by the egress TLS tests.
#
# The mesh test apps simulate an external HTTPS site with an in-cluster nginx
# ("egress TLS echo"). Two independent CAs are produced:
#
#   trusted CA  - handed to the mesh as TlsDef.tls.trustedCA (Core mesh) and as
#                 Secret ca.crt referenced by DestinationRule.credentialName
#                 (Istio). Signs the server certificate for verified.external.test,
#                 gwdefault.external.test, mtls.external.test and
#                 implicit.external.test, and the client certificate the mTLS
#                 scenario presents.
#   rogue CA    - never handed to the mesh. Signs the server certificate for
#                 insecure.external.test, so skipping verification is the only way
#                 a request to that host can succeed.
#
# Certificates are valid for 10 years; regenerate only when the host names change.
#
# Usage: ./gen-certs.sh [output-dir]
# Default output-dir: mesh-test-service-spring/helm-templates/mesh-test-service-spring/files/egress-tls

set -e

# Keep Git Bash on Windows from rewriting the /CN=... subjects into Windows paths.
export MSYS2_ARG_CONV_EXCL="*"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$(dirname "$SCRIPT_DIR")")"
OUT_DIR="${1:-$PROJECT_ROOT/mesh-test-service-spring/helm-templates/mesh-test-service-spring/files/egress-tls}"

DAYS=3650

mkdir -p "$OUT_DIR"
# Kept next to the output rather than in /tmp so that a native Windows openssl
# can resolve the path as well.
WORK_DIR="$(mktemp -d "$OUT_DIR/.pki-XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "Generating egress TLS test PKI into $OUT_DIR"

sign() {
    local csr=$1 out=$2 ca_crt=$3 ca_key=$4 ext=$5
    openssl x509 -req -in "$csr" -out "$out" -days "$DAYS" -sha256 \
        -CA "$ca_crt" -CAkey "$ca_key" -CAserial "$WORK_DIR/ca.srl" -CAcreateserial \
        -extfile "$ext"
}

# --- trusted CA ---------------------------------------------------------------
openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" -sha256 \
    -keyout "$WORK_DIR/ca.key" -out "$OUT_DIR/ca.crt" \
    -subj "/CN=mesh-egress-test-ca/O=qubership-core-test-apps"

# --- rogue CA (deliberately not trusted by the mesh) --------------------------
openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" -sha256 \
    -keyout "$WORK_DIR/rogue-ca.key" -out "$WORK_DIR/rogue-ca.crt" \
    -subj "/CN=mesh-egress-rogue-ca/O=qubership-core-test-apps"

# --- server certificate for the trusted hosts ---------------------------------
printf 'subjectAltName=DNS:verified.external.test,DNS:gwdefault.external.test,DNS:mtls.external.test,DNS:implicit.external.test\nextendedKeyUsage=serverAuth\n' > "$WORK_DIR/server.ext"
openssl req -newkey rsa:2048 -nodes \
    -keyout "$OUT_DIR/server.key" -out "$WORK_DIR/server.csr" \
    -subj "/CN=verified.external.test/O=qubership-core-test-apps"
sign "$WORK_DIR/server.csr" "$OUT_DIR/server.crt" "$OUT_DIR/ca.crt" "$WORK_DIR/ca.key" "$WORK_DIR/server.ext"

# --- server certificate signed by the rogue CA --------------------------------
printf 'subjectAltName=DNS:insecure.external.test\nextendedKeyUsage=serverAuth\n' > "$WORK_DIR/rogue.ext"
openssl req -newkey rsa:2048 -nodes \
    -keyout "$OUT_DIR/rogue-server.key" -out "$WORK_DIR/rogue-server.csr" \
    -subj "/CN=insecure.external.test/O=qubership-core-test-apps"
sign "$WORK_DIR/rogue-server.csr" "$OUT_DIR/rogue-server.crt" "$WORK_DIR/rogue-ca.crt" "$WORK_DIR/rogue-ca.key" "$WORK_DIR/rogue.ext"

# --- client certificate for the mTLS scenario ---------------------------------
printf 'extendedKeyUsage=clientAuth\n' > "$WORK_DIR/client.ext"
openssl req -newkey rsa:2048 -nodes \
    -keyout "$OUT_DIR/client.key" -out "$WORK_DIR/client.csr" \
    -subj "/CN=egress-gateway-client/O=qubership-core-test-apps"
sign "$WORK_DIR/client.csr" "$OUT_DIR/client.crt" "$OUT_DIR/ca.crt" "$WORK_DIR/ca.key" "$WORK_DIR/client.ext"

echo "Done:"
ls -1 "$OUT_DIR"
