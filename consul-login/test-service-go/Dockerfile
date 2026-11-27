FROM golang:1.27@sha256:b76020929b709ddb6202afc05410a0faefae20ae05e27ad548b3870092688a69 AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.6.1@sha256:7be670fc57c243195c63597106413e1b0365381c2d186a71cb6d12fcffc2278b AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/consul-login-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/app/consul-login-test-service-go"]
