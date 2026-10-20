package priv.kit.input

import android.os.SystemClock
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

// Robolectric's default clock stays paused while delay/Thread.sleep elapses.
@Implements(SystemClock::class)
class RealtimeSystemClock {
    companion object {
        @JvmStatic @Implementation fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
    }
}
