package priv.kit.core.internal.core

import android.os.Build
import android.os.Bundle
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29, 30, 36])
class PrivilegeContentProviderCallTest {
    @Test
    fun passesAuthorityThroughThePlatformSpecificCallSignature() {
        val invocations = mutableListOf<Array<out Any?>>()
        val response = Bundle()
        val testClassLoader = requireNotNull(javaClass.classLoader)
        val providerCallClass = testClassLoader.loadClass(
            "priv.kit.core.internal.core.PrivilegeContentProviderCall",
        )
        val providerClass = requireNotNull(providerCallClass.classLoader).loadClass(
            "android.content.IContentProvider",
        )
        val provider = Proxy.newProxyInstance(
            providerClass.classLoader,
            arrayOf(providerClass),
        ) { _, method, args ->
            if (method.name == "call") {
                invocations += requireNotNull(args)
                response
            } else {
                error("Unexpected IContentProvider method: ${method.name}")
            }
        }
        val callProvider = providerCallClass.declaredMethods.single { method ->
            method.name.startsWith("callProvider") &&
                method.parameterCount == 5 &&
                method.parameterTypes.firstOrNull() == providerClass
        }.apply {
            isAccessible = true
        }
        val instance = providerCallClass.getDeclaredField("INSTANCE").get(null)

        val result = callProvider.invoke(
            instance,
            provider,
            AUTHORITY,
            METHOD,
            ARGUMENT,
            Bundle(),
        )

        assertSame(response, result)
        val args = invocations.single()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                assertEquals(5, args.size)
                assertEquals("android.content.AttributionSource", args[0]?.javaClass?.name)
                assertEquals(AUTHORITY, args[1])
                assertEquals(METHOD, args[2])
                assertEquals(ARGUMENT, args[3])
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                assertEquals(6, args.size)
                assertEquals(AUTHORITY, args[2])
                assertEquals(METHOD, args[3])
                assertEquals(ARGUMENT, args[4])
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                assertEquals(5, args.size)
                assertEquals(AUTHORITY, args[1])
                assertEquals(METHOD, args[2])
                assertEquals(ARGUMENT, args[3])
            }

            else -> {
                assertEquals(4, args.size)
                assertEquals(METHOD, args[1])
                assertEquals(ARGUMENT, args[2])
            }
        }
    }

    private companion object {
        const val AUTHORITY = "priv.kit.sample.privilege.handshake"
        const val METHOD = "serverReady"
        const val ARGUMENT = "token"
    }
}
