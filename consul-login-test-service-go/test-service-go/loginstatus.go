package main

import (
	"github.com/gofiber/fiber/v2"
	"github.com/netcracker/qubership-core-lib-go/v3/configloader"
)

// What the login left behind, in the shape the scenarios read: which way the service was configured for, and the
// property it got from Consul. Unlike the Spring and Quarkus services this one names no token: the Consul token stays
// inside the property source of the Go library, and under a deny-by-default policy the marker below arrives only
// through a token Consul issued.
type loginStatus struct {
	LoginMode    string `json:"loginMode"`
	AuthMethod   string `json:"authMethod"`
	Audience     string `json:"audience"`
	ConsulMarker string `json:"consulMarker"`
}

func loginStatusHandler(ctx *fiber.Ctx) error {
	return ctx.JSON(loginStatus{
		LoginMode:    configloader.GetOrDefaultString("consul.auth.mode", "UNSET"),
		AuthMethod:   configloader.GetOrDefaultString("consul.auth.method", "UNSET"),
		Audience:     configloader.GetOrDefaultString("consul.auth.audience", "UNSET"),
		ConsulMarker: configloader.GetOrDefaultString("service.marker", "ABSENT"),
	})
}
