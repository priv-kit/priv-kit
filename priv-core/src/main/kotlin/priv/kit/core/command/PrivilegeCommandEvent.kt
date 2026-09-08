package priv.kit.core.command

/** A single event emitted while consuming a command as a stream. */
public sealed interface PrivilegeCommandEvent {
    /** A chunk read from standard output. Chunk boundaries are not text boundaries. */
    public class Stdout internal constructor(
        public val bytes: ByteArray,
    ) : PrivilegeCommandEvent

    /** A chunk read from standard error. Chunk boundaries are not text boundaries. */
    public class Stderr internal constructor(
        public val bytes: ByteArray,
    ) : PrivilegeCommandEvent

    /** The final event, emitted after the process exits and both output streams reach EOF. */
    public class Exited internal constructor(
        public val exitCode: Int,
    ) : PrivilegeCommandEvent
}
