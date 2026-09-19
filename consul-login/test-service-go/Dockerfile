FROM golang:1.27@sha256:3680233e3204827fbdc66088528ae6d4b3d034f51d03a99d454f6de034888244 AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:2.4.1@sha256:c668333b6b03d897bfea3a7345bcff14a3b9224fffe62024202b2a125a6b0171 AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/consul-login-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/app/consul-login-test-service-go"]
