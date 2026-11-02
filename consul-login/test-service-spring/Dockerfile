FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.9@sha256:d2715fc7372feb9fb5f35e65d20a5aed412c20aba0b046f3f74656050309c940
LABEL maintainer="qubership"

COPY --chown=10001:0 target/consul-login-test-service-spring-*.jar /app/consul-login-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx128m", "-jar", "/app/consul-login-test-service-spring.jar"]
