---
description: 在嵌入式或独立特权进程中运行应用自定义 AIDL 服务。
---

# UserService {#user-service}

UserService 在特权进程中运行应用代码，并返回自定义 AIDL 接口的 Binder。

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

`startUserService`、`bindUserService` 和 `stopUserService` 是可取消的挂起函数。取消会移除待处理任务及尚未接收的资源。`connection.unbind()` 幂等，并在不可取消上下文完成清理。

每个实例由 `serviceClassName + tag` 标识。`version` 表达同一实例能否复用；实现不再
兼容时修改该值，运行时会替换实例。

## 使用嵌入式服务 {#embedded-service}

设置 `embedded = true`，让服务直接运行在 Privileged Server 中，无需额外进程，节省进程启动和内存开销：

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    version = 1,
    embedded = true,
)
```

嵌入式服务共享服务端进程，构造异步且可取消。

## 判断运行环境 {#execution-environment}

`PrivilegeUserServiceEnvironment.isEmbedded` 在共享服务端进程中为 `true`，在独立子进程中为 `false`，进程存活期间不变。

退出进程前先检查该值：嵌入式服务只能清理自己的资源，不能调用 `exitProcess`，否则会结束整个特权进程。通过 `PrivilegeUserServiceSpec.embedded` 选择模式。
