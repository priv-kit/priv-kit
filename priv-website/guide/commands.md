---
description: Run commands and read their output.
---

# Commands {#commands}

The command API executes an argument list directly in the connected server, returning stdout and stderr separately.

## Start a process {#start-process}

`Privilege.startCommand` suspends until the remote process starts and returns a single-use
`PrivilegeCommandProcess`:

```kotlin
val process = Privilege.startCommand(
    PrivilegeCommand(
        arguments = listOf("/system/bin/id"),
        environment = mapOf("LANG" to "C"),
        workingDirectory = "/data/local/tmp",
    ),
)
```

The executable is the first argument. To opt into shell parsing, pass the shell explicitly:

```kotlin
val process = Privilege.startCommand(
    PrivilegeCommand(
        listOf("/system/bin/sh", "-c", "for i in 1 2 3; do echo \$i; sleep 1; done"),
    ),
)
```

## Read output {#consume-once}

A process permits exactly one output-consumption path. Use `awaitResult()` when output is needed
only after exit:

```kotlin
val result = process.use { it.awaitResult() }
println(result.exitCode)
println(result.stdout.toString(Charsets.UTF_8))
println(result.stderr.toString(Charsets.UTF_8))
```

Each stream is capped at 1 MiB by default. Excess output is drained but not retained; `stdoutTruncated` or `stderrTruncated` indicates truncation. Set a positive `maxBytesPerStream` to change the limit.

Use `stream()` for live output:

```kotlin
process.use {
    it.stream().collect { event ->
        when (event) {
            is PrivilegeCommandEvent.Stdout -> showStdout(event.bytes)
            is PrivilegeCommandEvent.Stderr -> showStderr(event.bytes)
            is PrivilegeCommandEvent.Exited -> showExitCode(event.exitCode)
        }
    }
}
```

The flow can be collected once. Byte chunks preserve order within their own stream but are not
lines or text boundaries; use an incremental decoder when displaying UTF-8. The exit event is
always last, after both output pipes reach EOF. A nonzero exit code is a normal exit event.

## Timeout and cancellation {#timeout-cancellation}

The default timeout is a fixed 30-second deadline from remote process startup. Pass a longer value for slow commands or `null` to disable the deadline. Cancelling stream
collection, cancelling `awaitResult`, calling `cancel()` or `close()`, owner death, and server
shutdown terminate unfinished commands.

Up to four commands run concurrently; additional starts fail immediately.

## Limits {#limits}

No stdin, PTY, interactive shell, or daemon management is provided. Programs may buffer output to pipes, so streamed data can still arrive in batches.