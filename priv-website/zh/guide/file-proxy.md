---
description: 通过已连接的服务端读写和管理文件。
---

# 文件代理 {#file-proxy}

用 `Privilege.file()` 通过已连接的服务端访问文件。同步文件操作应放在后台线程。

## 创建句柄 {#create-handle}

传入绝对路径。`resolve()` 在本地生成子路径句柄。

```kotlin
val directory = Privilege.file("/data/local/tmp/example")
val child = directory.resolve("payload.txt")

Log.d("file", child.absolutePath)
Log.d("file", child.parentFile.toString())
```

## 调用常见文件操作 {#common-operations}

多数操作遵循 `java.io.File` 的语义。需要服务端的操作会在断线时抛出 `PrivilegeServerUnavailableException`。

```kotlin
withContext(Dispatchers.IO) {
    if (!directory.mkdirs() && !directory.isDirectory()) {
        error("无法创建 $directory")
    }

    if (child.createNewFile()) {
        Log.d("file", "已创建 $child")
    }

    val renamed = directory.resolve("renamed.txt")
    if (!child.renameTo(renamed)) {
        error("无法重命名 $child")
    }

    renamed.delete()
}
```

## 删除目录树 {#delete-recursively}

`deleteRecursively()` 在目标已不存在时返回 `true`。符号链接按叶子条目删除，文件系统根目录会被拒绝。取消或失败可能留下部分未删除的条目。

```kotlin
val deleted = directory.deleteRecursively()
if (!deleted) {
    Log.w("file", "$directory 中有条目无法删除")
}
```

## 原子替换文件 {#atomic-replacement}

在同一文件系统准备临时文件，再用 `replaceAtomically()` 替换目标。`syncOnClose = true` 可在替换前同步临时文件。若需要在系统崩溃后保留重命名结果，还需单独同步父目录。

```kotlin
val temporary = Privilege.file("${target.absolutePath}.tmp")
temporary.openOutputStream(syncOnClose = true).use { output ->
    output.write(text.toByteArray(Charsets.UTF_8))
}
temporary.replaceAtomically(target)
```

## 一次读取完整元数据 {#metadata}

用 `metadata()` 一次读取多项属性。默认读取符号链接自身，传入 `followSymbolicLinks = true` 可读取目标。

```kotlin
val metadata = withContext(Dispatchers.IO) {
    child.metadata()
}

Log.d("file", "${metadata.type} ${metadata.sizeBytes} ${metadata.uid}:${metadata.gid}")
```

## 打开文件内容 {#open-content}

用 `use` 关闭流。输出流关闭时会等待写入完成并报告错误。`syncOnClose = true` 还会同步文件。同时最多进行四次内容传输。

```kotlin
withContext(Dispatchers.IO) {
    child.openOutputStream().bufferedWriter().use { writer ->
        writer.write("hello")
    }

    val text = child.openInputStream().bufferedReader().use { it.readText() }
}
```

## 遍历目录树 {#walk-directory}

`walk()` 流式输出后代条目，省略根目录。`maxDepth = 1` 只列直接子项。`skipDirectoryGlobs` 按目录名匹配并跳过其内容，目录自身仍会输出。

```kotlin
directory.walk(
    maxDepth = 8,
    skipDirectoryGlobs = listOf("node_modules", ".git", "build-*"),
).collect { entry ->
    Log.d("file", "depth=${entry.depth}: ${entry.absolutePath}")
}
```

结果按深度优先遍历，不排序。能看到条目名称却无法读取属性时，元数据可以为空。遍历将符号链接视为叶子。
