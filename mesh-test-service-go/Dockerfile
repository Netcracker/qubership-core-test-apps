FROM golang:1.26.4@sha256:f96cc555eb8db430159a3aa6797cd5bae561945b7b0fe7d0e284c63a3b291609 AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:2.3.4@sha256:55b2de47ca9fd85127a2077c65b9187f04ffd41849b2460f49c71b7e6372f940 AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/mesh-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

WORKDIR /app

USER 10001:10001

CMD ["/app/mesh-test-service-go"]