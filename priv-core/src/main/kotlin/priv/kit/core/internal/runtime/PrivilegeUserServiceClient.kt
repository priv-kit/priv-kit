package priv.kit.core.internal.runtime

import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ResultReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.binder.PrivilegeUnavailableException
import priv.kit.core.internal.userservice.IPrivilegeUserServiceManager
import priv.kit.core.internal.userservice.PrivilegeUserServiceConnectionState
import priv.kit.core.internal.userservice.PrivilegeUserServiceContract
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUserServiceClient(
    private val managerProvider: () -> IBinder,
) {
    private val ownerBinder = Binder()

    suspend fun bind(spec: PrivilegeUserServiceSpec, daemon: Boolean = false): PrivilegeUserServiceConnection =
        withConnectionState { manager, state ->
            execute(manager, { operationId, receiver ->
                manager.bindUserService(
                    operationId,
                    PrivilegeUserServiceContract.requestBundle(spec),
                    daemon,
                    ownerBinder,
                    state,
                    receiver,
                )
            }) { response ->
                decodeConnection(manager, response, state)
            }
        }

    suspend fun peek(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceConnection? =
        withConnectionState { manager, state ->
            execute(manager, { operationId, receiver ->
                manager.peekUserService(
                    operationId,
                    PrivilegeUserServiceContract.requestBundle(spec),
                    ownerBinder,
                    state,
                    receiver,
                )
            }) { response ->
                if (response.containsKey(PrivilegeUserServiceContract.KEY_CONNECTION_ID)) {
                    decodeConnection(manager, response, state)
                } else {
                    state.disconnect()
                    null
                }
            }
        }

    private suspend fun <T> withConnectionState(
        block: suspend (IPrivilegeUserServiceManager, PrivilegeUserServiceConnectionState) -> T,
    ): T {
        val manager = manager()
        val state = PrivilegeUserServiceConnectionState()
        state.observeServer(manager.asBinder())
        return try {
            block(manager, state)
        } catch (throwable: Throwable) {
            state.disconnect()
            throw throwable
        }
    }

    private fun decodeConnection(
        manager: IPrivilegeUserServiceManager,
        response: Bundle,
        state: PrivilegeUserServiceConnectionState,
    ): PrivilegeUserServiceConnection {
        val connectionId = response.getString(PrivilegeUserServiceContract.KEY_CONNECTION_ID)
            ?: throw IllegalStateException("UserService bind response is missing a connection id")
        val binder = response.getBinder(PrivilegeUserServiceContract.KEY_SERVICE_BINDER)
            ?: throw IllegalStateException("UserService bind response is missing a service Binder")
        return PrivilegeUserServiceConnection(
            binder = binder,
            unbindAction = { unbind(manager, connectionId) },
            connectionState = state,
        )
    }

    suspend fun stop(spec: PrivilegeUserServiceSpec) {
        val manager = manager()
        execute(manager) { operationId, receiver ->
            manager.stopUserService(
                operationId,
                PrivilegeUserServiceContract.requestBundle(spec),
                ownerBinder,
                receiver,
            )
        }
    }

    private suspend fun manager(): IPrivilegeUserServiceManager =
        withContext(Dispatchers.IO) {
            IPrivilegeUserServiceManager.Stub.asInterface(managerProvider())
        }

    private suspend fun execute(
        manager: IPrivilegeUserServiceManager,
        launch: (String, ResultReceiver) -> Unit,
    ) {
        execute(manager, launch) { }
    }

    private suspend fun <T> execute(
        manager: IPrivilegeUserServiceManager,
        launch: (String, ResultReceiver) -> Unit,
        decode: (Bundle) -> T,
    ): T {
        val operationId = UUID.randomUUID().toString()
        val cancellationSent = AtomicBoolean(false)
        val cancelOperation = {
            if (cancellationSent.compareAndSet(false, true)) {
                runCatching {
                    manager.cancelUserServiceOperation(operationId)
                }
            }
            Unit
        }
        try {
            val response = awaitResult(
                manager = manager,
                operationId = operationId,
                launch = launch,
                cancelOperation = cancelOperation,
            )
            ensureSuccess(response)
            val result = decode(response)
            try {
                manager.acknowledgeUserServiceOperation(operationId)
            } catch (exception: DeadObjectException) {
                throw PrivilegeUnavailableException(cause = exception)
            }
            return result
        } catch (throwable: Throwable) {
            cancelOperation()
            throw throwable
        }
    }

    private suspend fun awaitResult(
        manager: IPrivilegeUserServiceManager,
        operationId: String,
        launch: (String, ResultReceiver) -> Unit,
        cancelOperation: () -> Unit,
    ): Bundle =
        suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val managerBinder = manager.asBinder()
            val managerLinked = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            lateinit var managerDeathRecipient: IBinder.DeathRecipient
            val unlinkManagerDeath = {
                if (managerLinked.compareAndSet(true, false)) {
                    runCatching {
                        managerBinder.unlinkToDeath(managerDeathRecipient, 0)
                    }
                }
                Unit
            }
            val resumeResult = { result: Bundle ->
                if (completed.compareAndSet(false, true)) {
                    unlinkManagerDeath()
                    continuation.resumeWith(Result.success(result))
                }
                Unit
            }
            val resumeFailure = { throwable: Throwable ->
                if (completed.compareAndSet(false, true)) {
                    unlinkManagerDeath()
                    val failure = if (throwable is DeadObjectException) {
                        PrivilegeUnavailableException(cause = throwable)
                    } else {
                        throwable
                    }
                    continuation.resumeWith(Result.failure(failure))
                }
                Unit
            }
            managerDeathRecipient = IBinder.DeathRecipient {
                resumeFailure(DeadObjectException("UserService manager died during operation"))
            }
            val receiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultData == null) {
                        resumeFailure(
                            IllegalStateException("UserService response is missing result data"),
                        )
                    } else {
                        resumeResult(resultData)
                    }
                }
            }
            try {
                managerLinked.set(true)
                managerBinder.linkToDeath(managerDeathRecipient, 0)
            } catch (throwable: Throwable) {
                managerLinked.set(false)
                resumeFailure(throwable)
                return@suspendCancellableCoroutine
            }
            try {
                launch(operationId, receiver)
            } catch (throwable: Throwable) {
                resumeFailure(throwable)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                completed.set(true)
                unlinkManagerDeath()
                cancelOperation()
            }
        }

    private suspend fun unbind(
        manager: IPrivilegeUserServiceManager,
        connectionId: String,
    ) {
        execute(manager) { operationId, receiver ->
            manager.unbindUserService(
                operationId,
                connectionId,
                ownerBinder,
                receiver,
            )
        }
    }

    private fun ensureSuccess(response: Bundle) {
        if (response.getBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, false)) {
            return
        }
        PrivilegeUserServiceContract.throwFailure(response)
    }
}
