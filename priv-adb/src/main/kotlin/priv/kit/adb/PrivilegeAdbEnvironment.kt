package priv.kit.adb

internal object PrivilegeAdbEnvironment {
    fun getAdbTcpPort(): Int {
        val servicePort = getSystemPropertyInt("service.adb.tcp.port", -1)
        if (servicePort > 0) return servicePort
        val persistPort = getSystemPropertyInt("persist.adb.tcp.port", -1)
        if (persistPort > 0) return persistPort
        return -1
    }

    private fun getSystemPropertyInt(name: String, defaultValue: Int): Int =
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            method.invoke(null, name, defaultValue) as Int
        }.getOrDefault(defaultValue)
}
