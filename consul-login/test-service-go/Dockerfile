FROM golang:1.27@sha256:2920bb124f1a2e12a1d39f0cab756ae0068441ee1da7655dec7f5e374944095b AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.9@sha256:ab860790e4ecce56f8df673495cd5745ce587fffa7d7eb210d6d098563ccb26d AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/consul-login-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/app/consul-login-test-service-go"]
