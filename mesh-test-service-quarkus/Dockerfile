FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.5@sha256:960fca655cd1af315dba03b2c16b778cf4498af8fa081132f2afa03f396761cf
LABEL maintainer="qubership"

COPY --chown=10001:0 target/lib/* /app/lib/
COPY --chown=10001:0 target/mesh-test-service-quarkus-*-runner.jar /app/mesh-test-service-quarkus-runner.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx512m", "-jar", "/app/mesh-test-service-quarkus-runner.jar"]
