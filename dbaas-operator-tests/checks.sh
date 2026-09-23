#!/usr/bin/env bash
# Checks that Cloud Core resolves its databases through the DBaaS Operator, with no REST fallback.
#
# Usage:
#   checks.sh snapshot <file>                      record each service's database while in legacy mode
#   checks.sh assert [--snapshot <file>] [--expect-no-legacy-secret]
#   checks.sh report <suite> [--job-status <s>]    write the recorded checks as a Surefire report
#
# The Cloud Core values must point API_DBAAS_ADDRESS at a service name that does not resolve
# (CORE_DBAAS_SERVICE_NAME in cloud-core-local-dev). The namespace label stays real, so the CRs and
# the RBAC still render, while any REST call from a Cloud Core service, directly or through
# dbaas-agent, fails. A service that reaches Ready has therefore resolved its database from the
# Secret the DBaaS Operator mounted.
#
# With REPORT_DIR set, every check is also recorded there, and `report` turns the records into
# REPORT_DIR/surefire-reports/TEST-<suite>.xml and REPORT_DIR/reports/surefire.html, the layout the
# integration tests report reads.
set -euo pipefail

CORE_NAMESPACE="${CORE_NAMESPACE:-core}"
PG_NAMESPACE="${PG_NAMESPACE:-postgres}"
DBAAS_NAMESPACE="${DBAAS_NAMESPACE:-dbaas}"
DBAAS_SERVICE_NAME="${DBAAS_SERVICE_NAME:-dbaas-aggregator}"
FAKE_DBAAS_SERVICE_NAME="${FAKE_DBAAS_SERVICE_NAME:-dbaas-rest-fallback-forbidden}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-600s}"
REPORT_DIR="${REPORT_DIR:-}"

# Every Cloud Core service that owns a database. Keep in sync with the charts.
SERVICES=(control-plane config-server site-management)
# Services whose chart converts a legacy DbPolicy into a DatabaseAccessPolicy.
POLICY_SERVICES=(config-server site-management)

failures=0
last_record="$(date +%s.%N)"

# Appends one check to REPORT_DIR/results.tsv: status, name, seconds since the previous check, message.
record() {
  [[ -n "$REPORT_DIR" ]] || return 0
  local now seconds
  now="$(date +%s.%N)"
  seconds="$(awk -v a="$last_record" -v b="$now" 'BEGIN { printf "%.3f", b - a }')"
  last_record="$now"
  mkdir -p "$REPORT_DIR"
  printf '%s\t%s\t%s\t%s\n' "$1" "$(printf '%s' "$2" | tr '\t\n' '  ')" "$seconds" "$(printf '%s' "${3:-}" | tr '\t\n' '  ')" \
    >>"$REPORT_DIR/results.tsv"
}

pass() {
  echo "PASS: $*"
  record pass "$*"
}
fail() {
  echo "FAIL: $*" >&2
  record fail "$*" "$*"
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
# The request runs from the control-plane pod, which has curl, as cloud-core-local-dev does for its
# own cleanup: that needs no extra image and no pod of its own.
aggregator_database() {
  local service="$1" user password body response name attempt
  user="$(kubectl -n "$PG_NAMESPACE" get secret dbaas-aggregator-registration-credentials -o jsonpath='{.data.username}' | base64 -d)"
  password="$(kubectl -n "$PG_NAMESPACE" get secret dbaas-aggregator-registration-credentials -o jsonpath='{.data.password}' | base64 -d)"
  if [[ -z "$user" || -z "$password" ]]; then
    echo "secret $PG_NAMESPACE/dbaas-aggregator-registration-credentials has no username or password" >&2
    return 1
  fi
  body="$(jq -nc --argjson c "$(classifier "$service")" --arg ns "$CORE_NAMESPACE" --arg origin "$service"     '{classifier: ($c + {namespace: $ns}), originService: $origin}')"

  for attempt in 1 2 3; do
    # Both streams are captured, so a failure is reported instead of vanishing.
    response="$(kube exec deploy/control-plane -- curl -sS -u "$user:$password"       -H 'Content-Type: application/json' -X POST -d "$body"       "http://$DBAAS_SERVICE_NAME.$DBAAS_NAMESPACE.svc.cluster.local:8080/api/v3/dbaas/$CORE_NAMESPACE/databases/get-by-classifier/postgresql" 2>&1)" || {
      echo "attempt $attempt: the request failed: $(head -c 300 <<<"$response")" >&2
      sleep 5
      continue
    }
    name="$(jq -r '.connectionProperties.name // empty' <<<"$response" 2>/dev/null || true)"
    if [[ -n "$name" ]]; then
      echo "$name"
      return 0
    fi
    echo "attempt $attempt: no database name in the response: $(head -c 300 <<<"$response")" >&2
    sleep 5
  done
  return 1
}

cmd_snapshot() {
  local file="$1" snapshot='{}' name
  for service in "${SERVICES[@]}"; do
    # config-server and site-management create their database over REST when they start, which can be
    # after the install returns, so wait for each service before asking the aggregator.
    if ! kube rollout status "deployment/$service" --timeout="$WAIT_TIMEOUT" >/dev/null; then
      fail "$service did not become ready, so its database cannot be recorded"
      exit 1
    fi
    if ! name="$(aggregator_database "$service")"; then
      fail "cannot look up the database of $service in dbaas-aggregator; see the errors above"
      exit 1
    fi
    pass "recorded the $service database $name"
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

xml_escape() {
  sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' <<<"$1"
}

# Writes the recorded checks as a Surefire report. The integration tests report reads the counts from
# the first <testsuite> tag, so the file has exactly one, with no <testsuites> wrapper around it.
cmd_report() {
  local suite="$1" job_status="success"
  shift
  while (($#)); do
    case "$1" in
      --job-status) job_status="$2"; shift 2 ;;
      *) echo "unknown option $1" >&2; exit 2 ;;
    esac
  done
  [[ -n "$REPORT_DIR" ]] || { echo "REPORT_DIR is not set" >&2; exit 2; }
  mkdir -p "$REPORT_DIR/surefire-reports" "$REPORT_DIR/reports"
  local results="$REPORT_DIR/results.tsv"
  touch "$results"

  # A step that failed outside the checks, such as the cluster setup, must not leave a report that
  # only lists passes, or a scenario that never ran would look healthy.
  if [[ "$job_status" != "success" ]] && ! grep -q '^fail' "$results"; then
    local message="a workflow step outside the checks failed (job status: $job_status); see the job log"
    [[ -s "$results" ]] || message="no checks ran: the cluster setup or an earlier step failed (job status: $job_status)"
    printf 'error\tsetup\t0\t%s\n' "$message" >>"$results"
  fi

  local tests failed errors total
  tests="$(wc -l <"$results" | tr -d ' ')"
  failed="$(grep -c '^fail' "$results" || true)"
  errors="$(grep -c '^error' "$results" || true)"
  total="$(awk -F'\t' '{ s += $3 } END { printf "%.3f", s }' "$results")"

  local xml="$REPORT_DIR/surefire-reports/TEST-$suite.xml" html="$REPORT_DIR/reports/surefire.html"
  local suite_xml
  suite_xml="$(xml_escape "$suite")"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    printf '<testsuite name="%s" tests="%s" failures="%s" errors="%s" skipped="0" time="%s" timestamp="%s">\n' \
      "$suite_xml" "$tests" "$failed" "$errors" "$total" "$(date -u +%Y-%m-%dT%H:%M:%S)"
    while IFS=$'\t' read -r status name seconds message; do
      printf '  <testcase classname="%s" name="%s" time="%s"' "$suite_xml" "$(xml_escape "$name")" "$seconds"
      case "$status" in
        pass) echo '/>' ;;
        fail) printf '>\n    <failure message="%s" type="AssertionError"/>\n  </testcase>\n' "$(xml_escape "$message")" ;;
        *) printf '>\n    <error message="%s" type="SetupError"/>\n  </testcase>\n' "$(xml_escape "$message")" ;;
      esac
    done <"$results"
    echo '</testsuite>'
  } >"$xml"

  {
    printf '<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<title>%s</title>\n' "$suite_xml"
    echo '<style>body{font-family:sans-serif;margin:2em}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:4px 8px;text-align:left}.pass{color:#1a7f37}.fail,.error{color:#cf222e}</style>'
    printf '</head>\n<body>\n<h1>%s</h1>\n' "$suite_xml"
    printf '<p>%s tests, %s failures, %s errors, %ss</p>\n' "$tests" "$failed" "$errors" "$total"
    echo '<table>'
    echo '<tr><th>Result</th><th>Check</th><th>Time (s)</th><th>Message</th></tr>'
    while IFS=$'\t' read -r status name seconds message; do
      printf '<tr><td class="%s">%s</td><td>%s</td><td>%s</td><td>%s</td></tr>\n' \
        "$status" "$status" "$(xml_escape "$name")" "$seconds" "$(xml_escape "$message")"
    done <"$results"
    echo '</table>'
    echo '<p>Generated by dbaas-operator-tests/checks.sh</p>'
    echo '</body>'
    echo '</html>'
  } >"$html"

  echo "Wrote $xml ($tests tests, $failed failures, $errors errors)"
}

case "${1:-}" in
  snapshot) cmd_snapshot "$2" ;;
  assert) shift; cmd_assert "$@" ;;
  report) shift; cmd_report "$@" ;;
  *) sed -n '2,7p' "$0"; exit 2 ;;
esac
