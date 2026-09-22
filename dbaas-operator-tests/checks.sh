#!/usr/bin/env bash
# Checks that Cloud Core resolves its databases through the DBaaS Operator, with no REST fallback.
#
# Usage:
#   checks.sh snapshot <file>                      record each service's database while in legacy mode
#   checks.sh assert [--snapshot <file>] [--expect-no-legacy-secret]
#   checks.sh break-claim <service>                recreate the service's claim with a mismatched role
#   checks.sh expect-not-ready <service>           fail unless the service's rollout never becomes ready
#
# The Cloud Core values must point API_DBAAS_ADDRESS at a service name that does not resolve
# (CORE_DBAAS_SERVICE_NAME in cloud-core-local-dev). The namespace label stays real, so the CRs and
# the RBAC still render, while any REST call from a Cloud Core service, directly or through
# dbaas-agent, fails. A service that reaches Ready has therefore resolved its database from the
# Secret the DBaaS Operator mounted.
set -euo pipefail

CORE_NAMESPACE="${CORE_NAMESPACE:-core}"
PG_NAMESPACE="${PG_NAMESPACE:-postgres}"
DBAAS_NAMESPACE="${DBAAS_NAMESPACE:-dbaas}"
DBAAS_SERVICE_NAME="${DBAAS_SERVICE_NAME:-dbaas-aggregator}"
FAKE_DBAAS_SERVICE_NAME="${FAKE_DBAAS_SERVICE_NAME:-dbaas-rest-fallback-forbidden}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-600s}"
NOT_READY_TIMEOUT="${NOT_READY_TIMEOUT:-300s}"

# Every Cloud Core service that owns a database. Keep in sync with the charts.
SERVICES=(control-plane config-server site-management)
# Services whose chart converts a legacy DbPolicy into a DatabaseAccessPolicy.
POLICY_SERVICES=(config-server site-management)

failures=0

pass() { echo "PASS: $*"; }
fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

# The classifier each service sends at runtime, without the namespace.
classifier() {
  case "$1" in
    site-management) echo '{"microserviceName":"site-management","scope":"service","dbClassifier":"default"}' ;;
    *) printf '{"microserviceName":"%s","scope":"service"}' "$1" ;;
  esac
}

kube() { kubectl -n "$CORE_NAMESPACE" "$@"; }

container_env() { # deployment, variable
  kube get deployment "$1" -o json |
    jq -r --arg name "$2" '.spec.template.spec.containers[0].env[]? | select(.name == $name) | .value'
}

claim_for() { # service -> the service's DatabaseSecretClaim as JSON
  kube get databasesecretclaims.dbaas.netcracker.com -l "app.kubernetes.io/name=$1" -o json | jq '.items[0] // empty'
}

# Asks dbaas-aggregator, from inside the cluster, which database a service's classifier resolves to.
aggregator_database() {
  local service="$1" user password body
  user="$(kubectl -n "$PG_NAMESPACE" get secret dbaas-aggregator-registration-credentials -o jsonpath='{.data.username}' | base64 -d)"
  password="$(kubectl -n "$PG_NAMESPACE" get secret dbaas-aggregator-registration-credentials -o jsonpath='{.data.password}' | base64 -d)"
  body="$(jq -nc --argjson c "$(classifier "$service")" --arg ns "$CORE_NAMESPACE" --arg origin "$service" \
    '{classifier: ($c + {namespace: $ns}), originService: $origin}')"
  kube run "dbaas-lookup-$service-$RANDOM" --rm -i --quiet --restart=Never --image=curlimages/curl:8.11.1 -- \
    curl -sSf -u "$user:$password" -H 'Content-Type: application/json' -X POST -d "$body" \
    "http://$DBAAS_SERVICE_NAME.$DBAAS_NAMESPACE.svc.cluster.local:8080/api/v3/dbaas/$CORE_NAMESPACE/databases/get-by-classifier/postgresql" |
    jq -r '.connectionProperties.name'
}

cmd_snapshot() {
  local file="$1" snapshot='{}' name
  for service in "${SERVICES[@]}"; do
    name="$(aggregator_database "$service")"
    [[ -n "$name" && "$name" != "null" ]] || { echo "no database registered for $service" >&2; exit 1; }
    echo "$service -> $name"
    snapshot="$(jq -c --arg s "$service" --arg n "$name" '. + {($s): $n}' <<<"$snapshot")"
  done
  echo "$snapshot" >"$file"
}

check_resources() {
  local kind="$1" expected="$2" items
  items="$(kube get "$kind.dbaas.netcracker.com" -o json)"
  if [[ "$(jq '.items | length' <<<"$items")" == "$expected" ]]; then
    pass "$expected $kind"
  else
    fail "expected $expected $kind, found $(jq -c '[.items[].metadata.name]' <<<"$items")"
  fi
  if kube wait --for=condition=Ready "$kind.dbaas.netcracker.com" --all --timeout="$WAIT_TIMEOUT" >/dev/null; then
    pass "all $kind Ready"
  else
    fail "not every $kind is Ready: $(jq -c '[.items[] | {name: .metadata.name, phase: .status.phase}]' <<<"$items")"
  fi
}

cmd_assert() {
  local snapshot="" expect_no_legacy_secret=false
  while (($#)); do
    case "$1" in
      --snapshot) snapshot="$2"; shift 2 ;;
      --expect-no-legacy-secret) expect_no_legacy_secret=true; shift ;;
      *) echo "unknown option $1" >&2; exit 2 ;;
    esac
  done

  # 1. The REST fallback is unreachable. Without this every later PASS proves nothing.
  for deployment in "${SERVICES[@]}" dbaas-agent; do
    local address
    address="$(container_env "$deployment" API_DBAAS_ADDRESS)"
    if [[ "$address" == "http://$FAKE_DBAAS_SERVICE_NAME.$DBAAS_NAMESPACE."* ]]; then
      pass "$deployment API_DBAAS_ADDRESS does not resolve ($address)"
    else
      fail "$deployment API_DBAAS_ADDRESS is '$address', so a REST fallback could still succeed"
    fi
  done

  # 2. The operator reconciled every resource the charts declare.
  check_resources internaldatabases "${#SERVICES[@]}"
  check_resources databasesecretclaims "${#SERVICES[@]}"
  check_resources databaseaccesspolicies "${#POLICY_SERVICES[@]}"

  # 3. Each service has its Secret, and runs on it.
  for service in "${SERVICES[@]}"; do
    local claim secret
    claim="$(claim_for "$service")"
    [[ -n "$claim" ]] || { fail "$service has no DatabaseSecretClaim"; continue; }
    secret="$(jq -r .spec.secretName <<<"$claim")"
    if kube get secret "$secret" -o json | jq -e '.data["metadata.json"] and .data["connectionProperties.json"]' >/dev/null; then
      pass "$service Secret $secret is materialized"
    else
      fail "$service Secret $secret is missing or incomplete"
    fi
    if kube rollout status "deployment/$service" --timeout="$WAIT_TIMEOUT" >/dev/null; then
      pass "$service is ready with no reachable REST fallback"
    else
      fail "$service did not become ready"
    fi
    if [[ -n "$snapshot" ]]; then
      local before after
      before="$(jq -r --arg s "$service" '.[$s]' "$snapshot")"
      after="$(kube get secret "$secret" -o json | jq -r '.data["connectionProperties.json"] | @base64d | fromjson | .name')"
      if [[ "$before" == "$after" ]]; then
        pass "$service adopted its existing database $before"
      else
        fail "$service now uses $after; before the upgrade it used $before"
      fi
    fi
  done

  # 4. control-plane runs in operator mode, without the core-bootstrap credentials.
  if [[ "$(container_env control-plane DBAAS_OPERATOR_ENABLED)" == "true" ]]; then
    pass "control-plane has DBAAS_OPERATOR_ENABLED=true"
  else
    fail "control-plane is not in operator mode"
  fi
  if kube get deployment control-plane -o json | jq -e '[.spec.template.spec.volumes[]?.name] | index("pod-secrets")' >/dev/null; then
    fail "control-plane still mounts the legacy pod-secrets volume"
  else
    pass "control-plane does not mount pod-secrets"
  fi
  if [[ "$expect_no_legacy_secret" == true ]]; then
    if kube get secret control-plane-db-credentials >/dev/null 2>&1; then
      fail "core-bootstrap still wrote control-plane-db-credentials"
    else
      pass "core-bootstrap did not write control-plane-db-credentials"
    fi
  fi

  if ((failures)); then
    echo "$failures check(s) failed" >&2
    exit 1
  fi
  echo "All checks passed"
}

cmd_break_claim() {
  local service="$1" claim name
  claim="$(claim_for "$service")"
  [[ -n "$claim" ]] || { echo "$service has no DatabaseSecretClaim" >&2; exit 1; }
  name="$(jq -r .metadata.name <<<"$claim")"
  # spec.userRole cannot change once set, so recreate the claim. The service requests an empty role,
  # so an explicit one is a lookup key the client never asks for.
  kube delete databasesecretclaim.dbaas.netcracker.com "$name" --wait
  jq '{apiVersion, kind, metadata: {name: .metadata.name, namespace: .metadata.namespace, labels: .metadata.labels},
       spec: (.spec + {userRole: "admin"})}' <<<"$claim" | kubectl apply -f -
  kube wait --for=condition=Ready "databasesecretclaim.dbaas.netcracker.com/$name" --timeout="$WAIT_TIMEOUT"
  kube rollout restart "deployment/$service"
}

cmd_expect_not_ready() {
  local service="$1"
  if kube rollout status "deployment/$service" --timeout="$NOT_READY_TIMEOUT"; then
    echo "FAIL: $service became ready with a mismatched claim, so these checks cannot detect a fallback" >&2
    exit 1
  fi
  echo "PASS: $service did not become ready with a mismatched claim"
}

case "${1:-}" in
  snapshot) cmd_snapshot "$2" ;;
  assert) shift; cmd_assert "$@" ;;
  break-claim) cmd_break_claim "$2" ;;
  expect-not-ready) cmd_expect_not_ready "$2" ;;
  *) sed -n '2,8p' "$0"; exit 2 ;;
esac
