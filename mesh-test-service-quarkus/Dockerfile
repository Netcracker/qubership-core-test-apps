FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.4.0@sha256:a7c78350fc6f7e24d64f91457c8e1c4bdd3395b033fcd13f97cdfd49c63a4c30
LABEL maintainer="qubership"

COPY --chown=10001:0 target/lib/* /app/lib/
COPY --chown=10001:0 target/mesh-test-service-quarkus-*-runner.jar /app/mesh-test-service-quarkus-runner.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx512m", "-jar", "/app/mesh-test-service-quarkus-runner.jar"]
