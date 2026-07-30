FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.3.7@sha256:0703cab49931a129f16f39e11d108a4c15e9455a32508fc3f6690ae719aa826d
LABEL maintainer="qubership"

COPY --chown=10001:0 target/lib/* /app/lib/
COPY --chown=10001:0 target/mesh-test-service-quarkus-*-runner.jar /app/mesh-test-service-quarkus-runner.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx512m", "-jar", "/app/mesh-test-service-quarkus-runner.jar"]
