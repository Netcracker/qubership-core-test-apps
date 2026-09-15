FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.0@sha256:1c44503742162db906ac42ba1b437067cd303f14c66314a8ab20e96a587a99c9
LABEL maintainer="qubership"

COPY --chown=10001:0 target/mesh-test-service-spring-*.jar /app/mesh-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx64m", "-jar", "/app/mesh-test-service-spring.jar"]
