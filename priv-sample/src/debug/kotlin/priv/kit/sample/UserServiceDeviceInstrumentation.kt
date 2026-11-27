package priv.kit.sample

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeConfig
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.binder.PrivilegeBinderCall
import priv.kit.core.binder.PrivilegeBinderCallFailure
import priv.kit.core.binder.PrivilegeBinderWrapper
import priv.kit.core.binder.PrivilegeUnavailableException
import priv.kit.core.binder.PrivilegeSystemServiceSource
import priv.kit.core.userservice.PrivilegeUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import priv.kit.sample.userservice.IPrivilegeSampleDedicatedUserService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only lifecycle, restart and Binder failure scenarios. */
class UserServiceDeviceInstrumentation : Instrumentation() {
    private var phase = "lifecycle"
    private val output = StringBuilder()
    private val heldConnections = mutableListOf<PrivilegeUserServiceConnection>()
    private val modes = listOf(
        PrivilegeUserServiceProcess.EMBEDDED,
        PrivilegeUserServiceProcess.INHERIT,
        PrivilegeUserServiceProcess.SHELL,
    )
    private val preferences by lazy { targetContext.getSharedPreferences("user-service-device-test", 0) }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase = arguments?.getString("phase") ?: "lifecycle"
        start()
    }

    override fun onStart() {
        super.onStart()
        try {
            runBlocking {
                withTimeout(120_000) {
                    PrivilegeConfig.configure(10 * 60_000L, activeReconnectOnOwnerDeath = false)
                    if (phase in listOf("lifecycle", "callbacks", "failures", "direct")) {
                        report("STARTER ${shell(Privilege.nativeStarterCommand).trim()}")
                    }
                    val server = withTimeout(30_000) { Privilege.serverState.filterNotNull().first() }
                    check(server.uid == 2000) { "Expected an ADB server, got UID ${server.uid}" }
                    report("CONNECTED owner=${Process.myPid()} server=${server.pid} uid=${server.uid} protocol=${server.protocolVersion}")
                    when (phase) {
                        "lifecycle" -> modes.forEach { lifecycle(it) }
                        "callbacks" -> callbacks()
                        "failures" -> failures()
                        "direct" -> directCalls()
                        "prepare-restart" -> prepareRestart()
                        "verify-restart" -> verifyRestart()
                        else -> error("Unknown phase: $phase")
                    }
                }
            }
            report("PASS phase=$phase")
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", output.toString()) })
        } catch (failure: Throwable) {
            report("FAIL phase=$phase\n${failure.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", output.toString()) })
        }
    }

    private suspend fun lifecycle(mode: PrivilegeUserServiceProcess) {
        val spec = spec(mode, "lifecycle")
        check(Privilege.peekUserService(spec) == null)
        val original = Privilege.bindUserService(spec)
        check(original.isConnected.value)
        val service = service(original)
        val pid = service.pid
        check(service.uid == 2000)
        check((pid == Privilege.getServerInfo().pid) == mode.isEmbedded)
        service.describe("before-policy-change")
        val count = service.callCount

        val enabled = Privilege.bindUserService(spec, daemon = true)
        check(service(enabled).pid == pid && service(enabled).callCount == count)
        check(service.callCount == count)
        val otherMode = if (mode.isEmbedded) PrivilegeUserServiceProcess.INHERIT else PrivilegeUserServiceProcess.EMBEDDED
        check(Privilege.peekUserService(spec.copy(process = otherMode)) == null)
        original.unbind()
        check(!original.isConnected.value && enabled.isConnected.value)
        enabled.unbind()
        check(!enabled.isConnected.value)

        val peeked = checkNotNull(Privilege.peekUserService(spec))
        check(service(peeked).pid == pid && service(peeked).callCount == count)
        peeked.unbind()
        check(!peeked.isConnected.value)
        val preserved = checkNotNull(Privilege.peekUserService(spec))
        val disabled = Privilege.bindUserService(spec)
        check(service(disabled).pid == pid && service(disabled).callCount == count)
        disabled.unbind()
        check(!disabled.isConnected.value && preserved.isConnected.value)
        check(service(preserved).pid == pid)
        preserved.unbind()
        check(Privilege.peekUserService(spec) == null)
        awaitProcessExit(mode, pid)
        report("PASS $mode dynamic-daemon pid=$pid count=$count; no recreation, peek preserves policy, default bind disables daemon, last unbind destroys")

        val stopSpec = spec(mode, "stop")
        val first = Privilege.bindUserService(stopSpec, daemon = true)
        val second = checkNotNull(Privilege.peekUserService(stopSpec))
        val stopPid = service(first).pid
        Privilege.stopUserService(stopSpec)
        check(Privilege.peekUserService(stopSpec) == null)
        val replacement = Privilege.bindUserService(stopSpec)
        check(service(replacement).callCount == 0)
        awaitDisconnected(first)
        awaitDisconnected(second)
        check(runCatching { service(first).pid }.exceptionOrNull() is PrivilegeUnavailableException)
        check(runCatching { service(second).pid }.exceptionOrNull() is PrivilegeUnavailableException)
        first.unbind()
        second.unbind()
        first.unbind()
        check(replacement.isConnected.value)
        check(service(replacement).pid > 0)
        val replacementPid = service(replacement).pid
        Privilege.stopUserService(stopSpec)
        awaitDisconnected(replacement)
        Privilege.stopUserService(stopSpec)
        replacement.unbind()
        check(Privilege.peekUserService(stopSpec) == null)
        awaitProcessExit(mode, stopPid)
        awaitProcessExit(mode, replacementPid)
        check(Privilege.pingServer())
        report("PASS $mode stop disconnects all bindings; immediate rebind is fresh, stale unbind is harmless, privileged server remains alive")

        if (!mode.isEmbedded) {
            val deathSpec = spec(mode, "process-death")
            val connection = Privilege.bindUserService(deathSpec, daemon = true)
            val childPid = service(connection).pid
            shell("kill $childPid")
            awaitDisconnected(connection)
            check(Privilege.peekUserService(deathSpec) == null)
            connection.unbind()
            awaitProcessExit(mode, childPid)
            report("PASS $mode unexpected child death updates isConnected")
        }
    }

    private suspend fun awaitDisconnected(connection: PrivilegeUserServiceConnection) {
        withTimeout(5_000) { connection.isConnected.first { !it } }
    }

    private suspend fun callbacks() {
        val retainedWatches = mutableListOf<DeathWatch>()
        val removedWatches = mutableListOf<DeathWatch>()
        try {
            for (mode in modes) {
                val spec = PrivilegeUserServiceSpec(UserServiceFailureProbe::class.java.name, "callbacks-$mode", mode)
                val connection = Privilege.bindUserService(spec, daemon = true)
                val peer = checkNotNull(Privilege.peekUserService(spec))
                val pid = transact(peer.binder, UserServiceFailureProbe.GET_PID) { readInt() }
                check((pid == Privilege.getServerInfo().pid) == mode.isEmbedded)
                val routes = callbackRoutes(connection)
                val watches = routes.map(::DeathWatch)
                val removed = routes.map(::DeathWatch)
                retainedWatches += watches
                removedWatches += removed

                connection.unbind()
                check(!connection.isConnected.value && peer.isConnected.value)
                check(transact(peer.binder, UserServiceFailureProbe.GET_PID) { readInt() } == pid)
                check(routes.none { it.isBinderAlive })
                assertUnavailable { transact(routes[0], UserServiceFailureProbe.GET_PID) { readInt() } }
                assertUnavailable { transact(routes[1], UserServiceFailureProbe.GET_PID) { readInt() } }
                removed.forEach { check(it.unlink()) }
                check(watches.all { it.calls.get() == 0 })
                report("PASS $mode unbind closes only its connection; no death callback, peer remains usable, unlinkToDeath succeeds after unbind")

                if (mode.isEmbedded) {
                    Privilege.stopUserService(spec)
                    awaitDisconnected(peer)
                    check(Privilege.peekUserService(spec) == null)
                    delay(250)
                    check(watches.all { it.calls.get() == 0 })
                    check(Privilege.pingServer())
                    report("PASS EMBEDDED stop disconnects service without a process-death callback")
                } else {
                    check(pid != Process.myPid())
                    shell("kill $pid")
                    awaitDisconnected(peer)
                    watches.forEach { it.awaitDeath() }
                    check(Privilege.peekUserService(spec) == null)
                    awaitProcessExit(mode, pid)
                    report("PASS $mode child death invokes direct, target-forwarding and system-service-forwarding callbacks exactly once")
                }
                check(removed.all { it.calls.get() == 0 })
                peer.unbind()
                connection.unbind()
            }

            val shutdownConnections = modes.map { mode ->
                Privilege.bindUserService(
                    PrivilegeUserServiceSpec(UserServiceFailureProbe::class.java.name, "callbacks-shutdown-$mode", mode),
                ).also { retainedWatches += callbackRoutes(it).map(::DeathWatch) }
            }
            Privilege.shutdownServer()
            shutdownConnections.forEach { awaitDisconnected(it); it.unbind() }
            retainedWatches.forEach { it.awaitDeath() }
            check(removedWatches.all { it.calls.get() == 0 })
            report("PASS server shutdown disconnects every mode and delivers every retained death callback exactly once; removed callbacks stay silent")
        } finally {
            runCatching { Privilege.shutdownServer() }
        }
        report("CLEANUP privileged server shut down")
    }

    private fun callbackRoutes(connection: PrivilegeUserServiceConnection): List<IBinder> {
        val target = transact(connection.binder, UserServiceFailureProbe.GET_TARGET) { readStrongBinder()!! }
        return listOf(
            connection.binder,
            PrivilegeBinderWrapper.fromBinder(target, connection),
            checkNotNull(PrivilegeBinderWrapper.fromSystemService(
                "package", PrivilegeSystemServiceSource.REMOTE_PROCESS, connection,
            )),
        )
    }

    private class DeathWatch(private val binder: IBinder) {
        val calls = AtomicInteger(0)
        private val died = CompletableDeferred<Unit>()
        private val recipient = IBinder.DeathRecipient {
            calls.incrementAndGet()
            died.complete(Unit)
        }

        init { binder.linkToDeath(recipient, 0) }

        fun unlink(): Boolean = binder.unlinkToDeath(recipient, 0)

        suspend fun awaitDeath() {
            withTimeout(5_000) { died.await() }
            check(calls.get() == 1) { "Expected one death callback, got ${calls.get()}" }
        }
    }

    private suspend fun failures() {
        val connections = modes.map { mode ->
            val spec = PrivilegeUserServiceSpec(UserServiceFailureProbe::class.java.name, "failure-$mode", mode)
            Privilege.bindUserService(spec)
        }
        for ((mode, connection) in modes.zip(connections)) {
            val target = transact(connection.binder, UserServiceFailureProbe.GET_TARGET) { readStrongBinder()!! }
            val routes = listOf(
                connection.binder,
                PrivilegeBinderWrapper.fromBinder(target),
                PrivilegeBinderWrapper.fromBinder(target, connection),
            )
            for (route in routes) {
                for ((code, type) in listOf(
                    UserServiceFailureProbe.STATE_ERROR to IllegalStateException::class.java,
                    UserServiceFailureProbe.SECURITY_ERROR to SecurityException::class.java,
                )) {
                    val thrown = runCatching {
                        PrivilegeBinderCall.orElse(
                            fallback = { error("Business exception entered fallback: $it") },
                        ) { transact(route, code) { } }
                    }.exceptionOrNull()
                    check(thrown?.javaClass == type) { "Expected $type, got $thrown" }
                }
                val pipe = ParcelFileDescriptor.createPipe()
                try {
                    transact(route, UserServiceFailureProbe.ECHO, write = {
                        writeString("parcel-$mode")
                        writeStrongBinder(target)
                        writeFileDescriptor(pipe[1].fileDescriptor)
                    }) {
                        check(readString() == "parcel-$mode")
                        check(readStrongBinder() == target)
                        ParcelFileDescriptor.AutoCloseOutputStream(readFileDescriptor()).use { it.write(42) }
                    }
                    ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { check(it.read() == 42) }
                } finally { pipe.forEach { it.close() } }
                check(connection.isConnected.value)
            }
            report("PASS $mode business exceptions bypass fallback; reply payload, Binder and FD survive all three routes")
        }

        val victimSpec = PrivilegeUserServiceSpec(
            UserServiceFailureProbe::class.java.name, "dead-target", PrivilegeUserServiceProcess.SHELL,
        )
        val victim = Privilege.bindUserService(victimSpec)
        val deadTarget = transact(victim.binder, UserServiceFailureProbe.GET_TARGET) { readStrongBinder()!! }
        val victimPid = transact(victim.binder, UserServiceFailureProbe.GET_PID) { readInt() }
        for (connection in connections) {
            check(transact(connection.binder, UserServiceFailureProbe.CALL_DEPENDENCY, write = {
                writeStrongBinder(deadTarget)
            }) { readInt() } == victimPid)
        }
        shell("kill $victimPid")
        awaitDisconnected(victim)
        assertUnavailable { transact(victim.binder, UserServiceFailureProbe.GET_PID) { readInt() } }
        val routes = listOf(PrivilegeBinderWrapper.fromBinder(deadTarget)) +
            connections.map { PrivilegeBinderWrapper.fromBinder(deadTarget, it) }
        for (route in routes) {
            val failure = PrivilegeBinderCall.orElse<PrivilegeBinderCallFailure>(fallback = { it }) {
                transact(route, UserServiceFailureProbe.GET_PID) { readInt() }
                error("Dead target unexpectedly succeeded")
            }
            check(failure is PrivilegeBinderCallFailure.BinderDied) { "Wrong target-death classification: $failure" }
        }
        check(connections.all { it.isConnected.value })
        report("PASS target death is BinderDied through main server and all UserService modes; healthy channels stay connected")
        for ((mode, connection) in modes.zip(connections)) {
            val failure = PrivilegeBinderCall.orElse<PrivilegeBinderCallFailure>(fallback = { it }) {
                transact(connection.binder, UserServiceFailureProbe.CALL_DEPENDENCY, write = {
                    writeStrongBinder(deadTarget)
                }) { readInt() }
                error("Dead business dependency unexpectedly succeeded")
            }
            check(failure is PrivilegeBinderCallFailure.BinderDied) { "Business dependency misclassified: $failure" }
            check(connection.isConnected.value)
            val livePid = transact(connection.binder, UserServiceFailureProbe.GET_PID) { readInt() }
            val liveTarget = transact(connection.binder, UserServiceFailureProbe.GET_TARGET) { readStrongBinder()!! }
            check(transact(PrivilegeBinderWrapper.fromBinder(liveTarget, connection), UserServiceFailureProbe.GET_PID) {
                readInt()
            } == livePid)
            report("PASS $mode business dependency death is BinderDied; subsequent business and forwarding calls still work")
        }
        victim.unbind()
        for (connection in connections) {
            val wrapper = PrivilegeBinderWrapper.fromBinder(deadTarget, connection)
            connection.unbind()
            assertUnavailable { transact(connection.binder, UserServiceFailureProbe.GET_PID) { readInt() } }
            assertUnavailable { transact(wrapper, UserServiceFailureProbe.GET_PID) { readInt() } }
        }
        report("PASS direct and forwarded calls after unbind are Unavailable")
        val oldServerWrapper = routes.first()
        Privilege.shutdownServer()
        withTimeout(5_000) { Privilege.serverState.first { it == null } }
        assertUnavailable { transact(oldServerWrapper, UserServiceFailureProbe.GET_PID) { readInt() } }
        report("PASS server shutdown is Unavailable; CLEANUP privileged server shut down")
    }

    private suspend fun directCalls() {
        val serverPid = Privilege.getServerInfo().pid
        val connections = mutableListOf<PrivilegeUserServiceConnection>()
        try {
            for (mode in modes.filterNot { it.isEmbedded }) {
                val custom = Privilege.bindUserService(
                    PrivilegeUserServiceSpec(UserServiceFailureProbe::class.java.name, "direct-custom-$mode", mode),
                ).also { connections += it }
                val builtin = Privilege.bindUserService(
                    PrivilegeUserServiceSpec(tag = "direct-builtin-$mode", process = mode),
                ).also { connections += it }
                val childPid = transact(custom.binder, UserServiceFailureProbe.GET_PID) { readInt() }
                val target = transact(custom.binder, UserServiceFailureProbe.GET_TARGET) { readStrongBinder()!! }
                val forwarded = PrivilegeBinderWrapper.fromBinder(target, custom)
                val builtinForwarded = PrivilegeBinderWrapper.fromBinder(target, builtin)
                val builtinPid = transact(builtinForwarded, UserServiceFailureProbe.GET_CALLER_PID) { readInt() }
                check(builtinPid != serverPid && builtinPid != Process.myPid() && builtinPid != childPid)
                check(transact(custom.binder, UserServiceFailureProbe.GET_CALLER_PID) { readInt() } == Process.myPid())

                // A route through the main server cannot complete while that process is stopped.
                val calls = FutureTask {
                    check(transact(custom.binder, UserServiceFailureProbe.GET_PID) { readInt() } == childPid)
                    check(transact(forwarded, UserServiceFailureProbe.GET_PID) { readInt() } == childPid)
                    check(transact(builtinForwarded, UserServiceFailureProbe.GET_CALLER_PID) { readInt() } == builtinPid)
                }
                try {
                    shell("kill -STOP $serverPid")
                    check(Regex("(?m)^State:\\s+T").containsMatchIn(shell("cat /proc/$serverPid/status")))
                    Thread(calls, "direct-user-service-test").start()
                    calls.get(5, TimeUnit.SECONDS)
                } finally {
                    shell("kill -CONT $serverPid")
                }
                report("PASS $mode direct AIDL caller is app; custom AIDL, custom forwarding and built-in forwarding complete while main server is stopped")
                custom.unbind()
                builtin.unbind()
            }
        } finally {
            connections.forEach { it.unbind() }
            Privilege.shutdownServer()
        }
        report("CLEANUP privileged server shut down")
    }

    private fun assertUnavailable(call: () -> Unit) {
        val failure = PrivilegeBinderCall.orElse<PrivilegeBinderCallFailure>(fallback = { it }) {
            call()
            error("Closed channel unexpectedly succeeded")
        }
        check(failure is PrivilegeBinderCallFailure.Unavailable) { "Wrong channel classification: $failure" }
    }

    private fun <T> transact(
        binder: IBinder,
        code: Int,
        write: Parcel.() -> Unit = {},
        read: Parcel.() -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.write()
            check(binder.transact(code, data, reply, 0))
            reply.readException()
            return reply.read()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private suspend fun prepareRestart() {
        val saved = preferences.edit().clear()
            .putInt("owner", Process.myPid())
            .putInt("server", Privilege.getServerInfo().pid)
        for (mode in modes) {
            for (daemon in listOf(false, true)) {
                val connection = Privilege.bindUserService(spec(mode, "restart-$daemon"), daemon)
                heldConnections += connection
                val service = service(connection)
                service.describe("before-owner-death")
                saved.putInt("$mode-$daemon-pid", service.pid)
                saved.putInt("$mode-$daemon-count", service.callCount)
                report("PREPARED $mode daemon=$daemon pid=${service.pid} count=${service.callCount}")
            }
        }
        check(saved.commit())
        // Completing instrumentation terminates its target process with these connections still open.
        Privilege.prepareOwnerRestart(120_000)
    }

    private suspend fun verifyRestart() {
        check(Process.myPid() != preferences.getInt("owner", -1)) { "Owner process did not restart" }
        check(Privilege.getServerInfo().pid == preferences.getInt("server", -1)) { "Privileged server was replaced" }
        for (mode in modes) {
            check(Privilege.peekUserService(spec(mode, "restart-false")) == null) { "$mode non-daemon survived owner death" }
            val daemonSpec = spec(mode, "restart-true")
            val connection = checkNotNull(Privilege.peekUserService(daemonSpec)) { "$mode daemon was lost" }
            val service = service(connection)
            val pid = service.pid
            check(pid == preferences.getInt("$mode-true-pid", -1))
            check(service.callCount == preferences.getInt("$mode-true-count", -1))
            service.describe("after-owner-restart")
            connection.unbind()
            Privilege.stopUserService(daemonSpec)
            check(Privilege.peekUserService(daemonSpec) == null) { "$mode retained an old connection" }
            awaitProcessExit(mode, pid)
            awaitProcessExit(mode, preferences.getInt("$mode-false-pid", -1))
            report("PASS $mode owner-death: non-daemon destroyed, daemon pid=$pid and state preserved, peek reconnected, no stale connections")
        }
        check(preferences.edit().clear().commit())
        val shutdownConnections = modes.map { Privilege.bindUserService(spec(it, "shutdown")) }
        Privilege.shutdownServer()
        shutdownConnections.forEach {
            awaitDisconnected(it)
            it.unbind()
        }
        report("PASS server shutdown disconnects all process modes")
        report("CLEANUP privileged server shut down")
    }

    private suspend fun awaitProcessExit(mode: PrivilegeUserServiceProcess, pid: Int) {
        if (mode.isEmbedded) return
        withTimeout(5_000) {
            while (shell("ps -p $pid -o PID").lineSequence().any { it.trim() == pid.toString() }) {
                delay(50)
            }
        }
    }

    private fun spec(mode: PrivilegeUserServiceProcess, tag: String) = PrivilegeUserServiceSpec(
        serviceClassName = "priv.kit.sample.userservice.PrivilegeSampleDedicatedUserService",
        tag = "device-$tag-${mode.name.lowercase()}",
        process = mode,
    )

    private fun service(connection: PrivilegeUserServiceConnection): IPrivilegeSampleDedicatedUserService =
        IPrivilegeSampleDedicatedUserService.Stub.asInterface(connection.binder)

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun report(message: String) {
        output.appendLine(message)
        sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
}
