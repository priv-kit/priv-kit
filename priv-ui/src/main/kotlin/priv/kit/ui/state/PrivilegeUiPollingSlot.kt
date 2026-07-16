package priv.kit.ui.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal object PrivilegeUiNoopCloseable : AutoCloseable {
    override fun close() = Unit
}

internal class PrivilegeUiPollingSlot(
    private val scope: CoroutineScope,
    private val name: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onStart: () -> Unit = {},
    private val onStop: () -> Unit = {},
    private val poll: suspend (AtomicBoolean) -> Unit,
) {
    private val lock = Any()
    private var leaseCount = 0
    private var generation = 0L
    private var stop: AtomicBoolean? = null
    private var job: Job? = null

    fun acquire(): AutoCloseable {
        val leaseGeneration: Long
        synchronized(lock) {
            leaseGeneration = generation
            leaseCount += 1
            if (job?.isActive != true) {
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
        val jobToCancel = synchronized(lock) {
            leaseCount = 0
            generation += 1
            stopJobLocked()
        }
        if (jobToCancel != null) {
            jobToCancel.cancel()
            onStop()
        }
    }

    private fun startLocked() {
        val currentStop = AtomicBoolean(false)
        lateinit var currentJob: Job
        currentJob = scope.launch(
            context = dispatcher + CoroutineName(name),
            start = CoroutineStart.LAZY,
        ) {
            try {
                poll(currentStop)
            } finally {
                handleJobExit(currentStop, currentJob)
            }
        }
        stop = currentStop
        job = currentJob
        onStart()
        currentJob.start()
    }

    private fun release(leaseGeneration: Long) {
        val jobToCancel = synchronized(lock) {
            if (leaseGeneration != generation || leaseCount <= 0) {
                return
            }
            leaseCount -= 1
            if (leaseCount == 0) {
                generation += 1
                stopJobLocked()
            } else {
                null
            }
        }
        if (jobToCancel != null) {
            jobToCancel.cancel()
            onStop()
        }
    }

    private fun stopJobLocked(): Job? {
        stop?.set(true)
        val currentJob = job
        stop = null
        job = null
        return currentJob
    }

    private fun handleJobExit(
        exitedStop: AtomicBoolean,
        exitedJob: Job,
    ) {
        val shouldNotifyStopped = synchronized(lock) {
            if (stop === exitedStop && job === exitedJob) {
                val notifyStopped = !exitedStop.get()
                leaseCount = 0
                generation += 1
                stop = null
                job = null
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
