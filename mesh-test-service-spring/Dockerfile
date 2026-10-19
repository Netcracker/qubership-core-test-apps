FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.8@sha256:7932aa11169aae4a72a7740d18f9148715729b087f35740ac682c89238bf989b
LABEL maintainer="qubership"

COPY --chown=10001:0 target/mesh-test-service-spring-*.jar /app/mesh-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx64m", "-jar", "/app/mesh-test-service-spring.jar"]
