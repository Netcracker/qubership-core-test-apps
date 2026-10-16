FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.8@sha256:7932aa11169aae4a72a7740d18f9148715729b087f35740ac682c89238bf989b
LABEL maintainer="qubership"

COPY --chown=10001:0 target/maas-failover-test-service-spring-*.jar /app/maas-failover-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a connection pool and an operation timeline.
CMD ["/usr/bin/java", "-Xmx192m", "-jar", "/app/maas-failover-test-service-spring.jar"]
