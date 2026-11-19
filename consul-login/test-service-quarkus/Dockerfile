FROM ghcr.io/netcracker/qubership-java-base:21-alpine-2.5.15@sha256:1686136b058ba54c38dfb9537eb0ac2d0d4ba8b29ccd96af5c25fa2c7c68d320
LABEL maintainer="qubership"

COPY --chown=10001:0 target/quarkus-app/lib/ /app/lib/
COPY --chown=10001:0 target/quarkus-app/*.jar /app/
COPY --chown=10001:0 target/quarkus-app/app/ /app/app/
COPY --chown=10001:0 target/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/usr/bin/java", "-Xmx128m", "-jar", "/app/quarkus-run.jar"]
