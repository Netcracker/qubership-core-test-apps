FROM ghcr.io/netcracker/qubership/java-base:1.0.0
LABEL maintainer="qubership"

COPY --chown=10001:0 test-service-spring/target/test-service-spring-*.jar /app/test-service-spring.jar

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx64m", "-jar", "/app/test-service-spring.jar"]
