package priv.kit.shared

import java.lang.reflect.Method

internal fun Class<*>.detectHiddenMethod(
    methodName: String,
    vararg signatures: Pair<Int, List<Class<*>>>,
): Int {
    val namedMethods = methods.filter { it.name == methodName }
    namedMethods.forEach { method ->
        val parameterTypes = method.parameterTypes.toList()
        signatures.forEach { (value, expectedTypes) ->
            if (parameterTypes == expectedTypes) {
                return value
            }
        }
    }
    if (namedMethods.isEmpty()) {
        throw NoSuchMethodException("$name::$methodName not found")
    }
    throw NoSuchMethodException(
        "$name::$methodName not match: ${namedMethods.joinToString { it.simpleString() }}",
    )
}

private fun Method.simpleString(): String =
    "$name(${parameterTypes.joinToString(",") { it.name }}):${returnType.name}"
