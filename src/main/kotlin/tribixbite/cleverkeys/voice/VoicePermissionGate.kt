package tribixbite.cleverkeys.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Phase 1 voice (2026-10-24): RECORD_AUDIO runtime-permission helper.
 *
 * An IME service cannot show the runtime permission dialog itself, so the
 * request must originate from an Activity (Settings or Launcher). The
 * keyboard checks [hasPermission] at tap time ([KeyboardReceiver]) and falls
 * back to the external voice IME when permission is missing.
 */
internal object VoicePermissionGate {
    const val PERMISSION: String = Manifest.permission.RECORD_AUDIO

    @JvmStatic
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Register the permission launcher. Must be called from the host
     * Activity's `onCreate`, before the Activity is STARTED.
     */
    @JvmStatic
    fun launcherFor(
        activity: ComponentActivity,
        onResult: (granted: Boolean) -> Unit
    ): ActivityResultLauncher<String> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> onResult(granted) }

    /**
     * Run [onResult] immediately if permission is already granted, otherwise
     * launch the system dialog and deliver the answer to [onResult].
     */
    @JvmStatic
    fun requestOrRun(
        activity: ComponentActivity,
        launcher: ActivityResultLauncher<String>,
        onResult: (granted: Boolean) -> Unit
    ) {
        if (hasPermission(activity)) {
            onResult(true)
        } else {
            launcher.launch(PERMISSION)
        }
    }

    /** Deep-link to this app's Settings page (for "don't ask again" recovery). */
    @JvmStatic
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
