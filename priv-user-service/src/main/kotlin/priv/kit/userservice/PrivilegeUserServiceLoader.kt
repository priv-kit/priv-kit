package priv.kit.userservice

internal object PrivilegeUserServiceLoader {
    fun instantiate(serviceClassName: String): Any {
        val clazz = loadClass(serviceClassName)
        return try {
            val constructor = clazz.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance()
        } catch (throwable: Throwable) {
            throw PrivilegeUserServiceDeclarationException(
                "UserService must have an accessible no-arg constructor: $serviceClassName",
                throwable,
            )
        }
    }

    private fun loadClass(serviceClassName: String): Class<*> {
        val classLoaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            PrivilegeUserServiceLoader::class.java.classLoader,
        ).distinct()

        var failure: Throwable? = null
        classLoaders.forEach { classLoader ->
            try {
                return Class.forName(serviceClassName, false, classLoader)
            } catch (throwable: ClassNotFoundException) {
                val previousFailure = failure
                if (previousFailure == null) {
                    failure = throwable
                } else {
                    previousFailure.addSuppressed(throwable)
                }
            }
        }

        throw PrivilegeUserServiceDeclarationException(
            "UserService class was not found: $serviceClassName",
            failure,
        )
    }
}
