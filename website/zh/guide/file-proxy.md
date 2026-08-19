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

`walk()` 和 `deleteRecursively()` 不同。目录遍历会在 `Dispatchers.IO` 中
发起 Binder 请求并读取 pipe；`deleteRecursively()` 会挂起调用方，并由服务端有界
协程执行遍历，因此两者都不会阻塞调用方线程。打开文件内容时仍需要同步完成 Binder
交接，之后的内容通过 pipe 传输，不会为每个数据块发起一次 Binder transaction。

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

`deleteRecursively()` 是挂起操作。最初的 Binder 调用只负责校验和受理请求，实际文件
遍历在 Privileged Server 的有界 IO 协程调度器中执行。服务端最多同时受理四个递归
删除且不设置等待队列；达到上限后，新请求直接返回 `false`，不会开始删除。

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

目标文件描述符会留在 Privileged Server 中，因为应用自身的 SELinux 域无权访问
底层目标时，Android 会拒绝把该描述符传给应用。文件内容改用 reliable pipe 流式
传输，仍然不会为每个数据块发起 Binder 调用。输入流在到达 EOF 时传播服务端读取
错误；关闭输出流会等待服务端消费全部字节并关闭目标，服务端写入失败会以
`IOException` 报告。需要在关闭前额外等待 `fsync(2)` 时，传入
`syncOnClose = true`。

文件内容传输使用独立的有界执行器，同时最多执行四个且不排队；继续打开会以
`EBUSY` 失败，因此应及时关闭流。该 API 提供顺序流，不提供随机访问文件描述符。

## 遍历目录树 {#walk-directory}

```kotlin
directory.walk(maxDepth = 2).collect { entry ->
    val type = entry.metadata?.type?.name ?: "METADATA_UNAVAILABLE"
    Log.d("file", "depth=${entry.depth} $type: ${entry.absolutePath}")
}
```

每次收集都会启动一次新的无排序、弱一致、深度优先先序遍历。接收者目录本身不会
输出，直接子项的深度为 1。默认最大深度为 `Int.MAX_VALUE`；传入 `maxDepth = 1` 时
只列出直接子项。条目通过 pipe 流式返回，不会装进一个 Binder 响应，也不会在内存中
缓存完整目录树。

每个 `PrivilegeFileEntry` 都包含已枚举到的绝对路径、相对深度和元数据快照。服务端
使用 `lstat`，所以符号链接会输出但不会进入。服务端 Linux 身份能看到名称、但读取
属性返回 `EACCES` 或 `EPERM` 时，`metadata` 为 null，这类条目同样不会进入。条目在
读取属性前消失时会跳过；起始目录或任一已进入的后代目录无法打开或迭代时，Flow 会
终止并保留对应 Linux 错误。

取消收集会关闭 pipe 和所有已打开的目录流。一次 walk 无论深度只使用一个服务端
协程和一个并发槽位。服务端最多同时执行四个 walk 且不设置等待队列；继续发起会以
`EBUSY` 失败。

应用为递归删除提供一个明确目标，并负责路径发现、保留规则、调度和存储清理策略。
