FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.7@sha256:36b4a4889f78d9e48e6afb71b6493aa6d5ca3295928f958e7a906286f8613ef0
LABEL maintainer="qubership"

COPY --chown=10001:0 target/consul-login-test-service-spring-*.jar /app/consul-login-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx128m", "-jar", "/app/consul-login-test-service-spring.jar"]
