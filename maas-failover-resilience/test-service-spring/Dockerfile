FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.10@sha256:9a2363c18e4b8f116bceac90aa379e0c44a6594a5f6126b8fdcbb7fb50e9f332
LABEL maintainer="qubership"

COPY --chown=10001:0 target/maas-failover-test-service-spring-*.jar /app/maas-failover-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

# More heap than the mesh apps get: this one holds a connection pool and an operation timeline.
CMD ["/usr/bin/java", "-Xmx192m", "-jar", "/app/maas-failover-test-service-spring.jar"]
