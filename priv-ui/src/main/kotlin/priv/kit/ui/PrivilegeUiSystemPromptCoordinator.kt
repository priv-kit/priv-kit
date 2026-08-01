package priv.kit.ui

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PrivilegeUiSystemPrompt(
    val title: PrivilegeUiText,
    val message: PrivilegeUiText,
)

internal class PrivilegeUiSystemPromptCoordinator : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val hosts = linkedMapOf<String, HostState>()
    private val visiblePromptState = MutableStateFlow<PrivilegeUiSystemPrompt?>(null)
    private var lastForegroundHostId: String? = null
    private var nextSessionId = 0L
    private var activeSession: PromptSession? = null

    val visiblePrompt: StateFlow<PrivilegeUiSystemPrompt?> = visiblePromptState.asStateFlow()

    fun registerHost(
        hostId: String,
        resumed: Boolean,
        hasWindowFocus: Boolean,
    ) {
        synchronized(lock) {
            if (closed.get()) return
            hosts[hostId] = HostState(
                resumed = resumed,
                hasWindowFocus = hasWindowFocus,
            )
            if (resumed && hasWindowFocus) {
                lastForegroundHostId = hostId
            }
            dismissReturnedPromptLocked(hostId)
        }
    }

    fun unregisterHost(
        hostId: String,
        changingConfigurations: Boolean,
    ) {
        synchronized(lock) {
            if (hosts.remove(hostId) == null) return
            if (lastForegroundHostId == hostId) {
                lastForegroundHostId = hosts.entries
                    .lastOrNull { it.value.isForeground }
                    ?.key
            }
            if (!changingConfigurations && activeSession?.ownerHostId == hostId) {
                clearActiveSessionLocked()
            }
        }
    }

    fun onHostResumed(
        hostId: String,
        hasWindowFocus: Boolean,
    ) {
        synchronized(lock) {
            val host = hosts[hostId] ?: return
            host.resumed = true
            host.hasWindowFocus = hasWindowFocus
            if (hasWindowFocus) {
                lastForegroundHostId = hostId
            }
            dismissReturnedPromptLocked(hostId)
        }
    }

    fun onHostPaused(hostId: String) {
        synchronized(lock) {
            val host = hosts[hostId] ?: return
            host.resumed = false
            revealPromptLocked(hostId)
        }
    }

    fun onHostWindowFocusChanged(
        hostId: String,
        hasWindowFocus: Boolean,
        resumed: Boolean,
    ) {
        synchronized(lock) {
            val host = hosts[hostId] ?: return
            host.resumed = resumed
            host.hasWindowFocus = hasWindowFocus
            if (hasWindowFocus && resumed) {
                lastForegroundHostId = hostId
            } else if (!hasWindowFocus) {
                revealPromptLocked(hostId)
            }
            dismissReturnedPromptLocked(hostId)
        }
    }

    fun begin(
        prompt: PrivilegeUiSystemPrompt,
        ownerHostId: String? = null,
    ): AutoCloseable {
        val sessionId = synchronized(lock) {
            if (closed.get() || activeSession != null) return NoOpCloseable
            val resolvedOwnerHostId = ownerHostId
                ?.takeIf(hosts::containsKey)
                ?: foregroundHostIdLocked()
                ?: return NoOpCloseable
            (++nextSessionId).also { id ->
                activeSession = PromptSession(
                    id = id,
                    prompt = prompt,
                    ownerHostId = resolvedOwnerHostId,
                )
            }
        }
        return PromptSessionCloseable(sessionId)
    }

    suspend fun <T> withPrompt(
        prompt: PrivilegeUiSystemPrompt,
        action: suspend () -> T,
    ): T {
        val session = begin(prompt)
        return try {
            action()
        } finally {
            session.close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            hosts.clear()
            lastForegroundHostId = null
            clearActiveSessionLocked()
        }
    }

    private fun foregroundHostIdLocked(): String? =
        lastForegroundHostId?.takeIf { hosts[it]?.isForeground == true }
            ?: hosts.entries.lastOrNull { it.value.isForeground }?.key

    private fun revealPromptLocked(hostId: String) {
        val session = activeSession ?: return
        if (session.ownerHostId != hostId || session.visible) return
        session.visible = true
        visiblePromptState.value = session.prompt
    }

    private fun dismissReturnedPromptLocked(hostId: String) {
        val session = activeSession ?: return
        val host = hosts[hostId] ?: return
        if (
            session.ownerHostId == hostId &&
            session.visible &&
            host.resumed &&
            (host.hasWindowFocus || session.completed)
        ) {
            clearActiveSessionLocked()
        }
    }

    private fun closeSession(sessionId: Long) {
        synchronized(lock) {
            val session = activeSession ?: return
            if (session.id != sessionId) return
            session.completed = true
            if (!session.visible) {
                clearActiveSessionLocked()
            } else {
                dismissReturnedPromptLocked(session.ownerHostId)
            }
        }
    }

    private fun clearActiveSessionLocked() {
        activeSession = null
        visiblePromptState.value = null
    }

    private inner class PromptSessionCloseable(
        private val sessionId: Long,
    ) : AutoCloseable {
        private val sessionClosed = AtomicBoolean(false)

        override fun close() {
            if (sessionClosed.compareAndSet(false, true)) {
                closeSession(sessionId)
            }
        }
    }

    private data class HostState(
        var resumed: Boolean,
        var hasWindowFocus: Boolean,
    ) {
        val isForeground: Boolean
            get() = resumed && hasWindowFocus
    }

    private data class PromptSession(
        val id: Long,
        val prompt: PrivilegeUiSystemPrompt,
        val ownerHostId: String,
        var visible: Boolean = false,
        var completed: Boolean = false,
    )

    private data object NoOpCloseable : AutoCloseable {
        override fun close() = Unit
    }
}

internal fun privilegeUiNotificationPermissionPrompt(): PrivilegeUiSystemPrompt =
    PrivilegeUiSystemPrompt(
        title = privilegeUiText(R.string.priv_ui_system_prompt_notification_title),
        message = privilegeUiText(R.string.priv_ui_system_prompt_notification_message),
    )

internal fun privilegeUiLocalNetworkPermissionPrompt(): PrivilegeUiSystemPrompt =
    PrivilegeUiSystemPrompt(
        title = privilegeUiText(R.string.priv_ui_system_prompt_local_network_title),
        message = privilegeUiText(R.string.priv_ui_system_prompt_local_network_message),
    )

internal fun privilegeUiTcpAuthorizationPrompt(): PrivilegeUiSystemPrompt =
    PrivilegeUiSystemPrompt(
        title = privilegeUiText(R.string.priv_ui_system_prompt_tcp_authorization_title),
        message = privilegeUiText(R.string.priv_ui_system_prompt_tcp_authorization_message),
    )

internal fun privilegeUiWirelessDebuggingPrompt(): PrivilegeUiSystemPrompt =
    PrivilegeUiSystemPrompt(
        title = privilegeUiText(R.string.priv_ui_system_prompt_wireless_debugging_title),
        message = privilegeUiText(R.string.priv_ui_system_prompt_wireless_debugging_message),
    )

internal fun privilegeUiRootAuthorizationPrompt(): PrivilegeUiSystemPrompt =
    PrivilegeUiSystemPrompt(
        title = privilegeUiText(R.string.priv_ui_system_prompt_root_title),
        message = privilegeUiText(R.string.priv_ui_system_prompt_root_message),
    )

internal fun privilegeUiExternalAuthorizationPrompt(
    providerLabel: CharSequence,
): PrivilegeUiSystemPrompt = PrivilegeUiSystemPrompt(
    title = privilegeUiText(
        R.string.priv_ui_system_prompt_external_title,
        providerLabel,
    ),
    message = privilegeUiText(
        R.string.priv_ui_system_prompt_external_message,
        providerLabel,
    ),
)
