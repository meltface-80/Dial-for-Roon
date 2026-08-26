package com.roondial.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the media session back after a reboot.
 *
 * Without this the service is started from exactly one place — the activity —
 * so from boot until someone opens the app there is no media session at all,
 * and an assistant asked to pause has nothing to talk to.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        try {
            context.startService(Intent(context, RoonPlaybackService::class.java))
        } catch (e: Exception) {
            // Background start restrictions vary by OEM; the app opening will
            // start it anyway.
            Log.w("BootReceiver", "could not start on boot: ${e.message}")
        }
    }
}
