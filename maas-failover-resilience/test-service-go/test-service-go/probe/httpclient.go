package probe

import (
	"net/http"
	"sync"

	"github.com/go-resty/resty/v2"
	"github.com/netcracker/qubership-core-lib-go/v3/security/rest"
)

// maasHttpClient is the client every MaaS client in this service is built with, so that they share
// one connection pool. Built on first use, because the configuration is not loaded at package
// initialisation.
var maasHttpClient = sync.OnceValue(newMaasHttpClient)

// newMaasHttpClient builds what maas-core builds for a client of its own: m2m authentication on
// the transport, and no retries, which the maas client performs.
func newMaasHttpClient() *resty.Client {
	return resty.New().
		SetTransport(&m2mRoundTripper{rest.NewMaasRestClient()}).
		SetRetryCount(0)
}

type m2mRoundTripper struct {
	client *rest.M2MRestClient
}

func (m *m2mRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	return m.client.DoRequest(req.Context(), req.Method, req.URL.String(), req.Header, req.Body)
}
