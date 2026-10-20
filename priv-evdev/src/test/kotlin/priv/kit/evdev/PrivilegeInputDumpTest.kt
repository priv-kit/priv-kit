package priv.kit.evdev

import android.os.Binder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.evdev.internal.readInputDump
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], manifest = Config.NONE)
class PrivilegeInputDumpTest {
    @Test(timeout = 10000) fun dumpFailureClosesWriterAndKeepsOriginalException() {
        val failure = IOException("dump failed")
        lateinit var writer: FileDescriptor
        val binder = object : Binder() {
            override fun dump(fd: FileDescriptor, args: Array<out String>?) {
                writer = fd
                FileOutputStream(fd).apply { write("partial".toByteArray()); flush() }
                throw failure
            }
        }
        assertSame(failure, assertThrows(IOException::class.java) { readInputDump(binder) })
        assertFalse(writer.valid())
    }
}
