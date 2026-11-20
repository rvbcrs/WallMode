package io.github.rvbcrs.wallmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action

        Log.d(TAG, "Received broadcast: action=$action")

        if (action != Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Ignoring broadcast: not BOOT_COMPLETED")
            return
        }

        Log.i(TAG, "Device boot completed")

        val settings = KioskPreferences(context).load()

        Log.d(TAG, "Auto-start on boot enabled: ${settings.autoStartOnBoot}")

        if (!settings.autoStartOnBoot) {
            Log.i(TAG, "Skipping app launch: auto-start disabled")
            return
        }

        Log.i(TAG, "Launching MainActivity after boot")

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(launchIntent)
            Log.i(TAG, "MainActivity launch requested successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity after boot", e)
        }
    }

    companion object {
        private const val TAG = "WallModeBootReceiver"
    }
}
