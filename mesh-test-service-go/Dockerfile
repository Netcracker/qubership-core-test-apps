FROM golang:1.27.3@sha256:b76020929b709ddb6202afc05410a0faefae20ae05e27ad548b3870092688a69 AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.11@sha256:1b80a6ba225210f2308c68679aa40e5211d4712269200a55862ec2a9061a55de AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/mesh-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

WORKDIR /app

USER 10001:10001

CMD ["/app/mesh-test-service-go"]