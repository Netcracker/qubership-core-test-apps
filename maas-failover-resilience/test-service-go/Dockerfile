FROM golang:1.26@sha256:6c2a5538f964f1c82f97ad14988bf05de100d922d159d0e398b54c7b0ca0c6c9 AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.7@sha256:f0836415633bc2a63c2a0431c4c0701d17bd7b31132176b26651b49927d9ad7e AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/maas-failover-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

WORKDIR /app

EXPOSE 8080

USER 10001:10001

CMD ["/app/maas-failover-test-service-go"]
