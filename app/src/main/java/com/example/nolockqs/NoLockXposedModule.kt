package com.example.nolockqs

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field

/**
 * NoLockQS Xposed Module
 * Optimized for Android 15, 16, and 17 (Pixel 7-11 Support).
 * Compatible with LibXposed API 102 (Vector 2.2).
 * Implements anti-obfuscation dynamic hooking for Pixel devices.
 */
class NoLockXposedModule : XposedModule() {

    private companion object {
        const val TAG = "NoLockQS"
        const val DISABLE2_QUICK_SETTINGS = 1
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)

        val packageName = param.packageName
        val classLoader = param.classLoader

        if ((packageName == "com.android.systemui") || (packageName == "com.google.android.systemui")) {
            Log.d(TAG, "SystemUI ($packageName) detected. Applying security hooks.")
            applyDeepHooks(classLoader)
        } else if (packageName == "com.example.nolockqs") {
            applySelfHooks(classLoader)
        }
    }

    private fun applyDeepHooks(classLoader: ClassLoader) {
        var hookCount = 0

        // 1. Direct Touch Interception (The ultimate physical block)
        // If we intercept the touch physics at the parent level, it bypasses all Android 15/16/17 logical bugs
        val shadeClassNames = listOf(
            "com.android.systemui.shade.NotificationPanelViewController",
            "com.android.systemui.statusbar.phone.NotificationPanelViewController"
        )
        shadeClassNames.forEach { className ->
            try {
                val clazz = classLoader.loadClass(className)
                // Intercept the method that determines if the touch should be forwarded to the QS panel
                val interceptMethods = clazz.declaredMethods.filter { 
                    it.name.contains("onInterceptTouchEvent") || it.name.contains("onTouchEvent") 
                }
                interceptMethods.forEach { method ->
                    hook(method).intercept { chain ->
                        val motionEvent = chain.args.find { it is android.view.MotionEvent } as? android.view.MotionEvent
                        if (motionEvent != null && isDeviceSecurelyLocked(chain.thisObject!!)) {
                            // If user is swiping down (Y is increasing) and starting near the top (Y < 200)
                            if (motionEvent.action == android.view.MotionEvent.ACTION_DOWN && motionEvent.y < 200) {
                                // Block the touch event entirely to prevent the shade from recognizing the drag
                                return@intercept false 
                            }
                        }
                        chain.proceed()
                    }
                }
            } catch (_: Exception) {}
        }

        // 1. CommandQueue (System-level flag enforcement)
        try {
            val commandQueueClass = classLoader.loadClass("com.android.systemui.statusbar.CommandQueue")
            val disableMethod = commandQueueClass.declaredMethods.find { 
                it.name == "disable" && (it.parameterCount == 3 || it.parameterCount == 4)
            }
            disableMethod?.let {
                hook(it).intercept { chain ->
                    val thisObject = chain.thisObject ?: return@intercept chain.proceed()
                    if (isDeviceSecurelyLocked(thisObject)) {
                        val state2Index = if (chain.args.size == 4) 2 else 1
                        val state2 = chain.args[state2Index] as Int
                        chain.args[state2Index] = state2 or DISABLE2_QUICK_SETTINGS
                    }
                    chain.proceed()
                }
                hookCount++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook CommandQueue", e)
        }

        // 2. Dynamic Fingerprinting for SceneInteractor (Android 16/17 Flexiglass)
        try {
            var sceneInteractorClass: Class<*>? = null
            try {
                sceneInteractorClass = classLoader.loadClass("com.android.systemui.scene.domain.interactor.SceneInteractor")
            } catch (_: Exception) {}

            sceneInteractorClass?.declaredMethods?.forEach { method ->
                if (method.parameterCount >= 1 && method.parameterTypes[0] != Int::class.javaPrimitiveType) {
                    if (method.name.contains("change") || method.name.contains("scene") || method.name == "a") { 
                        hook(method).intercept { chain ->
                            val targetScene = chain.args[0]?.toString()?.lowercase() ?: ""
                            if ((targetScene.contains("shade") || targetScene.contains("quicksettings")) && 
                                isDeviceSecurelyLocked(chain.thisObject!!)) {
                                Log.d(TAG, "Flexiglass: Blocking $targetScene.")
                                return@intercept null
                            }
                            chain.proceed()
                        }
                        hookCount++
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Dynamic Fingerprinting for Shade Controllers (Android 15+)
        val shadeClassNamesSecondary = listOf(
            "com.android.systemui.shade.NotificationPanelViewController",
            "com.android.systemui.statusbar.phone.NotificationPanelViewController",
            "com.android.systemui.shade.QuickSettingsController",
            "com.android.systemui.shade.QuickSettingsControllerImpl",
        )
        
        shadeClassNamesSecondary.forEach { className ->
            try {
                val clazz = classLoader.loadClass(className)
                clazz.declaredMethods.forEach { method ->
                    val name = method.name
                    // isExpansionEnabled, canPanelBeExpanded, expandToQs, setQsExpansion, updateQsState
                    if (name.contains("Expansion") || name.contains("Expanded") || name.contains("expandToQs") || name == "updateQsState") {
                        if (method.returnType == Boolean::class.javaPrimitiveType) {
                            hook(method).intercept { chain ->
                                if (isDeviceSecurelyLocked(chain.thisObject!!)) false else chain.proceed()
                            }
                            hookCount++
                        } else if (method.parameterCount > 0 && method.parameterTypes[0] == Float::class.javaPrimitiveType) {
                            hook(method).intercept { chain ->
                                if (isDeviceSecurelyLocked(chain.thisObject!!)) {
                                    chain.args[0] = 0.0f // Force to 0 height
                                }
                                chain.proceed()
                            }
                            hookCount++
                        }
                    }
                }

                // Specifically hook setQsExpansionEnabledPolicy if it exists (A15 policy override)
                val setPolicyMethod = clazz.declaredMethods.find { it.name == "setQsExpansionEnabledPolicy" }
                setPolicyMethod?.let {
                    hook(it).intercept { chain ->
                        if (isDeviceSecurelyLocked(chain.thisObject!!)) {
                            chain.args[0] = false // Force the policy to false (expansion disabled)
                        }
                        chain.proceed()
                    }
                    hookCount++
                }

            } catch (_: Exception) {}
        }

        // 4. Interactors - Business Logic Layer
        val interactors = listOf(
            "com.android.systemui.shade.domain.interactor.ShadeInteractor",
            "com.android.systemui.shade.domain.interactor.ShadeInteractorLegacyImpl",
            "com.android.systemui.qs.domain.interactor.QuickSettingsInteractor"
        )
        interactors.forEach { className ->
            try {
                val clazz = classLoader.loadClass(className)
                clazz.declaredMethods.forEach { method ->
                    if (method.returnType == Boolean::class.javaPrimitiveType && 
                        (method.name.contains("isShade") || method.name.contains("isQuickSettings"))) {
                        hook(method).intercept { chain ->
                            if (isDeviceSecurelyLocked(chain.thisObject!!)) false else chain.proceed()
                        }
                        hookCount++
                    }
                }
            } catch (_: Exception) {}
        }

        // 5. CentralSurfacesImpl UI Overrides (Core SystemUI controller)
        try {
            val centralSurfacesClass = classLoader.loadClass("com.android.systemui.statusbar.phone.CentralSurfacesImpl")
            
            // Look for any method related to updating or checking QS expansion
            centralSurfacesClass.declaredMethods.forEach { method ->
                if (method.name.contains("updateQsExpansionEnabled") || method.name.contains("setQsExpansionEnabled")) {
                    hook(method).intercept { chain ->
                        val thisObject = chain.thisObject ?: return@intercept chain.proceed()
                        if (isDeviceSecurelyLocked(thisObject)) {
                            // Some versions take a boolean arg, some don't.
                            if (chain.args.isNotEmpty() && chain.args[0] is Boolean) {
                                chain.args[0] = false
                            } else {
                                // If it doesn't take args, we try to manually poke the controller 
                                // inside the CentralSurfacesImpl (like mNotificationPanelViewController)
                                try {
                                    val shadeControllerField = findFieldByType(thisObject.javaClass, "ShadeViewController")
                                        ?: findFieldByType(thisObject.javaClass, "NotificationPanelViewController")

                                    shadeControllerField?.let { field ->
                                        field.isAccessible = true
                                        val shadeController = field.get(thisObject)
                                        if (shadeController != null) {
                                            val setEnabledMethod = shadeController.javaClass.methods.find { m -> 
                                                m.name == "setQsExpansionEnabled" || m.name == "setQsExpansionEnabledPolicy" 
                                            }
                                            setEnabledMethod?.invoke(shadeController, false)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        chain.proceed()
                    }
                    hookCount++
                }
            }
        } catch (_: Exception) {}

        Log.d(TAG, "Applied $hookCount dynamic hooks to SystemUI.")
    }

    private fun applySelfHooks(classLoader: ClassLoader) {
        try {
            val activityClass = classLoader.loadClass("com.example.nolockqs.MainActivity")
            val method = activityClass.getDeclaredMethod("isModuleActive")
            hook(method).intercept { true }
        } catch (_: Exception) {}
    }

    /**
     * Highly resilient, anti-obfuscation lock detection.
     */
    private fun isDeviceSecurelyLocked(thisObject: Any): Boolean {
        try {
            val classLoader = thisObject.javaClass.classLoader ?: return false

            // Priority 1: KeyguardUpdateMonitor (Singleton via Dependency)
            try {
                val depClass = classLoader.loadClass("com.android.systemui.Dependency")
                val getMethod = depClass.declaredMethods.find { it.name == "get" && it.parameterCount == 1 && it.parameterTypes[0] == Class::class.java }
                val monitorClass = classLoader.loadClass("com.android.keyguard.KeyguardUpdateMonitor")
                
                getMethod?.invoke(null, monitorClass)?.let { monitor ->
                    val isShowingMethod = monitor.javaClass.methods.find { it.name.lowercase().contains("iskeyguardshowing") || it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount == 0 && it.name.length > 5 }
                    if (isShowingMethod?.invoke(monitor) == true) return true
                }
            } catch (_: Exception) {}

            // Priority 2: KeyguardManager (Standard Android API via Context search)
            findContext(thisObject)?.getSystemService(Context.KEYGUARD_SERVICE)?.let { km ->
                if ((km as KeyguardManager).isKeyguardLocked) return true
            }
            
            // Priority 3: Fallback state checks via reflection on StatusBarStateController types
            findStatusBarStateControllerField(thisObject.javaClass)?.let {
                it.isAccessible = true
                it.get(thisObject)?.let { controller ->
                    val stateMethod = controller.javaClass.methods.find { m -> m.name == "getState" || (m.returnType == Int::class.javaPrimitiveType && m.parameterCount == 0) }
                    val state = stateMethod?.invoke(controller) as? Int
                    if (state == 1 || state == 2) return true // 1: KEYGUARD, 2: SHADE_LOCKED
                }
            }

        } catch (_: Exception) {}
        
        return false
    }

    private fun findStatusBarStateControllerField(clazz: Class<*>): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            for (field in current.declaredFields) {
                if (field.type.name.contains("StatusBarStateController", ignoreCase = true)) return field
            }
            current = current.superclass
        }
        return null
    }

    private fun findFieldByType(clazz: Class<*>, typeNamePart: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            for (field in current.declaredFields) {
                if (field.type.name.contains(typeNamePart, ignoreCase = true)) return field
            }
            current = current.superclass
        }
        return null
    }

    private fun findContext(obj: Any): Context? {
        var current: Class<*>? = obj.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (field.type == Context::class.java || field.name == "mContext") {
                    try {
                        field.isAccessible = true
                        return field.get(obj) as? Context
                    } catch (_: Exception) {}
                }
            }
            current = current.superclass
        }
        return null
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean = true
    override fun onHotReloaded(param: HotReloadedParam) {}
}
