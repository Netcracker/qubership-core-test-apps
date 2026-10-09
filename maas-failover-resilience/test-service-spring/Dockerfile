FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.7@sha256:36b4a4889f78d9e48e6afb71b6493aa6d5ca3295928f958e7a906286f8613ef0
LABEL maintainer="qubership"

COPY --chown=10001:0 target/maas-failover-test-service-spring-*.jar /app/maas-failover-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a connection pool and an operation timeline.
CMD ["/usr/bin/java", "-Xmx192m", "-jar", "/app/maas-failover-test-service-spring.jar"]
