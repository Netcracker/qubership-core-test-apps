FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.11@sha256:c9019537572ef7b09613a3352739fa6d2b7c6c3b8e63e6a3312d77595dac6c88
LABEL maintainer="qubership"

COPY --chown=10001:0 target/consul-login-test-service-spring-*.jar /app/consul-login-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx128m", "-jar", "/app/consul-login-test-service-spring.jar"]
