package com.sqlai.assistant.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Version-correct storage permission logic covering Android 10 through 17.
 *
 *  - Android 11+ (API 30..) : Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
 *                             with a `package:` URI (fallback to the generic
 *                             ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION page).
 *  - Android 10    (API 29) : legacy READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE
 *                             runtime request.
 *  - Android 13+   (API 33+): READ_MEDIA_IMAGES / VIDEO / AUDIO runtime fallback
 *                             (plus READ_MEDIA_VISUAL_USER_SELECTED on 14+).
 */
object PermissionManager {

    /** True when the app holds "All files access" (MANAGE_EXTERNAL_STORAGE). */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            false
        }

    /** True when granular media permissions are granted (Android 13+). */
    fun hasMediaAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasGranted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
            hasGranted(context, Manifest.permission.READ_MEDIA_AUDIO) ||
            hasGranted(context, Manifest.permission.READ_MEDIA_VIDEO)

    /** True when legacy external-storage permission is granted (Android 12-). */
    fun hasLegacyStorage(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            hasGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE) ||
            hasGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /**
     * Overall storage verdict used by the permission card:
     * API 30+ requires All Files access OR (13+) media access; API 29 needs
     * the legacy runtime permission.
     */
    fun hasStorage(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            hasAllFilesAccess() || hasMediaAccess(context)

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            hasAllFilesAccess() || hasLegacyStorage(context)

        else -> hasLegacyStorage(context)
    }

    /** The exact settings intent for All Files access, null when unsupported. */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val packageUri = Uri.parse("package:${context.packageName}")
        val primary = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            packageUri
        )
        return if (intentResolves(context, primary)) {
            primary
        } else {
            // Some OEM skins drop the app-scoped action - generic page as fallback.
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Legacy storage runtime permissions (Android 10 and below). */
    fun legacyStoragePermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    /** Granular media runtime permissions (Android 13+). */
    fun mediaPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 34) {
            list += "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        }
        return list.toTypedArray()
    }

    /** Version-correct runtime set for the combined "Storage" card. */
    fun storageRuntimePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> mediaPermissions()
        else -> legacyStoragePermissions()
    }

    /** What the storage card's Grant button should do on this OS version. */
    sealed class StorageAction {
        data class OpenSettings(val intent: Intent) : StorageAction()
        data class RequestPermissions(val permissions: Array<String>) : StorageAction()
        object Granted : StorageAction()
    }

    fun storageAction(context: Context): StorageAction = when {
        hasAllFilesAccess() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            StorageAction.Granted

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            val intent = allFilesAccessIntent(context)
            if (intent != null) StorageAction.OpenSettings(intent)
            else StorageAction.RequestPermissions(legacyStoragePermissions())
        }

        else -> StorageAction.RequestPermissions(legacyStoragePermissions())
    }

    private fun hasGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun intentResolves(context: Context, intent: Intent): Boolean =
        try {
            intent.resolveActivity(context.packageManager) != null
        } catch (e: Exception) {
            false
        }
}
