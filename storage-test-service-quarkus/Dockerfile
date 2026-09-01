FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.3.7@sha256:0703cab49931a129f16f39e11d108a4c15e9455a32508fc3f6690ae719aa826d
LABEL maintainer="qubership"

COPY --chown=10001:0 target/lib/* /app/lib/
COPY --chown=10001:0 target/storage-test-service-quarkus-*-runner.jar /app/storage-test-service-quarkus-runner.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a producer, a consumer and an operation timeline.
CMD ["/usr/bin/java", "-Xmx256m", "-jar", "/app/storage-test-service-quarkus-runner.jar"]
