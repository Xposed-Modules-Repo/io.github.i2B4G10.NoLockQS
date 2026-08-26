package com.example.nolockqs

import android.app.KeyguardManager
import android.content.Context
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*

/**
 * NoLockQS Xposed Module
 * Optimized for JingMatrix/Vector API 102.
 * Features: Dynamic dead-zone scaling, SystemProperties toggle, and clean execution.
 */
class NoLockXposedModule : XposedModule() {

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val ROOT_WINDOW_CLASS = "com.android.systemui.shade.NotificationShadeWindowView"
        const val TOGGLE_PROPERTY = "persist.sys.nolockqs.enabled"
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)

        if (param.packageName == "com.example.nolockqs") {
            applySelfHook(param.classLoader)
        }

        if (param.packageName == SYSTEM_UI_PACKAGE) {
            applyRootWindowHook(param.classLoader)
        }
    }

    private fun applySelfHook(classLoader: ClassLoader) {
        try {
            val mainActivityClass = classLoader.loadClass("com.example.nolockqs.MainActivity")
            val isModuleActiveMethod = mainActivityClass.declaredMethods.find { it.name == "isModuleActive" }
            isModuleActiveMethod?.let { method ->
                hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        return true
                    }
                })
            }
        } catch (ignored: Exception) {
            // Silently ignore
        }
    }

    private fun applyRootWindowHook(classLoader: ClassLoader) {
        try {
            val windowViewClass = classLoader.loadClass(ROOT_WINDOW_CLASS)
            val dispatchMethod = windowViewClass.declaredMethods.find {
                it.name == "dispatchTouchEvent" && it.parameterCount == 1
            }

            dispatchMethod?.let { method ->
                hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // 1. Check if the module is toggled ON via System Properties
                        if (!isModuleEnabled()) {
                            return chain.proceed()
                        }

                        val event = chain.args[0] as? MotionEvent
                        val view = chain.thisObject as? View

                        if (event != null && view != null) {
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                // 2. Dynamically calculate the status bar height
                                val dynamicDeadZone = getStatusBarHeight(view.context)

                                // 3. Intercept and block touches within the dead-zone
                                if (event.rawY <= dynamicDeadZone) {
                                    if (isDeviceSecurelyLocked(view.context)) {
                                        return true // Consume touch event
                                    }
                                }
                            }
                        }
                        return chain.proceed()
                    }
                })
            }
        } catch (ignored: Exception) {
            // Silently ignore hook failures in production to prevent bootloops
        }
    }

    /**
     * Dynamically fetches the device's exact status bar height in pixels.
     * Falls back to a safe 150f if the system resource is unavailable.
     */
    private fun getStatusBarHeight(context: Context): Float {
        return try {
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                context.resources.getDimensionPixelSize(resourceId).toFloat()
            } else {
                150f
            }
        } catch (e: Exception) {
            150f
        }
    }

    /**
     * Reads a persistent system property to act as a bug-free toggle switch.
     * Defaults to true (enabled) if the property has never been set.
     */
    private fun isModuleEnabled(): Boolean {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getBooleanMethod = systemPropertiesClass.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            getBooleanMethod.invoke(null, TOGGLE_PROPERTY, true) as Boolean
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Verifies if the device is currently locked securely.
     */
    private fun isDeviceSecurelyLocked(context: Context): Boolean {
        return try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.isKeyguardLocked == true
        } catch (e: Exception) {
            false
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean = true
    override fun onHotReloaded(param: HotReloadedParam) {}
}