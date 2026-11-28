package com.example.nolockqs

/**
 * What the hooks in System UI and System Framework tell the app, as explicit broadcasts to
 * [ReportReceiver]. Only senders holding [SENDER_PERMISSION] get through, which System UI and
 * System Framework do and other apps can't, so no app can fake a report.
 */
object Reports {
    /** A protection refused something while the phone was locked. */
    const val ACTION_BLOCKED = "io.github.i2B4G10.NoLockQS.action.BLOCKED"

    /** Which hooks a process installed, sent once it is up. */
    const val ACTION_STATUS = "io.github.i2B4G10.NoLockQS.action.STATUS"

    /** [ACTION_BLOCKED]: one of the KIND_ values. */
    const val EXTRA_KIND = "kind"

    /** Both: when it happened, in milliseconds since the epoch. */
    const val EXTRA_TIME = "time"

    /** [ACTION_STATUS]: the reporting process, one of the PROCESS_ values. */
    const val EXTRA_PROCESS = "process"

    /** [ACTION_STATUS]: one line per protection layer, such as "Power menu: 3 hook(s)". */
    const val EXTRA_LAYERS = "layers"

    const val KIND_POWER_MENU = "power_menu"
    const val KIND_QUICK_SETTINGS = "quick_settings"

    /**
     * Failed unlocks on the lock screen. Only the kind and the time are ever sent: never what was
     * entered, nor how long it was.
     */
    const val KIND_WRONG_PIN = "wrong_pin"
    const val KIND_WRONG_PATTERN = "wrong_pattern"
    const val KIND_WRONG_PASSWORD = "wrong_password"
    const val KIND_FAILED_FINGERPRINT = "failed_fingerprint"
    const val KIND_FAILED_FACE = "failed_face"

    /** The failed-unlock kinds, which the history shows with a lock icon. */
    val FAILED_UNLOCK_KINDS = setOf(KIND_WRONG_PIN, KIND_WRONG_PATTERN, KIND_WRONG_PASSWORD, KIND_FAILED_FINGERPRINT, KIND_FAILED_FACE)

    const val PROCESS_SYSTEM_UI = "system_ui"
    const val PROCESS_SYSTEM_SERVER = "system_server"

    /** Signature|privileged: held by System UI, and granted to System Framework as the system uid. */
    const val SENDER_PERMISSION = "android.permission.STATUS_BAR_SERVICE"

    /** The receiver's class name, kept as text so the hooked processes never load the app's classes. */
    const val RECEIVER_CLASS = "com.example.nolockqs.ReportReceiver"
}
