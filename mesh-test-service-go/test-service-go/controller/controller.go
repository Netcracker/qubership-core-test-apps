package controller

import (
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"

	"github.com/netcracker/qubership-mesh-test-service-go/domain"
	"github.com/netcracker/qubership-mesh-test-service-go/utils"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"github.com/netcracker/qubership-core-lib-go/v3/configloader"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxhelper"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	coretls "github.com/netcracker/qubership-core-lib-go/v3/utils"
)

type Controller struct {
}

var (
	logger            logging.Logger
	serviceName       string
	familyName        string
	namespace         string
	deploymentVersion string
	host              string
	PodId             = uuid.New().String()
	springPort        string
	springProtocol    string
)

func init() {
	logger = logging.GetLogger("controller")
}

func (с *Controller) ProxyTCP(fiberCtx *fiber.Ctx) error {
	requestURL := string(fiberCtx.Body())
	logger.Infof("Received proxy tcp request to: %s", requestURL)

	tcpAddr, err := net.ResolveTCPAddr("tcp", requestURL)
	if err != nil {
		logger.Errorf("Resolve tcp addr failed: %s", err.Error())
		return err
	}

	conn, err := net.DialTCP("tcp", nil, tcpAddr)
	if err != nil {
		logger.Errorf("Found error while tcp dial. %s", err.Error())
		return err
	}
	defer conn.Close()

	_, err = conn.Write([]byte("Hello from go"))
	if err != nil {
		logger.Errorf("Found error while tcp write. %s", err.Error())
		return err
	}

	if err = conn.CloseWrite(); err != nil {
		logger.Errorf("Found error while close tcp write. %s", err.Error())
		return err
	}

	logger.Infof("Read tcp answer")
	reply := make([]byte, 1024)
	n, err := conn.Read(reply)
	if err != nil {
		logger.Errorf("Found error while tcp write. %s", err.Error())
		return err
	}
	resp := string(reply[:n])
	logger.Infof("TCP answer: %s", resp)

	return utils.RespondWithString(fiberCtx, fiber.StatusOK, resp)
}

func NewController() *Controller {
	familyName = configloader.GetOrDefaultString("microservice.name", "")
	deploymentVersion = configloader.GetOrDefaultString("deployment.version", "")
	serviceName = os.Getenv("CLOUD_SERVICE_NAME")
	namespace = os.Getenv("NAMESPACE")
	host = os.Getenv("HOSTNAME")
	springPort = "8080"
	springProtocol = "http"
	return &Controller{}
}

func (с *Controller) HelloHandler(fiberCtx *fiber.Ctx) error {
	logger.Info("hello")
	allHeaders := fiberCtx.GetReqHeaders()
	logger.Debugf("All headers:%+v", allHeaders)
	xVersionHeader := с.getHeaderValue(allHeaders, "X-Version")
	logger.Debugf("X-Version header:%+v", xVersionHeader)
	xVersionNameHeader := с.getHeaderValue(allHeaders, "X-Version-Name")
	logger.Debugf("X-Version-Name header:%+v", xVersionNameHeader)
	fastHttpCtx := fiberCtx.Context()
	path := string(fiberCtx.Request().URI().Path())
	response := domain.TraceResponse{
		ServiceName:  serviceName,
		FamilyName:   familyName,
		Namespace:    namespace,
		Version:      deploymentVersion,
		PodId:        PodId,
		RequestHost:  string(fastHttpCtx.Host()),
		ServerHost:   host,
		RemoteAddr:   fastHttpCtx.RemoteAddr().String(),
		Path:         path,
		Method:       fiberCtx.Method(),
		Xversion:     fmt.Sprint(xVersionHeader),
		XVersionName: fmt.Sprint(xVersionNameHeader),
	}

	logger.Infof("Responding with service name:%+v host:%+v method:%+v path:%+v version:%+v xVersionHeader:%+v xVersionNameHeader:%+v", response.ServiceName, response.ServerHost, response.Method, response.Path, response.Version, response.Xversion, response.XVersionName)
	return utils.RespondWithJson(fiberCtx, http.StatusOK, &response)
}

func (c *Controller) getHeaderValue(headers map[string][]string, headerName string) string {
	values := headers[headerName]
	if values == nil || len(values) == 0 {
		return ""
	}

	return values[0]
}

func (с *Controller) HelloSpringHandler(fiberCtx *fiber.Ctx) error {
	ctx := fiberCtx.UserContext()
	logger.Info("hello from spring")
	client := coretls.GetClient()
	requestURL := springProtocol + "://mesh-test-service-spring:" + springPort + "/api/v1/mesh-test-service-spring/hello"
	request, _ := http.NewRequest("GET", requestURL, nil)
	err := ctxhelper.AddSerializableContextData(ctx, request.Header.Add)

	utils.AddAuthTokenHeader(ctx, request.Header)

	res, err := client.Do(request)
	if err != nil {
		logger.Infof("error making http request: %s\n", err)
		os.Exit(1)
	}
	defer res.Body.Close()
	logger.Infof("client: got response!\n")
	logger.Infof("client: status code: %d\n", res.StatusCode)

	body, err := io.ReadAll(res.Body)
	if err != nil {
		log.Fatalln(err)
	}

	responseMessage := fmt.Sprintf("Spring answered:%s", string(body))
	logger.Info(responseMessage)
	return utils.RespondWithString(fiberCtx, fiber.StatusOK, string(body))
}
