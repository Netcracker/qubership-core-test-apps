FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.4@sha256:e2c93e22e265a36801183291e9825ba6969b8281f96a043f4adb3fce05368abc
LABEL maintainer="qubership"

COPY --chown=10001:0 target/maas-failover-test-service-spring-*.jar /app/maas-failover-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a connection pool and an operation timeline.
CMD ["/usr/bin/java", "-Xmx192m", "-jar", "/app/maas-failover-test-service-spring.jar"]
