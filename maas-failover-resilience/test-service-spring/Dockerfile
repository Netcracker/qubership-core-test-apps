FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.5@sha256:960fca655cd1af315dba03b2c16b778cf4498af8fa081132f2afa03f396761cf
LABEL maintainer="qubership"

COPY --chown=10001:0 target/maas-failover-test-service-spring-*.jar /app/maas-failover-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a connection pool and an operation timeline.
CMD ["/usr/bin/java", "-Xmx192m", "-jar", "/app/maas-failover-test-service-spring.jar"]
