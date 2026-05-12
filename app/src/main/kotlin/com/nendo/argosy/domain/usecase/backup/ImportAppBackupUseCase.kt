package com.nendo.argosy.domain.usecase.backup

import android.content.Context
import android.net.Uri
import com.nendo.argosy.core.notification.NotificationManager
import com.nendo.argosy.core.notification.NotificationType
import com.nendo.argosy.core.notification.showError
import com.nendo.argosy.data.backup.AppBackupRepository
import com.nendo.argosy.data.backup.BackupImportResult
import com.nendo.argosy.data.backup.BackupManifest
import com.nendo.argosy.data.backup.ManifestValidation
import com.nendo.argosy.data.backup.validateManifest
import com.nendo.argosy.data.local.ALauncherDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ImportAppBackupUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AppBackupRepository,
    private val database: ALauncherDatabase,
    private val notificationManager: NotificationManager
) {
    /**
     * Reads the manifest, validates compatibility, but does NOT stage. The UI calls this first
     * so it can show the user a "this will overwrite local data" warning with archive details
     * before commit.
     */
    suspend fun inspect(inputUri: Uri): InspectResult {
        val manifest = repository.readManifest(inputUri)
            ?: return InspectResult.Invalid("Archive has no readable manifest")
        return when (val v = validateManifest(
            manifest = manifest,
            currentPackageName = context.packageName,
            currentDatabaseVersion = database.openHelper.readableDatabase.version
        )) {
            ManifestValidation.Ok -> InspectResult.Ok(manifest)
            is ManifestValidation.Invalid -> InspectResult.Invalid(v.reason)
            is ManifestValidation.Incompatible -> InspectResult.Incompatible(manifest, v.reason)
        }
    }

    /** Stage the archive for application on the next app launch. */
    suspend fun stage(inputUri: Uri, manifest: BackupManifest): BackupImportResult {
        notificationManager.show(
            title = "Preparing restore",
            subtitle = "Staging archive for next launch...",
            type = NotificationType.INFO
        )
        val result = repository.stageRestore(inputUri, manifest)
        when (result) {
            is BackupImportResult.Staged -> {
                notificationManager.show(
                    title = "Restart required",
                    subtitle = "Quit and reopen Argosy to finish restoring",
                    type = NotificationType.SUCCESS,
                    duration = com.nendo.argosy.core.notification.NotificationDuration.LONG
                )
            }
            is BackupImportResult.ValidationFailed -> {
                notificationManager.showError("Restore failed: ${result.reason}")
            }
            is BackupImportResult.Failure -> {
                notificationManager.showError("Restore failed: ${result.reason}")
            }
        }
        return result
    }

    sealed class InspectResult {
        data class Ok(val manifest: BackupManifest) : InspectResult()
        data class Incompatible(val manifest: BackupManifest, val reason: String) : InspectResult()
        data class Invalid(val reason: String) : InspectResult()
    }
}
