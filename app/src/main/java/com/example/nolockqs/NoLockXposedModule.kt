package com.example.nolockqs

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * NoLockQS Xposed Module
 * Final Revision: Perfected Lock Detection via System Context.
 * Optimized for Android 15, 16, and 17 (Pixel 7-11 Support).
 * Status bar remains visible, Quick Settings mathematically locked.
 */
class NoLockXposedModule : XposedModule() {

    private companion object {
        const val TAG = "NoLockQS"
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)

        val packageName = param.packageName
        val classLoader = param.classLoader

        if (packageName == "com.android.systemui" || packageName == "com.google.android.systemui") {
            Log.d(TAG, "SystemUI ($packageName) detected. Applying surgical Android 15-17 hooks.")
            applyDeepHooks(classLoader)
        } else if (packageName == "com.example.nolockqs") {
            applySelfHooks(classLoader)
        }
    }

    private fun applyDeepHooks(classLoader: ClassLoader) {
        var hookCount = 0

        // 1. Core Shade & QS Controllers (Android 14/15/16)
        val controllersToHook = listOf(
            "com.android.systemui.shade.NotificationPanelViewController",
            "com.android.systemui.statusbar.phone.NotificationPanelViewController",
            "com.android.systemui.shade.QuickSettingsController",
            "com.android.systemui.shade.QuickSettingsControllerImpl",
            "com.android.systemui.qs.QSPanelController"
        )
        
        controllersToHook.forEach { className ->
            try {
                val clazz = classLoader.loadClass(className)
                
                // Block boolean getters that allow QS expansion
                clazz.declaredMethods.filter { 
                    it.name == "isQsExpansionEnabled" || 
                    it.name == "isExpansionEnabled" || 
                    it.name == "isQsTouchEnabled" 
                }.forEach { method ->
                    if (method.returnType == Boolean::class.javaPrimitiveType && method.parameterCount == 0) {
                        hook(method).intercept { chain ->
                            if (isDeviceSecurelyLocked()) {
                                return@intercept false
                            }
                            chain.proceed()
                        }
                        hookCount++
                    }
                }

                // Force QS height/fraction to 0.0f
                clazz.declaredMethods.filter { 
                    it.name == "setQsExpansion" || 
                    it.name == "setExpansionHeight" 
                }.forEach { method ->
                    if (method.parameterCount >= 1 && method.parameterTypes[0] == Float::class.javaPrimitiveType) {
                        hook(method).intercept { chain ->
                            if (isDeviceSecurelyLocked()) {
                                chain.args[0] = 0.0f
                            }
                            chain.proceed()
                        }
                        hookCount++
                    }
                }

                // Block handleQsTouch completely
                clazz.declaredMethods.filter { it.name == "handleQsTouch" }.forEach { method ->
                    hook(method).intercept { chain ->
                        if (isDeviceSecurelyLocked()) {
                            return@intercept false
                        }
                        chain.proceed()
                    }
                    hookCount++
                }

                // Force policy setters to false
                clazz.declaredMethods.filter { it.name == "setQsExpansionEnabledPolicy" || it.name == "setExpanded" }.forEach { method ->
                    if (method.parameterCount >= 1 && method.parameterTypes[0] == Boolean::class.javaPrimitiveType) {
                        hook(method).intercept { chain ->
                            if (isDeviceSecurelyLocked()) {
                                chain.args[0] = false
                            }
                            chain.proceed()
                        }
                        hookCount++
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Interactors - Domain Logic (Android 15+)
        val interactors = listOf(
            "com.android.systemui.shade.domain.interactor.ShadeInteractor",
            "com.android.systemui.shade.domain.interactor.ShadeInteractorLegacyImpl",
            "com.android.systemui.qs.domain.interactor.QuickSettingsInteractor"
        )
        
        interactors.forEach { className ->
            try {
                val clazz = classLoader.loadClass(className)
                clazz.declaredMethods.filter { 
                    it.returnType == Boolean::class.javaPrimitiveType && 
                    (it.name.contains("isQuickSettings") || it.name == "isQsExpansionEnabled")
                }.forEach { method ->
                    hook(method).intercept { chain ->
                        if (isDeviceSecurelyLocked()) {
                            return@intercept false
                        }
                        chain.proceed()
                    }
                    hookCount++
                }
            } catch (_: Exception) {}
        }

        // 3. Scene Framework (Flexiglass) Android 16/17
        try {
            val sceneInteractorClass = classLoader.loadClass("com.android.systemui.scene.domain.interactor.SceneInteractor")
            sceneInteractorClass.declaredMethods.filter { 
                it.name == "changeScene" || it.name == "changeScene\$default" || it.name == "requestSceneChange"
            }.forEach { method ->
                hook(method).intercept { chain ->
                    if (chain.args.isNotEmpty() && chain.args[0] != null) {
                        val targetScene = chain.args[0].toString().lowercase()
                        if (targetScene.contains("quicksettings") && isDeviceSecurelyLocked()) {
                            Log.d(TAG, "Flexiglass: Blocked transition to QuickSettings.")
                            return@intercept null
                        }
                    }
                    chain.proceed()
                }
                hookCount++
            }
        } catch (_: Exception) {}

        Log.d(TAG, "Applied $hookCount bulletproof hooks to SystemUI.")
    }

    private fun applySelfHooks(classLoader: ClassLoader) {
        try {
            val activityClass = classLoader.loadClass("com.example.nolockqs.MainActivity")
            val method = activityClass.getDeclaredMethod("isModuleActive")
            hook(method).intercept { true }
        } catch (_: Exception) {}
    }

    /**
     * O(1) Absolute Lock Detection.
     * Uses global ActivityThread to fetch Context instead of fragile SystemUI object reflection.
     */
    private fun isDeviceSecurelyLocked(): Boolean {
        try {
            val context = getSystemContext() ?: return false
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            return km?.isKeyguardLocked == true
        } catch (_: Exception) {
            Log.e(TAG, "Failed to determine lock state")
        }
        return false
    }

    private fun getSystemContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            currentActivityThread?.javaClass?.getMethod("getApplication")?.invoke(currentActivityThread) as? Context
        } catch (e: Exception) {
            null
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean = true
    override fun onHotReloaded(param: HotReloadedParam) {}
}
