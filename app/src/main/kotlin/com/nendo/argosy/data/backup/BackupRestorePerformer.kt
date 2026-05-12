package com.nendo.argosy.data.backup

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "BackupRestorePerformer"

private const val PENDING_RESTORE_DIR = "backup_pending_restore"
private const val PENDING_RESTORE_MARKER = "PENDING.json"
private const val DATABASE_NAME = "alauncher.db"

/**
 * Applies a staged backup, if one is present, before Room / DataStore open their files.
 *
 * Must be invoked from `Application.attachBaseContext` so it runs strictly before any Hilt-managed
 * singleton has a chance to open its files. Once applied, the staging dir and Room sidecar
 * files (-wal / -shm) are removed so the restored DB opens cleanly.
 *
 * Failures here are non-fatal: we delete the staging dir and let the app boot with whatever
 * existing state it had. A user-visible "restore failed" path is the export path next time.
 */
object BackupRestorePerformer {
    fun applyPendingIfAny(context: Context) {
        val stagingDir = File(context.filesDir, PENDING_RESTORE_DIR)
        val marker = File(stagingDir, PENDING_RESTORE_MARKER)
        if (!marker.exists()) return

        Log.i(TAG, "Pending restore detected; applying staged files")
        try {
            applyDatabase(context, stagingDir)
            applyDatastore(context, stagingDir)
            applySharedPrefs(context, stagingDir)
            Log.i(TAG, "Pending restore applied successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply pending restore", t)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun applyDatabase(context: Context, stagingDir: File) {
        val staged = File(stagingDir, ENTRY_DATABASE)
        if (!staged.exists()) return
        val target = context.getDatabasePath(DATABASE_NAME)
        target.parentFile?.mkdirs()

        // Remove WAL/SHM sidecars from the *existing* DB so the restored file is not
        // misinterpreted as out-of-date relative to a stale journal.
        File(target.parentFile, "$DATABASE_NAME-wal").delete()
        File(target.parentFile, "$DATABASE_NAME-shm").delete()
        File(target.parentFile, "$DATABASE_NAME-journal").delete()

        staged.copyTo(target, overwrite = true)
    }

    private fun applyDatastore(context: Context, stagingDir: File) {
        val staged = File(stagingDir, ENTRY_DATASTORE)
        if (!staged.exists()) return
        val target = File(context.filesDir, "datastore/settings.preferences_pb")
        target.parentFile?.mkdirs()
        staged.copyTo(target, overwrite = true)
    }

    private fun applySharedPrefs(context: Context, stagingDir: File) {
        val stagedDir = File(stagingDir, ENTRY_SHARED_PREFS_DIR.trimEnd('/'))
        if (!stagedDir.exists() || !stagedDir.isDirectory) return
        val targetDir = File(context.applicationInfo.dataDir, "shared_prefs")
        targetDir.mkdirs()

        // Replace existing XMLs so removed-in-export keys don't linger. We only manage *.xml files
        // here; other content (lockfiles, etc.) is left alone.
        targetDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.forEach { it.delete() }
        stagedDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.forEach { xml ->
            xml.copyTo(File(targetDir, xml.name), overwrite = true)
        }
    }
}
