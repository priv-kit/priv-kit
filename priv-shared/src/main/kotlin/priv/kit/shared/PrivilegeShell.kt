package priv.kit.shared

/** Quotes this value as one POSIX shell argument when bare text would be unsafe. */
public fun String.toPrivilegeShellArgument(): String =
    if (isNotEmpty() && all(::isPrivilegeShellBareCharacter)) {
        this
    } else {
        "'" + replace("'", "'\"'\"'") + "'"
    }

private fun isPrivilegeShellBareCharacter(character: Char): Boolean =
    character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character in '0'..'9' ||
        character == '/' ||
        character == '.' ||
        character == '_' ||
        character == '-' ||
        character == ':' ||
        character == '=' ||
        character == '@' ||
        character == '%' ||
        character == '+' ||
        character == ',' ||
        character == '~'
