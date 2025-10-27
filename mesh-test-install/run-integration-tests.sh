#!/bin/bash

# Integration tests runner script
# Runs integration tests sequentially for all mesh test services
# Usage: ./run-integration-tests.sh <kube-context> <namespace> <node-ip-mapping>

set -e  # Exit on any error

# Function to show usage
show_usage() {
    echo "Usage: $0 <kube-context> <namespace> <node-ip-mapping>"
    echo ""
    echo "Arguments (all required):"
    echo "  kube-context      Kubernetes context for tests"
    echo "  namespace         Kubernetes namespace"
    echo "  node-ip-mapping   Node IP mapping (use node ip that belongs to pods network)"
    echo ""
    echo "Examples:"
    echo "  $0 minikube core minikube:10.244.0.1"
    echo "  $0 docker-desktop test-ns docker-desktop:10.0.0.1"
    echo ""
}

# Show help if requested
if [[ "$1" == "-h" || "$1" == "--help" ]]; then
    show_usage
    exit 0
fi

# Check if all required arguments are provided
if [[ -z "$1" || -z "$2" || -z "$3" ]]; then
    echo "Error: All arguments are required!"
    echo ""
    show_usage
    exit 1
fi

# Parse command line arguments
KUBE_CONTEXT="$1"
NAMESPACE="$2"
NODE_IP_MAPPING="$3"

echo "Starting integration tests for all mesh test services..."
echo "Kube context: $KUBE_CONTEXT"
echo "Namespace: $NAMESPACE"
echo "Node IP mapping: $NODE_IP_MAPPING"
echo ""

# Initialize global arrays for test results tracking
SERVICE_NAMES=()
TESTS_RUN=()
TESTS_FAILED=()
TESTS_ERRORS=()
TESTS_SKIPPED=()
SUCCESSFUL_SERVICES=()
FAILED_SERVICES=()

# Function to check if maven is installed and can access GitHub Packages
check_maven() {
    if ! command -v mvn &> /dev/null; then
        echo "Error: Maven is not installed. Please install Maven first."
        exit 1
    fi
    echo "Maven version: $(mvn --version | head -1)"
    
    # Check if GitHub Packages access is configured
    echo "Checking GitHub Packages access..."
    echo "Attempting to fetch test dependency from GitHub Packages..."
    local group_id="com.netcracker.cloud.junit.cloudcore"
    local artifact_id="cloud-core-extension"
    local version="8.0.0"
    if ! mvn dependency:get \
        -DremoteRepositories=github::default::https://maven.pkg.github.com/netcracker/qubership-core-junit-k8s-extension \
        -Dartifact=$group_id:$artifact_id:$version:pom
    then
        echo "Error: Unable to access GitHub Packages. Please check your Maven settings and credentials."
        exit 1
    fi
    echo "✅ GitHub Packages access verified"
}

# Function to extract test results from surefire reports
extract_test_results() {
    local service_dir=$1
    local service_name=$2
    
    echo "🔍 Searching for test reports in $service_dir..."
    
    # Initialize counters
    local tests_run=0
    local tests_failed=0
    local tests_errors=0
    local tests_skipped=0
    local found_reports=false
    
    # Known surefire report locations for this project structure
    local surefire_paths=(
        "$service_dir/target/surefire-reports"
        "$service_dir/test-service-spring-integration-tests/target/surefire-reports"
        "$service_dir/test-service-quarkus-integration-tests/target/surefire-reports"
        "$service_dir/test-service-go-integration-tests/target/surefire-reports"
    )
    
    # Check each known location
    for surefire_path in "${surefire_paths[@]}"; do
        if [[ -d "$surefire_path" ]]; then
            echo "   Found surefire reports directory: $surefire_path"
            found_reports=true
            
            # Parse all TEST-*.xml files in this directory
            for xml_file in "$surefire_path"/TEST-*.xml; do
                if [[ -f "$xml_file" ]]; then
                    echo "   Parsing: $(basename "$xml_file")"
                    
                    # Extract test statistics from XML testsuite tag
                    local testsuite_line=$(grep '<testsuite' "$xml_file" 2>/dev/null | head -1)
                    if [[ -n "$testsuite_line" ]]; then
                        local file_tests=$(echo "$testsuite_line" | grep -o 'tests="[0-9]*"' | sed 's/tests="//; s/"//')
                        local file_failures=$(echo "$testsuite_line" | grep -o 'failures="[0-9]*"' | sed 's/failures="//; s/"//')
                        local file_errors=$(echo "$testsuite_line" | grep -o 'errors="[0-9]*"' | sed 's/errors="//; s/"//')
                        local file_skipped=$(echo "$testsuite_line" | grep -o 'skipped="[0-9]*"' | sed 's/skipped="//; s/"//')
                        
                        # Debug output
                        echo "     Tests: ${file_tests:-0}, Failures: ${file_failures:-0}, Errors: ${file_errors:-0}, Skipped: ${file_skipped:-0}"
                        
                        # Add to totals (default to 0 if not found)
                        tests_run=$((tests_run + ${file_tests:-0}))
                        tests_failed=$((tests_failed + ${file_failures:-0}))
                        tests_errors=$((tests_errors + ${file_errors:-0}))
                        tests_skipped=$((tests_skipped + ${file_skipped:-0}))
                    else
                        echo "     No testsuite tag found in $xml_file"
                    fi
                fi
            done
        fi
    done
    
    if [[ "$found_reports" == false ]]; then
        echo "   ⚠️  No surefire-reports directories found for $service_name"
    fi
    
    # Store results in global arrays
    SERVICE_NAMES+=("$service_name")
    TESTS_RUN+=($tests_run)
    TESTS_FAILED+=($tests_failed)
    TESTS_ERRORS+=($tests_errors)
    TESTS_SKIPPED+=($tests_skipped)
    
    echo "📊 Test Results for $service_name:"
    echo "   Tests Run: $tests_run"
    if [[ $tests_failed -ne 0 ]]; then
        echo "   Failures: $tests_failed ❌"
    else
        echo "   Failures: $tests_failed"
    fi
    if [[ $tests_errors -ne 0 ]]; then
        echo "   Errors: $tests_errors ❌"
    else
        echo "   Errors: $tests_errors"
    fi
    echo "   Skipped: $tests_skipped"
}

# Function to run integration tests for a specific service
run_integration_tests() {
    local service_name=$1
    local service_dir=$2
    
    echo ""
    echo "========================================"
    echo "Running integration tests for $service_name"
    echo "========================================"
    echo "Directory: $service_dir"
    
    # Check if service directory exists
    if [ ! -d "$service_dir" ]; then
        echo "Error: Service directory $service_dir does not exist"
        exit 1
    fi
    
    # Change to service directory
    cd "$service_dir"
    
    echo "Running Maven integration tests..."
    local maven_exit_code=0
    mvn surefire-report:report -P integration-test \
        -DskipIT=false \
        -Dclouds.cloud.name="$KUBE_CONTEXT" \
        -Dclouds.cloud.namespaces.namespace="$NAMESPACE" \
        -DNODE_IP_MAPPING="$NODE_IP_MAPPING" \
        -DORIGIN_NAMESPACE="$NAMESPACE" \
        -Denv.cloud-namespace="$NAMESPACE" || maven_exit_code=$?
    
    # Extract test results regardless of Maven exit code
    extract_test_results "$service_dir" "$service_name"
    
    if [ $maven_exit_code -eq 0 ]; then
        echo "Integration tests for $service_name executed successfully"
        SUCCESSFUL_SERVICES+=("$service_name")
    else
        echo "❌ Integration tests execution for $service_name failed"
        FAILED_SERVICES+=("$service_name")
        # Don't exit immediately, continue with other services to get full report
    fi
    
    # Return to original directory
    cd - > /dev/null
}

# Function to generate final comprehensive report
generate_final_report() {
    echo ""
    echo "========================================"
    echo "🎯 FINAL INTEGRATION TESTS REPORT"
    echo "========================================"
    echo ""
    
    # Calculate totals
    local total_tests=0
    local total_failed=0
    local total_errors=0
    local total_skipped=0
    local total_passed=0
    
    # Print individual service results
    echo "📊 Individual Service Results:"
    echo "----------------------------------------"
    for i in "${!SERVICE_NAMES[@]}"; do
        local service="${SERVICE_NAMES[$i]}"
        local run="${TESTS_RUN[$i]}"
        local failed="${TESTS_FAILED[$i]}"
        local errors="${TESTS_ERRORS[$i]}"
        local skipped="${TESTS_SKIPPED[$i]}"
        local passed=$((run - failed - errors - skipped))
        
        # Determine status icon
        local status_icon=""
        if [[ " ${FAILED_SERVICES[*]} " =~ " ${service} " ]]; then
            status_icon="❌"
        fi
        
        printf "%-30s %s\n" "$service" "$status_icon"
        printf "  %-20s %3d\n" "Tests Run:" "$run"
        printf "  %-20s %3d\n" "Passed:" "$passed"
        if [[ $failed -ne 0 ]]; then
            printf "  %-20s %3d ❌\n" "Failed:" "$failed"
        else
            printf "  %-20s %3d\n" "Failed:" "$failed"
        fi
        if [[ $errors -ne 0 ]]; then
            printf "  %-20s %3d ❌\n" "Errors:" "$errors"
        else
            printf "  %-20s %3d\n" "Errors:" "$errors"
        fi
        printf "  %-20s %3d\n" "Skipped:" "$skipped"
        echo ""
        
        # Add to totals
        total_tests=$((total_tests + run))
        total_failed=$((total_failed + failed))
        total_errors=$((total_errors + errors))
        total_skipped=$((total_skipped + skipped))
    done
    
    total_passed=$((total_tests - total_failed - total_errors - total_skipped))
    
    echo "========================================"
    echo "📈 OVERALL SUMMARY"
    echo "========================================"
    printf "%-25s %3d\n" "Total Tests Run:" "$total_tests"
    printf "%-25s %3d\n" "Total Passed:" "$total_passed"
    if [[ $total_failed -ne 0 ]]; then
        printf "%-25s %3d ❌\n" "Total Failed:" "$total_failed"
    else
        printf "%-25s %3d\n" "Total Failed:" "$total_failed"
    fi
    if [[ $total_errors -ne 0 ]]; then
        printf "%-25s %3d ❌\n" "Total Errors:" "$total_errors"
    else
        printf "%-25s %3d\n" "Total Errors:" "$total_errors"
    fi
    printf "%-25s %3d\n" "Total Skipped:" "$total_skipped"
    echo ""
    
    echo "🏆 SERVICE EXECUTION SUMMARY"
    echo "========================================"
    printf "%-25s %3d\n" "Successful Services:" "${#SUCCESSFUL_SERVICES[@]}"
    printf "%-25s %3d\n" "Failed Services:" "${#FAILED_SERVICES[@]}"
    printf "%-25s %3d\n" "Total Services:" "${#SERVICE_NAMES[@]}"
    echo ""
    
    # Only show service lists when there are failures (to highlight issues)
    if [[ ${#FAILED_SERVICES[@]} -gt 0 ]]; then
        echo "❌ Failed Services:"
        for service in "${FAILED_SERVICES[@]}"; do
            echo "   - $service"
        done
        echo ""
        
        # Show successful services only when there are also failures
        if [[ ${#SUCCESSFUL_SERVICES[@]} -gt 0 ]]; then
            echo "✅ Successful Services:"
            for service in "${SUCCESSFUL_SERVICES[@]}"; do
                echo "   - $service"
            done
            echo ""
        fi
    fi
    
    # Calculate success rate
    local success_rate=0
    if [[ ${#SERVICE_NAMES[@]} -gt 0 ]]; then
        success_rate=$(( (${#SUCCESSFUL_SERVICES[@]} * 100) / ${#SERVICE_NAMES[@]} ))
    fi
    
    echo "📊 SUCCESS RATE: $success_rate% (${#SUCCESSFUL_SERVICES[@]}/${#SERVICE_NAMES[@]} services)"
    
    # Determine final status based on failures and errors
    local has_issues=false
    if [[ ${#FAILED_SERVICES[@]} -gt 0 || $total_failed -gt 0 || $total_errors -gt 0 ]]; then
        has_issues=true
    fi
    
    # Final status
    echo ""
    echo "========================================"
    if [[ "$has_issues" == false ]]; then
        echo "🎉 ALL INTEGRATION TESTS COMPLETED SUCCESSFULLY!"
        echo ""
        echo "Configuration used:"
        echo "  Kube context: $KUBE_CONTEXT"
        echo "  Namespace: $NAMESPACE"
        echo "  Node IP mapping: $NODE_IP_MAPPING"
        echo ""
        echo "🟢 FINAL STATUS: OK"
        return 0
    else
        echo "⚠️  SOME INTEGRATION TESTS FAILED!"
        echo ""
        echo "Please check the logs above for detailed error information."
        echo "Failed services may need attention before deployment."
        echo ""
        echo "🔴 FINAL STATUS: NOT OK"
        return 1
    fi
}

# Main function
main() {
    echo "Checking prerequisites..."
    check_maven
    
    # Get the script directory to determine the project root
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
    
    echo "Project root: $PROJECT_ROOT"
    
    # Store original directory
    ORIGINAL_DIR="$(pwd)"
    
    # Define service directories
    SPRING_DIR="$PROJECT_ROOT/mesh-test-service-spring"
    QUARKUS_DIR="$PROJECT_ROOT/mesh-test-service-quarkus"
    GO_DIR="$PROJECT_ROOT/mesh-test-service-go-integration-tests"
    
    echo ""
    echo "Service directories:"
    echo "Spring:  $SPRING_DIR"
    echo "Quarkus: $QUARKUS_DIR"
    echo "Go:      $GO_DIR"
    
    # Run integration tests in sequence
    echo ""
    echo "🚀 Starting integration tests execution..."
    
    # 1. Run Spring integration tests
    run_integration_tests "mesh-test-service-spring" "$SPRING_DIR"
    
    # 2. Run Quarkus integration tests
    run_integration_tests "mesh-test-service-quarkus" "$QUARKUS_DIR"
    
    # 3. Run Go integration tests
    run_integration_tests "mesh-test-service-go" "$GO_DIR"
    
    # Return to original directory
    cd "$ORIGINAL_DIR"
    
    # Generate comprehensive final report
    generate_final_report
    local final_exit_code=$?
    
    # Exit with appropriate code
    if [[ $final_exit_code -ne 0 ]]; then
        exit 1
    fi
}

# Handle script interruption
trap 'echo ""; echo "Integration tests interrupted."; exit 1' INT TERM

# Run main function
main "$@"
