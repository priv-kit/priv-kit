package priv.kit.evdev

import java.io.IOException

/** A touch frame was not fully written. */
public class PrivilegeEvdevWriteException(public val result: PrivilegeEvdevWriteResult) : IOException(
    "Incomplete touch frame: requested=${result.requestedEventCount}, submitted=${result.submittedEventCount}, errno=${result.errno}",
)
