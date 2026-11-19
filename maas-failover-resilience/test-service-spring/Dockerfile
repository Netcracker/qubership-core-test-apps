FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.15@sha256:1686136b058ba54c38dfb9537eb0ac2d0d4ba8b29ccd96af5c25fa2c7c68d320
LABEL maintainer="qubership"

COPY --chown=10001:0 target/maas-failover-test-service-spring-*.jar /app/maas-failover-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a connection pool and an operation timeline.
CMD ["/usr/bin/java", "-Xmx192m", "-jar", "/app/maas-failover-test-service-spring.jar"]
