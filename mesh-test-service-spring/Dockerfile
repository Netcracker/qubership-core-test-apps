FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.6@sha256:4efef3ebe0c3fbd8eaddcb3cbbed18c85b140b4517e148b969bfbf86a4b87ca3
LABEL maintainer="qubership"

COPY --chown=10001:0 target/mesh-test-service-spring-*.jar /app/mesh-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx64m", "-jar", "/app/mesh-test-service-spring.jar"]
