#!/usr/bin/env bash
# Records the checks of a Bash test suite and writes them as a Surefire report, so a suite with no JUnit
# runner joins the integration tests report like the Java suites do.
#
# The report has the layout that .github/workflows/update-integration-tests-report.yml reads from each
# uploaded artifact:
#
#   REPORT_DIR/surefire-reports/TEST-<suite>.xml   one <testsuite>, with one <testcase> per check
#   REPORT_DIR/reports/surefire.html               the same checks as an HTML table
#
# A suite sources this file, records each check with `pass` or `fail`, and calls `surefire_report` at the
# end, usually from a later workflow step that runs even when the checks failed.
#
# SOURCING
#
# Source the file with a path relative to the calling script, so the suite works from any directory:
#
#   # shellcheck source=../test-utils/surefire-report.sh
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../test-utils/surefire-report.sh"
#
# Sourcing sets `failures` to 0 and starts the clock that times the first check.
#
# SHELL OPTIONS
#
# The suite is expected to run under `set -Eeuo pipefail`, set before sourcing. `-e` stops the script at
# the first command that fails outside a condition, and `-E` makes the ERR trap fire inside functions too,
# so a command that fails inside a check function is caught. `surefire_record_aborts` relies on both:
# without `-E`, a failure inside a function stops the script without being recorded; without `-e`, the
# script does not stop, yet each failing command is still recorded as an abort.
#
# FUNCTIONS
#
#   pass <message>
#       Prints "PASS: <message>" to stdout and records a `pass` check named <message>.
#
#   fail <message>
#       Prints "FAIL: <message>" to stderr, records a `fail` check named <message> with <message> as the
#       failure message, and adds 1 to `failures`. It returns 0, so the suite goes on to the next check.
#
#   record <status> <name> [message]
#       Appends one check to REPORT_DIR/results.tsv. <status> is `pass`, `fail`, or `error`. It prints
#       nothing and does not change `failures`; `pass` and `fail` call it. Call it directly only for a
#       status they do not cover, such as an `error` for a step that could not run. Tabs and newlines in
#       <name> and <message> become spaces. Without REPORT_DIR it does nothing.
#
#   surefire_record_aborts <label>
#       Sets an ERR trap that records a command that stops the script. The trap prints
#       "ERROR: <label> stopped at line <n>: '<command>' exited with <status>, ..." to stderr and records
#       an `error` check named "<label> stopped at line <n>", so the report names the step that stopped
#       and does not silently lose the checks after it. The script still stops.
#       Call it once, after sourcing and before the first check; it replaces any ERR trap set earlier.
#       Only the top-level shell records: a command that fails inside `$(...)` also fails the assignment
#       that runs it, and that assignment is the one recorded.
#
#   surefire_report <suite> [--job-status <status>]
#       Reads REPORT_DIR/results.tsv and writes REPORT_DIR/surefire-reports/TEST-<suite>.xml and
#       REPORT_DIR/reports/surefire.html, then prints "Wrote <xml> (<n> tests, <n> failures, <n> errors)".
#       <suite> names the <testsuite> and the XML file. It reads only results.tsv, so it can run in a
#       separate process from the checks, as a later workflow step. The XML holds exactly one <testsuite>
#       with no <testsuites> wrapper, because the integration tests report reads the counts from the first
#       <testsuite> tag. A `fail` becomes a <failure type="AssertionError">, an `error` becomes an
#       <error type="SetupError">. It returns 0 whatever the checks found, and 2 if REPORT_DIR is not set
#       or an option is unknown.
#
# `surefire_on_abort` (the ERR trap handler) and `surefire_xml_escape` are internal; do not call them.
#
# THE --job-status CONTRACT
#
# In a workflow, pass `--job-status "${{ job.status }}"`; the default is `success`. When the status is not
# `success` and results.tsv has no `fail` or `error` row, `surefire_report` adds an `error` check named
# `setup`: "no checks ran" if results.tsv is empty, or "a workflow step outside the checks failed"
# otherwise. A scenario whose cluster setup failed then shows as failed, instead of passing with no checks
# or with only the checks that ran before the failure.
#
# VARIABLES
#
#   REPORT_DIR (input)
#       The directory for results.tsv and the report. Sourcing keeps a value already set. Empty or unset
#       means checks are only printed, and `surefire_report` fails. A relative path resolves against the
#       working directory of each process, so the check and report steps must run from the same directory.
#
#   failures (output)
#       The number of `fail` calls in this shell process. The suite uses it to decide its exit status,
#       for example `((failures == 0)) || exit 1`. It does not count `error` rows or the checks of other
#       processes; the report counts from results.tsv.
#
# `surefire_last_record` and `SUREFIRE_ABORT_LABEL` are internal; do not read or set them.
#
# RESULTS.TSV
#
# `record` appends one line per check to REPORT_DIR/results.tsv, with four tab-separated fields:
#
#   <status>   pass, fail, or error
#   <name>     the check name
#   <seconds>  seconds since the previous check in this process, or since sourcing, with three decimals
#   <message>  the failure or error message; empty for a pass
#
# The file is only appended to, so every process that shares a REPORT_DIR adds to the same report. Start
# each run with an empty REPORT_DIR.
#
# EXAMPLE
#
# A minimal suite, my-suite/checks.sh, with a `run` command for the checks and a `report` command for the
# report:
#
#   #!/usr/bin/env bash
#   # Checks that the demo service is ready and healthy.
#   set -Eeuo pipefail
#
#   # shellcheck source=../test-utils/surefire-report.sh
#   source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../test-utils/surefire-report.sh"
#
#   check_ready() {
#     if kubectl -n demo rollout status deployment/demo --timeout=120s; then
#       pass "demo is ready"
#     else
#       fail "demo is not ready"
#     fi
#   }
#
#   check_health() {
#     local body
#     # A kubectl error stops the script here; surefire_record_aborts records it.
#     body="$(kubectl -n demo exec deployment/demo -- curl -fsS http://localhost:8080/health)"
#     if [[ "$body" == *'"UP"'* ]]; then
#       pass "demo reports UP"
#     else
#       fail "demo reports $body"
#     fi
#   }
#
#   surefire_record_aborts "my-suite/checks.sh ${1:-}"
#
#   case "${1:-}" in
#     run)
#       check_ready
#       check_health
#       ((failures == 0)) || exit 1
#       ;;
#     report) shift; surefire_report "$@" ;;
#     *) echo "usage: checks.sh run | report <suite> [--job-status <status>]" >&2; exit 2 ;;
#   esac
#
# The workflow job sets REPORT_DIR to artifacts/<suite>, runs the checks, then writes and uploads the
# report even when an earlier step failed, as .github/workflows/dbaas-operator-integration-tests.yml does:
#
#   my-suite:
#     runs-on: ubuntu-latest
#     env:
#       REPORT_DIR: artifacts/my-suite
#     steps:
#       - name: Checkout repository
#         uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
#
#       - name: Run the checks
#         run: ./my-suite/checks.sh run
#
#       - name: Write Surefire report
#         if: always()
#         run: ./my-suite/checks.sh report my-suite --job-status "${{ job.status }}"
#
#       - name: Upload Surefire report
#         if: always()
#         uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
#         with:
#           name: surefire-reports-my-suite
#           path: artifacts/*
#
# Name the artifact surefire-reports-<suite>: update-integration-tests-report.yml downloads every artifact
# that matches surefire-reports* into one artifacts/ directory. `path: artifacts/*` keeps the <suite>
# directory in the artifact, and its name becomes the module name in the integration tests report, so
# each job needs its own. The artifacts reach the report only from a workflow that
# update-integration-tests-report.yml calls: add a job there with `uses:` the suite's workflow, and add
# that job to the `needs` of its update-report job.

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

# Under `set -Eeuo pipefail`, a command that fails inside a check stops the script. This records where,
# as an error named after the label, so the report names the step that stopped and does not lose the
# checks after it. Only the top-level shell records: a failing command substitution also fails the
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
      --job-status)
        [[ $# -ge 2 ]] || { echo "--job-status needs a value" >&2; return 2; }
        job_status="$2"; shift 2 ;;
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
    echo '<p>Generated by test-utils/surefire-report.sh</p>'
    echo '</body>'
    echo '</html>'
  } >"$html"

  echo "Wrote $xml ($tests tests, $failed failures, $errors errors)"
}
