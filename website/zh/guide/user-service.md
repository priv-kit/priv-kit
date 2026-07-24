---
description: 在嵌入式或独立特权进程中运行应用自定义 AIDL 服务。
---

# UserService

UserService 在特权运行时中执行应用自有代码，并返回应用自定义 AIDL 接口的
raw Binder。

## 定义 AIDL 契约

使用 transaction code `16777114` 标记销毁方法：

```java
interface IMyPrivilegeService {
    void destroy() = 16777114;
    String getUid() = 1;
}
```

## 实现服务

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
失败后回退到 package Context；嵌入式服务获得 package Context。

## 使用独立进程

这是默认模式。服务运行在单独的 `app_process` 子进程中：

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "main",
)

Privilege.startUserService(spec)

Privilege.bindUserService(spec).use { connection ->
    val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
    service.getUid()
}

Privilege.stopUserService(spec)
```

每个实例由 `serviceClassName + tag` 标识。version 值只控制同一实例能否复用，
或是否必须替换。

## 使用嵌入式服务

设置 `embedded = true`，让服务直接运行在 Privileged Server 中：

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    embedded = true,
)
```

嵌入式模式省去额外进程，适合小型、低风险工作。它的 `destroy()` 只清理服务
自身资源；调用 `exitProcess(0)` 会终止整个 Privileged Server。

## 所有权边界

Priv Kit 负责加载、生命周期、握手和进程清理，应用完整掌控 AIDL 中定义的
业务方法。
