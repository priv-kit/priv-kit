package priv.kit.core.adb

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import priv.kit.shared.PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.Executor
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeAdbMdns(
    private val nsdManager: NsdManager,
    private val serviceType: String,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executor { command -> handler.post(command) }
    private val serviceInfoCallbacks = mutableSetOf<NsdManager.ServiceInfoCallback>()
    private var registered = false
    private var closing = false
    private var continuation: kotlinx.coroutines.CancellableContinuation<PrivilegeAdbEndpoint>? = null
    private val listener = DiscoveryListener()

    suspend fun discoverEndpoint(timeoutMillis: Long): PrivilegeAdbEndpoint =
        try {
            withTimeout(timeoutMillis.milliseconds) {
                suspendCancellableCoroutine { continuation ->
                    this@PrivilegeAdbMdns.continuation = continuation
                    continuation.invokeOnCancellation { close() }
                    handler.post {
                        if (continuation.isActive) {
                            registered = true
                            runCatching {
                                nsdManager.discoverServices(
                                    serviceType,
                                    NsdManager.PROTOCOL_DNS_SD,
                                    listener,
                                )
                            }.onFailure {
                                registered = false
                                failDiscovery(it)
                            }
                        }
                    }
                }
            }
        } catch (throwable: kotlinx.coroutines.TimeoutCancellationException) {
            throw PrivilegeAdbException("Timed out discovering ADB service $serviceType", throwable)
        } finally {
            close()
        }

    private fun close() {
        handler.post {
            closing = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceInfoCallbacks.toList().forEach { callback ->
                    runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
                }
                serviceInfoCallbacks.clear()
            }
            if (registered) {
                runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
            registered = false
        }
    }

    private fun onServiceResolved(info: NsdServiceInfo) {
        val localHost = localServiceHost(info) ?: return
        val resolvedEndpoint = privilegeAdbReachableLocalEndpoint(
            serviceHost = localHost,
            port = info.port,
            isPortListeningOnHost = ::isPortListeningOnHost,
        )
        if (resolvedEndpoint != null) {
            val current = continuation
            continuation = null
            current?.takeIf { it.isActive }?.resume(resolvedEndpoint) { _, _, _ -> close() }
        }
    }

    private fun failDiscovery(throwable: Throwable) {
        val current = continuation
        continuation = null
        current?.takeIf { it.isActive }?.resumeWithException(throwable)
    }

    private fun localServiceHost(info: NsdServiceInfo): String? {
        val hostAddresses = info.hostAddressesForCurrentApi()
        if (hostAddresses.isEmpty()) {
            return null
        }
        val localAddresses = NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .mapNotNull { it.hostAddress }
            .toSet()
        return hostAddresses.firstNotNullOfOrNull { hostAddress ->
            hostAddress.hostAddress?.takeIf { it in localAddresses }
        }
    }

    private fun NsdServiceInfo.hostAddressesForCurrentApi(): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses
        } else {
            preApi34HostAddresses()
        }

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.preApi34HostAddresses(): List<InetAddress> =
        listOfNotNull(host)

    private fun isPortListeningOnHost(host: String, port: Int): Boolean =
        try {
            ServerSocket().use {
                it.bind(InetSocketAddress(host, port), 1)
                false
            }
        } catch (_: IOException) {
            true
        }

    private inner class DiscoveryListener : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            registered = true
            if (closing) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            registered = false
            failDiscovery(
                PrivilegeAdbException("Failed to discover ADB service $serviceType: $errorCode"),
            )
        }

        override fun onDiscoveryStopped(serviceType: String) {
            registered = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            registered = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (closing) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerServiceInfoCallback(serviceInfo)
            } else {
                resolveServicePreApi34(serviceInfo)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerServiceInfoCallback(serviceInfo: NsdServiceInfo) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                serviceInfoCallbacks.remove(this)
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                this@PrivilegeAdbMdns.onServiceResolved(serviceInfo)
            }

            override fun onServiceLost() = Unit

            override fun onServiceInfoCallbackUnregistered() {
                serviceInfoCallbacks.remove(this)
            }
        }
        serviceInfoCallbacks.add(callback)
        runCatching {
            nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
        }.onFailure {
            serviceInfoCallbacks.remove(callback)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveServicePreApi34(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, ResolveListener())
    }

    private inner class ResolveListener : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            this@PrivilegeAdbMdns.onServiceResolved(serviceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    }
}

internal fun privilegeAdbReachableLocalEndpoint(
    serviceHost: String,
    port: Int,
    isPortListeningOnHost: (String, Int) -> Boolean,
): PrivilegeAdbEndpoint? {
    if (isPortListeningOnHost(PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST, port)) {
        return PrivilegeAdbEndpoint.local(port)
    }
    return PrivilegeAdbEndpoint(serviceHost, port).takeIf {
        isPortListeningOnHost(serviceHost, port)
    }
}
