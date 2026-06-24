package priv.kit.adb.crypto.certificate

import java.io.OutputStream

internal interface ContentSigner {
    fun getAlgorithmIdentifier(): AlgorithmIdentifier
    fun getOutputStream(): OutputStream
    fun getSignature(): ByteArray
}
