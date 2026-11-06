FROM golang:1.27@sha256:e519d3b0a5b285e5aa53ab9eef02bc6bbb9cb04608389b743a136a9ce75f0d1e AS build

WORKDIR /app

COPY test-service-go/ .

RUN go mod download
RUN go build -o test-service-go .

FROM ghcr.io/netcracker/qubership-core-base:alpine-2.5.10@sha256:51173b982248bb866b236f504579b71c0a9c4a41975b957595f9faa9ab3fb5d6 AS run

COPY --chown=10001:0 --chmod=555 --from=build app/test-service-go /app/consul-login-test-service-go
COPY --chown=10001:0 --chmod=444 --from=build app/application.yaml /app/

EXPOSE 8080

WORKDIR /app

USER 10001:10001

CMD ["/app/consul-login-test-service-go"]
