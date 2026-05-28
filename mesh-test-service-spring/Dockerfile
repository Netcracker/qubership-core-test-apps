FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.2.13@sha256:ed0ee1413fa27f2484f683efd2295e6f63bb0b3441873b8fa2c14e785313d0cb
LABEL maintainer="qubership"

COPY --chown=10001:0 target/mesh-test-service-spring-*.jar /app/mesh-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx64m", "-jar", "/app/mesh-test-service-spring.jar"]
