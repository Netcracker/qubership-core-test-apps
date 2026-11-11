FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.13@sha256:ea4f438afd668fb01996481a4475fddcff8b64c2d771288da7d206e2e202d024
LABEL maintainer="qubership"

COPY --chown=10001:0 target/maas-failover-test-service-spring-*.jar /app/maas-failover-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a connection pool and an operation timeline.
CMD ["/usr/bin/java", "-Xmx192m", "-jar", "/app/maas-failover-test-service-spring.jar"]
