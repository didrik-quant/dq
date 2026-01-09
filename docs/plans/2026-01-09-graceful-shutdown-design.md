# Graceful Shutdown with Hard Timeout

## Problem

Bot hangs after fatal error instead of exiting. The current flow:
1. `exitProcess(1)` triggers shutdown hook
2. Shutdown hook runs `runBlocking` with cleanup that never completes
3. Non-daemon Disruptor threads keep JVM alive

## Solution

Two-phase shutdown with watchdog thread:
1. **Soft phase** (5 seconds): Attempt graceful cleanup
2. **Hard phase**: Force exit with `Runtime.halt()`

## Implementation

### DisruptorConfig.kt

Remove `exitProcess(1)` from exception handler - callback handles exit.

### Main.kt

Add shutdown coordinator:

```kotlin
val shutdownLatch = CountDownLatch(1)
val exitCode = AtomicInteger(0)

// Watchdog thread
Thread {
    shutdownLatch.await(5, TimeUnit.SECONDS)
    runCatching {
        scope.cancel()
        publicWs.close()
        privateWs?.close()
        pipeline.stop()
        restClient?.close()
    }
    Runtime.getRuntime().halt(exitCode.get())
}.apply { isDaemon = true; name = "shutdown-watchdog"; start() }

// Minimal shutdown hook for Ctrl+C
Runtime.getRuntime().addShutdownHook(Thread {
    shutdownLatch.countDown()
})

// Fatal error callback signals watchdog
onFatalError = { ex ->
    commandSender?.cancelAllAndShutdown()
    exitCode.set(1)
    shutdownLatch.countDown()
}
```

## Exit Paths

- Fatal error → callback → `countDown()` → watchdog cleanup → `halt(1)`
- Ctrl+C → shutdown hook → `countDown()` → watchdog cleanup → `halt(0)`
