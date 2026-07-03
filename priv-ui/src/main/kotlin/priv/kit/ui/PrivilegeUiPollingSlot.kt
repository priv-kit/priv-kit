package priv.kit.ui

import java.util.concurrent.atomic.AtomicBoolean

internal object PrivilegeUiNoopCloseable : AutoCloseable {
    override fun close() = Unit
}

internal class PrivilegeUiPollingSlot(
    private val threadName: String,
    private val onStart: () -> Unit = {},
    private val onStop: () -> Unit = {},
    private val poll: (AtomicBoolean) -> Unit,
) {
    private val lock = Any()
    private var leaseCount = 0
    private var generation = 0L
    private var stop: AtomicBoolean? = null
    private var thread: Thread? = null

    fun acquire(): AutoCloseable {
        val leaseGeneration: Long
        synchronized(lock) {
            leaseGeneration = generation
            leaseCount += 1
            if (thread?.isAlive != true) {
                startLocked()
            }
        }
        return Lease(leaseGeneration)
    }

    fun currentStop(): AtomicBoolean? =
        synchronized(lock) {
            stop
        }

    fun stopAll() {
        val threadToInterrupt = synchronized(lock) {
            leaseCount = 0
            generation += 1
            stopThreadLocked()
        }
        if (threadToInterrupt != null) {
            threadToInterrupt.interrupt()
            onStop()
        }
    }

    private fun startLocked() {
        val currentStop = AtomicBoolean(false)
        lateinit var currentThread: Thread
        currentThread = Thread {
            try {
                poll(currentStop)
            } finally {
                handleThreadExit(currentStop, currentThread)
            }
        }.apply {
            name = threadName
            isDaemon = true
        }
        stop = currentStop
        thread = currentThread
        onStart()
        currentThread.start()
    }

    private fun release(leaseGeneration: Long) {
        val threadToInterrupt = synchronized(lock) {
            if (leaseGeneration != generation || leaseCount <= 0) {
                return
            }
            leaseCount -= 1
            if (leaseCount == 0) {
                generation += 1
                stopThreadLocked()
            } else {
                null
            }
        }
        if (threadToInterrupt != null) {
            threadToInterrupt.interrupt()
            onStop()
        }
    }

    private fun stopThreadLocked(): Thread? {
        stop?.set(true)
        val currentThread = thread
        stop = null
        thread = null
        return currentThread
    }

    private fun handleThreadExit(
        exitedStop: AtomicBoolean,
        exitedThread: Thread,
    ) {
        val shouldNotifyStopped = synchronized(lock) {
            if (stop === exitedStop && thread === exitedThread) {
                val notifyStopped = !exitedStop.get()
                leaseCount = 0
                generation += 1
                stop = null
                thread = null
                notifyStopped
            } else {
                false
            }
        }
        if (shouldNotifyStopped) {
            onStop()
        }
    }

    private inner class Lease(
        private val leaseGeneration: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                release(leaseGeneration)
            }
        }
    }
}
