FROM golang:1.27@sha256:f20d83a83e18dbd2a4714b8ffd4b30b287b6243962dd75608e86abcc63ba4efc AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.8@sha256:0c2c9e4b49e73efffb30edf8d3de8b3eb43194a6c4f688da08ec3236b570b486 AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/maas-failover-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

WORKDIR /app

EXPOSE 8080

USER 10001:10001

CMD ["/app/maas-failover-test-service-go"]
