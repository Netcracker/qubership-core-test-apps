package utils

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/netcracker/qubership-core-lib-go/v3/configloader"
	"github.com/netcracker/qubership-core-lib-go/v3/context-propagation/ctxhelper"
	"github.com/netcracker/qubership-core-lib-go/v3/logging"
	"github.com/netcracker/qubership-core-lib-go/v3/security"
	"github.com/netcracker/qubership-core-lib-go/v3/serviceloader"
	coretls "github.com/netcracker/qubership-core-lib-go/v3/utils"
)

var logger logging.Logger

func init() {
	logger = logging.GetLogger("client-utils")
}

// DoRetryRequest is the way to send request with token to another microservice
func DoRetryRequest(logContext context.Context, method string, url string) (*http.Response, error) {
	attemptDelayStart, _ := strconv.Atoi(configloader.GetOrDefaultString("http.client.retry.attemptDelay", "2000"))
	retryLimit, _ := strconv.Atoi(configloader.GetOrDefaultString("http.client.retry.maxAttempts", "5"))

	logger.DebugC(logContext, "Execute secure request (retryLimit: %v, retry delay: %v * n)", retryLimit, attemptDelayStart)
	response := &http.Response{}
	errMsg := ""
	headers, err := collectHeaders(logContext, logger)
	if err != nil {
		logger.ErrorC(logContext, "Error occurred during headers propagation: %+v", err)
		return nil, err
	}

	for i := 0; i < retryLimit; i++ {
		if i > 0 {
			waitInterval := time.Duration(attemptDelayStart) * time.Duration(i*i)
			logger.InfoC(logContext, "Sleep %v before retry", waitInterval)
			time.Sleep(waitInterval)
		}

		client := &http.Client{
			Transport: coretls.GetTransport(),
			Timeout:   5 * time.Second,
		}
		req, err := createRequest(logContext, method, url, headers, logger)
		if err != nil {
			errMsg = fmt.Sprintf("Secure %s request handler to %s failed with error: %s, retrying", method, url, err)
			logger.WarnC(logContext, errMsg)
			continue
		}
		response, err = client.Do(req)
		if err != nil {
			errMsg = fmt.Sprintf("Secure %s request to %s failed with error: %s, retrying", method, url, err)
			logger.WarnC(logContext, errMsg)
			continue
		}

		if response.StatusCode >= http.StatusInternalServerError {
			logger.WarnC(logContext, "Secure %s request to %s failed with 5xx http status code: %s, retrying", method, url, response.StatusCode)
			response.Body.Close()
			continue
		} else {
			return response, nil
		}
		response.Body.Close()
	}
	if response != nil {
		return nil, errors.New(fmt.Sprintf("Secure %s request to %s failed with 5xx http status code: %v", method, url, response.StatusCode))
	} else {
		return nil, errors.New(errMsg)
	}
}

func AddAuthTokenHeader(ctx context.Context, headers http.Header) error {
	tokenProvider := serviceloader.MustLoad[security.TokenProvider]()
	token, err := tokenProvider.GetToken(ctx)
	if err != nil {
		return err
	}
	if token != "" {
		headers.Add("Authorization", fmt.Sprintf("Bearer %s", token))
	}
	return nil
}

func collectHeaders(ctx context.Context, logger logging.Logger) (http.Header, error) {
	headers := http.Header{}
	if err := AddAuthTokenHeader(ctx, headers); err != nil {
		return nil, err
	}
	headers.Add("Content-Type", "application/json")
	return headers, nil
}

func createRequest(ctx context.Context, method string, url string, headers http.Header, logger logging.Logger) (*http.Request, error) {
	logger.Debugf(`Building secure request with arguments: 
	method=%v, 
	url=%v`, method, url)

	req, err := http.NewRequest(method, url, nil)
	if err != nil {
		return req, err
	}
	req.Header = headers
	err = ctxhelper.AddSerializableContextData(ctx, req.Header.Set) // this enables context-propagation for current request
	if err != nil {
		logger.ErrorC(ctx, "Error during context serializing: %+v", err)
		return nil, err
	}
	return req, nil
}
