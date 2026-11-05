FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.10@sha256:9a2363c18e4b8f116bceac90aa379e0c44a6594a5f6126b8fdcbb7fb50e9f332
LABEL maintainer="qubership"

COPY --chown=10001:0 target/consul-login-test-service-spring-*.jar /app/consul-login-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx128m", "-jar", "/app/consul-login-test-service-spring.jar"]
