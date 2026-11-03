package com.galaxydual.plus

// Placeholder activity: shows privilege state, no real dual-app calls yet.
import android.app.Activity
import android.os.Bundle
import com.galaxydual.core.Privilege
import com.galaxydual.core.routePrivilege

// Minimal skeleton activity kept dependency-free for CI lint.
class MainActivity : Activity() {
    // Inflates a placeholder view; real UI will observe PrivilegeRouter later.
    override fun onCreate(savedInstanceState: Bundle?) {
        // Standard activity init; probe flags are stubbed until Shizuku wiring lands.
        super.onCreate(savedInstanceState)
        val privilege: Privilege = routePrivilege(hookActive = false, shizukuAlive = false)
        // Read-only placeholder: title reflects current privilege level.
        title = "GalaxyDual Plus ($privilege)"
    }
}
