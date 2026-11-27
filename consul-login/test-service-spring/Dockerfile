FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.6.1@sha256:82026253e3c04e90d2a03310e5e5c23619c3b0010990956ec7521312cdc94223
LABEL maintainer="qubership"

COPY --chown=10001:0 target/consul-login-test-service-spring-*.jar /app/consul-login-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx128m", "-jar", "/app/consul-login-test-service-spring.jar"]
