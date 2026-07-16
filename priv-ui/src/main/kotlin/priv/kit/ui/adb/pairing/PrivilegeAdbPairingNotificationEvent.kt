package priv.kit.ui.adb.pairing

import androidx.annotation.RestrictTo

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public sealed interface PrivilegeAdbPairingNotificationEvent {
    public val ownerId: String

    public data class Submit public constructor(
        public override val ownerId: String,
        public val pairingCode: String,
    ) : PrivilegeAdbPairingNotificationEvent

    public data class Unavailable public constructor(
        public override val ownerId: String,
        public val message: String,
    ) : PrivilegeAdbPairingNotificationEvent

    public data class Stop public constructor(
        public override val ownerId: String,
    ) : PrivilegeAdbPairingNotificationEvent

    public data class Detached public constructor(
        public override val ownerId: String,
    ) : PrivilegeAdbPairingNotificationEvent
}
