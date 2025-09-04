package main

import (
	"test-service-go/controller"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/netcracker/qubership-core-lib-go-actuator-common/v2/health"
	"github.com/netcracker/qubership-core-lib-go-actuator-common/v2/tracing"
	fiberserver "github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v2"
	"github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v2/server"
	routeregistration "github.com/netcracker/qubership-core-lib-go-rest-utils/v2/route-registration"
	"github.com/netcracker/qubership-core-lib-go/v3/configloader"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	"github.com/netcracker/qubership-core-lib-go/v3/security"
	"github.com/netcracker/qubership-core-lib-go/v3/serviceloader"
)

var (
	logger logging.Logger
)

func init() {
	configloader.Init(configloader.BasePropertySources()...)
	logger = logging.GetLogger("server")
	serviceloader.Register(1, &security.DummyToken{})
}

func main() {
	fiberConfig := fiber.Config{Network: fiber.NetworkTCP, IdleTimeout: 30 * time.Second}
	healthService, err := health.NewHealthService()
	if err != nil {
		logger.Error("Couldn't create healthService")
	}
	app, err := fiberserver.New(fiberConfig).
		WithPprof("6060").
		WithPrometheus("/prometheus").
		WithTracer(tracing.NewZipkinTracer()).
		WithHealth("/health", healthService).
		Process()
	if err != nil {
		logger.Error("Error while create app because: " + err.Error())
		return
	}

	control := controller.NewController()
	apiV1 := app.Group("/api/v1")
	apiV1.Get("/hello", control.HelloHandler)
	apiV1.Get("/hello/spring", control.HelloSpringHandler)
	apiV1.Post("/proxy/tcp", control.ProxyTCP)

	routeregistration.NewRegistrar().WithRoutes(
		routeregistration.Route{From: "/api/v1/mesh-test-service-go",
			To:        "/api/v1",
			RouteType: routeregistration.Public,
		},
	).Register()

	go func() {
		server.StartServer(app, "http.server.bind")
	}()

	startSecondServer(fiberConfig)
}

func startSecondServer(fiberConfig fiber.Config) {
	logger.Info("Start second server")
	app2, err := fiberserver.New(fiberConfig).Process()
	if err != nil {
		logger.Error("Error while create app2 because: " + err.Error())
		return
	}

	control := controller.NewController()
	apiV1 := app2.Group("/api/v1")
	apiV1.Get("/1234/hello", control.HelloHandler)

	routeregistration.NewRegistrar().WithRoutes(
		routeregistration.Route{From: "/api/v1/mesh-test-service-go/1234",
			To:        "/api/v1/1234",
			RouteType: routeregistration.Public,
		},
	).Register()

	server.StartServer(app2, "http.server2.bind")
}
