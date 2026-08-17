package controller

import (
	"os"
	"runtime"

	"github.com/gofiber/fiber/v2"
	"github.com/netcracker/qubership-storage-test-service-go/storage"
	"github.com/netcracker/qubership-storage-test-service-go/workload"
)

const defaultOperationsPerSecond = 10

// Controller is the contract the suite drives; storage-specific behaviour sits behind the
// {type} path segment.
type Controller struct {
	workload *workload.Runner
}

func New(runner *workload.Runner) *Controller {
	return &Controller{workload: runner}
}

// Register wires the whole contract onto the given router.
func (c *Controller) Register(router fiber.Router) {
	router.Post("/db/:type/init", c.Init)
	router.Post("/db/:type/write", c.Write)
	router.Get("/db/:type/read", c.Read)
	router.Post("/workload/start", c.Start)
	router.Post("/workload/stop", c.Stop)
	router.Get("/workload/stats", c.Stats)
	router.Get("/diag", c.Diag)
}

func (c *Controller) Init(ctx *fiber.Ctx) error {
	storageType := ctx.Params("type")
	probe, err := c.workload.Probe(storageType)
	if err != nil {
		return fiber.NewError(fiber.StatusBadRequest, err.Error())
	}
	if err = probe.Init(ctx.UserContext()); err != nil {
		return fiber.NewError(fiber.StatusInternalServerError, err.Error())
	}
	return ctx.JSON(fiber.Map{"storage": storageType, "initialised": true})
}

func (c *Controller) Write(ctx *fiber.Ctx) error {
	storageType := ctx.Params("type")
	probe, err := c.workload.Probe(storageType)
	if err != nil {
		return fiber.NewError(fiber.StatusBadRequest, err.Error())
	}
	key := ctx.Query("key")
	value := ctx.Query("value")
	read, err := probe.WriteAndRead(ctx.UserContext(), handleMode(ctx), key, value)
	if err != nil {
		return fiber.NewError(fiber.StatusInternalServerError, err.Error())
	}
	return ctx.JSON(fiber.Map{"storage": storageType, "key": key, "value": read})
}

func (c *Controller) Read(ctx *fiber.Ctx) error {
	storageType := ctx.Params("type")
	probe, err := c.workload.Probe(storageType)
	if err != nil {
		return fiber.NewError(fiber.StatusBadRequest, err.Error())
	}
	key := ctx.Query("key")
	value, err := probe.Read(ctx.UserContext(), handleMode(ctx), key)
	if err != nil {
		return fiber.NewError(fiber.StatusInternalServerError, err.Error())
	}
	return ctx.JSON(fiber.Map{"storage": storageType, "key": key, "found": value != "", "value": value})
}

func (c *Controller) Start(ctx *fiber.Ctx) error {
	storageType := ctx.Query("storage")
	mode := handleMode(ctx)
	operationsPerSecond := ctx.QueryInt("operationsPerSecond", defaultOperationsPerSecond)

	if err := c.workload.Start(ctx.UserContext(), storageType, mode, operationsPerSecond); err != nil {
		return fiber.NewError(fiber.StatusInternalServerError, err.Error())
	}
	return ctx.JSON(fiber.Map{"storage": storageType, "handleMode": mode,
		"operationsPerSecond": operationsPerSecond, "running": true})
}

func (c *Controller) Stop(ctx *fiber.Ctx) error {
	c.workload.Stop()
	return ctx.JSON(fiber.Map{"running": false})
}

func (c *Controller) Stats(ctx *fiber.Ctx) error {
	return ctx.JSON(c.workload.Stats())
}

// Diag reports what the leak scenario compares: after N fault cycles these must return to
// baseline. threadCount is the goroutine count, which is this runtime's equivalent.
func (c *Controller) Diag(ctx *fiber.Ctx) error {
	var memory runtime.MemStats
	runtime.ReadMemStats(&memory)

	storages := make(map[string]any)
	for _, probe := range c.workload.Probes() {
		storages[probe.Type()] = probe.Diagnostics()
	}
	return ctx.JSON(fiber.Map{
		"threadCount":         runtime.NumGoroutine(),
		"openFileDescriptors": openFileDescriptors(),
		"heapUsedBytes":       memory.HeapAlloc,
		"storages":            storages,
	})
}

func handleMode(ctx *fiber.Ctx) storage.HandleMode {
	if ctx.Query("handleMode") == string(storage.LongHeld) {
		return storage.LongHeld
	}
	return storage.PerCall
}

// openFileDescriptors returns -1 where the platform does not expose them; every leaked
// connection is one descriptor.
func openFileDescriptors() int {
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return -1
	}
	return len(entries)
}
