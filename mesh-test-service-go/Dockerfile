FROM golang:1.27.1@sha256:162be5298a40ed317005c8339c6de4d10d3eef336d66dc8e9259b03ab9d3a6d2 AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.7@sha256:f0836415633bc2a63c2a0431c4c0701d17bd7b31132176b26651b49927d9ad7e AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/mesh-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

WORKDIR /app

USER 10001:10001

CMD ["/app/mesh-test-service-go"]