---
description: Perform basic file operations in the connected Privileged Server without defining a UserService.
---

# File proxy {#file-proxy}

The built-in file proxy performs basic absolute-path operations directly in the
connected Privileged Server. The application does not define, start, or bind a
UserService for these operations.

`PrivilegeFile` is an immutable path handle, not a `java.io.File` subclass.
Constructing and resolving a handle, reading path properties, and evaluating
`isHidden()` are local. Filesystem methods use synchronous Binder calls. They
can run on any thread, but should normally run on a worker thread because the
caller waits for Binder dispatch and server-side filesystem I/O. The
file-service Binder is installed with the server handshake, so a filesystem
method normally adds one Binder round trip to the corresponding filesystem call.

`walk()` and `deleteRecursively()` are different. Directory walking runs its
Binder request and pipe reader on `Dispatchers.IO`.
`deleteRecursively()` suspends while a bounded server-side coroutine performs
the traversal, so neither operation blocks the caller's thread. Opening file
content requires a synchronous Binder handoff, but the content then moves
through pipes without one Binder transaction per data chunk.

## Create a handle {#create-handle}

```kotlin
val directory = Privilege.file("/data/local/tmp/example")
val child = directory.resolve("payload.txt")

Log.d("file", child.absolutePath)
Log.d("file", child.parentFile.toString())
```

Paths must be absolute, contain no NUL character, and fit within 4095 UTF-8
bytes. The proxy does not canonicalize `.` or `..` segments.

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

`deleteRecursively()` is a suspending operation. Its initial Binder call only
validates and admits the request; filesystem traversal runs in the Privileged
Server on a bounded IO coroutine dispatcher. The server accepts at most four
recursive deletes at once and has no waiting queue. A request rejected at that
limit returns `false` without starting.

A regular file or symbolic link is deleted directly. A directory is traversed
children-first with secure descriptor-relative directory operations, and
symbolic links are never followed. The public client method lexically normalizes
repeated separators and `.` or `..` segments before contacting the server. A
target that resolves to filesystem root is rejected with
`IllegalArgumentException`. The server uses the resulting path directly. If
safe relative directory operations are unavailable, the method returns `false`
instead of falling back to a traversal vulnerable to symbolic-link replacement
races.

The method returns `true` when the target is already absent or every entry was
deleted. It returns `false` if any entry remains, but continues attempting its
siblings. Cancellation, a `false` result, or Privileged Server death can leave a
partially deleted tree. Cancellation stops at an entry boundary and cannot
restore prior deletions. Do not automatically retry after server death because
the completed portion is unknown.

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

The source and destination must be on the same mounted filesystem. An existing
destination is replaced atomically; the operation never falls back to copy and
delete. Failure raises `IOException` whose cause is the corresponding
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
snapshot also contains modification time and the raw Unix mode. It is never
cached by `PrivilegeFile`.

## Open file contents {#open-content}

```kotlin
withContext(Dispatchers.IO) {
    child.openOutputStream().bufferedWriter().use { writer ->
        writer.write("hello")
    }

    val text = child.openInputStream().bufferedReader().use { it.readText() }
}
```

The target file descriptor remains in the Privileged Server because Android can
reject transferring a descriptor when the application SELinux domain cannot
access the underlying target. Bytes instead stream through reliable pipes; this
still avoids one Binder call per chunk. Input errors propagate when the reader
reaches EOF. Closing an output stream waits for the server to consume all bytes
and close the target, and reports a server write failure as `IOException`.
Pass `syncOnClose = true` when close must also wait for `fsync(2)`.

File content transfers use a separate bounded executor. Four transfers may be
active at once without a waiting queue; another open fails with `EBUSY`. Always
close streams promptly. Random-access descriptors are intentionally not exposed.

## Walk a directory tree {#walk-directory}

```kotlin
directory.walk(maxDepth = 2).collect { entry ->
    val type = entry.metadata?.type?.name ?: "METADATA_UNAVAILABLE"
    Log.d("file", "depth=${entry.depth} $type: ${entry.absolutePath}")
}
```

Each collection starts a new unsorted, weakly-consistent depth-first pre-order
walk. The receiving directory is not emitted; direct children have depth 1.
The default maximum depth is `Int.MAX_VALUE`, while `maxDepth = 1` lists only
direct children. Entries stream over a pipe instead of being packed into one
Binder response or buffered as a complete tree.

Every `PrivilegeFileEntry` contains its enumerated absolute path, relative
depth, and metadata snapshot. The server uses `lstat`, so symbolic links are
emitted but never entered. `metadata` is null when the server identity can see
the name but receives `EACCES` or `EPERM` for its attributes; such an entry is
also not entered. An entry that disappears before `lstat` is skipped. Failure
to open or iterate the starting directory or any entered descendant terminates
the flow and preserves the Linux error.

Cancelling collection closes the pipe and the open directory streams. One walk
uses one server coroutine and one concurrency slot regardless of depth. The
server permits four active walks without a waiting queue; another walk fails
with `EBUSY`.

The proxy does not turn recursive deletion into a storage-cleanup policy. It
does not discover targets, select retained files, expose configurable traversal
rules, or schedule persistent cleanup work; the application must still choose
the one explicit path to delete.
