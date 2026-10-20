---
description: Read, write, and manage files through the connected server.
---

# File proxy {#file-proxy}

Use `Privilege.file(absolutePath)` to access files in the connected server.

`PrivilegeFile` is an immutable path handle. Path properties, `resolve()` and `isHidden()` are local; filesystem methods make synchronous Binder calls and should run on a worker thread.

`walk()` reads on `Dispatchers.IO`; `deleteRecursively()` suspends while the server traverses. Opening a stream is synchronous; subsequent bytes travel through a pipe.

## Create a handle {#create-handle}

```kotlin
val directory = Privilege.file("/data/local/tmp/example")
val child = directory.resolve("payload.txt")

Log.d("file", child.absolutePath)
Log.d("file", child.parentFile.toString())
```

The proxy accepts absolute, NUL-free paths up to 4095 UTF-8 bytes. It preserves
`.` and `..` segments.

## Use familiar file operations {#common-operations}

```kotlin
withContext(Dispatchers.IO) {
    if (!directory.mkdirs() && !directory.isDirectory()) {
        error("Unable to create $directory")
    }

    if (child.createNewFile()) {
        Log.d("file", "Created $child")
    }

    val renamed = directory.resolve("renamed.txt")
    if (!child.renameTo(renamed)) {
        error("Unable to rename $child")
    }

    renamed.delete()
}
```

`isHidden()` is derived locally from the file name. `exists`, `isFile`,
`isDirectory`, `canRead`, `canWrite`, `canExecute`, `length`, `lastModified`,
`createNewFile`, `mkdir`, `mkdirs`, `delete`, and `renameTo` follow the
corresponding `java.io.File` return-value semantics. In particular, a mutating
Boolean method does not describe why it returned `false`. A missing or dead
Privileged Server is different and raises `PrivilegeServerUnavailableException`
for an operation that requires the server.

As with `java.io.File`, `createNewFile()` returns `false` when the target already
exists and raises `IOException` for other I/O failures. Whether `renameTo()` can
replace an existing destination depends on the server filesystem. Metadata and
scan failures preserve the Linux error as `ErrnoException`; open failures use
`IOException` with that errno exception as their cause.

## Delete a tree {#delete-recursively}

```kotlin
val deleted = directory.deleteRecursively()
if (!deleted) {
    Log.w("file", "Some entries could not be deleted from $directory")
}
```

`deleteRecursively()` suspends while the server deletes the tree. At most four deletes run concurrently; excess requests return `false`.

A regular file or symbolic link is deleted directly. Directories use secure,
descriptor-relative, children-first traversal, with symbolic links treated as
leaf entries. The public client method lexically normalizes
repeated separators and `.` or `..` segments before contacting the server. A
target that resolves to filesystem root is rejected with
`IllegalArgumentException`. The server uses the resulting path directly. If
safe relative directory operations are unavailable, the method returns `false`.

The method returns `true` when the target is already absent or every entry was
deleted. It returns `false` if any entry remains, but continues attempting its
siblings. Cancellation, a `false` result, or Privileged Server death can leave a
partially deleted tree. Cancellation stops at an entry boundary and leaves prior
deletions in place. After server death, the application decides how to continue
because the completed portion is unknown.

## Replace a file atomically {#atomic-replacement}

Use `replaceAtomically()` when a prepared temporary file must replace its
destination with one Linux `rename(2)` operation:

```kotlin
val temporary = Privilege.file("${target.absolutePath}.tmp")
temporary.openOutputStream(syncOnClose = true).use { output ->
    output.write(text.toByteArray(Charsets.UTF_8))
}
temporary.replaceAtomically(target)
```

The source and destination share one mounted filesystem. One rename atomically
replaces an existing destination. Failure raises `IOException` whose cause is the corresponding
`ErrnoException`, including `EXDEV` for a cross-filesystem attempt. The method
does not synchronize file data or the parent directory by itself. In the example,
`syncOnClose` synchronizes the temporary file before `replaceAtomically()` runs;
it does not synchronize the parent directory after the rename.

## Read metadata once {#metadata}

Use one metadata call when several attributes are needed:

```kotlin
val metadata = withContext(Dispatchers.IO) {
    child.metadata()
}

Log.d("file", "${metadata.type} ${metadata.sizeBytes} ${metadata.uid}:${metadata.gid}")
```

`metadata()` uses `lstat` by default, so a symbolic link is reported as
`SYMBOLIC_LINK`. Pass `followSymbolicLinks = true` to inspect its target. The
snapshot also contains modification time and the raw Unix mode. Each call reads
a fresh snapshot.

## Open file contents {#open-content}

```kotlin
withContext(Dispatchers.IO) {
    child.openOutputStream().bufferedWriter().use { writer ->
        writer.write("hello")
    }

    val text = child.openInputStream().bufferedReader().use { it.readText() }
}
```

The target fd stays in the server to avoid SELinux restrictions on fd transfer. Reliable pipes carry the data. Input errors appear at EOF; output close waits for the server to finish writing and reports failures as `IOException`. `syncOnClose = true` also waits for `fsync(2)`.

File content transfers use a separate bounded executor. Four transfers may be
active at once; another open fails with `EBUSY`. Close
streams promptly. This API reads and writes files through sequential streams.

## Walk a directory tree {#walk-directory}

```kotlin
directory.walk(maxDepth = 2).collect { entry ->
    val type = entry.metadata?.type?.name ?: "METADATA_UNAVAILABLE"
    Log.d("file", "depth=${entry.depth} $type: ${entry.absolutePath}")
}
```

`flushBatchSize` sets the number of entries between explicit flushes (default 32, must be positive). The first entry is flushed immediately; a full buffer may flush earlier.

```kotlin
directory.walk(maxDepth = 2, flushBatchSize = 64).collect { entry ->
    Log.d("file", entry.absolutePath)
}
```

To prune common generated or dependency directories on the server:

```kotlin
directory.walk(
    maxDepth = 8,
    skipDirectoryGlobs = listOf("node_modules", ".git", "build-*"),
).collect { entry ->
    Log.d("file", "depth=${entry.depth}: ${entry.absolutePath}")
}
```

Each collection starts a new unsorted, weakly consistent depth-first pre-order walk. The root is omitted; children start at depth 1. `maxDepth` defaults to `Int.MAX_VALUE`; set 1 for direct children only. Entries stream over a pipe.

Skip globs match a directory's complete basename case-sensitively. `*` matches
zero or more characters, `?` matches one character, and `\` escapes `*`, `?`,
or `\`; `/` is not accepted. A matching directory is emitted, but the server
does not open or enumerate its descendants. Use app-side traversal or a custom
UserService when pruning depends on paths, metadata, or dynamic business rules.

Every `PrivilegeFileEntry` contains its enumerated absolute path, relative
depth, and metadata snapshot. The server uses `lstat`, so symbolic links are
emitted as leaf entries. `metadata` is null when the server identity can see
the name but receives `EACCES` or `EPERM` for its attributes; such an entry is
also not entered. An entry that disappears before `lstat` is skipped. Failure
to open or iterate the starting directory or any entered descendant terminates
the flow and preserves the Linux error.

Cancellation closes the pipe and open directories. Each walk uses one server slot regardless of depth; at most four run concurrently, with excess requests failing with `EBUSY`.
