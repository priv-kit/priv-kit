package priv.kit.adb

import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLProtocolException
import priv.kit.shared.hasPrivilegeAdbCertificateUnknownMessage
import priv.kit.shared.hasPrivilegeAdbKeyNotAuthorizedMessage

internal fun Throwable.toFailureMessage(): String =
    "${javaClass.simpleName}: ${message.orEmpty()}".trim()

internal fun Throwable.isAdbTlsCertificateUnknown(): Boolean =
    this is SSLProtocolException &&
        message.orEmpty().hasPrivilegeAdbCertificateUnknownMessage()

internal fun Throwable.toPairingCheckFailureStatus(): PrivilegeAdbPairingCheckStatus =
    when {
        isAdbKeyNotAuthorized() -> PrivilegeAdbPairingCheckStatus.UNPAIRED
        this is ConnectException || this is SocketTimeoutException -> PrivilegeAdbPairingCheckStatus.UNAVAILABLE
        else -> PrivilegeAdbPairingCheckStatus.ERROR
    }

internal fun Throwable.isAdbKeyNotAuthorized(): Boolean =
    hasPrivilegeAdbKeyNotAuthorizedMessage() ||
        generateSequence(this) { it.cause }.any { throwable ->
            throwable.isAdbTlsCertificateUnknown()
        }

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
