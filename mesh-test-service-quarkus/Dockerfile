FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.3.4@sha256:d1a5e920c234fcdc82612438ca4328038e420e9d7b043f1443da99b91ea0d24c
LABEL maintainer="qubership"

COPY --chown=10001:0 target/lib/* /app/lib/
COPY --chown=10001:0 target/mesh-test-service-quarkus-*-runner.jar /app/mesh-test-service-quarkus-runner.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx512m", "-jar", "/app/mesh-test-service-quarkus-runner.jar"]
