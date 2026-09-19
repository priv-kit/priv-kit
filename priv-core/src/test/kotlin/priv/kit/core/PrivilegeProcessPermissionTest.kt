package priv.kit.core

import android.app.IActivityManager
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.RemoteException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowServiceManager
import priv.kit.core.binder.PrivilegeServerUnavailableException
import priv.kit.core.internal.binder.IPrivilegeServer
import priv.kit.core.internal.core.PrivilegeProtocol
import priv.kit.core.testing.TestBinder
import priv.kit.core.testing.testHandshakeResult
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeProcessPermissionTest {
    @After
    fun cleanUp() {
        runCatching { Privilege.shutdownServer() }
        ShadowServiceManager.reset()
    }

    @Test
    fun checksTargetProcessDirectlyAndRefreshesRestrictionWithoutCaching() {
        val server = Server()
        connect(server)
        var result = PackageManager.PERMISSION_DENIED
        val permissions = mutableListOf<String>()
        installService { permission, pid, uid ->
            assertEquals(1234, pid)
            assertEquals(2000, uid)
            permissions += permission
            result
        }
        assertEquals(result, Privilege.checkServerPermission("permission.TEST"))
        assertTrue(Privilege.isPermissionRestricted())
        result = PackageManager.PERMISSION_GRANTED
        assertFalse(Privilege.isPermissionRestricted())
        assertEquals(listOf("permission.TEST", GRANT, GRANT), permissions)
    }

    @Test
    fun rootRestrictionSkipsActivityManager() {
        connect(Server(), uid = 0)
        installService { _, _, _ -> error("Root restriction must not query ActivityManager") }
        assertFalse(Privilege.isPermissionRestricted())
    }

    @Test
    fun systemUidStillUsesPermissionCheck() {
        connect(Server(), uid = 1000)
        installService { permission, _, uid ->
            assertEquals(GRANT, permission)
            assertEquals(1000, uid)
            PackageManager.PERMISSION_GRANTED
        }
        assertFalse(Privilege.isPermissionRestricted())
    }

    @Test
    fun activityManagerFailuresDoNotDisconnectLiveServer() {
        val info = connect(Server())
        for (failure in listOf(DeadObjectException("activity died"), RemoteException("activity failed"))) {
            installService { _, _, _ -> throw failure }
            assertSame(failure, assertThrows(RemoteException::class.java) {
                Privilege.checkServerPermission(GRANT)
            })
            assertSame(info, Privilege.getServerInfo())
        }
    }

    @Test
    fun disconnectedAndDeadServerDoNotQueryActivityManager() {
        installService { _, _, _ -> error("No live server") }
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.checkServerPermission(GRANT)
        }
        val server = Server()
        connect(server)
        server.binder.killBinder(notifyDeathRecipients = false)
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.checkServerPermission(GRANT)
        }
        assertNull(Privilege.serverState.value)
    }

    @Test
    fun deathDuringPermissionQueryDiscardsSuccessfulResult() {
        val server = Server()
        connect(server)
        installService { _, _, _ ->
            server.binder.killBinder(notifyDeathRecipients = false)
            PackageManager.PERMISSION_GRANTED
        }
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.checkServerPermission(GRANT)
        }
        assertNull(Privilege.serverState.value)
    }

    @Test
    fun replacementDuringPermissionQueryDiscardsOldResultWithoutDisconnectingReplacement() {
        val old = Server()
        connect(old)
        var replacement: PrivilegeServerInfo? = null
        installService { _, pid, _ ->
            assertEquals(1234, pid)
            replacement = connect(Server(), pid = 5678)
            PackageManager.PERMISSION_GRANTED
        }
        assertThrows(PrivilegeServerUnavailableException::class.java) {
            Privilege.checkServerPermission(GRANT)
        }
        assertSame(replacement, Privilege.getServerInfo())
    }

    @Test
    fun startupPrecheckUsesHandshakeIdentityBeforeConnectionIsInstalled() {
        val server = Server()
        val info = info(pid = 5678)
        var result = PackageManager.PERMISSION_DENIED
        installService { permission, pid, uid ->
            assertEquals(GRANT, permission)
            assertEquals(info.pid, pid)
            assertEquals(info.uid, uid)
            result
        }
        fun grant() = Privilege.grantRuntimePermissionForRuntime(
            info, server.api, "test.package", "permission.TEST", 10,
        )
        assertFalse(grant())
        assertTrue(server.grants.isEmpty())
        result = PackageManager.PERMISSION_GRANTED
        assertTrue(grant())
        assertEquals(listOf(listOf("test.package", "permission.TEST", 10)), server.grants)
        assertNull(Privilege.serverState.value)
    }

    private fun installService(check: (String, Int, Int) -> Int) {
        val service = Proxy.newProxyInstance(
            IActivityManager::class.java.classLoader,
            arrayOf(IActivityManager::class.java),
        ) { _, method, args ->
            when (method.name) {
                "checkPermission" -> check(args!![0] as String, args[1] as Int, args[2] as Int)
                else -> null
            }
        } as IActivityManager
        ShadowServiceManager.addBinderService("activity", IActivityManager::class.java, service)
    }

    private fun info(uid: Int = 2000, pid: Int = 1234) = PrivilegeServerInfo(
        uid = uid,
        pid = pid,
        protocolVersion = PrivilegeProtocol.VERSION,
        lifecycleBinder = TestBinder(),
    )

    private fun connect(server: Server, uid: Int = 2000, pid: Int = 1234): PrivilegeServerInfo {
        val info = info(uid, pid)
        Privilege.connectHandshake(testHandshakeResult(info, server.binder), null)
        return info
    }

    private class Server {
        val grants = mutableListOf<List<Any?>>()
        val api: IPrivilegeServer = Proxy.newProxyInstance(
            IPrivilegeServer::class.java.classLoader,
            arrayOf(IPrivilegeServer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "grantRuntimePermission" -> { grants += args!!.toList(); null }
                "shutdown", "updateRuntimeConfig" -> null
                else -> error("Unexpected server call: ${method.name}")
            }
        } as IPrivilegeServer
        val binder: TestBinder = TestBinder(localInterface = api)
    }

    private companion object {
        const val GRANT = "android.permission.GRANT_RUNTIME_PERMISSIONS"
    }
}
