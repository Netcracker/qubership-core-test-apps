FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.3.6@sha256:6925353c16218651bc911f9f64ae941eeabe99e7e54c24a9d63edb09c58342b9
LABEL maintainer="qubership"

COPY --chown=10001:0 target/lib/* /app/lib/
COPY --chown=10001:0 target/mesh-test-service-quarkus-*-runner.jar /app/mesh-test-service-quarkus-runner.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx512m", "-jar", "/app/mesh-test-service-quarkus-runner.jar"]
