package priv.kit.ui

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiExternalStartActionsTest {
    @Test
    fun refreshBeforeAttachUsesConstructionContext() {
        val provider = CountingExternalStartProvider()
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val actions = PrivilegeUiExternalStartActions(
            store = store,
            runtimeActions = PrivilegeUiRuntimeActions(store),
        )
        try {
            store.config = PrivilegeUiConfig(externalStartProviders = listOf(provider))

            actions.refreshExternalStartStatusNow(stop = null, providerId = null)

            assertEquals(1, provider.snapshotCalls.get())
            assertEquals(false, store.externalStartStatusRefreshRunning.get())
        } finally {
            actions.close()
            store.close()
        }
    }

    private class CountingExternalStartProvider : PrivilegeUiExternalStartProvider {
        val snapshotCalls = AtomicInteger(0)

        override val id: String = "external"
        override val label: CharSequence = "External"

        override fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot {
            snapshotCalls.incrementAndGet()
            return PrivilegeUiExternalStartSnapshot()
        }

        override fun start(
            context: Context,
            commandLine: String,
        ) = Unit
    }
}
