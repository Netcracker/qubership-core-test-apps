FROM ghcr.io/netcracker/qubership/java-base:1.0.0
LABEL maintainer="qubership"

COPY --chown=10001:0 test-service-quarkus/target/lib/* /app/lib/
COPY --chown=10001:0 test-service-quarkus/target/test-service-quarkus-*-runner.jar /app/test-service-quarkus-runner.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx512m", "-jar", "/app/test-service-quarkus-runner.jar"]
