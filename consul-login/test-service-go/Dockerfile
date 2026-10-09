FROM golang:1.26@sha256:52d57e3542f28ad72e9a1084e209366d8ca6af04997636134c138d16702a70a6 AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.7@sha256:f0836415633bc2a63c2a0431c4c0701d17bd7b31132176b26651b49927d9ad7e AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/consul-login-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/app/consul-login-test-service-go"]
