---
description: Run a non-interactive command in the Privileged Server and consume its output once as a stream or a bounded result.
---

# Commands {#commands}

The command API starts one non-interactive process in the connected Privileged Server. It executes
an argument list directly, keeps stdout and stderr separate, and does not add a shell.

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

## Choose one consumption path {#consume-once}

A process permits exactly one output-consumption path. Use `awaitResult()` when output is needed
only after exit:

```kotlin
val result = process.use { it.awaitResult() }
println(result.exitCode)
println(result.stdout.toString(Charsets.UTF_8))
println(result.stderr.toString(Charsets.UTF_8))
```

Each returned stream is limited to 1 MiB by default. Excess data is drained to let the process
finish, while the corresponding `stdoutTruncated` or `stderrTruncated` flag becomes true. Pass a
different positive `maxBytesPerStream` when required.

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

The default timeout is 30 seconds. It starts when the remote process starts and is not reset by new
output. Pass a longer value for slow commands or `null` to disable the deadline. Cancelling stream
collection, cancelling `awaitResult`, calling `cancel()` or `close()`, owner death, and server
shutdown terminate unfinished commands.

Four commands may run concurrently. Additional starts fail immediately instead of waiting in a
queue.

## Deliberate limits {#limits}

The command API has no stdin or PTY. It does not support interactive shells, terminal sizing,
terminal signals, ANSI rendering, or daemon management. Some programs buffer output when stdout is
a pipe rather than a terminal; their data may arrive in batches even though transport is streamed.
