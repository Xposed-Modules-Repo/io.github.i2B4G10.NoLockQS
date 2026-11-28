package com.example.nolockqs

import android.annotation.SuppressLint
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap
import java.util.concurrent.Executor

/**
 * NoLockQS Xposed module (libxposed API 101+).
 *
 * While the device is locked it keeps these out of reach:
 *  - Quick Settings: SystemUI asks the status bar service to disable Quick Settings
 *    (`DISABLE2_QUICK_SETTINGS`) whenever the keyguard shows, and lifts it
 *    once it is gone. SystemUI then hides the tiles and refuses every way into them, pull-downs,
 *    two-finger pulls and requests from apps alike, while the notifications stay usable: they can
 *    still be tapped, expanded and acted on. On the scene-container SystemUI (Android 16+) the
 *    Quick Settings scene and overlay are also vetoed directly, in case the flag arrives late.
 *    On the legacy shade (up to Android 16) the shade's own backgrounds stay as see-through as
 *    the lock screen's, so pulling down or expanding a notification shows no empty panel where
 *    the tiles would be.
 *  - The power menu: in system_server, every trigger (power key, key chords, accessibility) is
 *    refused before SystemUI or the legacy dialog is asked to show it. SystemUI refuses it too,
 *    as a fallback for when the System Framework scope is not enabled.
 *
 * Each protection has a switch in the app (see [FeatureSettings]), which the framework shares with
 * the hooked processes and keeps current, so a change applies right away.
 */
class NoLockXposedModule : XposedModule() {

    private companion object {
        const val TAG = "NoLockQS"

        /** Master kill-switch: `setprop persist.sys.nolockqs.enabled false` (as root) pauses every layer. */
        const val TOGGLE_PROPERTY = "persist.sys.nolockqs.enabled"

        val SYSTEM_UI_PACKAGES = setOf("com.android.systemui", "com.google.android.systemui")

        /** StatusBarManager.DISABLE2_QUICK_SETTINGS. */
        const val DISABLE2_QUICK_SETTINGS = 1

        /** StatusBarManager.DISABLE2_NONE. */
        const val DISABLE2_NONE = 0

        /** How often, and for how long, to wait for SystemUI's application before giving up. */
        const val STARTUP_RETRY_MS = 500L
        const val STARTUP_RETRIES = 120

        /**
         * While Quick Settings is disabled and the screen is on, the lock state is checked again this
         * often, so the flag is lifted after an unlock even when no lock event reaches SystemUI.
         */
        const val LOCK_RECHECK_MS = 1000L

        /**
         * After System UI reports a keyguard change, the lock state is read again at these delays:
         * the window manager, which answers "locked?", takes a moment to catch up with System UI.
         */
        val KEYGUARD_EVENT_FOLLOW_UPS_MS = longArrayOf(150L, 600L, 1500L, 2500L, 3100L)

        /**
         * Once System UI starts taking the keyguard away after an unlock, Quick Settings counts as
         * unlocked for this long, even while the window manager still answers "locked": on Android
         * 16 that answer only flips after the unlock animation, about a second late. A real re-lock
         * ([KEYGUARD_SHOW_EVENTS]) or the screen turning off ends it at once.
         */
        const val GOING_AWAY_GRACE_MS = 2000L

        /**
         * The other way round: once System UI shows the keyguard again, Quick Settings counts as
         * locked for this long, even while the window manager still answers "unlocked", so a quick
         * re-lock right after an unlock is covered at once. Afterwards the window manager decides
         * again, so a stale event can never keep Quick Settings off while the phone is unlocked.
         */
        const val SHOWN_GRACE_MS = 3000L

        /** [KEYGUARD_STATE_EVENTS] that mean the keyguard is being dismissed after an unlock. */
        val KEYGUARD_GOING_AWAY_EVENTS = setOf("handleStartKeyguardExitAnimation", "handleStartKeyguardExitAnimationInner")

        /** [KEYGUARD_STATE_EVENTS] that mean the keyguard is shown again. */
        val KEYGUARD_SHOW_EVENTS = setOf("handleShow", "handleShowInner")

        /** The same blocked attempt seen again within this window counts once. */
        const val REPORT_DEBOUNCE_MS = 3000L

        /** Failed unlocks are each worth a line, but one touch can fail a fingerprint twice. */
        const val FAILED_UNLOCK_DEBOUNCE_MS = 1000L

        /**
         * Failed unlocks on the lock screen. A wrong PIN, pattern or password reaches the framework
         * through `LockPatternUtils.reportFailedPasswordAttempt(userId)` in System UI. A fingerprint
         * or face that isn't recognized reaches System UI's own FingerprintManager or FaceManager
         * through their binder receivers' `onAuthenticationFailed()` (Android 17), or their
         * `sendAuthenticatedFailed()` before that. System UI only authenticates for the lock
         * screen; an app's biometric prompt reports to that app. Older builds also pass
         * `KeyguardUpdateMonitor`. Only the fact and the time are reported, never the credential.
         */
        val FAILED_UNLOCK_EVENTS = mapOf(
            "com.android.internal.widget.LockPatternUtils" to setOf("reportFailedPasswordAttempt"),
            "android.hardware.fingerprint.FingerprintManager" to setOf("sendAuthenticatedFailed"),
            "android.hardware.fingerprint.FingerprintManager\$FingerprintServiceReceiver" to setOf("onAuthenticationFailed"),
            "android.hardware.face.FaceManager" to setOf("sendAuthenticatedFailed"),
            "android.hardware.face.FaceManager\$FaceServiceReceiver" to setOf("onAuthenticationFailed"),
            "com.android.keyguard.KeyguardUpdateMonitor" to setOf("handleFingerprintAuthFailed", "handleFaceAuthFailed"),
        )

        /** `LockPatternUtils.CREDENTIAL_TYPE_*` values. */
        const val CREDENTIAL_TYPE_PATTERN = 1
        const val CREDENTIAL_TYPE_PIN = 3

        /** system_server reports its hooks once boot completes; it checks this often, this many times. */
        const val STATUS_RETRY_MS = 15_000L
        const val STATUS_RETRIES = 60

        /** Methods that open the power menu in system_server, keyed by declaring class. */
        val SYSTEM_SERVER_POWER_MENU = mapOf(
            "com.android.server.policy.PhoneWindowManager" to setOf("showGlobalActionsInternal"),
            "com.android.server.policy.GlobalActions" to setOf("showDialog"),
            "com.android.server.policy.LegacyGlobalActions" to setOf("showDialog"),
        )

        /** Methods that open the power menu in SystemUI, keyed by declaring class. */
        val SYSTEM_UI_POWER_MENU = mapOf(
            "com.android.systemui.globalactions.GlobalActionsComponent" to setOf("handleShowGlobalActionsMenu"),
            "com.android.systemui.globalactions.GlobalActionsImpl" to setOf("showGlobalActions"),
            "com.android.systemui.globalactions.GlobalActionsDialogLite" to setOf("showOrHideDialog", "handleShow"),
        )

        /** SystemUI's callback interface back to system_server for the power menu. */
        const val GLOBAL_ACTIONS_MANAGER = "GlobalActionsManager"

        /**
         * Scene-container entry points (Android 16+ "Flexiglass" SystemUI): opening Quick Settings
         * settles here as a change to the Quick Settings scene or overlay, which is vetoed while
         * locked. Up to Android 16 `SceneInteractor` is the class itself; on Android 17 it is an
         * interface and the work happens in `LegacySceneInteractorImpl`. The notification shade is
         * left alone, as tapping a notification to expand it opens the shade scene.
         */
        private val SCENE_CHANGE_METHODS = setOf("changeScene", "snapToScene", "showOverlay", "instantlyShowOverlay")
        val SCENE_CONTAINER_ENTRY = mapOf(
            "com.android.systemui.scene.domain.interactor.SceneInteractor" to SCENE_CHANGE_METHODS,
            "com.android.systemui.scene.domain.interactor.LegacySceneInteractorImpl" to SCENE_CHANGE_METHODS,
        )

        /**
         * The legacy shade's backgrounds (up to Android 16): the scrim behind the shade and the
         * notifications panel get their alpha from these, directly or as an animation's target.
         */
        val SHADE_SCRIM = mapOf(
            "com.android.systemui.statusbar.phone.ScrimController" to setOf("setScrimAlpha", "getCurrentScrimAlpha"),
        )

        /** Scrim states of the lock screen and of the shade pulled down over it. */
        val LOCKED_SHADE_SCRIM_STATES = setOf("KEYGUARD", "SHADE_LOCKED")

        /**
         * Where System UI itself learns that the keyguard is shown, hidden or going away. Hooked
         * to follow the lock state the moment it changes; the hooked methods run as usual. While
         * at least one of them is hooked, the 1 s re-check is not needed.
         */
        val KEYGUARD_STATE_EVENTS = mapOf(
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl" to setOf("notifyKeyguardState", "notifyKeyguardGoingAway"),
            // handleShowInner and handleStartKeyguardExitAnimationInner on 16+, the plain names before.
            "com.android.systemui.keyguard.KeyguardViewMediator" to setOf(
                "handleShow", "handleShowInner", "handleHide",
                "handleStartKeyguardExitAnimation", "handleStartKeyguardExitAnimationInner",
                "setPendingLock", "handleNotifyStartedGoingToSleep",
            ),
        )

        /** Requests from apps and `cmd statusbar` to open Quick Settings; reported while blocked, never changed. */
        val QUICK_SETTINGS_REQUESTS = mapOf(
            "com.android.systemui.statusbar.CommandQueue" to setOf("animateExpandSettingsPanel"),
        )

    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Methods already hooked in this process, so a repeated package callback never hooks one twice. */
    private val hookedMethods: MutableSet<Method> = Collections.synchronizedSet(HashSet())

    /** SystemUI class loaders already hooked in this process. */
    private val hookedLoaders: MutableSet<ClassLoader> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>()))

    /** What each protection layer installed in this process, for the status report. */
    private val layerStatus: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())

    /** When each kind of blocked attempt was last reported, for [REPORT_DEBOUNCE_MS]. */
    private val lastReported = ConcurrentHashMap<String, Long>()

    /** Sends reports off the hooked thread, which may hold system locks. */
    private val reportHandler: Handler by lazy { Handler(HandlerThread("NoLockQS-reports").apply { start() }.looper) }

    /** True once a [KEYGUARD_STATE_EVENTS] hook is in: lock changes then arrive as they happen. */
    @Volatile
    private var keyguardEventsHooked = false

    /** Until this uptime the keyguard is going away after an unlock; see [GOING_AWAY_GRACE_MS]. */
    @Volatile
    private var goingAwayUntil = 0L

    /** Until this uptime System UI has just shown the keyguard again; see [SHOWN_GRACE_MS]. */
    @Volatile
    private var shownUntil = 0L

    /** The app's feature switches as the framework shares them; read on first use, see [isFeatureEnabled]. */
    @Volatile
    private var featureSettings: SharedPreferences? = null

    @Volatile
    private var featureSettingsFailureLogged = false

    @delegate:SuppressLint("PrivateApi") // Runs inside SystemUI and system_server, which may use hidden APIs.
    private val systemPropertiesGetBoolean: Method? by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }

    @delegate:SuppressLint("PrivateApi") // As above.
    private val systemPropertiesGet: Method? by lazy {
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java) }.getOrNull()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            in SYSTEM_UI_PACKAGES -> {
                // The framework can report SystemUI more than once per process, also with a class
                // loader that holds none of its code; hook each real SystemUI loader once.
                if (!isSystemUiLoader(param.classLoader) || !hookedLoaders.add(param.classLoader)) return
                // SystemUI runs some services in processes of their own (the screenshot service, for
                // one). Those are cached and frozen soon after their work is done, and a frozen
                // process hears no unlock: a Quick Settings flag sent from one would outlive the lock
                // screen. Nothing to protect runs there, so only the main process is hooked.
                val process = processName()
                if (process != null && process != param.packageName) {
                    log(Log.INFO, TAG, "Skipping $process: not SystemUI's main process.")
                    return
                }
                log(Log.INFO, TAG, "Hooking ${param.packageName} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}).")
                keyguardEventsHooked = installHooks(param.classLoader, KEYGUARD_STATE_EVENTS, keyguardEventHooker, "Unlock detection") > 0
                startQuickSettingsGuard()
                installHooks(param.classLoader, SCENE_CONTAINER_ENTRY, sceneContainerEntryHooker, "Quick Settings scene")
                installHooks(param.classLoader, QUICK_SETTINGS_REQUESTS, quickSettingsRequestHooker, "Quick Settings requests")
                installHooks(param.classLoader, SHADE_SCRIM, shadeScrimHooker, "Shade background")
                installHooks(param.classLoader, SYSTEM_UI_POWER_MENU, systemUiPowerMenuHooker, "Power menu (SystemUI)", everyRelease = true)
                installHooks(param.classLoader, FAILED_UNLOCK_EVENTS, failedUnlockHooker, "Failed unlocks")
            }
            moduleApplicationInfo.packageName -> installModuleStatusHook(param.classLoader)
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        installHooks(param.classLoader, SYSTEM_SERVER_POWER_MENU, systemServerPowerMenuHooker, "Power menu (system_server)", everyRelease = true)
        reportSystemServerStatus()
    }

    /** The lock-state watchers belong to this generation's code: drop them, the next one starts its own. */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        mainHandler.post { stopQuickSettingsGuard() }
        return true
    }

    /**
     * Package callbacks are not replayed after a hot reload, so each live hook is atomically
     * re-pointed at this generation's code (protection never lapses) and unknown ones are dropped.
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        var inSystemUi = false
        for (old in param.oldHookHandles) {
            val hooker = hookerFor(old.executable)
            if (old.executable.declaringClass.name.startsWith("com.android.systemui.")) inSystemUi = true
            if (hooker === keyguardEventHooker) keyguardEventsHooked = true
            runCatching { if (hooker != null) old.replaceHook(hooker) else old.unhook() }
                .onFailure { log(Log.WARN, TAG, "Hot reload: could not update the hook on ${old.executable}", it) }
        }
        if (inSystemUi) startQuickSettingsGuard()
    }

    // ---------------------------------------------------------------------------------------
    // Quick Settings: disabled through the status bar service while locked
    // ---------------------------------------------------------------------------------------

    /** SystemUI's application, once [startQuickSettingsGuard] has it. Main thread only. */
    private var guardContext: Context? = null

    /**
     * This module's own StatusBarManager. The service keeps one disable record per manager, so a
     * manager of its own keeps the flag apart from the ones SystemUI sets through its shared one.
     */
    private var statusBar: Any? = null
    private var lockReceiver: BroadcastReceiver? = null
    private var lockListener: Any? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** What was last asked of the status bar service: true while Quick Settings is disabled. Set on the main thread only. */
    @Volatile
    private var quickSettingsDisabled: Boolean? = null

    /**
     * Starts following the lock state in SystemUI. It needs SystemUI's own application, which is
     * not set up yet when the package is reported, so it waits for it on the main thread.
     */
    private fun startQuickSettingsGuard(attempt: Int = 0) {
        mainHandler.post {
            if (guardContext != null) return@post
            val context = currentApplication()
            if (context == null) {
                if (attempt < STARTUP_RETRIES) mainHandler.postDelayed({ startQuickSettingsGuard(attempt + 1) }, STARTUP_RETRY_MS)
                else log(Log.ERROR, TAG, "Quick Settings: SystemUI's application never came up, layer inactive.")
                return@post
            }
            // Only SystemUI's main process, which is never frozen, may hold the flag (see onPackageReady).
            val process = processName()
            if (process != null && process != context.packageName) return@post
            guardContext = context
            // A context of its own comes with its own service cache, hence its own manager.
            statusBar = runCatching { context.createConfigurationContext(context.resources.configuration).getSystemService("statusbar") }.getOrNull()
            watchLockState(context)
            log(Log.INFO, TAG, "Quick Settings: following the lock state (lock listener=${lockListener != null}, unlock detection=${if (keyguardEventsHooked) "System UI events" else "1 s re-check"}).")
            layerStatus["Quick Settings guard"] = "active, lock listener ${if (lockListener != null) "yes" else "no"}, " +
                if (keyguardEventsHooked) "unlocks seen as they happen" else "unlocks seen by a 1 s re-check"
            refreshQuickSettings()
            sendStatus(context, Reports.PROCESS_SYSTEM_UI)
        }
    }

    /**
     * Follows the keyguard: the locked-state listener reports every change, also those without a
     * screen change (boot, Smart Lock, face unlock). The screen and user-present broadcasts back it
     * up, and the feature switch applies as soon as it is flipped.
     */
    @SuppressLint("MissingPermission") // SystemUI may hold SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE; if not, the broadcasts do.
    private fun watchLockState(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) goingAwayUntil = 0L
                if (intent.action == Intent.ACTION_USER_PRESENT) shownUntil = 0L
                refreshQuickSettings()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) }
            .onSuccess { lockReceiver = receiver }
            .onFailure { log(Log.WARN, TAG, "Quick Settings: could not watch the screen state", it) }

        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val listener = KeyguardManager.KeyguardLockedStateListener { refreshQuickSettings() }
        runCatching { keyguard.addKeyguardLockedStateListener(Executor { mainHandler.post(it) }, listener) }
            .onSuccess { lockListener = listener }
            .onFailure { log(Log.INFO, TAG, "Quick Settings: no lock-state listener here (${it.javaClass.simpleName}), using the screen events.") }

        val settings = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mainHandler.post { refreshQuickSettings() } }
        runCatching { featureSettings()?.registerOnSharedPreferenceChangeListener(settings) }
            .onSuccess { settingsListener = settings }
    }

    /** Stops following the lock state and lifts this generation's flag; the next one sets its own. */
    @SuppressLint("MissingPermission") // Only removes the listener that watchLockState managed to add.
    private fun stopQuickSettingsGuard() {
        val context = guardContext ?: return
        mainHandler.removeCallbacks(lockRecheck)
        if (quickSettingsDisabled == true) setQuickSettingsDisabled(false)
        lockReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        (lockListener as? KeyguardManager.KeyguardLockedStateListener)?.let { listener ->
            runCatching { context.getSystemService(KeyguardManager::class.java).removeKeyguardLockedStateListener(listener) }
        }
        settingsListener?.let { runCatching { featureSettings()?.unregisterOnSharedPreferenceChangeListener(it) } }
        lockReceiver = null
        lockListener = null
        settingsListener = null
        statusBar = null
        quickSettingsDisabled = null
        guardContext = null
    }

    /**
     * Disables Quick Settings while the keyguard is locked and the switch is on, else enables it.
     * Lock changes arrive from System UI's own keyguard events ([keyguardEventHooker]). Only on a
     * build where none of them could be hooked is the lock state checked again every
     * [LOCK_RECHECK_MS] while Quick Settings is disabled and the screen is on, so it still comes
     * back after an unlock that no other event reports.
     */
    private fun refreshQuickSettings() {
        val context = guardContext ?: return
        val now = SystemClock.uptimeMillis()
        val goingAway = now < goingAwayUntil
        val justShown = now < shownUntil
        val disable = isFeatureEnabled(FeatureSettings.BLOCK_QUICK_SETTINGS) && !goingAway &&
            ((justShown && isDeviceSecure(context)) || isKeyguardLocked(context))
        if (disable != quickSettingsDisabled) setQuickSettingsDisabled(disable)
        mainHandler.removeCallbacks(lockRecheck)
        if (!keyguardEventsHooked && quickSettingsDisabled == true && isInteractive(context)) {
            mainHandler.postDelayed(lockRecheck, LOCK_RECHECK_MS)
        }
    }

    private val lockRecheck = Runnable { refreshQuickSettings() }

    /** Follows a keyguard event: now, and again at [KEYGUARD_EVENT_FOLLOW_UPS_MS] as the system catches up. */
    private val keyguardFollowUp = Runnable { refreshQuickSettings() }

    private fun onKeyguardEvent() {
        mainHandler.removeCallbacks(keyguardFollowUp)
        mainHandler.post(keyguardFollowUp)
        val now = SystemClock.uptimeMillis()
        for (delay in KEYGUARD_EVENT_FOLLOW_UPS_MS) mainHandler.postAtTime(keyguardFollowUp, now + delay)
    }

    private fun keyguardGoingAway(now: Long) {
        // A screen going off cancels a running unlock animation, which ends in these same calls:
        // that is no unlock.
        if (guardContext?.let(::isInteractive) == false) return
        goingAwayUntil = now + GOING_AWAY_GRACE_MS
        shownUntil = 0L
    }

    /** Whether KeyguardViewMediator will lock once the screen is off (its `mPendingLock`). */
    private fun pendingLock(mediator: Any?): Boolean {
        var cls: Class<*>? = mediator?.javaClass
        while (cls != null && cls != Any::class.java) {
            val current: Class<*> = cls
            val field = runCatching { current.getDeclaredField("mPendingLock") }.getOrNull()
            if (field != null) return runCatching { field.isAccessible = true; field.getBoolean(mediator) }.getOrDefault(false)
            cls = current.superclass
        }
        return false
    }

    /** Lets System UI's keyguard event run, then follows the lock state. */
    private val keyguardEventHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val now = SystemClock.uptimeMillis()
            when (chain.executable.name) {
                in KEYGUARD_GOING_AWAY_EVENTS -> keyguardGoingAway(now)
                "notifyKeyguardGoingAway" -> if (chain.args.firstOrNull() == true) keyguardGoingAway(now)
                in KEYGUARD_SHOW_EVENTS -> {
                    goingAwayUntil = 0L
                    shownUntil = now + SHOWN_GRACE_MS
                }
                // The screen is going off and the keyguard will lock as soon as it is (power button
                // locks instantly, or no lock delay): count as shown now. On Android 17 the keyguard's
                // own SHOW comes most of a second later.
                "setPendingLock", "handleNotifyStartedGoingToSleep" -> if (pendingLock(chain.thisObject)) {
                    goingAwayUntil = 0L
                    shownUntil = now + SHOWN_GRACE_MS
                }
                // notifyKeyguardState(showing, occluded): while going away it still says "showing".
                "notifyKeyguardState" -> when (chain.args.firstOrNull()) {
                    true -> if (now >= goingAwayUntil) shownUntil = now + SHOWN_GRACE_MS
                    false -> shownUntil = 0L
                }
            }
            onKeyguardEvent()
            return result
        }
    }

    /**
     * Reports a failed unlock on the lock screen; the call itself runs on unchanged. Its arguments
     * hold no credential (a user id at most), and nothing of them is kept or sent.
     */
    private val failedUnlockHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            runCatching {
                if (!isKeyguardLocked(guardContext ?: systemContext())) return@runCatching
                val owner = chain.executable.declaringClass.name
                val kind = when {
                    "FingerprintManager" in owner || chain.executable.name == "handleFingerprintAuthFailed" ->
                        Reports.KIND_FAILED_FINGERPRINT
                    "FaceManager" in owner || chain.executable.name == "handleFaceAuthFailed" -> Reports.KIND_FAILED_FACE
                    else -> credentialKind(chain.thisObject, chain.args.firstOrNull() as? Int)
                }
                reportBlocked(kind, FAILED_UNLOCK_DEBOUNCE_MS)
            }
            return result
        }
    }

    /** Which kind of credential the user has, from `LockPatternUtils.getCredentialTypeForUser`. */
    private fun credentialKind(lockPatternUtils: Any?, userId: Int?): String {
        val type = runCatching {
            lockPatternUtils!!.javaClass.getMethod("getCredentialTypeForUser", Int::class.javaPrimitiveType)
                .invoke(lockPatternUtils, userId ?: 0) as Int
        }.getOrNull()
        return when (type) {
            CREDENTIAL_TYPE_PATTERN -> Reports.KIND_WRONG_PATTERN
            CREDENTIAL_TYPE_PIN -> Reports.KIND_WRONG_PIN
            else -> Reports.KIND_WRONG_PASSWORD
        }
    }

    /** Reports an app's or a command's request for Quick Settings that the lock refuses; it runs on unchanged. */
    private val quickSettingsRequestHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (quickSettingsDisabled == true) reportBlocked(Reports.KIND_QUICK_SETTINGS)
            return chain.proceed()
        }
    }

    private fun isInteractive(context: Context): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)

    /** Sets or clears DISABLE2_QUICK_SETTINGS on this module's disable record. */
    private fun setQuickSettingsDisabled(disable: Boolean) {
        runCatching {
            val statusBar = statusBar ?: error("no status bar service")
            statusBar.javaClass.getMethod("disable2", Int::class.javaPrimitiveType)
                .invoke(statusBar, if (disable) DISABLE2_QUICK_SETTINGS else DISABLE2_NONE)
        }.onSuccess {
            val changed = quickSettingsDisabled != disable
            quickSettingsDisabled = disable
            if (changed) log(Log.INFO, TAG, if (disable) "Quick Settings disabled: the device is locked." else "Quick Settings enabled again.")
        }.onFailure { log(Log.ERROR, TAG, "Quick Settings: the status bar service refused the change", it) }
    }

    /**
     * Refuses the Quick Settings scene or overlay over the lock screen (Android 16+). Every hooked
     * method takes the destination first; the shade itself, Lockscreen, Bouncer, Gone and the rest
     * go through, so notifications keep working.
     */
    private val sceneContainerEntryHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            val destination = chain.args.firstOrNull()
            if (destination != null && isQuickSettingsKey(destination) &&
                isFeatureEnabled(FeatureSettings.BLOCK_QUICK_SETTINGS) && isKeyguardLocked(systemContext())
            ) {
                log(Log.INFO, TAG, "Blocked the '${sceneKeyName(destination)}' scene over the lock screen (${chain.executable.name}).")
                reportBlocked(Reports.KIND_QUICK_SETTINGS)
                return blockedResult(chain.executable)
            }
            // Asked for Quick Settings while unlocked: make sure this module's flag is not in the way.
            if (destination != null && quickSettingsDisabled == true && isQuickSettingsKey(destination)) mainHandler.post { refreshQuickSettings() }
            return chain.proceed()
        }
    }

    /** A scene or overlay key's debug name (SceneTransitionLayout `Key.debugName`), else its toString. */
    private fun sceneKeyName(key: Any): String =
        runCatching { key.javaClass.getMethod("getDebugName").invoke(key) as? String }.getOrNull()
            ?: key.toString()

    /** Scenes.QuickSettings and Overlays.QuickSettingsShade, matched on the debug name. */
    private fun isQuickSettingsKey(key: Any): Boolean =
        "quicksettings" in sceneKeyName(key).lowercase().filter(Char::isLetterOrDigit)

    /**
     * With Quick Settings disabled the legacy shade still draws its panel where the tiles would
     * be: the notifications panel, and the scrim behind, which then serves as the Quick Settings
     * background and is clipped to a rounded panel above the notifications. While this module
     * keeps Quick Settings disabled, the lock screen and the shade pulled down over it keep the
     * notifications panel clear and fade the scrim behind out as the shade opens, so only the
     * notifications show over the wallpaper. `setScrimAlpha(scrim, alpha)` sets a scrim,
     * `getCurrentScrimAlpha(scrim)` is where its animations head; both run on the main thread,
     * like the guard.
     */
    private val shadeScrimHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            val controller = chain.thisObject ?: return chain.proceed()
            val scrim = chain.args.firstOrNull() ?: return chain.proceed()
            if (chain.args.size == 2) {
                val alpha = chain.args[1] as? Float ?: return chain.proceed()
                val clear = lockedShadeScrimAlpha(controller, scrim, alpha)
                return if (clear == alpha) chain.proceed() else chain.proceed(arrayOf(scrim, clear))
            }
            val alpha = chain.proceed() as? Float ?: return null
            return lockedShadeScrimAlpha(controller, scrim, alpha)
        }
    }

    /** The alpha [scrim] gets over the locked shade while Quick Settings is disabled, else [alpha]. */
    private fun lockedShadeScrimAlpha(controller: Any, scrim: Any, alpha: Float): Float {
        if (quickSettingsDisabled != true) return alpha
        val state = (fieldValue(controller, "mState") as? Enum<*>)?.name
        if (state !in LOCKED_SHADE_SCRIM_STATES) return alpha
        return when {
            scrim === fieldValue(controller, "mNotificationsScrim") -> 0f
            scrim === fieldValue(controller, "mScrimBehind") -> {
                // The lock screen's own dim while the shade is closed, none once it is open.
                val opened = if (state == "KEYGUARD") fieldValue(controller, "mTransitionToFullShadeProgress") as? Float ?: 0f else 1f
                (fieldValue(controller, "mScrimBehindAlphaKeyguard") as? Float)?.let { minOf(alpha, it * (1f - opened)) } ?: alpha
            }
            else -> alpha
        }
    }

    /** A ScrimController field by name; null when this build has no such field. */
    private fun fieldValue(target: Any, name: String): Any? = runCatching {
        scrimFields.getOrPut(name) { target.javaClass.getDeclaredField(name).apply { isAccessible = true } }.get(target)
    }.getOrNull()

    private val scrimFields = HashMap<String, Field>()

    // ---------------------------------------------------------------------------------------
    // Power menu
    // ---------------------------------------------------------------------------------------

    private val systemServerPowerMenuHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            // GlobalActions#showDialog receives "keyguard showing" as its first argument.
            val keyguardShowing = chain.args.firstOrNull() == true
            if (isFeatureEnabled(FeatureSettings.BLOCK_POWER_MENU) && (keyguardShowing || isSystemServerLocked(chain.thisObject))) {
                log(Log.INFO, TAG, "Blocked the power menu (${chain.executable.name}).")
                reportBlocked(Reports.KIND_POWER_MENU)
                return blockedResult(chain.executable)
            }
            return chain.proceed()
        }
    }

    private val systemUiPowerMenuHooker = object : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (isFeatureEnabled(FeatureSettings.BLOCK_POWER_MENU) && isKeyguardLocked(systemContext())) {
                acknowledgeGlobalActions(chain)
                log(Log.INFO, TAG, "Blocked the power menu in SystemUI (${chain.executable.name}).")
                reportBlocked(Reports.KIND_POWER_MENU)
                return blockedResult(chain.executable)
            }
            return chain.proceed()
        }
    }

    /** PhoneWindowManager answers directly; other policy classes go through [KeyguardManager]. */
    private fun isSystemServerLocked(target: Any?): Boolean {
        val direct = runCatching { target?.javaClass?.getMethod("isKeyguardLocked")?.invoke(target) as? Boolean }.getOrNull()
        return direct ?: isKeyguardLocked(fieldValues(target).filterIsInstance<Context>().firstOrNull() ?: systemContext())
    }

    /**
     * Reports the refused menu as shown and then hidden. Without this, system_server waits a few
     * seconds for SystemUI and then opens its own legacy power menu.
     */
    private fun acknowledgeGlobalActions(chain: Chain) {
        val candidates = chain.args.asSequence() + sequenceOf(chain.thisObject) + fieldValues(chain.thisObject)
        for (candidate in candidates) {
            val manager = candidate?.let { globalActionsManagerType(it.javaClass) } ?: continue
            for (name in arrayOf("onGlobalActionsShown", "onGlobalActionsHidden")) {
                runCatching { manager.getMethod(name).invoke(candidate) }
            }
            return
        }
    }

    private fun globalActionsManagerType(cls: Class<*>): Class<*>? {
        val pending = ArrayDeque<Class<*>>()
        generateSequence<Class<*>>(cls) { it.superclass }.forEach { pending += it.interfaces }
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (type.simpleName == GLOBAL_ACTIONS_MANAGER) return type
            pending += type.interfaces
        }
        return null
    }

    /** A harmless value for a skipped call: false for a check, nothing for an action. */
    private fun blockedResult(executable: Executable): Any? = when ((executable as? Method)?.returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }

    // ---------------------------------------------------------------------------------------
    // Module status (this app's own process)
    // ---------------------------------------------------------------------------------------

    private val moduleActiveHooker = object : Hooker {
        override fun intercept(chain: Chain): Any = true
    }

    /** Makes [MainActivity.isModuleActive] return true; its callers are deoptimized so it is not inlined. */
    private fun installModuleStatusHook(classLoader: ClassLoader) {
        val activity = loadClassOrNull(classLoader, MainActivity::class.java.name) ?: return
        val method = activity.declaredMethods.firstOrNull { it.name == "isModuleActive" } ?: return
        if (hookSafely(method, moduleActiveHooker)) {
            activity.declaredMethods.filter { it != method }.forEach { deoptimize(it) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reports to the app (see Reports)
    // ---------------------------------------------------------------------------------------

    /** Tells the app a protection refused [kind] while locked; a burst of the same kind counts once. */
    private fun reportBlocked(kind: String, debounceMs: Long = REPORT_DEBOUNCE_MS) {
        val now = SystemClock.elapsedRealtime()
        val last = lastReported[kind]
        if (last != null && now - last < debounceMs) return
        lastReported[kind] = now
        val time = System.currentTimeMillis()
        reportHandler.post {
            send(systemContext(), Intent(Reports.ACTION_BLOCKED).putExtra(Reports.EXTRA_KIND, kind).putExtra(Reports.EXTRA_TIME, time))
        }
    }

    /** Tells the app which layers this process installed. */
    private fun sendStatus(context: Context?, process: String) {
        val layers = synchronized(layerStatus) { layerStatus.map { (layer, state) -> "$layer: $state" } }
        val intent = Intent(Reports.ACTION_STATUS)
            .putExtra(Reports.EXTRA_PROCESS, process)
            .putExtra(Reports.EXTRA_TIME, System.currentTimeMillis())
            .putStringArrayListExtra(Reports.EXTRA_LAYERS, ArrayList(layers))
        reportHandler.post { send(context, intent) }
    }

    /** system_server is far from ready for broadcasts when it is hooked: report once boot completes. */
    private fun reportSystemServerStatus(attempt: Int = 0) {
        reportHandler.postDelayed({
            val booted = runCatching { systemPropertiesGet?.invoke(null, "sys.boot_completed", "") == "1" }.getOrDefault(false)
            val context = systemContext()
            when {
                booted && context != null -> sendStatus(context, Reports.PROCESS_SYSTEM_SERVER)
                attempt < STATUS_RETRIES -> reportSystemServerStatus(attempt + 1)
            }
        }, STATUS_RETRY_MS)
    }

    /**
     * Sends a report to the app's [Reports.RECEIVER_CLASS] in the current user, reaching it even
     * before it was first opened. This runs inside System UI or system_server, which hold
     * INTERACT_ACROSS_USERS; lint only sees the module app's manifest, which doesn't need it.
     */
    @SuppressLint("MissingPermission")
    private fun send(context: Context?, intent: Intent) {
        if (context == null) return
        intent.setClassName(moduleApplicationInfo.packageName, Reports.RECEIVER_CLASS)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        runCatching { context.sendBroadcastAsUser(intent, currentUser()) }
            .onFailure { log(Log.WARN, TAG, "Could not send ${intent.action} to the app", it) }
    }

    /** UserHandle.CURRENT (hidden), else this process's own user. */
    private fun currentUser(): UserHandle =
        runCatching { UserHandle::class.java.getField("CURRENT").get(null) as UserHandle }.getOrNull()
            ?: android.os.Process.myUserHandle()

    // ---------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------

    /** Whether [classLoader] holds SystemUI's own code: any class this module knows how to hook. */
    private fun isSystemUiLoader(classLoader: ClassLoader): Boolean =
        (SCENE_CONTAINER_ENTRY.keys + SHADE_SCRIM.keys + SYSTEM_UI_POWER_MENU.keys).any { loadClassOrNull(classLoader, it) != null }

    private fun installHooks(
        classLoader: ClassLoader,
        targets: Map<String, Set<String>>,
        hooker: Hooker,
        layer: String,
        everyRelease: Boolean = false,
    ): Int {
        val methods = targets.flatMap { (className, names) ->
            loadClassOrNull(classLoader, className)?.declaredMethods
                ?.filter { it.name in names && !Modifier.isAbstract(it.modifiers) }
                .orEmpty()
        }
        val count = methods.count { hookSafely(it, hooker) }
        when {
            count > 0 -> log(Log.INFO, TAG, "$layer: $count hook(s) installed.")
            everyRelease -> log(Log.WARN, TAG, "$layer: no known target on this build, layer inactive.")
            else -> log(Log.INFO, TAG, "$layer: not part of this build.")
        }
        layerStatus[layer] = when {
            count > 0 -> "$count hook(s)"
            everyRelease -> "INACTIVE, no known target on this build"
            else -> "not part of this build"
        }
        return count
    }

    private fun hookSafely(method: Method, hooker: Hooker): Boolean {
        if (!hookedMethods.add(method)) return true
        return try {
            hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker)
            true
        } catch (t: Throwable) {
            hookedMethods.remove(method)
            log(Log.ERROR, TAG, "Could not hook ${method.declaringClass.name}#${method.name}", t)
            false
        }
    }

    /** Maps a hooked method back to its hooker; used to carry hooks across a hot reload. */
    private fun hookerFor(executable: Executable): Hooker? {
        val owner = executable.declaringClass.name
        val name = executable.name
        return when {
            SCENE_CONTAINER_ENTRY[owner]?.contains(name) == true -> sceneContainerEntryHooker
            SHADE_SCRIM[owner]?.contains(name) == true -> shadeScrimHooker
            KEYGUARD_STATE_EVENTS[owner]?.contains(name) == true -> keyguardEventHooker
            QUICK_SETTINGS_REQUESTS[owner]?.contains(name) == true -> quickSettingsRequestHooker
            FAILED_UNLOCK_EVENTS[owner]?.contains(name) == true -> failedUnlockHooker
            name == "isModuleActive" -> moduleActiveHooker
            SYSTEM_SERVER_POWER_MENU[owner]?.contains(name) == true -> systemServerPowerMenuHooker
            SYSTEM_UI_POWER_MENU[owner]?.contains(name) == true -> systemUiPowerMenuHooker
            else -> null
        }
    }

    private fun loadClassOrNull(classLoader: ClassLoader, name: String): Class<*>? = try {
        classLoader.loadClass(name)
    } catch (ignored: ClassNotFoundException) {
        null
    } catch (ignored: LinkageError) {
        null
    }

    /**
     * Whether a protection is on: the master property must allow it, and so must its switch in
     * the app. Anything that can't be read counts as on.
     */
    private fun isFeatureEnabled(key: String): Boolean =
        isProtectionEnabled() &&
            (runCatching { featureSettings()?.getBoolean(key, FeatureSettings.DEFAULT_ON) }.getOrNull() ?: FeatureSettings.DEFAULT_ON)

    /** The feature switches, fetched from the framework once it can provide them. */
    private fun featureSettings(): SharedPreferences? = featureSettings ?: try {
        getRemotePreferences(FeatureSettings.GROUP).also { featureSettings = it }
    } catch (t: Throwable) {
        if (!featureSettingsFailureLogged) {
            featureSettingsFailureLogged = true
            log(Log.WARN, TAG, "The feature switches can't be read, so every protection stays on.", t)
        }
        null
    }

    /** Defaults to enabled when the property is unset or unreadable. */
    private fun isProtectionEnabled(): Boolean =
        runCatching { systemPropertiesGetBoolean?.invoke(null, TOGGLE_PROPERTY, true) as? Boolean }.getOrNull() ?: true

    /** True whenever the keyguard is showing, including while an app is shown over it. */
    /** Whether a PIN, pattern or password protects the phone; without one the keyguard never locks. */
    private fun isDeviceSecure(context: Context): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java).isDeviceSecure
    }.getOrDefault(true)

    private fun isKeyguardLocked(context: Context?): Boolean = runCatching {
        context?.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    /** Non-null instance field values of [target], walking up its class hierarchy lazily. */
    private fun fieldValues(target: Any?): Sequence<Any> = sequence {
        var cls: Class<*>? = target?.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                runCatching { field.isAccessible = true; field.get(target) }.getOrNull()?.let { yield(it) }
            }
            cls = cls.superclass
        }
    }

    @delegate:SuppressLint("PrivateApi") // Runs inside SystemUI and system_server, which may use hidden APIs.
    private val activityThread: Class<*>? by lazy { runCatching { Class.forName("android.app.ActivityThread") }.getOrNull() }

    /** This process's name, or null if it can't be told yet. */
    private fun processName(): String? = runCatching { Application.getProcessName() }.getOrNull()

    /** The process's application, or null while it is not set up yet. */
    private fun currentApplication(): Context? = runCatching {
        activityThread?.getMethod("currentApplication")?.invoke(null) as? Context
    }.getOrNull()

    /** The process's application, else its system context: either answers [KeyguardManager]. */
    private fun systemContext(): Context? = currentApplication() ?: runCatching {
        activityThread?.getMethod("currentActivityThread")?.invoke(null)
            ?.let { activityThread?.getMethod("getSystemContext")?.invoke(it) as? Context }
    }.getOrNull()
}
