FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.3.7@sha256:0703cab49931a129f16f39e11d108a4c15e9455a32508fc3f6690ae719aa826d
LABEL maintainer="qubership"

COPY --chown=10001:0 target/storage-test-service-spring-*.jar /app/storage-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a connection pool and an operation timeline.
CMD ["/usr/bin/java", "-Xmx192m", "-jar", "/app/storage-test-service-spring.jar"]
