package com.example.nolockqs

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.example.nolockqs.databinding.ActivityMainBinding
import com.example.nolockqs.databinding.ItemAttemptBinding
import com.example.nolockqs.databinding.ItemFeatureBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private companion object {
        /** System UI's package on Pixels (AOSP's and Google's names), as Vector lists it in the scope. */
        val SYSTEM_UI_PACKAGES = listOf("com.android.systemui", "com.google.android.systemui")

        /** System Framework (system_server), as Vector lists it in the scope. */
        const val SYSTEM_FRAMEWORK = "system"

        /** How many of the newest blocked attempts the screen lists. */
        const val ATTEMPTS_SHOWN = 20

        /** Set once the notification permission was asked for, so the app asks only once by itself. */
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }

    /** A protection's box on this screen and the switch in [FeatureSettings] it controls. */
    private class Feature(
        val key: String,
        val views: ItemFeatureBinding,
        @param:DrawableRes val icon: Int,
        @param:StringRes val title: Int,
        @param:StringRes val body: Int,
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var features: List<Feature>

    private val app get() = application as NoLockApplication

    /** The last known state of the switches, shown until the framework connects. */
    private val savedSwitches by lazy { getSharedPreferences("feature_switches", MODE_PRIVATE) }

    /** The blocked attempts and the hooks' status reports, kept by [ReportReceiver]. */
    private val attemptLog by lazy { AttemptLog(this) }

    /** Redraws the history whenever [ReportReceiver] records an attempt while the screen is open. */
    private val attemptLogListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderAttempts() }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderAttempts() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The window is edge-to-edge: keep the content clear of the system bars and camera cutout.
        // The top is a margin, not padding, so scrolled content never slides under the status bar;
        // the bottom stays padding, so it still scrolls behind the navigation bar.
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                if (params.topMargin != bars.top) {
                    params.topMargin = bars.top
                    view.layoutParams = params
                }
            }
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        val versionName = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        binding.version.text = getString(R.string.version_format, versionName.orEmpty())
        binding.patreonButton.setOnClickListener { openLink(getString(R.string.patreon_url)) }
        binding.kofiButton.setOnClickListener { openLink(getString(R.string.kofi_url)) }
        binding.developerCard.setOnClickListener { openLink(getString(R.string.developer_url)) }
        binding.statusCard.setOnClickListener { requestMissingScope() }

        features = listOf(
            Feature(
                FeatureSettings.BLOCK_QUICK_SETTINGS, binding.quickSettings,
                R.drawable.ic_quick_settings, R.string.feature_qs_title, R.string.feature_qs_body,
            ),
            Feature(
                FeatureSettings.BLOCK_POWER_MENU, binding.powerMenu,
                R.drawable.ic_power_menu, R.string.feature_power_title, R.string.feature_power_body,
            ),
        )
        features.forEach(::setUpFeature)
        setUpTheme()

        binding.alertsRow.setOnClickListener { toggleAlerts() }
        binding.clearHistory.setOnClickListener { confirmClearHistory() }
        binding.diagnosticsButton.setOnClickListener { createDiagnosticsReport() }
        ReportReceiver.createChannel(this)
        // Alerts are on by default, so ask for the notification permission they need once.
        if (savedInstanceState == null && attemptLog.alertsEnabled && !canNotify() && !savedSwitches.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) {
            askForNotifications()
        }
    }

    /** The theme switch: picking an option saves it and redraws the app in it at once. */
    private fun setUpTheme() {
        val buttons = mapOf(
            AppTheme.SYSTEM to binding.themeSystem,
            AppTheme.LIGHT to binding.themeLight,
            AppTheme.DARK to binding.themeDark,
        )
        // Material buttons keep to one line, which cuts "Same as device" short: let labels wrap.
        buttons.values.forEach { button ->
            button.isSingleLine = false
            button.maxLines = 2
        }
        binding.themeGroup.check(buttons.getValue(AppTheme.get(this)).id)
        binding.themeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val theme = buttons.entries.first { it.value.id == id }.key
            if (theme != AppTheme.get(this)) AppTheme.set(this, theme)
        }
    }

    override fun onStart() {
        super.onStart()
        app.onFeatureSettingsChanged = ::onFeatureSettingsChanged
        onFeatureSettingsChanged()
        attemptLog.prefs.registerOnSharedPreferenceChangeListener(attemptLogListener)
        renderAttempts()
    }

    override fun onResume() {
        super.onResume()
        // The history is on screen: the alert has done its job.
        attemptLog.markSeen()
        ReportReceiver.cancelAlert(this)
    }

    override fun onStop() {
        app.onFeatureSettingsChanged = null
        attemptLog.prefs.unregisterOnSharedPreferenceChangeListener(attemptLogListener)
        super.onStop()
    }

    private fun setUpFeature(feature: Feature) = with(feature.views) {
        featureIcon.setImageResource(feature.icon)
        featureTitle.setText(feature.title)
        featureBody.setText(feature.body)
        root.setOnClickListener { toggle(feature.key) }
        // Screen readers announce the whole box as a switch.
        ViewCompat.setAccessibilityDelegate(root, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.checked = if (isOn(feature.key)) {
                    AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                } else {
                    AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
                }
            }
        })
    }

    /** Updates the saved copy of the switches from the framework, then the screen. */
    private fun onFeatureSettingsChanged() {
        app.featureSettings?.let { settings ->
            savedSwitches.edit {
                features.forEach { putBoolean(it.key, settings.getBoolean(it.key, FeatureSettings.DEFAULT_ON)) }
            }
        }
        render()
    }

    private fun toggle(key: String) {
        val settings = app.featureSettings
        if (settings == null) {
            Toast.makeText(this, R.string.switches_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val on = !isOn(key)
        settings.edit { putBoolean(key, on) }
        savedSwitches.edit { putBoolean(key, on) }
        render()
    }

    private fun isOn(key: String): Boolean =
        app.featureSettings?.getBoolean(key, FeatureSettings.DEFAULT_ON)
            ?: savedSwitches.getBoolean(key, FeatureSettings.DEFAULT_ON)

    private fun render() {
        features.forEach(::renderFeature)
        updateModuleStatus()
    }

    /** Green when on, grey when off; dimmed while the framework isn't connected to save changes. */
    private fun renderFeature(feature: Feature) = with(feature.views) {
        val on = isOn(feature.key)
        root.setCardBackgroundColor(getColor(if (on) R.color.feature_on_container else R.color.feature_off_container))
        root.alpha = if (app.featureSettings != null) 1f else 0.6f
        featureTitle.setTextColor(getColor(if (on) R.color.feature_on_content else R.color.feature_off_content))
        featureBody.setTextColor(getColor(if (on) R.color.feature_on_content else R.color.feature_off_content_variant))
        featureIcon.backgroundTintList = getColorStateList(if (on) R.color.feature_on_accent else R.color.feature_off_accent)
        featureIcon.imageTintList = getColorStateList(if (on) R.color.feature_on_on_accent else R.color.feature_off_on_accent)
        featureSwitch.isChecked = on
        ViewCompat.setStateDescription(root, getString(if (on) R.string.feature_state_on else R.string.feature_state_off))
    }

    /**
     * The scope entries the hooks need that the framework reports as missing: System UI for Quick
     * Settings, System Framework for the power menu. Empty while the framework isn't connected.
     */
    private fun missingScope(): List<String> {
        val scope = app.scope ?: return emptyList()
        return buildList {
            if (SYSTEM_UI_PACKAGES.none { it in scope }) add(SYSTEM_UI_PACKAGES.first())
            if (SYSTEM_FRAMEWORK !in scope) add(SYSTEM_FRAMEWORK)
        }
    }

    private fun scopeLabel(packageName: String): String =
        getString(if (packageName == SYSTEM_FRAMEWORK) R.string.scope_system_framework else R.string.scope_system_ui)

    /** Asks the framework to add what the scope is missing; it shows the user a confirmation. */
    private fun requestMissingScope() {
        val missing = missingScope().ifEmpty { return }
        Toast.makeText(this, R.string.scope_request_sent, Toast.LENGTH_LONG).show()
        app.requestScope(missing) { failure ->
            if (failure != null) Toast.makeText(this, R.string.scope_request_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateModuleStatus() {
        // The hook marks this screen active; failing that (the app itself not in scope), a framework
        // connection means NoLockQS is enabled.
        val active = isModuleActive() || app.featureSettings != null
        val missing = missingScope()
        binding.statusCard.isClickable = active && missing.isNotEmpty()
        if (active && missing.isNotEmpty()) {
            renderScopeIncomplete(missing)
            return
        }
        val quickSettings = isOn(FeatureSettings.BLOCK_QUICK_SETTINGS)
        val powerMenu = isOn(FeatureSettings.BLOCK_POWER_MENU)
        val protecting = active && (quickSettings || powerMenu)
        val container = getColorStateList(if (protecting) R.color.status_active_container else R.color.status_inactive_container)
        val content = getColorStateList(if (protecting) R.color.status_on_active_container else R.color.status_on_inactive_container)
        binding.statusCard.setCardBackgroundColor(container)
        binding.statusIcon.setImageResource(if (protecting) R.drawable.ic_shield_active else R.drawable.ic_shield_inactive)
        binding.statusIcon.imageTintList = content
        binding.statusTitle.setText(
            when {
                !active -> R.string.status_inactive_title
                protecting -> R.string.status_active_title
                else -> R.string.status_off_title
            },
        )
        binding.statusTitle.setTextColor(content)
        binding.statusBody.setText(
            when {
                !active -> R.string.status_inactive_body
                quickSettings && powerMenu -> R.string.status_active_body
                quickSettings -> R.string.status_active_body_qs
                powerMenu -> R.string.status_active_body_power
                else -> R.string.status_off_body
            },
        )
        binding.statusBody.setTextColor(content)
    }

    /** Enabled, but without the scope a protection needs: say which, and let a tap request it. */
    private fun renderScopeIncomplete(missing: List<String>) {
        val container = getColorStateList(R.color.status_inactive_container)
        val content = getColorStateList(R.color.status_on_inactive_container)
        binding.statusCard.setCardBackgroundColor(container)
        binding.statusIcon.setImageResource(R.drawable.ic_shield_inactive)
        binding.statusIcon.imageTintList = content
        binding.statusTitle.setText(R.string.status_scope_title)
        binding.statusTitle.setTextColor(content)
        binding.statusBody.text = getString(R.string.status_scope_body, missing.joinToString(getString(R.string.scope_separator), transform = ::scopeLabel))
        binding.statusBody.setTextColor(content)
    }

    // ---------------------------------------------------------------------------------------
    // Blocked attempts
    // ---------------------------------------------------------------------------------------

    private fun canNotify(): Boolean =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun toggleAlerts() {
        val on = !attemptLog.alertsEnabled
        attemptLog.alertsEnabled = on
        if (on && !canNotify()) askForNotifications()
        renderAttempts()
    }

    /** Asks for the notification permission, or opens the settings once Android stops asking. */
    private fun askForNotifications() {
        val asked = savedSwitches.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        if (!asked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            savedSwitches.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
    }

    private fun renderAttempts() {
        val alerts = attemptLog.alertsEnabled
        binding.alertsSwitch.isChecked = alerts
        binding.alertsBody.setText(if (alerts && !canNotify()) R.string.alerts_body_no_permission else R.string.alerts_body)
        ViewCompat.setStateDescription(binding.alertsRow, getString(if (alerts) R.string.feature_state_on else R.string.feature_state_off))
        ViewCompat.setAccessibilityDelegate(binding.alertsRow, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.checked = if (attemptLog.alertsEnabled) {
                    AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                } else {
                    AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
                }
            }
        })

        val attempts = attemptLog.attempts()
        binding.attemptsSummary.text = when {
            attempts.isEmpty() -> getString(R.string.attempts_empty)
            attempts.size > ATTEMPTS_SHOWN -> resources.getQuantityString(R.plurals.attempts_summary_latest, attempts.size, ATTEMPTS_SHOWN, attempts.size)
            else -> resources.getQuantityString(R.plurals.attempts_summary, attempts.size, attempts.size)
        }
        binding.attemptsList.removeAllViews()
        attempts.take(ATTEMPTS_SHOWN).forEach { attempt ->
            val row = ItemAttemptBinding.inflate(layoutInflater, binding.attemptsList, false)
            val (bubble, tint) = ReportReceiver.kindColors(attempt.kind)
            row.attemptIcon.setImageResource(ReportReceiver.kindIcon(attempt.kind))
            row.attemptIcon.backgroundTintList = getColorStateList(bubble)
            row.attemptIcon.imageTintList = getColorStateList(tint)
            row.attemptKind.text = ReportReceiver.kindLabel(this, attempt.kind)
            row.attemptTime.text = DateUtils.formatDateTime(
                this, attempt.time,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            )
            binding.attemptsList.addView(row.root)
        }
        binding.clearHistory.visibility = if (attempts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmClearHistory() {
        val count = attemptLog.attempts().size
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.attempts_clear_title)
            .setMessage(resources.getQuantityString(R.plurals.attempts_clear_message, count, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.attempts_clear) { _, _ ->
                attemptLog.clear()
                renderAttempts()
            }
            .show()
            // Clearing can't be undone: the confirm button is in the error color.
            .getButton(DialogInterface.BUTTON_POSITIVE)
            ?.setTextColor(getColor(R.color.p_error))
    }

    // ---------------------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------------------

    private fun createDiagnosticsReport() {
        val versionName = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty()
        val state = Diagnostics.AppState(
            version = versionName,
            moduleActive = isModuleActive() || app.featureSettings != null,
            frameworkConnected = app.featureSettings != null,
            scope = app.scope,
            switches = features.associate { it.key to isOn(it.key) },
        )
        binding.diagnosticsButton.isEnabled = false
        binding.diagnosticsButton.setText(R.string.diagnostics_working)
        Thread {
            val report = Diagnostics.build(applicationContext, state)
            runOnUiThread {
                binding.diagnosticsButton.isEnabled = true
                binding.diagnosticsButton.setText(R.string.diagnostics_button)
                if (!isFinishing && !isDestroyed) showDiagnosticsReport(report)
            }
        }.start()
    }

    private fun showDiagnosticsReport(report: String) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            this.text = report
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(padding, padding / 2, padding, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diagnostics_title)
            .setView(ScrollView(this).apply { addView(text) })
            .setNegativeButton(R.string.diagnostics_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_title), report))
                Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.diagnostics_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_subject))
                    .putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(send, getString(R.string.diagnostics_share)))
            }
            .setNeutralButton(R.string.diagnostics_close, null)
            .show()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (ignored: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    /** Returns false here; [NoLockXposedModule] hooks it to return true inside this app's process. */
    fun isModuleActive(): Boolean = false
}
