FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.11@sha256:c9019537572ef7b09613a3352739fa6d2b7c6c3b8e63e6a3312d77595dac6c88
LABEL maintainer="qubership"

COPY --chown=10001:0 target/maas-failover-test-service-spring-*.jar /app/maas-failover-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a connection pool and an operation timeline.
CMD ["/usr/bin/java", "-Xmx192m", "-jar", "/app/maas-failover-test-service-spring.jar"]
