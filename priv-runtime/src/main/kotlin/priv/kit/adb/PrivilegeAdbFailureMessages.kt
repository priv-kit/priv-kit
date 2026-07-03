package priv.kit.adb

import java.net.ConnectException
import java.net.SocketTimeoutException

internal fun Throwable.toFailureMessage(): String =
    "${javaClass.simpleName}: ${message.orEmpty()}".trim()

internal fun Throwable.toTcpAuthorizationCheckResult(
    output: PrivilegeAdbOutput,
    identity: PrivilegeAdbIdentity,
    publicKeyFingerprint: String,
): PrivilegeAdbAuthorizationCheckResult =
    PrivilegeAdbAuthorizationCheckResult(
        status = if (this is ConnectException || this is SocketTimeoutException) {
            PrivilegeAdbAuthorizationStatus.UNAVAILABLE
        } else {
            PrivilegeAdbAuthorizationStatus.ERROR
        },
        outputText = output.text(),
        identity = identity,
        publicKeyFingerprint = publicKeyFingerprint,
        failureMessage = toFailureMessage(),
    )
