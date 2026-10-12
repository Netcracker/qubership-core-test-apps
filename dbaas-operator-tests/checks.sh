#!/usr/bin/env bash
# Checks that Cloud Core resolves its databases through the DBaaS Operator, with no REST fallback.
#
# Usage:
#   checks.sh snapshot <file>                      record each service's database while in legacy mode
#   checks.sh arm                                  block the DBaaS REST API for Cloud Core, and prove it
#   checks.sh assert [--snapshot <file>] [--expect-no-legacy-secret]
#   checks.sh report <suite> [--job-status <s>]    write the recorded checks as a Surefire report
#
# `arm` needs Istio in ambient mode. It puts the Cloud Core namespace in the mesh, and a waypoint in
# front of dbaas-aggregator that denies /api/v3/dbaas/* to Cloud Core (assets/rest-fallback-trap.yaml).
# Run it before Cloud Core is installed or upgraded onto the operator. Any REST call for a database
# from a Cloud Core service, directly or through dbaas-agent, is then denied with 403, and the
# waypoint counts it. `assert` requires every service to be ready, captured by the mesh, and to have
# had no request denied: each one resolved its database from the Secret the DBaaS Operator mounted.
#
# With REPORT_DIR set, every check is also recorded there, and `report` turns the records into
# REPORT_DIR/surefire-reports/TEST-<suite>.xml and REPORT_DIR/reports/surefire.html, the layout the
# integration tests report reads.
set -euo pipefail

CORE_NAMESPACE="${CORE_NAMESPACE:-core}"
PG_NAMESPACE="${PG_NAMESPACE:-postgres}"
DBAAS_NAMESPACE="${DBAAS_NAMESPACE:-dbaas}"
DBAAS_SERVICE_NAME="${DBAAS_SERVICE_NAME:-dbaas-aggregator}"
ISTIO_NAMESPACE="${ISTIO_NAMESPACE:-istio-system}"
WAYPOINT_NAME="${WAYPOINT_NAME:-dbaas-aggregator-waypoint}"
PROBE_NAME="${PROBE_NAME:-dbaas-rest-fallback-probe}"
# The Cloud Core application chart, whose pre-install hook runs core-bootstrap.
BOOTSTRAP_RELEASE="${BOOTSTRAP_RELEASE:-cloud-core-app-chart}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-600s}"
REPORT_DIR="${REPORT_DIR:-}"

AGGREGATOR_URL="http://$DBAAS_SERVICE_NAME.$DBAAS_NAMESPACE.svc.cluster.local:8080"
ASSETS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/assets"

# Every Cloud Core service that owns a database. Keep in sync with the charts.
SERVICES=(control-plane config-server site-management)
# Every Cloud Core workload that could fall back to the REST API: the services, and dbaas-agent,
# which forwards their calls.
REST_CLIENTS=("${SERVICES[@]}" dbaas-agent)
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
    response="$(kube exec deploy/control-plane -- curl -sS -u "$user:$password"       -H 'Content-Type: application/json' -X POST -d "$body"       "$AGGREGATOR_URL/api/v3/dbaas/$CORE_NAMESPACE/databases/get-by-classifier/postgresql" 2>&1)" || {
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

# Passes when every pod of the deployment is captured by the ambient mesh. A pod outside it would
# reach dbaas-aggregator without passing the waypoint, so the trap would not see its calls.
check_captured() {
  local deployment="$1" selector pods
  selector="$(kube get deployment "$deployment" -o json |
    jq -r '.spec.selector.matchLabels | to_entries | map("\(.key)=\(.value)") | join(",")')"
  pods="$(kube get pods -l "$selector" -o json |
    jq -c '[.items[] | select(.metadata.deletionTimestamp == null)
      | {name: .metadata.name, redirection: (.metadata.annotations["ambient.istio.io/redirection"] // "none")}]')"
  if jq -e 'length > 0 and all(.redirection == "enabled")' <<<"$pods" >/dev/null; then
    pass "every $deployment pod is in the ambient mesh"
  else
    fail "$deployment has pods outside the ambient mesh, which the trap cannot see: $pods"
  fi
}

# Sends a request from the probe pod and prints the response body, then the status code on a line
# of its own.
probe() { # method, path
  kube exec "$PROBE_NAME" -- curl -sS -X "$1" -H 'Content-Type: application/json' -d '{}' \
    -w '\n%{http_code}' "$AGGREGATOR_URL$2" 2>&1 || true
}

denied_response() { # response
  [[ "$(tail -n1 <<<"$1")" == 403 && "$1" == *"RBAC: access denied"* ]]
}

cmd_arm() {
  # The Istio install returns before its components are ready, and istiod also validates the policy
  # applied below, so wait for the control plane and the ambient data plane first.
  local components component
  components="$(kubectl -n "$ISTIO_NAMESPACE" get deployments,daemonsets -o name)"
  if ! grep -q '^deployment.apps/istiod$' <<<"$components" || ! grep -q '^daemonset.apps/ztunnel$' <<<"$components"; then
    fail "Istio ambient is not installed in $ISTIO_NAMESPACE: $(paste -sd " " <<<"$components")"
    exit 1
  fi
  for component in $components; do
    if ! kubectl -n "$ISTIO_NAMESPACE" rollout status "$component" --timeout="$WAIT_TIMEOUT" >/dev/null; then
      fail "Istio $component in $ISTIO_NAMESPACE is not ready"
      exit 1
    fi
  done
  pass "Istio ambient is ready: $(paste -sd " " <<<"$components")"

  # Cloud Core joins the mesh before it is installed, so every pod it starts is captured.
  # core-mesh-config adds the waypoint of the Cloud Core namespace later; the trap needs only the capture.
  kubectl create namespace "$CORE_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl label namespace "$CORE_NAMESPACE" "$DBAAS_NAMESPACE" istio.io/dataplane-mode=ambient --overwrite >/dev/null
  local manifest attempt
  # shellcheck disable=SC2016 # the placeholders are for envsubst, not the shell
  manifest="$(WAYPOINT_NAME="$WAYPOINT_NAME" PROBE_NAME="$PROBE_NAME" CORE_NAMESPACE="$CORE_NAMESPACE" \
    DBAAS_NAMESPACE="$DBAAS_NAMESPACE" DBAAS_SERVICE_NAME="$DBAAS_SERVICE_NAME" \
    envsubst '${WAYPOINT_NAME} ${PROBE_NAME} ${CORE_NAMESPACE} ${DBAAS_NAMESPACE} ${DBAAS_SERVICE_NAME}' \
    <"$ASSETS_DIR/rest-fallback-trap.yaml")"
  # istiod's validating webhook can refuse connections for a few seconds after istiod is ready.
  for attempt in $(seq 1 10); do
    kubectl apply -f - <<<"$manifest" >/dev/null && break
    if ((attempt == 10)); then
      fail "cannot apply $ASSETS_DIR/rest-fallback-trap.yaml"
      exit 1
    fi
    sleep 6
  done

  if kubectl -n "$DBAAS_NAMESPACE" wait --for=condition=Programmed "gateways.gateway.networking.k8s.io/$WAYPOINT_NAME" --timeout="$WAIT_TIMEOUT" >/dev/null; then
    pass "waypoint $WAYPOINT_NAME is programmed"
  else
    fail "waypoint $WAYPOINT_NAME is not programmed: $(kubectl -n "$DBAAS_NAMESPACE" get "gateways.gateway.networking.k8s.io/$WAYPOINT_NAME"       -o jsonpath='{range .status.conditions[*]}{.type}={.status} ({.reason}: {.message}) {end}' 2>&1)"
    exit 1
  fi
  kubectl -n "$DBAAS_NAMESPACE" label service "$DBAAS_SERVICE_NAME" "istio.io/use-waypoint=$WAYPOINT_NAME" --overwrite >/dev/null
  if ! kube wait --for=condition=Ready "pod/$PROBE_NAME" --timeout="$WAIT_TIMEOUT" >/dev/null; then
    fail "probe pod $PROBE_NAME did not become ready"
    exit 1
  fi
  if [[ "$(kube get pod "$PROBE_NAME" -o jsonpath='{.metadata.annotations.ambient\.istio\.io/redirection}')" == enabled ]]; then
    pass "the probe pod is in the ambient mesh"
  else
    fail "the probe pod is outside the ambient mesh, so it cannot test the trap"
    exit 1
  fi

  # A DENY rule that does not apply blocks nothing and reports nothing, so prove that it applies.
  # The policy reaches the waypoint a few seconds after it is applied.
  local response=""
  for attempt in $(seq 1 30); do
    response="$(probe POST "/api/v3/dbaas/$CORE_NAMESPACE/databases/get-by-classifier/postgresql")"
    denied_response "$response" && break
    sleep 2
  done
  if denied_response "$response"; then
    pass "the DBaaS REST API denies Cloud Core"
  else
    fail "the DBaaS REST API does not deny Cloud Core; the last response was: $(head -c 300 <<<"$response")"
  fi
  # And that it denies nothing else: core-operator still needs /api/composite through dbaas-agent.
  response="$(probe GET /health)"
  if [[ "$(tail -n1 <<<"$response")" != 000 && "$response" != *"RBAC: access denied"* ]]; then
    pass "dbaas-aggregator still answers Cloud Core outside /api/v3/dbaas (HTTP $(tail -n1 <<<"$response"))"
  else
    fail "dbaas-aggregator did not answer /health from Cloud Core: $(head -c 300 <<<"$response")"
  fi

  if ((failures)); then
    echo "$failures check(s) failed" >&2
    exit 1
  fi
}

# Prints the requests the waypoint passed on from the Cloud Core namespace, as tab-separated
# "<source workload> <source service account> <response code> <count>" lines. The metrics are read
# through the API server, so no pod needs a shell.
waypoint_requests() {
  local pod
  pod="$(kubectl -n "$DBAAS_NAMESPACE" get pods -l "gateway.networking.k8s.io/gateway-name=$WAYPOINT_NAME" \
    -o jsonpath='{.items[0].metadata.name}')"
  [[ -n "$pod" ]] || return 1
  kubectl get --raw "/api/v1/namespaces/$DBAAS_NAMESPACE/pods/$pod:15020/proxy/stats/prometheus" |
    awk -v ns="$CORE_NAMESPACE" '
      function label(name) {
        if (match($0, "[{,]" name "=\"[^\"]*\"")) return substr($0, RSTART + length(name) + 3, RLENGTH - length(name) - 4)
        return ""
      }
      /^istio_requests_total\{/ && label("source_workload_namespace") == ns {
        principal = label("source_principal")
        sub(/.*\/sa\//, "", principal)
        requests[label("source_workload") "\t" principal "\t" label("response_code")] += $NF
      }
      END { for (r in requests) print r "\t" requests[r] }'
}

check_no_denials() {
  local rows
  if ! rows="$(waypoint_requests)"; then
    fail "cannot read the request metrics of waypoint $WAYPOINT_NAME"
    return
  fi
  # The probe's own denied requests show that the metrics are there to read.
  if ! awk -F'\t' -v p="$PROBE_NAME" '$1 == p && $3 == 403 { found = 1 } END { exit !found }' <<<"$rows"; then
    fail "waypoint $WAYPOINT_NAME reports no denied request from the probe, so its metrics cannot be trusted: '$rows'"
    return
  fi

  local workload denied
  for workload in "${REST_CLIENTS[@]}"; do
    denied="$(awk -F'\t' -v w="$workload" '$1 == w && $3 == 403 { n += $4 } END { print n + 0 }' <<<"$rows")"
    if [[ "$denied" == 0 ]]; then
      pass "$workload made no REST call for a database"
    else
      fail "$workload made $denied REST call(s) for a database, which the trap denied"
    fi
  done
  # Any other Cloud Core workload, too, so a new client or a Job cannot fall back unnoticed.
  local others
  others="$(awk -F'\t' -v p="$PROBE_NAME" -v known=" ${REST_CLIENTS[*]} " \
    '$3 == 403 && $1 != p && index(known, " " $1 " ") == 0 { n[$1] += $4 }
     END { for (w in n) printf "%s%s (%d)", (sep++ ? ", " : ""), w, n[w] }' <<<"$rows")"
  if [[ -z "$others" ]]; then
    pass "no other Cloud Core workload made a REST call for a database"
  else
    fail "other Cloud Core workloads made REST calls for a database, which the trap denied: $others"
  fi

  check_bootstrap_silent "$rows"
}

# core-bootstrap runs as a helm hook Job of the Cloud Core application chart. The Job and its service
# account are hooks too, deleted once the Job succeeds, so the account is read from the hook manifests
# the release keeps, and the requests are matched by it.
check_bootstrap_silent() {
  local rows="$1" hooks job account requests
  if ! hooks="$(helm get hooks "$BOOTSTRAP_RELEASE" -n "$CORE_NAMESPACE" 2>&1)"; then
    fail "cannot read the hooks of release $BOOTSTRAP_RELEASE: $hooks"
    return
  fi
  # The hook manifests as JSON, one document per line, so the Job can be picked by its kind.
  if ! job="$(yq -o=json -I=0 'select(.kind == "Job" and (.metadata.name | test("-pre-hook$")))' <<<"$hooks")"; then
    fail "cannot parse the hooks of release $BOOTSTRAP_RELEASE with yq"
    return
  fi
  account="$(jq -r '.spec.template.spec.serviceAccountName // empty' <<<"$job")"
  if [[ -z "$account" ]]; then
    fail "cannot find the core-bootstrap Job or its service account in the hooks of release $BOOTSTRAP_RELEASE"
    return
  fi
  # The Job pod is gone, so its capture is shown by the namespace it ran in and its own labels.
  if [[ "$(kubectl get namespace "$CORE_NAMESPACE" -o jsonpath='{.metadata.labels.istio\.io/dataplane-mode}')" == ambient ]] &&
    ! jq -e '.spec.template.metadata.labels["istio.io/dataplane-mode"] == "none"' <<<"$job" >/dev/null; then
    pass "the core-bootstrap Job ran in the ambient mesh"
  else
    fail "the core-bootstrap Job may have run outside the ambient mesh, where the trap cannot see it"
  fi
  # The probe runs as the default service account; its requests show the account label is read.
  if ! awk -F'\t' -v p="$PROBE_NAME" '$1 == p && $2 == "default" { found = 1 } END { exit !found }' <<<"$rows"; then
    fail "waypoint $WAYPOINT_NAME reports no service account for the probe, so the core-bootstrap check cannot be trusted"
    return
  fi
  # With DBAAS_OPERATOR_ENABLED and no balancing rules, core-bootstrap has no reason to call the
  # aggregator at all, so any request counts, not only the denied ones.
  requests="$(awk -F'\t' -v a="$account" '$2 == a { n += $4 } END { print n + 0 }' <<<"$rows")"
  if [[ "$requests" == 0 ]]; then
    pass "core-bootstrap made no request to dbaas-aggregator"
  else
    fail "core-bootstrap made $requests request(s) to dbaas-aggregator: $(awk -F'\t' -v a="$account" '$2 == a' <<<"$rows" | tr '\t\n' ' ;')"
  fi
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

  # 1. Every REST client is seen by the trap. Without this every later PASS proves nothing.
  for deployment in "${REST_CLIENTS[@]}"; do
    check_captured "$deployment"
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
      pass "$service is ready"
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

  # 5. No Cloud Core workload even tried the REST API, and core-bootstrap made no request at all.
  # Readiness alone would miss a call that failed and was then retried from the Secret.
  check_no_denials

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
  arm) cmd_arm ;;
  assert) shift; cmd_assert "$@" ;;
  report) shift; cmd_report "$@" ;;
  *) sed -n '2,8p' "$0"; exit 2 ;;
esac
