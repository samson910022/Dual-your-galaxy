// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.plus

// Minimal launcher activity kept free of Compose for cheap CI placeholder builds.
// Uses android.app.Activity plus a plain TextView; no Compose dependency.
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.galaxydual.core.DualRepository
import com.galaxydual.core.NoOpGateway
import com.galaxydual.core.routePrivilege

// MVP entry point: probes privilege, wires a placeholder repository, shows status text.
// TODO: bind Shizuku (moe.shizuku.manager.permission.APIV2) here and re-probe with
// TODO: shizukuAlive; prefer the LSPosed hook channel (ROOT) before falling back to SHELL.
class MainActivity : Activity() {
    // Builds the MVP screen; real Shizuku binding and list UI land in the next phase.
    override fun onCreate(savedInstanceState: Bundle?) {
        // Standard activity init; probes are stubbed until Shizuku wiring lands.
        super.onCreate(savedInstanceState)
        // Pure-function privilege routing: hookActive maps to LSPosed, shizukuAlive to Shizuku.
        val privilege = routePrivilege(hookActive = false, shizukuAlive = false)
        // Placeholder repository with no privilege; replaced by Shell/Root gateway later.
        val repository = DualRepository(NoOpGateway())
        // Pure-Kotlin view model holding MVP state; no Android dependency inside.
        val viewModel = DualViewModel(repository)
        // Read-only placeholder text describing current state and next steps.
        val status = TextView(this)
        status.text = "Galaxy Dual Plus ($privilege)\n" +
            "Profiles: ${viewModel.state.profiles.size}\n" +
            "TODO: bind Shizuku APIV2; LSPosed hook channel first, Shizuku shell fallback."
        setContentView(status)
        // Title mirrors the routed privilege for quick manual verification.
        title = "GalaxyDual Plus ($privilege)"
    }
}
