---
description: 无需定义 UserService，直接通过已连接的 Privileged Server 执行基础文件操作。
---

# 文件代理 {#file-proxy}

内置文件代理可以直接在已连接的 Privileged Server 中执行基础的绝对路径文件操作。
应用不需要为此定义、启动或绑定 UserService。

`PrivilegeFile` 是不可变的路径句柄，不是 `java.io.File` 子类。创建和拼接句柄、
读取路径属性以及调用 `isHidden()` 都只在客户端计算。访问文件系统的方法会发起同步
Binder 调用，可以在任意线程调用，但调用方需要等待 Binder 调度和服务端文件系统
I/O，通常应放在工作线程执行。文件服务 Binder 已随服务端握手安装，因此一次文件系统
方法通常只增加一次到对应文件操作的 Binder 往返。

`scanDirectory()` 不同：它会在 `Dispatchers.IO` 中发起 Binder 请求并读取 pipe，
因此扫描工作不会阻塞收集 Flow 的线程。打开文件内容时需要同步完成 Binder 交接，
之后的内容通过 pipe 传输，不会为每个数据块发起一次 Binder transaction。

## 创建句柄 {#create-handle}

```kotlin
val directory = Privilege.file("/data/local/tmp/example")
val child = directory.resolve("payload.txt")

Log.d("file", child.absolutePath)
Log.d("file", child.parentFile.toString())
```

路径必须是绝对路径，不能包含 NUL 字符，并且 UTF-8 长度不能超过 4095 字节。
代理不会规范化 `.` 或 `..` 路径段。

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

源和目标必须位于同一挂载文件系统。已有目标会被原子替换，该操作不会退化为复制后
删除。失败时抛出 `IOException`，其 cause 是对应的 `ErrnoException`；跨文件系统操作
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
mode。`PrivilegeFile` 不会缓存元数据。

## 打开文件内容 {#open-content}

```kotlin
withContext(Dispatchers.IO) {
    child.openOutputStream().bufferedWriter().use { writer ->
        writer.write("hello")
    }

    val text = child.openInputStream().bufferedReader().use { it.readText() }
}
```

目标文件描述符会留在 Privileged Server 中，因为应用自身的 SELinux 域无权访问
底层目标时，Android 会拒绝把该描述符传给应用。文件内容改用 reliable pipe 流式
传输，仍然不会为每个数据块发起 Binder 调用。输入流在到达 EOF 时传播服务端读取
错误；关闭输出流会等待服务端消费全部字节并关闭目标，服务端写入失败会以
`IOException` 报告。需要在关闭前额外等待 `fsync(2)` 时，传入
`syncOnClose = true`。

文件内容传输使用独立的有界执行器，同时最多执行四个且不排队；继续打开会以
`EBUSY` 失败，因此应及时关闭流。该 API 刻意不暴露随机访问文件描述符。

## 流式扫描目录 {#scan-directory}

```kotlin
directory.scanDirectory().collect { entry ->
    val type = entry.metadata?.type?.name ?: "METADATA_UNAVAILABLE"
    Log.d("file", "$type: ${entry.absolutePath}")
}
```

每次收集都会启动一次新的无排序、非递归、弱一致扫描。目录项通过 pipe 流式返回，
不会装进一个 Binder 响应。每个 `PrivilegeFileDirectoryEntry` 都包含已枚举到的绝对
路径，服务端随后尝试 `lstat`；服务端 Linux 身份能看到名称、但读取属性返回
`EACCES` 或 `EPERM` 时，`metadata` 为 null。条目在读取属性前消失时会跳过；目标目录
本身无法打开或迭代仍会让扫描失败。取消收集会关闭 pipe。服务端最多同时执行四个
扫描且不设置等待队列；继续发起扫描会以 `EBUSY` 失败。

文件代理刻意不提供 `deleteRecursively()` 或存储清理 API。破坏性的遍历策略仍由
接入应用负责。
