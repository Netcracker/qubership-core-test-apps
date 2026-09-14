package main

import (
	"context"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"fmt"
	"os"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/netcracker/qubership-core-lib-go/v3/security"
)

const (
	privateKeyVariable = "CONSUL_LOGIN_M2M_PRIVATE_KEY"
	issuerVariable     = "CONSUL_LOGIN_M2M_ISSUER"
	audienceVariable   = "CONSUL_LOGIN_M2M_AUDIENCE"
	subjectVariable    = "CONSUL_LOGIN_M2M_SUBJECT"

	tokenLifetime = 10 * time.Minute
	clockSkew     = time.Minute
)

// Stands in for the customer security library that real services pull in. The login of the m2m way asks the service
// loader for a security.TokenProvider, so this one is registered as the only implementation the service has.
//
// With a signing key in CONSUL_LOGIN_M2M_PRIVATE_KEY it signs its own JWT, which is the old way end to end on a stand
// that has no Identity Provider; Consul is configured with the matching public key. Without a key it hands out the
// empty token of the dummy provider, which Consul rejects.
//
// nbf is mandatory: without it Consul rejects the login with a validation error that says nothing about the caller.
type StandInTokenProvider struct {
	security.DummyToken
}

func (p *StandInTokenProvider) GetToken(ctx context.Context) (string, error) {
	if os.Getenv(privateKeyVariable) == "" {
		return p.DummyToken.GetToken(ctx)
	}

	key, err := signingKey()
	if err != nil {
		return "", err
	}
	claims, err := claims(time.Now())
	if err != nil {
		return "", err
	}
	return jwt.NewWithClaims(jwt.SigningMethodRS256, claims).SignedString(key)
}

func claims(issuedAt time.Time) (jwt.MapClaims, error) {
	issuer, err := required(issuerVariable)
	if err != nil {
		return nil, err
	}
	subject, err := required(subjectVariable)
	if err != nil {
		return nil, err
	}
	audience, err := required(audienceVariable)
	if err != nil {
		return nil, err
	}
	return jwt.MapClaims{
		"iss": issuer,
		"sub": subject,
		"aud": audience,
		"iat": issuedAt.Unix(),
		"nbf": issuedAt.Add(-clockSkew).Unix(),
		"exp": issuedAt.Add(tokenLifetime).Unix(),
	}, nil
}

// The key never reaches an error message: a broken key is reported by the name of the variable it came from.
func signingKey() (*rsa.PrivateKey, error) {
	encoded, err := base64.StdEncoding.DecodeString(os.Getenv(privateKeyVariable))
	if err != nil {
		return nil, fmt.Errorf("cannot decode the m2m signing key of %s", privateKeyVariable)
	}
	parsed, err := x509.ParsePKCS8PrivateKey(encoded)
	if err != nil {
		return nil, fmt.Errorf("cannot read the m2m signing key of %s", privateKeyVariable)
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("the m2m signing key of %s is not an RSA key", privateKeyVariable)
	}
	return key, nil
}

func required(variable string) (string, error) {
	value := os.Getenv(variable)
	if value == "" {
		return "", fmt.Errorf("%s is required by the m2m stand-in", variable)
	}
	return value, nil
}
