package com.example.nolockqs

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Builds the diagnostics report: the device, the app's view of the framework, the hooks each
 * process reported, and, with root, NoLockQS's own log lines and the status bar's disable flags.
 * It holds nothing personal: no notifications, no other app's logs. Call off the main thread.
 */
object Diagnostics {

    /** The facts the screen knows; the report adds the rest. */
    class AppState(
        val version: String,
        val moduleActive: Boolean,
        val frameworkConnected: Boolean,
        val scope: List<String>?,
        val switches: Map<String, Boolean>,
    )

    private const val LOG_LINES = 200
    private const val ROOT_TIMEOUT_S = 15L

    fun build(context: Context, state: AppState): String = buildString {
        val log = AttemptLog(context)
        fun time(millis: Long) = "${DateFormat.getDateFormat(context).format(Date(millis))} ${DateFormat.getTimeFormat(context).format(Date(millis))}"

        appendLine("NoLockQS diagnostics, ${time(System.currentTimeMillis())}")
        appendLine()
        appendLine("## Device")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.ID}, patch ${Build.VERSION.SECURITY_PATCH}")
        appendLine(Build.FINGERPRINT)
        appendLine()
        appendLine("## App")
        appendLine("Version ${state.version}")
        appendLine("Module active: ${state.moduleActive}, framework connected: ${state.frameworkConnected}")
        appendLine("Scope: ${state.scope?.joinToString().orEmpty().ifEmpty { "unknown" }}")
        state.switches.forEach { (key, on) -> appendLine("$key: ${if (on) "on" else "off"}") }
        appendLine("Alerts: ${if (log.alertsEnabled) "on" else "off"}, blocked attempts logged: ${log.attempts().size}")
        appendLine()
        appendLine("## Hooks")
        for ((process, label) in listOf(Reports.PROCESS_SYSTEM_UI to "System UI", Reports.PROCESS_SYSTEM_SERVER to "System Framework")) {
            val status = log.status(process)
            if (status == null) {
                appendLine("$label: no report since the app was installed")
            } else {
                appendLine("$label, reported ${time(status.time)}:")
                status.layers.forEach { appendLine("  $it") }
            }
        }
        appendLine()

        val root = runAsRoot(
            "getprop persist.sys.nolockqs.enabled; echo ---; " +
                "dumpsys statusbar | grep -iE 'disable' | head -n 40; echo ---; " +
                "logcat -d -v time -s NoLockQS:V | tail -n $LOG_LINES",
        )
        if (root == null) {
            appendLine("## Root")
            appendLine("Not available, or not granted to NoLockQS. Grant root to include the log.")
            return@buildString
        }
        val (property, flags, lines) = (root.split("---\n") + listOf("", "", "")).take(3)
        appendLine("## Master switch (persist.sys.nolockqs.enabled)")
        appendLine(property.trim().ifEmpty { "not set (on)" })
        appendLine()
        appendLine("## Status bar disable flags")
        appendLine(flags.trim().ifEmpty { "none reported" })
        appendLine()
        appendLine("## Log (last $LOG_LINES lines)")
        appendLine(lines.trim().ifEmpty { "No NoLockQS lines in the log buffer. It may have rotated: restart the phone, then make a new report." })
    }

    /** Runs [command] through `su`, or returns null without root or when it is refused or hangs. */
    private fun runAsRoot(command: String): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
        if (!process.waitFor(ROOT_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        reader.join(1000)
        // Without the separators the command never ran, e.g. root was refused.
        output.toString().takeIf { "---" in it }
    }.getOrNull()
}
