package priv.kit.core

public open class PrivilegeStartupException public constructor(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
