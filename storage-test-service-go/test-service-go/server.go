package main

import (
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/netcracker/qubership-core-lib-go-actuator-common/v2/health"
	fiberserver "github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v2"
	fibersecurity "github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v2/security"
	"github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v2/server"
	consul "github.com/netcracker/qubership-core-lib-go-rest-utils/v2/consul-propertysource"
	"github.com/netcracker/qubership-core-lib-go/v3/configloader"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	"github.com/netcracker/qubership-core-lib-go/v3/security"
	"github.com/netcracker/qubership-core-lib-go/v3/serviceloader"
	"github.com/netcracker/qubership-storage-test-service-go/controller"
	"github.com/netcracker/qubership-storage-test-service-go/storage"
	"github.com/netcracker/qubership-storage-test-service-go/workload"
)

var logger logging.Logger

func init() {
	logger = logging.GetLogger("server")
	// Without these the MaaS client has no token to send and every request is rejected.
	serviceloader.Register(1, &security.DummyToken{})
	serviceloader.Register(1, &fibersecurity.DummyFiberServerSecurityMiddleware{})
}

func main() {
	consulPS := consul.NewPropertySource()
	configloader.InitWithSourcesArray(append(configloader.BasePropertySources(), consulPS))

	healthService, err := health.NewHealthService()
	if err != nil {
		logger.Error("Failed to create the health service: %s", err.Error())
		return
	}

	app, err := fiberserver.New(fiber.Config{Network: fiber.NetworkTCP, IdleTimeout: 30 * time.Second}).
		WithPrometheus("/prometheus").
		WithHealth("/health", healthService).
		Process()
	if err != nil {
		logger.Error("Failed to create the server: %s", err.Error())
		return
	}

	kafkaProbe := storage.NewKafka()
	defer kafkaProbe.Close()

	runner := workload.NewRunner(storage.NewMaasKafka(), storage.NewMaasRabbit(),
		storage.NewMaasWatch(), kafkaProbe)
	controller.New(runner).Register(app.Group("/api/v1"))

	server.StartServer(app, "http.server.bind")
}
