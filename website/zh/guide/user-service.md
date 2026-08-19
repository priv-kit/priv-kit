---
description: 在嵌入式或独立特权进程中运行应用自定义 AIDL 服务。
---

# UserService {#user-service}

UserService 在特权运行时中执行应用自己的代码，并返回应用自定义 AIDL 接口对应的
Binder。

## 定义 AIDL 接口 {#define-aidl-contract}

将销毁方法的 transaction code 设为 `16777114`：

```java
interface IMyPrivilegeService {
    void destroy() = 16777114;
    String getUid() = 1;
}
```

## 实现服务 {#implement-service}

```kotlin
class MyPrivilegeService private constructor(
    private val context: Context?,
) : IMyPrivilegeService.Stub() {
    @Keep
    constructor() : this(context = null)

    @Keep
    constructor(context: Context) : this(context = context)

    override fun getUid(): String {
        return "uid=${android.os.Process.myUid()}"
    }

    override fun destroy() {
        if (!PrivilegeUserServiceEnvironment.isEmbedded) {
            exitProcess(0)
        }
    }
}
```

运行时支持无参构造器或 `Context` 构造器。独立进程优先初始化 Application，
失败后改用 package Context。嵌入式服务获得 package Context。

## 判断运行环境 {#execution-environment}

`PrivilegeUserServiceEnvironment.isEmbedded` 用来判断 UserService 是否直接运行在
Privileged Server 进程中。直接运行时返回 `true`，运行在独立的 `app_process`
子进程时返回 `false`。该值在进程生命周期内保持不变，并会在首次读取后缓存。

执行进程退出或全局清理前，先检查这个属性。上面的独立子进程会在 `destroy()` 中
调用 `exitProcess(0)`；嵌入式服务与 Privileged Server 共享进程，只清理自身资源。
应用通过 `PrivilegeUserServiceSpec.embedded` 选择进程模式。

## 使用独立进程 {#dedicated-process}

这是默认模式。服务运行在单独的 `app_process` 子进程中：

```kotlin
lifecycleScope.launch {
    val spec = PrivilegeUserServiceSpec(
        serviceClassName = MyPrivilegeService::class.java.name,
        tag = "main",
        version = 1,
    )

    Privilege.startUserService(spec)

    val connection = Privilege.bindUserService(spec)
    try {
        val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
        service.getUid()
    } finally {
        connection.unbind()
    }

    Privilege.stopUserService(spec)
}
```

`startUserService`、`bindUserService` 和 `stopUserService` 都是可取消的挂起函数。
等待服务锁或启动独立进程期间不会阻塞调用线程。如果协程在操作被接收前取消，运行时
会移除待处理状态，并销毁只为该次已取消操作创建的进程或连接。
`connection.unbind()` 是幂等的挂起函数。调用一旦进入，就会在不可取消上下文中完成，
避免丢弃必要的资源清理；server 仍通过同一套有界异步协议在 Binder 线程之外执行。

每个实例由 `serviceClassName + tag` 标识。`version` 表达同一实例能否复用；实现不再
兼容时修改该值，运行时会替换实例。

## 使用嵌入式服务 {#embedded-service}

设置 `embedded = true`，让服务直接运行在 Privileged Server 中：

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    version = 1,
    embedded = true,
)
```

嵌入式模式省去额外进程，适合简单且风险较低的服务。它的 `destroy()` 清理服务
自身资源并保留共享的 Privileged Server。绑定通常更快，因为省去了子进程启动和认领；
服务构造仍会异步执行，并支持取消。
