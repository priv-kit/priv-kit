package priv.kit.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class PrivilegeAdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataCrc32: Int,
    val magic: Int,
    val data: ByteArray?,
) {
    constructor(command: Int, arg0: Int, arg1: Int, data: String) : this(
        command = command,
        arg0 = arg0,
        arg1 = arg1,
        data = "$data\u0000".toByteArray(),
    )

    constructor(command: Int, arg0: Int, arg1: Int, data: ByteArray?) : this(
        command = command,
        arg0 = arg0,
        arg1 = arg1,
        dataLength = data?.size ?: 0,
        dataCrc32 = crc32(data),
        magic = command xor -0x1,
        data = data,
    )

    fun validate(): Boolean {
        if (command != (magic xor -0x1)) return false
        if (dataLength != 0 && crc32(data) != dataCrc32) return false
        return true
    }

    fun validateOrThrow() {
        if (!validate()) {
            throw IllegalArgumentException("Bad ADB message ${toStringShort()}")
        }
    }

    fun toByteArray(): ByteArray {
        val payload = data
        val length = HEADER_LENGTH + (payload?.size ?: 0)
        return ByteBuffer.allocate(length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(command)
                putInt(arg0)
                putInt(arg1)
                putInt(dataLength)
                putInt(dataCrc32)
                putInt(magic)
                if (payload != null) {
                    put(payload)
                }
            }
            .array()
    }

    fun toStringShort(): String {
        val commandString = when (command) {
            PrivilegeAdbProtocol.A_SYNC -> "A_SYNC"
            PrivilegeAdbProtocol.A_CNXN -> "A_CNXN"
            PrivilegeAdbProtocol.A_AUTH -> "A_AUTH"
            PrivilegeAdbProtocol.A_OPEN -> "A_OPEN"
            PrivilegeAdbProtocol.A_OKAY -> "A_OKAY"
            PrivilegeAdbProtocol.A_CLSE -> "A_CLSE"
            PrivilegeAdbProtocol.A_WRTE -> "A_WRTE"
            PrivilegeAdbProtocol.A_STLS -> "A_STLS"
            else -> command.toString()
        }
        return "command=$commandString, arg0=$arg0, arg1=$arg1, dataLength=$dataLength"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrivilegeAdbMessage) return false
        if (command != other.command) return false
        if (arg0 != other.arg0) return false
        if (arg1 != other.arg1) return false
        if (dataLength != other.dataLength) return false
        if (dataCrc32 != other.dataCrc32) return false
        if (magic != other.magic) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + dataLength
        result = 31 * result + dataCrc32
        result = 31 * result + magic
        result = 31 * result + (data?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val HEADER_LENGTH = 24

        fun fromByteArray(bytes: ByteArray): PrivilegeAdbMessage {
            require(bytes.size >= HEADER_LENGTH) { "ADB message is too short" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val dataLength = buffer.int
            val checksum = buffer.int
            val magic = buffer.int
            val data = if (dataLength > 0) {
                ByteArray(dataLength).also { buffer.get(it) }
            } else {
                null
            }
            return PrivilegeAdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        }

        private fun crc32(data: ByteArray?): Int {
            if (data == null) return 0
            var result = 0
            for (byte in data) {
                result += if (byte >= 0) byte.toInt() else byte + 256
            }
            return result
        }
    }
}
