package priv.kit.core

class PrivilegeStartupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
