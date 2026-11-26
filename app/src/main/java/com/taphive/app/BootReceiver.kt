package com.taphive.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Resumes capture after a reboot, but only when the user opted in AND capture
 * was left running before the reboot. Both checks are required: booting the
 * device alone must never silently start the VPN.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.startAtBoot || !prefs.captureWanted) return
        // Must be synchronous: BOOT_COMPLETED grants a short window for FGS starts.
        CaptureService.start(app)
    }
}
