FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.4.2@sha256:f1167b5ab05fc01059d87d56363647acd137156fd3b5a35ce7cbceec94115ff4
LABEL maintainer="qubership"

COPY --chown=10001:0 target/lib/* /app/lib/
COPY --chown=10001:0 target/mesh-test-service-quarkus-*-runner.jar /app/mesh-test-service-quarkus-runner.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx512m", "-jar", "/app/mesh-test-service-quarkus-runner.jar"]
