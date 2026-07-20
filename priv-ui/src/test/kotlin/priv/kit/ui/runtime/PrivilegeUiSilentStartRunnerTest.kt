package priv.kit.ui.runtime

import android.content.Context
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.adb.PrivilegeAdbStartOptions
import priv.kit.core.adb.PrivilegeAdbWirelessDebuggingControl
import priv.kit.core.internal.runtime.PrivilegeRuntimeClientLaunch
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartLease
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiExternalStartProvider
import priv.kit.ui.PrivilegeUiExternalStartSnapshot
import priv.kit.ui.PrivilegeUiStartupMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiSilentStartRunnerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val serverInfo = PrivilegeServerInfo(uid = 2000, pid = 42, protocolVersion = 1)
    private lateinit var runtimeStartLease: PrivilegeRuntimeStartLease
    private lateinit var clientLaunch: PrivilegeRuntimeClientLaunch

    @Before
    fun acquireRuntimeClientLaunch() {
        runtimeStartLease = checkNotNull(
            PrivilegeRuntimeStartCoordinator.tryCommitClientStart(
                PrivilegeRuntimeStartCoordinator.beginPreflight(),
            ),
        )
        clientLaunch = checkNotNull(
            PrivilegeRuntimeStartCoordinator.beginClientLaunch(runtimeStartLease),
        )
    }

    @After
    fun releaseRuntimeClientLaunch() {
        runtimeStartLease.close()
    }

    @Test
    fun rootMethodStartsOnlyRootWhenEnabled() = runBlocking {
        val backend = RecordingBackend(serverInfo)
        val runner = runner(
            config = PrivilegeUiConfig(startupModes = setOf(PrivilegeUiStartupMode.ROOT)),
            backend = backend,
        )

        assertSame(serverInfo, runner.start(PrivilegeUiStartMethod.Root, clientLaunch))
        assertEquals(1, backend.rootCalls)
        assertSame(clientLaunch, backend.lastLaunch)
        assertEquals(0, backend.adbCalls)
        assertEquals(0, backend.externalCalls)
    }

    @Test
    fun disabledRootMethodReturnsNullWithoutFallback() = runBlocking {
        val backend = RecordingBackend(serverInfo)
        val runner = runner(
            config = PrivilegeUiConfig(startupModes = setOf(PrivilegeUiStartupMode.ADB)),
            backend = backend,
        )

        assertNull(runner.start(PrivilegeUiStartMethod.Root, clientLaunch))
        assertEquals(0, backend.totalCalls)
    }

    @Test
    fun cancellingRootStartInterruptsBlockingBackendCall() = runBlocking {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)
        val backend = object : PrivilegeUiSilentStartBackend {
            override fun startRoot(
                launch: PrivilegeRuntimeClientLaunch,
                timeoutMillis: Long,
            ): PrivilegeServerInfo {
                entered.countDown()
                try {
                    neverReleased.await()
                } catch (exception: InterruptedException) {
                    interrupted.countDown()
                    throw exception
                }
                error("unexpected release")
            }

            override fun startAdb(
                launch: PrivilegeRuntimeClientLaunch,
                options: PrivilegeAdbStartOptions,
                timeoutMillis: Long,
                adbDeviceName: String?,
            ): PrivilegeServerInfo = error("unexpected ADB start")

            override suspend fun startExternal(
                launch: PrivilegeRuntimeClientLaunch,
                context: Context,
                provider: PrivilegeUiExternalStartProvider,
                timeoutMillis: Long,
            ): PrivilegeServerInfo? = error("unexpected external start")
        }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val job = launch(dispatcher) {
                runner(
                    config = PrivilegeUiConfig(startupModes = setOf(PrivilegeUiStartupMode.ROOT)),
                    backend = backend,
                ).start(PrivilegeUiStartMethod.Root, clientLaunch)
            }

            assertEquals(true, entered.await(2, TimeUnit.SECONDS))
            job.cancelAndJoin()

            assertEquals(true, interrupted.await(2, TimeUnit.SECONDS))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun emptyStartupModesUseSameRootFallbackAsForegroundUi() = runBlocking {
        val backend = RecordingBackend(serverInfo)
        val runner = runner(
            config = PrivilegeUiConfig(startupModes = emptySet()),
            backend = backend,
        )

        assertSame(serverInfo, runner.start(PrivilegeUiStartMethod.Root, clientLaunch))
        assertEquals(1, backend.rootCalls)
    }

    @Test
    fun wirelessMethodDiscoversEndpointAndAllowsManagedEnable() = runBlocking {
        val backend = RecordingBackend(serverInfo)
        val runner = runner(
            config = PrivilegeUiConfig(
                startupModes = setOf(PrivilegeUiStartupMode.ADB),
                tcpPort = 5566,
                enableManagedWirelessAdb = true,
            ),
            backend = backend,
        )

        assertSame(
            serverInfo,
            runner.start(PrivilegeUiStartMethod.AdbWireless, clientLaunch),
        )
        assertSame(clientLaunch, backend.lastLaunch)
        val options = backend.lastAdbOptions!!
        assertNull(options.port)
        assertEquals(true, options.discoverPort)
        assertEquals(5566, options.tcpPort)
        assertEquals(
            PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE,
            options.wirelessDebuggingControl,
        )
    }

    @Test
    fun wirelessMethodDoesNotStartWhenLocalNetworkPermissionIsMissing() = runBlocking {
        val backend = RecordingBackend(serverInfo)
        val runner = PrivilegeUiSilentStartRunner(
            context = context,
            config = PrivilegeUiConfig(startupModes = setOf(PrivilegeUiStartupMode.ADB)),
            backend = backend,
            requiredLocalNetworkPermission = { "android.permission.ACCESS_LOCAL_NETWORK" },
        )

        assertNull(runner.start(PrivilegeUiStartMethod.AdbWireless, clientLaunch))
        assertEquals(0, backend.totalCalls)
    }

    @Test
    fun wirelessMethodNeverManagesWirelessWhenDisabledByConfig() = runBlocking {
        val backend = RecordingBackend(serverInfo)
        val runner = runner(
            config = PrivilegeUiConfig(
                startupModes = setOf(PrivilegeUiStartupMode.ADB),
                enableManagedWirelessAdb = false,
            ),
            backend = backend,
        )

        runner.start(PrivilegeUiStartMethod.AdbWireless, clientLaunch)

        assertEquals(
            PrivilegeAdbWirelessDebuggingControl.NEVER,
            backend.lastAdbOptions!!.wirelessDebuggingControl,
        )
    }

    @Test
    fun tcpipMethodUsesOnlyCurrentConfiguredLoopbackPort() = runBlocking {
        val backend = RecordingBackend(serverInfo)
        val runner = runner(
            config = PrivilegeUiConfig(
                startupModes = setOf(PrivilegeUiStartupMode.ADB),
                tcpPort = 6677,
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
            ),
            backend = backend,
        )

        assertSame(serverInfo, runner.start(PrivilegeUiStartMethod.AdbTcpip, clientLaunch))
        val options = backend.lastAdbOptions!!
        assertEquals(6677, options.port)
        assertFalse(options.discoverPort)
        assertEquals(
            PrivilegeAdbWirelessDebuggingControl.NEVER,
            options.wirelessDebuggingControl,
        )
    }

    @Test
    fun disabledTcpipMethodReturnsNullWithoutWirelessFallback() = runBlocking {
        val backend = RecordingBackend(serverInfo)
        val runner = runner(
            config = PrivilegeUiConfig(
                startupModes = setOf(PrivilegeUiStartupMode.ADB),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
            backend = backend,
        )

        assertNull(runner.start(PrivilegeUiStartMethod.AdbTcpip, clientLaunch))
        assertEquals(0, backend.totalCalls)
    }

    @Test
    fun authorizedExternalMethodStartsExactProviderWithoutRequestingAuthorization() = runBlocking {
        val provider = RecordingExternalProvider(
            id = "provider:child",
            snapshot = PrivilegeUiExternalStartSnapshot(available = true, authorized = true),
        )
        val backend = RecordingBackend(serverInfo)
        val runner = runner(
            config = PrivilegeUiConfig(externalStartProviders = listOf(provider)),
            backend = backend,
        )

        assertSame(
            serverInfo,
            runner.start(PrivilegeUiStartMethod.External("provider:child"), clientLaunch),
        )
        assertEquals(1, provider.snapshotCalls)
        assertEquals(0, provider.authorizationCalls)
        assertEquals(1, backend.externalCalls)
        assertSame(clientLaunch, backend.lastLaunch)
        assertSame(provider, backend.lastExternalProvider)
    }

    @Test
    fun unauthorizedOrMissingExternalMethodFailsWithoutAuthorizationOrFallback() = runBlocking {
        val provider = RecordingExternalProvider(
            id = "provider",
            snapshot = PrivilegeUiExternalStartSnapshot(available = true, authorized = false),
        )
        val backend = RecordingBackend(serverInfo)
        val runner = runner(
            config = PrivilegeUiConfig(externalStartProviders = listOf(provider)),
            backend = backend,
        )

        assertNull(runner.start(PrivilegeUiStartMethod.External("provider"), clientLaunch))
        assertNull(runner.start(PrivilegeUiStartMethod.External("missing"), clientLaunch))
        assertEquals(1, provider.snapshotCalls)
        assertEquals(0, provider.authorizationCalls)
        assertEquals(0, backend.totalCalls)
    }

    @Test
    fun externalSnapshotAndBackendShareOneOverallTimeout() = runBlocking {
        val provider = RecordingExternalProvider(
            id = "provider",
            snapshot = PrivilegeUiExternalStartSnapshot(available = true, authorized = true),
        )
        val backend = object : PrivilegeUiSilentStartBackend {
            override fun startRoot(
                launch: PrivilegeRuntimeClientLaunch,
                timeoutMillis: Long,
            ): PrivilegeServerInfo =
                error("unexpected Root start")

            override fun startAdb(
                launch: PrivilegeRuntimeClientLaunch,
                options: PrivilegeAdbStartOptions,
                timeoutMillis: Long,
                adbDeviceName: String?,
            ): PrivilegeServerInfo = error("unexpected ADB start")

            override suspend fun startExternal(
                launch: PrivilegeRuntimeClientLaunch,
                context: Context,
                provider: PrivilegeUiExternalStartProvider,
                timeoutMillis: Long,
            ): PrivilegeServerInfo? = awaitCancellation()
        }
        val runner = runner(
            config = PrivilegeUiConfig(
                externalStartProviders = listOf(provider),
                startTimeoutMillis = 25L,
            ),
            backend = backend,
        )

        assertNull(runner.start(PrivilegeUiStartMethod.External("provider"), clientLaunch))
        assertEquals(1, provider.snapshotCalls)
        assertEquals(0, provider.authorizationCalls)
    }

    private fun runner(
        config: PrivilegeUiConfig,
        backend: PrivilegeUiSilentStartBackend,
    ): PrivilegeUiSilentStartRunner =
        PrivilegeUiSilentStartRunner(
            context = context,
            config = config,
            backend = backend,
            requiredLocalNetworkPermission = { null },
        )

    private class RecordingBackend(
        private val result: PrivilegeServerInfo,
    ) : PrivilegeUiSilentStartBackend {
        var rootCalls = 0
        var adbCalls = 0
        var externalCalls = 0
        var lastLaunch: PrivilegeRuntimeClientLaunch? = null
        var lastAdbOptions: PrivilegeAdbStartOptions? = null
        var lastExternalProvider: PrivilegeUiExternalStartProvider? = null

        val totalCalls: Int
            get() = rootCalls + adbCalls + externalCalls

        override fun startRoot(
            launch: PrivilegeRuntimeClientLaunch,
            timeoutMillis: Long,
        ): PrivilegeServerInfo {
            rootCalls++
            lastLaunch = launch
            return result
        }

        override fun startAdb(
            launch: PrivilegeRuntimeClientLaunch,
            options: PrivilegeAdbStartOptions,
            timeoutMillis: Long,
            adbDeviceName: String?,
        ): PrivilegeServerInfo {
            adbCalls++
            lastLaunch = launch
            lastAdbOptions = options
            return result
        }

        override suspend fun startExternal(
            launch: PrivilegeRuntimeClientLaunch,
            context: Context,
            provider: PrivilegeUiExternalStartProvider,
            timeoutMillis: Long,
        ): PrivilegeServerInfo {
            externalCalls++
            lastLaunch = launch
            lastExternalProvider = provider
            return result
        }
    }

    private class RecordingExternalProvider(
        override val id: String,
        private val snapshot: PrivilegeUiExternalStartSnapshot,
    ) : PrivilegeUiExternalStartProvider {
        override val label: CharSequence = id
        var snapshotCalls = 0
        var authorizationCalls = 0

        override fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot {
            snapshotCalls++
            return snapshot
        }

        override fun requestAuthorization(context: Context): PrivilegeUiExternalStartSnapshot {
            authorizationCalls++
            return snapshot
        }

        override fun start(context: Context, commandLine: String) = Unit
    }
}
