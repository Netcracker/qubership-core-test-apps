FROM golang:1.26.4@sha256:f96cc555eb8db430159a3aa6797cd5bae561945b7b0fe7d0e284c63a3b291609 AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:2.3.3@sha256:1339716127a7d170ba307b89f3a933f5e09c447607c89e16bf8d5a379db4e1f6 AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/mesh-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

WORKDIR /app

USER 10001:10001

CMD ["/app/mesh-test-service-go"]