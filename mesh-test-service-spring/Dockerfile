FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.3.3@sha256:4c17f37e13bc57e01c70cde41af64a067969d301b92c53c4d7d22ff88abff61d
LABEL maintainer="qubership"

COPY --chown=10001:0 target/mesh-test-service-spring-*.jar /app/mesh-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx64m", "-jar", "/app/mesh-test-service-spring.jar"]
