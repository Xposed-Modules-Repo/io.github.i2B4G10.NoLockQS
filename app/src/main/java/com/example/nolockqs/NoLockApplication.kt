package com.example.nolockqs

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Connects to the Xposed framework's service, which stores the feature switches where the hooks
 * in System UI and System Framework can read them. The framework only connects while NoLockQS is
 * enabled in it.
 */
class NoLockApplication : Application(), XposedServiceHelper.OnServiceListener {

    /** The framework's copy of the feature switches, or null while it isn't connected. */
    var featureSettings: SharedPreferences? = null
        private set

    /** The packages NoLockQS is enabled for in the framework, or null while it isn't connected. */
    var scope: List<String>? = null
        private set

    /** Runs on the main thread whenever [featureSettings] or [scope] change. */
    var onFeatureSettingsChanged: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var service: XposedService? = null

    override fun onCreate() {
        super.onCreate()
        AppTheme.apply(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        val settings = runCatching { service.getRemotePreferences(FeatureSettings.GROUP) }.getOrNull()
        val scope = runCatching { service.scope }.getOrNull()
        mainHandler.post {
            this.service = service
            featureSettings = settings
            this.scope = scope
            onFeatureSettingsChanged?.invoke()
        }
    }

    override fun onServiceDied(service: XposedService) {
        mainHandler.post {
            this.service = null
            featureSettings = null
            scope = null
            onFeatureSettingsChanged?.invoke()
        }
    }

    /**
     * Asks the framework to add [packages] to the scope; it asks the user to confirm. [onResult]
     * runs on the main thread with null once they are added, or with the framework's reason.
     */
    fun requestScope(packages: List<String>, onResult: (String?) -> Unit) {
        val service = service ?: return onResult("")
        val listener = object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) {
                val scope = runCatching { service.scope }.getOrNull()
                mainHandler.post {
                    this@NoLockApplication.scope = scope
                    onFeatureSettingsChanged?.invoke()
                    onResult(null)
                }
            }

            override fun onScopeRequestFailed(message: String) {
                mainHandler.post { onResult(message) }
            }
        }
        runCatching { service.requestScope(packages, listener) }
            .onFailure { failure -> onResult(failure.message.orEmpty()) }
    }
}
