---
description: Read, write, and manage files through the connected server.
---

# File proxy {#file-proxy}

`Privilege.file()` provides file access through the connected server. Synchronous file operations block the calling thread.

## Create a handle {#create-handle}

Use an absolute path. `resolve()` builds a child handle locally.

```kotlin
val directory = Privilege.file("/data/local/tmp/example")
val child = directory.resolve("payload.txt")

Log.d("file", child.absolutePath)
Log.d("file", child.parentFile.toString())
```

## Use familiar file operations {#common-operations}

Most operations follow `java.io.File` semantics. A disconnected server raises `PrivilegeServerUnavailableException` for operations that need it.

```kotlin
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
```

## Delete a tree {#delete-recursively}

`deleteRecursively()` returns `true` when the target is gone. It removes symbolic links as leaf entries and rejects the filesystem root. Cancellation or failure can leave a partially deleted tree.

```kotlin
val deleted = directory.deleteRecursively()
if (!deleted) {
    Log.w("file", "Some entries could not be deleted from $directory")
}
```

## Replace a file atomically {#atomic-replacement}

Prepare a temporary file on the same filesystem, then replace the destination with `replaceAtomically()`. `syncOnClose = true` syncs the temporary file before replacement. Sync the parent directory separately if you need the rename to survive a crash.

```kotlin
val temporary = Privilege.file("${target.absolutePath}.tmp")
temporary.openOutputStream(syncOnClose = true).use { output ->
    output.write(text.toByteArray(Charsets.UTF_8))
}
temporary.replaceAtomically(target)
```

## Read metadata once {#metadata}

Use `metadata()` to read several attributes at once. It reports the link itself by default. Pass `followSymbolicLinks = true` to read its target.

```kotlin
val metadata = child.metadata()

Log.d("file", "${metadata.type} ${metadata.sizeBytes} ${metadata.uid}:${metadata.gid}")
```

## Open file contents {#open-content}

Use `use` to close streams. Output close waits for the write and reports errors. `syncOnClose = true` also syncs the file. Up to four transfers can run at once.

```kotlin
child.openOutputStream().bufferedWriter().use { writer ->
    writer.write("hello")
}

val text = child.openInputStream().bufferedReader().use { it.readText() }
```

## Walk a directory tree {#walk-directory}

`walk()` streams descendants without the root itself. `maxDepth = 1` lists direct children. Set `skipDirectoryGlobs` to skip a directory’s contents by matching its basename. The directory entry itself remains in the results.

```kotlin
directory.walk(
    maxDepth = 8,
    skipDirectoryGlobs = listOf("node_modules", ".git", "build-*"),
).collect { entry ->
    Log.d("file", "depth=${entry.depth}: ${entry.absolutePath}")
}
```

Entries arrive in depth-first order with no sorting. Metadata can be null for visible entries whose attributes cannot be read. Traversal treats symbolic links as leaves.
