package priv.kit.evdev.internal

internal interface PrivilegeEvdevBackend {
    fun open(path: String, writable: Boolean): Int
    fun name(fd: Int): ByteArray
    fun bits(fd: Int, type: Int): IntArray
    fun axis(fd: Int, axis: Int): IntArray
    fun slots(fd: Int): IntArray
    fun write(fd: Int, events: IntArray): IntArray
    fun close(fd: Int)
}

internal object PrivilegeEvdevNative : PrivilegeEvdevBackend {
    init { System.loadLibrary("privkitinput") }

    external override fun open(path: String, writable: Boolean): Int
    external override fun name(fd: Int): ByteArray
    external override fun bits(fd: Int, type: Int): IntArray
    external override fun axis(fd: Int, axis: Int): IntArray
    external override fun slots(fd: Int): IntArray
    external override fun write(fd: Int, events: IntArray): IntArray
    external override fun close(fd: Int)
}
