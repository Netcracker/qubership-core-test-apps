FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.4.1@sha256:3292f3d70adf9862e4c4a1d1d9a8a820543a31d14a48212c95d9194d2f1e626f
LABEL maintainer="qubership"

COPY --chown=10001:0 target/lib/* /app/lib/
COPY --chown=10001:0 target/mesh-test-service-quarkus-*-runner.jar /app/mesh-test-service-quarkus-runner.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx512m", "-jar", "/app/mesh-test-service-quarkus-runner.jar"]
