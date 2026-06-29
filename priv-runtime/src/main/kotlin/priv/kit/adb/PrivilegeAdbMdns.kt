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
import java.util.concurrent.atomic.AtomicInteger

internal class PrivilegeAdbMdns(
    private val nsdManager: NsdManager,
    private val serviceType: String,
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executor { command -> handler.post(command) }
    private val serviceInfoCallbacks = mutableSetOf<NsdManager.ServiceInfoCallback>()
    private var registered = false
    private val port = AtomicInteger(-1)
    private var latch: CountDownLatch? = null
    private val listener = DiscoveryListener()

    fun discoverPort(timeoutMillis: Long): Int {
        val discoveryLatch = CountDownLatch(1)
        latch = discoveryLatch
        handler.post {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        discoveryLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        close()
        val discoveredPort = port.get()
        if (discoveredPort <= 0) {
            throw PrivilegeAdbException("Timed out discovering ADB service $serviceType")
        }
        return discoveredPort
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
        if (isLocalService(info) && isPortListeningOnLocalhost(info.port)) {
            port.compareAndSet(-1, info.port)
            latch?.countDown()
        }
    }

    private fun isLocalService(info: NsdServiceInfo): Boolean {
        val hostAddresses = info.hostAddressesForCurrentApi()
        if (hostAddresses.isEmpty()) {
            return false
        }
        return NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .any { localAddress ->
                hostAddresses.any { it.hostAddress == localAddress.hostAddress }
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

    private fun isPortListeningOnLocalhost(port: Int): Boolean =
        try {
            ServerSocket().use {
                it.bind(InetSocketAddress("127.0.0.1", port), 1)
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
