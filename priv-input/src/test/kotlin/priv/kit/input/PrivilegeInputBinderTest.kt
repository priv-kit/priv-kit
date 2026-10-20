package priv.kit.input

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PrivilegeInputBinderTest {
    @Test @Config(sdk = [28, 34]) fun blockedCallDoesNotSerializeOtherCalls() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun blockTouch() {
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
        }
        val input = inputFixture(
            inject = { event, _ ->
                if (event is MotionEvent && event.action == MotionEvent.ACTION_DOWN) blockTouch()
                true
            },
        )
        val workers = Executors.newFixedThreadPool(2)
        val touch = workers.submit { kotlinx.coroutines.runBlocking { input.tap(1f, 2f) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            workers.submit { kotlinx.coroutines.runBlocking { input.keyEvent(29) } }.get(5, TimeUnit.SECONDS)
            assertFalse(touch.isDone)
        } finally {
            release.countDown()
            try { touch.get(5, TimeUnit.SECONDS) } finally { workers.shutdownNow() }
        }
    }

}
