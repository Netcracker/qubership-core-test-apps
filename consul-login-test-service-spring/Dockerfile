FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.4.1@sha256:3292f3d70adf9862e4c4a1d1d9a8a820543a31d14a48212c95d9194d2f1e626f
LABEL maintainer="qubership"

COPY --chown=10001:0 target/consul-login-test-service-spring-*.jar /app/consul-login-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx128m", "-jar", "/app/consul-login-test-service-spring.jar"]
