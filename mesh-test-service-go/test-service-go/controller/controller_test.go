package controller

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"testing"

	"github.com/netcracker/qubership-mesh-test-service-go/domain"

	"github.com/gofiber/fiber/v2"
	fiberserver "github.com/netcracker/qubership-core-lib-go-fiber-server-utils/v2"
	"github.com/netcracker/qubership-core-lib-go/v3/configloader"
	"github.com/stretchr/testify/assert"
)

func TestController_HelloHandler(t *testing.T) {
	os.Setenv("deployment.version", "v1")
	defer os.Unsetenv("deployment.version")
	os.Setenv("microservice.name", "mesh-test-service-go")
	defer os.Unsetenv("microservice.name")
	os.Setenv("CLOUD_SERVICE_NAME", "mesh-test-service-go-v1")
	defer os.Unsetenv("CLOUD_SERVICE_NAME")
	configloader.Init(configloader.EnvPropertySource())
	control := NewController()

	app, err := fiberserver.New().Process()
	assert.Nil(t, err)
	app.Get("api/v1/hello", func(ctx *fiber.Ctx) error {
		return control.HelloHandler(ctx)
	})

	respMock, err := app.Test(makeRequestWithoutBody("api/v1/hello", http.MethodGet), -1)
	defer respMock.Body.Close()
	assert.Nil(t, err)
	bodyBytes, err := io.ReadAll(respMock.Body)
	assert.Nil(t, err)
	assert.Equal(t, http.StatusOK, respMock.StatusCode)
	var traceResponse domain.TraceResponse
	err = json.Unmarshal(bodyBytes, &traceResponse)
	assert.Nil(t, err)
	assert.Equal(t, "mesh-test-service-go-v1", traceResponse.ServiceName)
	assert.Equal(t, "mesh-test-service-go", traceResponse.FamilyName)
	assert.Equal(t, "v1", traceResponse.Version)
}

func TestController_HelloHandler_AccessForbidden(t *testing.T) {
	control := NewController()
	configloader.Init(configloader.EnvPropertySource())
	app, err := fiberserver.New().Process()
	assert.Nil(t, err)
	app.Get("api/v1/hello", func(ctx *fiber.Ctx) error {
		return control.HelloHandler(ctx)
	})

	respMock, err := app.Test(makeRequestWithoutBody("api/v1/hello", http.MethodGet), -1)
	defer respMock.Body.Close()
	assert.Nil(t, err)

	assert.Equal(t, http.StatusForbidden, respMock.StatusCode)
}

func makeRequestWithoutBody(path string, method string) *http.Request {
	reqUrl := &url.URL{
		Scheme: "http",
		Host:   "test-service",
		Path:   path,
	}
	req, _ := http.NewRequest(method, reqUrl.String(), nil)
	return req
}
