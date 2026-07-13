package priv.kit.adb

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal class PrivilegeAdbMdns(
    private val nsdManager: NsdManager,
    private val serviceType: String,
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executor { command -> handler.post(command) }
    private val serviceInfoCallbacks = mutableSetOf<NsdManager.ServiceInfoCallback>()
    private var registered = false
    @Volatile
    private var endpoint: PrivilegeAdbEndpoint? = null
    private var latch: CountDownLatch? = null
    private val listener = DiscoveryListener()

    fun discoverEndpoint(timeoutMillis: Long): PrivilegeAdbEndpoint {
        val discoveryLatch = CountDownLatch(1)
        latch = discoveryLatch
        handler.post {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        try {
            discoveryLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            close()
        }
        return endpoint ?: run {
            throw PrivilegeAdbException("Timed out discovering ADB service $serviceType")
        }
    }

    override fun close() {
        handler.post {
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
            endpoint = resolvedEndpoint
            latch?.countDown()
        }
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
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            latch?.countDown()
        }

        override fun onDiscoveryStopped(serviceType: String) {
            registered = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            registered = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
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
    if (isPortListeningOnHost(PRIVILEGE_ADB_LOCAL_HOST, port)) {
        return PrivilegeAdbEndpoint.local(port)
    }
    return PrivilegeAdbEndpoint(serviceHost, port).takeIf {
        isPortListeningOnHost(serviceHost, port)
    }
}
