# Qubership Core Test Apps

A collection of small applications used to test Qubership Cloud-Core components.

## Table of Contents

- [About](#about)
- [Applications Included](#applications-included)
- [Getting Started](#getting-started)
- [Running Tests](#running-tests)
- [Architecture](#architecture)
- [Contributing](#contributing)
- [License](#license)
- [Code of Conduct](#code-of-conduct)

## About

This repository contains a suite of lightweight test applications and services designed to validate the behavior of the Qubership Cloud-Core. These applications are used in integration tests, CI pipelines, and local development to ensure core components work as expected.

### Daily Integration Test Report

A daily report of integration test results is generated and published via GitHub Pages. The report provides:

- Pass/fail summary for each project module.
- Historical trends over the last 7 days.
- Links to individual test reports for each module.
- Quick access to the GitHub Actions run for each day.

The report is automatically updated by a scheduled workflow and can be accessed online at:
https://netcracker.github.io/qubership-core-test-apps/

## Applications Included

- `mesh-test-service-go` — a Go-based test service
- `mesh-test-service-quarkus` — a Quarkus (Java) test microservice
- `mesh-test-service-spring` — a Spring Boot test service
- `mesh-test-install` — scripts and helpers for installing and running test apps

## Getting Started

### Prerequisites

- JDK 21+ (for Java-based services)
- Go (for Go-based services)
- Maven (depending on the service)
- Docker / Kind / Kubernetes (for integration tests)
- `kubectl` (for interacting with Kubernetes)

## Getting Started

To get started quickly with the test applications:

1. **Install prerequisites**: JDK 21+, Go, Maven, Docker, Kind/Kubernetes, and `kubectl`.
2. **Build the services**:
    - Java (Quarkus/Spring): `cd mesh-test-service-quarkus && ./mvnw clean package`
    - Go: `cd mesh-test-service-go && go build ./...`
3. **Deploy Cloud-Core locally** (optional for full integration testing).
4. **Run services locally** or in Kubernetes using scripts in `mesh-test-install`.

After this, you can move on to running automated integration tests.

## Running Tests

Integration tests are typically run via workflow scripts:

1. Set up a Kubernetes cluster (for instance, using [kind](https://kind.sigs.k8s.io/)).
2. Deploy Cloud-Core.
3. Deploy the test applications.
4. Run integration test scripts: `mesh-test-install/run-integration-tests.sh`

## Architecture

Each test service validates:

- Startup behavior
- Cloud-Core interaction
- Failure handling
- Cleanup after tests

`mesh-test-install` contains scripts to deploy services, run tests, and collect results.
