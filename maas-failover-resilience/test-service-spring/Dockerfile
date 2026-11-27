FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.6.1@sha256:82026253e3c04e90d2a03310e5e5c23619c3b0010990956ec7521312cdc94223
LABEL maintainer="qubership"

COPY --chown=10001:0 target/maas-failover-test-service-spring-*.jar /app/maas-failover-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a connection pool and an operation timeline.
CMD ["/usr/bin/java", "-Xmx192m", "-jar", "/app/maas-failover-test-service-spring.jar"]
