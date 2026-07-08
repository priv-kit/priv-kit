package priv.kit.ui.adb.pairing

import priv.kit.ui.*
import priv.kit.ui.adb.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

import android.app.PendingIntent
import android.content.Intent
import android.os.Build

internal object PrivilegeAdbPairingIntentContract {
    internal const val ACTION_START: String = "priv.kit.ui.action.START_ADB_PAIRING_NOTIFICATION"
    internal const val ACTION_REPLY: String = "priv.kit.ui.action.REPLY_ADB_PAIRING_NOTIFICATION"
    internal const val ACTION_INPUT_LEFT: String = "priv.kit.ui.action.ADB_PAIRING_INPUT_LEFT"
    internal const val ACTION_INPUT_UP: String = "priv.kit.ui.action.ADB_PAIRING_INPUT_UP"
    internal const val ACTION_INPUT_DOWN: String = "priv.kit.ui.action.ADB_PAIRING_INPUT_DOWN"
    internal const val ACTION_INPUT_RIGHT: String = "priv.kit.ui.action.ADB_PAIRING_INPUT_RIGHT"
    internal const val ACTION_INPUT_SUBMIT: String = "priv.kit.ui.action.ADB_PAIRING_INPUT_SUBMIT"
    internal const val ACTION_STOP: String = "priv.kit.ui.action.STOP_ADB_PAIRING_NOTIFICATION"

    internal const val EXTRA_REQUESTED_ADB_DEVICE_NAME: String = "requested_adb_device_name"
    internal const val REMOTE_INPUT_PAIRING_CODE: String = "pairing_code"
    internal const val NOTIFICATION_CHANNEL_ID: String = "priv_ui_adb_pairing"
    internal const val NOTIFICATION_ID: Int = 201
    internal const val INPUT_NOTIFICATION_ID: Int = 202
    internal const val REQUEST_REPLY: Int = 1
    internal const val REQUEST_STOP: Int = 3
    internal const val REQUEST_INPUT_LEFT: Int = 5
    internal const val REQUEST_INPUT_UP: Int = 6
    internal const val REQUEST_INPUT_DOWN: Int = 7
    internal const val REQUEST_INPUT_RIGHT: Int = 8
    internal const val REQUEST_INPUT_SUBMIT: Int = 9

    internal fun mutablePendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

    internal fun immutablePendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}

internal val Intent.privilegeAdbRequestedDeviceName: String?
    get() = getStringExtra(PrivilegeAdbPairingIntentContract.EXTRA_REQUESTED_ADB_DEVICE_NAME)
