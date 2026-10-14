#!/usr/bin/env bash
# Records the checks of a Bash test suite and writes them as a Surefire report, the layout the
# integration tests report reads, so a suite with no JUnit runner joins that report like the Java ones.
#
# Source it from the suite's script:
#
#   source "<path>/integration-tests-report/surefire-report.sh"
#   surefire_record_aborts "my-suite.sh $1"      # optional: report a check that stops the script
#   pass "the service is ready"
#   fail "the service is not ready: $reason"     # also counts the failure in $failures
#   surefire_report my-suite --job-status "$status"
#
# With REPORT_DIR set, every pass, fail, and abort is appended to REPORT_DIR/results.tsv, and
# surefire_report turns the records into REPORT_DIR/surefire-reports/TEST-<suite>.xml and
# REPORT_DIR/reports/surefire.html. Without REPORT_DIR, checks are only printed.

REPORT_DIR="${REPORT_DIR:-}"
failures=0
surefire_last_record="$(date +%s.%N)"

# Appends one check to REPORT_DIR/results.tsv: status (pass, fail, or error), name, seconds since the
# previous check, message.
record() {
  [[ -n "$REPORT_DIR" ]] || return 0
  local now seconds
  now="$(date +%s.%N)"
  seconds="$(awk -v a="$surefire_last_record" -v b="$now" 'BEGIN { printf "%.3f", b - a }')"
  surefire_last_record="$now"
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

# Under `set -Eeu`, a command that fails inside a check stops the script. This records where, as an
# error named after the label, so the report names the step that stopped and does not lose the checks
# after it. Only the top-level shell records: a failing command substitution also fails the
# assignment that runs it.
surefire_record_aborts() { # label, such as "checks.sh assert"
  SUREFIRE_ABORT_LABEL="$1"
  trap 'surefire_on_abort "$LINENO" "$BASH_COMMAND" "$?"' ERR
}

surefire_on_abort() { # line, command, exit status
  ((BASH_SUBSHELL == 0)) || return 0
  local message="$SUREFIRE_ABORT_LABEL stopped at line $1: '$2' exited with $3, so the checks after it did not run"
  echo "ERROR: $message" >&2
  record error "$SUREFIRE_ABORT_LABEL stopped at line $1" "$message"
}

surefire_xml_escape() {
  sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' <<<"$1"
}

# Writes the recorded checks as a Surefire report. The integration tests report reads the counts from
# the first <testsuite> tag, so the file has exactly one, with no <testsuites> wrapper around it.
surefire_report() { # suite [--job-status <status>]
  local suite="$1" job_status="success"
  shift
  while (($#)); do
    case "$1" in
      --job-status) job_status="$2"; shift 2 ;;
      *) echo "unknown option $1" >&2; return 2 ;;
    esac
  done
  [[ -n "$REPORT_DIR" ]] || { echo "REPORT_DIR is not set" >&2; return 2; }
  mkdir -p "$REPORT_DIR/surefire-reports" "$REPORT_DIR/reports"
  local results="$REPORT_DIR/results.tsv"
  touch "$results"

  # A step that failed outside the checks, such as the cluster setup, must not leave a report that
  # only lists passes, or a scenario that never ran would look healthy. A failed check or an abort is
  # already recorded.
  if [[ "$job_status" != "success" ]] && ! grep -qE '^(fail|error)' "$results"; then
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
  suite_xml="$(surefire_xml_escape "$suite")"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    printf '<testsuite name="%s" tests="%s" failures="%s" errors="%s" skipped="0" time="%s" timestamp="%s">\n' \
      "$suite_xml" "$tests" "$failed" "$errors" "$total" "$(date -u +%Y-%m-%dT%H:%M:%S)"
    while IFS=$'\t' read -r status name seconds message; do
      printf '  <testcase classname="%s" name="%s" time="%s"' "$suite_xml" "$(surefire_xml_escape "$name")" "$seconds"
      case "$status" in
        pass) echo '/>' ;;
        fail) printf '>\n    <failure message="%s" type="AssertionError"/>\n  </testcase>\n' "$(surefire_xml_escape "$message")" ;;
        *) printf '>\n    <error message="%s" type="SetupError"/>\n  </testcase>\n' "$(surefire_xml_escape "$message")" ;;
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
        "$status" "$status" "$(surefire_xml_escape "$name")" "$seconds" "$(surefire_xml_escape "$message")"
    done <"$results"
    echo '</table>'
    echo '<p>Generated by integration-tests-report/surefire-report.sh</p>'
    echo '</body>'
    echo '</html>'
  } >"$html"

  echo "Wrote $xml ($tests tests, $failed failures, $errors errors)"
}
