---
description: Run commands and read their output.
---

# Commands {#commands}

Run a command in the connected server and read stdout and stderr separately.

## Start a process {#start-process}

Pass the executable and its arguments directly. To use shell syntax, explicitly invoke `/system/bin/sh -c`.

```kotlin
val process = Privilege.startCommand(
    PrivilegeCommand(
        arguments = listOf("/system/bin/id"),
        environment = mapOf("LANG" to "C"),
        workingDirectory = "/data/local/tmp",
    ),
)
```

```kotlin
val process = Privilege.startCommand(
    PrivilegeCommand(
        listOf("/system/bin/sh", "-c", "for i in 1 2 3; do echo \$i; sleep 1; done"),
    ),
)
```

## Read output {#consume-once}

Choose `awaitResult()` for captured output or `stream()` for live output. Each process permits one consumer.

```kotlin
val result = process.use { it.awaitResult() }
println(result.exitCode)
println(result.stdout.toString(Charsets.UTF_8))
println(result.stderr.toString(Charsets.UTF_8))
```

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

Captured output defaults to 1 MiB per stream. Check `stdoutTruncated` and `stderrTruncated` or change `maxBytesPerStream`. Live output arrives in byte chunks, so use incremental decoding for text. A nonzero exit code is returned as a result.

## Timeout and cancellation {#timeout-cancellation}

The default deadline is 30 seconds. Set a longer timeout or pass `null` to disable it. Cancellation, closing the handle, owner death, or server shutdown terminates an unfinished command. A deadline throws `PrivilegeCommandTimeoutException`.

## Limits {#limits}

At most four commands can run at once. The API supports non-interactive commands with no stdin or PTY.
