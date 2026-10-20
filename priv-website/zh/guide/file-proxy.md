---
description: 通过已连接的服务端读写和管理文件。
---

# 文件代理 {#file-proxy}

通过 `Privilege.file(absolutePath)` 在已连接的服务端访问文件。

`PrivilegeFile` 是不可变的路径句柄。路径属性、`resolve()` 和 `isHidden()` 在本地计算；文件系统方法通过同步 Binder 调用，应放在工作线程。

`walk()` 在 `Dispatchers.IO` 读取；`deleteRecursively()` 挂起等待服务端遍历。打开流是同步操作，后续数据通过管道传输。

## 创建句柄 {#create-handle}

```kotlin
val directory = Privilege.file("/data/local/tmp/example")
val child = directory.resolve("payload.txt")

Log.d("file", child.absolutePath)
Log.d("file", child.parentFile.toString())
```

代理接受 UTF-8 长度不超过 4095 字节的绝对路径，并保留 `.`、`..` 路径段。路径中
不含 NUL 字符。

## 调用常见文件操作 {#common-operations}

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

`isHidden()` 根据文件名在客户端计算。`exists`、`isFile`、`isDirectory`、
`canRead`、`canWrite`、`canExecute`、`length`、`lastModified`、`createNewFile`、
`mkdir`、`mkdirs`、`delete` 和 `renameTo` 保持对应 `java.io.File` 方法的返回值
语义。因此，修改操作返回 `false` 时不会说明具体失败原因。需要服务端的操作遇到
Privileged Server 未连接或已经死亡时，会抛出 `PrivilegeServerUnavailableException`。

与 `java.io.File` 一样，目标已存在时 `createNewFile()` 返回 `false`，其他 I/O
失败抛出 `IOException`。`renameTo()` 是否能够替换已有目标取决于服务端文件系统。
元数据和扫描失败会保留 Linux `errno` 并抛出 `ErrnoException`；打开失败使用
`IOException`，其 cause 是对应的 errno 异常。

## 删除目录树 {#delete-recursively}

```kotlin
val deleted = directory.deleteRecursively()
if (!deleted) {
    Log.w("file", "$directory 中有条目无法删除")
}
```

`deleteRecursively()` 挂起等待服务端删除。最多同时执行四个，超出的请求返回 `false`。

普通文件和符号链接会被直接删除。目录使用安全的描述符相对操作按“子项优先”顺序
遍历，符号链接按叶子条目处理。客户端公开方法会在连接服务端前按词法合并重复分隔符及
`.`、`..` 路径段；合并后指向文件系统根的目标会抛出 `IllegalArgumentException`，
服务端直接使用合并结果。底层无法提供安全的相对目录操作时，该方法返回 `false`。

目标已经不存在或所有条目均删除时返回 `true`。任一条目无法删除时返回 `false`，但
仍会继续尝试其兄弟条目。取消、`false` 结果或 Privileged Server 死亡都可能留下已经
部分删除的目录树。取消在条目边界停止后续工作，已经删除的内容保持不变。服务端死亡
后已完成范围未知，由应用决定如何继续。

## 原子替换文件 {#atomic-replacement}

准备好的临时文件需要通过一次 Linux `rename(2)` 替换目标时，使用
`replaceAtomically()`：

```kotlin
val temporary = Privilege.file("${target.absolutePath}.tmp")
temporary.openOutputStream(syncOnClose = true).use { output ->
    output.write(text.toByteArray(Charsets.UTF_8))
}
temporary.replaceAtomically(target)
```

源和目标位于同一挂载文件系统时，一次 rename 会原子替换已有目标。失败时抛出
`IOException`，其 cause 是对应的 `ErrnoException`；跨文件系统操作
会保留 `EXDEV`。该方法本身不会同步文件数据或父目录。示例中的 `syncOnClose` 会在
调用 `replaceAtomically()` 前同步临时文件，但不会在重命名后同步父目录。

## 一次读取完整元数据 {#metadata}

需要多个属性时，使用一次元数据调用：

```kotlin
val metadata = withContext(Dispatchers.IO) {
    child.metadata()
}

Log.d("file", "${metadata.type} ${metadata.sizeBytes} ${metadata.uid}:${metadata.gid}")
```

`metadata()` 默认使用 `lstat`，所以符号链接会报告为 `SYMBOLIC_LINK`。传入
`followSymbolicLinks = true` 可以读取链接目标。快照还包含修改时间和原始 Unix
mode。每次调用都会读取新快照。

## 打开文件内容 {#open-content}

```kotlin
withContext(Dispatchers.IO) {
    child.openOutputStream().bufferedWriter().use { writer ->
        writer.write("hello")
    }

    val text = child.openInputStream().bufferedReader().use { it.readText() }
}
```

目标 fd 留在服务端，避免 SELinux 对描述符传递的限制；数据通过 reliable pipe 传输。输入错误在 EOF 时报告，输出流关闭会等待服务端写完，失败抛出 `IOException`。`syncOnClose = true` 还会等待 `fsync(2)`。

文件内容传输使用独立的有界执行器，同时最多执行四个且不排队；继续打开会以
`EBUSY` 失败，因此应及时关闭流。该 API 通过顺序流读写文件。

## 遍历目录树 {#walk-directory}

```kotlin
directory.walk(maxDepth = 2).collect { entry ->
    val type = entry.metadata?.type?.name ?: "METADATA_UNAVAILABLE"
    Log.d("file", "depth=${entry.depth} $type: ${entry.absolutePath}")
}
```

`flushBatchSize` 指定每次显式刷新之间的条目数，默认 32，必须为正数。首项立即刷新，缓冲区满时可能提前刷新。

```kotlin
directory.walk(maxDepth = 2, flushBatchSize = 64).collect { entry ->
    Log.d("file", entry.absolutePath)
}
```

如果需要在服务端剪枝常见的依赖或生成目录：

```kotlin
directory.walk(
    maxDepth = 8,
    skipDirectoryGlobs = listOf("node_modules", ".git", "build-*"),
).collect { entry ->
    Log.d("file", "depth=${entry.depth}: ${entry.absolutePath}")
}
```

每次收集启动一次无排序、弱一致、深度优先先序遍历。根目录不输出，子项深度从 1 开始。`maxDepth` 默认 `Int.MAX_VALUE`，设为 1 只列直接子项。结果通过管道返回。

跳过规则会区分大小写地匹配目录的完整 basename。`*` 匹配零个或多个字符，`?` 匹配
一个字符，`\` 用于转义 `*`、`?` 或 `\`，且规则不能包含 `/`。命中的目录本身仍会
输出，但服务端不会打开或枚举其后代。需要根据路径、元数据或动态业务规则剪枝时，应由
应用自行遍历或使用自定义 UserService。

每个 `PrivilegeFileEntry` 都包含已枚举到的绝对路径、相对深度和元数据快照。服务端
使用 `lstat`，所以符号链接会输出但不会进入。服务端 Linux 身份能看到名称、但读取
属性返回 `EACCES` 或 `EPERM` 时，`metadata` 为 null，这类条目同样不会进入。条目在
读取属性前消失时会跳过；起始目录或任一已进入的后代目录无法打开或迭代时，Flow 会
终止并保留对应 Linux 错误。

取消会关闭管道和已打开目录。每次遍历只占一个槽位，与深度无关；最多同时四个，超出时抛出 `EBUSY`。
