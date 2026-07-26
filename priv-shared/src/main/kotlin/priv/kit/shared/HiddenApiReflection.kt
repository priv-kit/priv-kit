package priv.kit.shared

import java.lang.reflect.Method
import kotlin.reflect.KClass

internal fun Class<*>.detectHiddenMethod(
    methodName: String,
    vararg signatures: Pair<Int, List<KClass<*>>>,
): Int {
    val namedMethods = methods.filter { it.name == methodName }
    namedMethods.forEach { method ->
        val parameterTypes = method.parameterTypes
        signatures.forEach { (value, expectedTypes) ->
            if (contentEquals(parameterTypes, expectedTypes)) {
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

private fun contentEquals(array: Array<Class<*>>, list: List<KClass<*>>): Boolean {
    if (array.size != list.size) return false
    repeat(array.size) { i ->
        if (array[i] != list[i].java) {
            return false
        }
    }
    return true
}

private fun Method.simpleString(): String =
    "$name(${parameterTypes.joinToString(",") { it.name }}):${returnType.name}"
