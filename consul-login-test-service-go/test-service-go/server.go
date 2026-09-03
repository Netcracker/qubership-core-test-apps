package main

import (
	"time"

	"github.com/netcracker/qubership-core-lib-go-rest-utils/v2/consul-propertysource"

	"github.com/gofiber/fiber/v2"
	fiberserver "github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v2"
	fibersecurity "github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v2/security"
	"github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v2/server"
	"github.com/netcracker/qubership-core-lib-go/v3/configloader"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	"github.com/netcracker/qubership-core-lib-go/v3/serviceloader"
)

var logger logging.Logger

// The stand-in is the only token provider the service registers, which is where the login of the m2m way asks for one.
func init() {
	logger = logging.GetLogger("server")
	serviceloader.Register(1, &StandInTokenProvider{})
	serviceloader.Register(1, &fibersecurity.DummyFiberServerSecurityMiddleware{})
}

func main() {
	configloader.InitWithSourcesArray(append(configloader.BasePropertySources(), consul.NewPropertySource()))

	fiberConfig := fiber.Config{Network: fiber.NetworkTCP, IdleTimeout: 30 * time.Second}
	app, err := fiberserver.New(fiberConfig).Process()
	if err != nil {
		logger.Panicf("cannot create the server: %s", err.Error())
	}
	app.Get("/login-status", loginStatusHandler)

	server.StartServer(app, "http.server.bind")
}
