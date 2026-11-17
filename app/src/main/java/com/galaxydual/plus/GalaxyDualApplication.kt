// MIT License - Original code by Galaxy-Dual-app-Plus contributors.
package com.galaxydual.plus

// Plain Application subclass; the only wiring it owns is registering the
// XposedServiceHelper listener once per process (Vector delivers its binder
// via a forged ContentProvider call on uid transitions). Never throws.
import android.app.Application

class GalaxyDualApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPolicyWriter.registerListener()
    }
}
