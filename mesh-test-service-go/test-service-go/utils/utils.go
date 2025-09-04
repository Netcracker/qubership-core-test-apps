package utils

import (
	"github.com/gofiber/fiber/v2"
)

func RespondWithError(c *fiber.Ctx, code int, msg string) error {
	return RespondWithJson(c, code, map[string]string{"error": msg})
}

func RespondWithJson(c *fiber.Ctx, code int, payload interface{}) error {
	return c.Status(code).JSON(payload)
}

func RespondWithString(c *fiber.Ctx, code int, response string) error {
	return c.Status(code).SendString(response)
}

func RespondWithoutBody(c *fiber.Ctx, code int) error {
	return c.Status(code).JSON("")
}
