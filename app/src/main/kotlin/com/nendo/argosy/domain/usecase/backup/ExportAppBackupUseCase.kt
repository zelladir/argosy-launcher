package com.nendo.argosy.domain.usecase.backup

import android.net.Uri
import com.nendo.argosy.core.notification.NotificationManager
import com.nendo.argosy.core.notification.NotificationType
import com.nendo.argosy.core.notification.showError
import com.nendo.argosy.core.notification.showSuccess
import com.nendo.argosy.data.backup.AppBackupRepository
import com.nendo.argosy.data.backup.BackupExportResult
import javax.inject.Inject

class ExportAppBackupUseCase @Inject constructor(
    private val repository: AppBackupRepository,
    private val notificationManager: NotificationManager
) {
    suspend operator fun invoke(outputUri: Uri): BackupExportResult {
        notificationManager.show(
            title = "Backing up app data",
            subtitle = "Snapshotting database and settings...",
            type = NotificationType.INFO
        )
        val result = repository.export(outputUri)
        when (result) {
            is BackupExportResult.Success -> {
                val sizeMb = result.bytesWritten / 1024.0 / 1024.0
                notificationManager.showSuccess("Backup saved (${"%.1f".format(sizeMb)} MB)")
            }
            is BackupExportResult.Failure -> {
                notificationManager.showError("Backup failed: ${result.reason}")
            }
        }
        return result
    }
}
