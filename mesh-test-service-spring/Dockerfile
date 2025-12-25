FROM ghcr.io/netcracker/qubership/java-base:1.2.2
LABEL maintainer="qubership"

COPY --chown=10001:0 target/mesh-test-service-spring-*.jar /app/mesh-test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx64m", "-jar", "/app/mesh-test-service-spring.jar"]
