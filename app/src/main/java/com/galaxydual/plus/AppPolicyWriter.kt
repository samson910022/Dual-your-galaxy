// MIT License - Original code by Galaxy-Dual-app-Plus contributors.
package com.galaxydual.plus

// App-side RemotePreferences write path for the Hook policy editor.
//
// Vector (libxposed) delivers its binder via a forged ContentProvider call
// from system uid; no permission is required and none is declared. The write
// is authenticated daemon-side (callingUid % PER_USER_RANGE == module appId),
// never here. Every entry point on this object is total (never throws) so a
// binder-thread callback or a UI click can never crash the process; failures
// fail closed and surface as null/empty results.
//
// Group/key are pinned to match the hook reader (RemotePolicyState):
// GROUP = "galaxydual_policy", KEY_PACKAGES = "packages", value = StringSet.
// Policy semantics: union-only whitelist seed; empty set = observe-only.
import android.util.Log
import com.galaxydual.hook.PolicySource
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

internal object AppPolicyWriter {
    private const val GROUP = "galaxydual_policy"
    private const val KEY_PACKAGES = "packages"
    private const val TAG = "GalaxyDualPolicy"

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var bindError: String? = null

    // called from GalaxyDualApplication.onCreate; NEVER throws
    fun registerListener() {
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    bindError = null
                }

                override fun onServiceDied(s: XposedService) {
                    if (service === s) service = null
                }
            })
        } catch (t: Throwable) {
            bindError = t.message
            Log.w(TAG, "registerListener failed: ${t.message}")
        }
    }

    fun isAvailable(): Boolean = service != null

    fun unavailableReason(): String? = bindError

    // reads from LOCAL snapshot (service-side prefs), not brokered round trip
    fun currentPackages(): Set<String> = try {
        val s = service ?: return emptySet()
        s.getRemotePreferences(GROUP)
            .getStringSet(KEY_PACKAGES, emptySet<String>())
            .orEmpty()
            .filterIsInstance<String>()
            .filter { PolicySource.isValidPackage(it) }
            .toCollection(LinkedHashSet())
    } catch (_: Throwable) {
        emptySet()
    }

    fun setPackages(packages: Set<String>): Set<String>? = try {
        val s = service ?: return null
        val sanitized = linkedSetOf<String>().apply {
            for (p in packages) if (PolicySource.isValidPackage(p)) add(p)
        }
        // commit() (not apply()): synchronous binder round-trip; false means
        // the daemon did not ack, so the write failure surfaces in the UI.
        val ok = s.getRemotePreferences(GROUP)
            .edit()
            .putStringSet(KEY_PACKAGES, sanitized)
            .commit()
        if (ok) sanitized else null
    } catch (t: Throwable) {
        bindError = t.message
        Log.w(TAG, "setPackages failed: ${t.message}")
        null
    }
}
