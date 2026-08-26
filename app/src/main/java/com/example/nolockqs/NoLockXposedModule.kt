package com.example.nolockqs

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*

/**
 * NoLockQS Xposed Module
 * Optimized for Vector 2.2 (API 102) on Android 15-17 Pixels (7 through 11).
 * Features: Dynamic QS dead-zone, Power Menu Block, and SystemProperties toggle.
 */
class NoLockXposedModule : XposedModule() {

    private companion object {
        const val TAG = "NoLockQS"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val ROOT_WINDOW_CLASS = "com.android.systemui.shade.NotificationShadeWindowView"
        const val TOGGLE_PROPERTY = "persist.sys.nolockqs.enabled"

        // Classes responsible for the Power Menu across different Android versions
        val GLOBAL_ACTIONS_CLASSES = listOf(
            "com.android.systemui.globalactions.GlobalActionsDialogLite",
            "com.android.systemui.globalactions.GlobalActionsDialog"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)

        if (param.packageName == SYSTEM_UI_PACKAGE) {
            Log.d(TAG, "SystemUI detected. Injecting universal security hooks.")
            applyRootWindowHook(param.classLoader)
            applyPowerMenuHook(param.classLoader)
        }
    }

    /**
     * FEATURE 1: Dynamic Quick Settings Dead-Zone
     */
    private fun applyRootWindowHook(classLoader: ClassLoader) {
        try {
            val windowViewClass = classLoader.loadClass(ROOT_WINDOW_CLASS)
            val dispatchMethod = windowViewClass.declaredMethods.find {
                it.name == "dispatchTouchEvent" && it.parameterCount == 1
            }

            dispatchMethod?.let { method ->
                hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!isModuleEnabled()) return chain.proceed()

                        val event = chain.args[0] as? MotionEvent
                        val view = chain.thisObject as? View

                        if (event != null && view != null && event.action == MotionEvent.ACTION_DOWN) {
                            val dynamicDeadZone = getStatusBarHeight(view.context)

                            // Block touches originating in the status bar area
                            if (event.rawY <= dynamicDeadZone && isDeviceSecurelyLocked(view.context)) {
                                Log.w(TAG, "Blocked QS dropdown swipe.")
                                return true // Consume and destroy the touch event
                            }
                        }
                        return chain.proceed()
                    }
                })
            }
        } catch (ignored: Exception) {}
    }

    /**
     * FEATURE 2: Power Menu (Global Actions) Interceptor
     */
    private fun applyPowerMenuHook(classLoader: ClassLoader) {
        GLOBAL_ACTIONS_CLASSES.forEach { className ->
            try {
                val globalActionsClass = classLoader.loadClass(className)

                // Target any method responsible for rendering the power menu
                val showMethods = globalActionsClass.declaredMethods.filter {
                    it.name.startsWith("show") || it.name == "handleShow"
                }

                showMethods.forEach { method ->
                    hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            if (isModuleEnabled() && isDeviceSecurelyLocked()) {
                                Log.w(TAG, "Blocked Power Menu on lockscreen.")
                                return null // Cancel the dialog from opening
                            }
                            return chain.proceed()
                        }
                    })
                }
            } catch (ignored: Exception) {}
        }
    }

    /**
     * UTILITY: Dynamically fetches the status bar height.
     */
    private fun getStatusBarHeight(context: Context): Float {
        return try {
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                context.resources.getDimensionPixelSize(resourceId).toFloat()
            } else 150f
        } catch (e: Exception) {
            150f
        }
    }

    /**
     * UTILITY: Master kill-switch via System Properties.
     */
    private fun isModuleEnabled(): Boolean {
        return try {
            val sysPropClass = Class.forName("android.os.SystemProperties")
            val getBooleanMethod = sysPropClass.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            getBooleanMethod.invoke(null, TOGGLE_PROPERTY, true) as Boolean
        } catch (e: Exception) {
            true
        }
    }

    /**
     * UTILITY: Bulletproof lock check. Defaults to ActivityThread if no View Context is available.
     */
    private fun isDeviceSecurelyLocked(context: Context? = null): Boolean {
        return try {
            var ctx = context
            if (ctx == null) {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                ctx = activityThreadClass.getMethod("currentApplication").invoke(null) as? Context
            }
            val km = ctx?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.isKeyguardLocked == true
        } catch (e: Exception) {
            false
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean = true
    override fun onHotReloaded(param: HotReloadedParam) {}
}