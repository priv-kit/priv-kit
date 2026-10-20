package priv.kit.input.internal

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import priv.kit.input.InputShellCommandException
import priv.kit.shared.transactShellCommand
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

internal fun inputShellCommand(binder: IBinder, args: Array<out String>): String {
    val done = CountDownLatch(1)
    var exitCode = 0
    val receiver = object : ResultReceiver(null) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            exitCode = resultCode
            done.countDown()
        }
    }
    val pipe = ParcelFileDescriptor.createPipe()
    return ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
        val output = FutureTask { input.bufferedReader().readText() }
        pipe[1].use { writer ->
            // Drain while the synchronous Binder transaction runs, including large help output.
            Thread(output, "input-command-reader").apply { isDaemon = true }.start()
            val reply = Parcel.obtain()
            try {
                ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY).use { stdin ->
                    if (!binder.transactShellCommand(stdin.fileDescriptor, writer.fileDescriptor, writer.fileDescriptor,
                            args, null, receiver, reply)) {
                        throw IOException("Input service does not support shell commands")
                    }
                }
                reply.readException()
            } finally { reply.recycle() }
        }
        try {
            val text = output.get()
            done.await()
            checkInputCommandResult(exitCode, text)
            text
        } catch (error: ExecutionException) {
            throw IOException("Cannot read input command output", error.cause)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Input command interrupted", error)
        }
    }
}

internal fun checkInputCommandResult(exitCode: Int, output: String) {
    // Some framework parsers print an error but still return zero.
    if (exitCode != 0 || output.lineSequence().any {
            it.startsWith("Error:") || it.startsWith("Unknown command:")
        }) {
        throw InputShellCommandException(exitCode, output)
    }
}
