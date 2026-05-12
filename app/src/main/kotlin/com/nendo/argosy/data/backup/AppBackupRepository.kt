package com.nendo.argosy.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nendo.argosy.BuildConfig
import com.nendo.argosy.data.local.ALauncherDatabase
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppBackupRepository"

private const val DATABASE_NAME = "alauncher.db"
private const val PENDING_RESTORE_DIR = "backup_pending_restore"
private const val PENDING_RESTORE_MARKER = "PENDING.json"

sealed class BackupExportResult {
    data class Success(val manifest: BackupManifest, val bytesWritten: Long) : BackupExportResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : BackupExportResult()
}

sealed class BackupImportResult {
    /** Restore is staged; user must restart the app for files to be applied. */
    data class Staged(val manifest: BackupManifest) : BackupImportResult()
    data class ValidationFailed(val reason: String) : BackupImportResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : BackupImportResult()
}

@Singleton
class AppBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ALauncherDatabase,
    moshi: Moshi
) {
    private val codec = BackupManifestCodec(moshi)

    /**
     * Snapshot app data into a zip written to [outputUri]. The database is checkpointed
     * (WAL truncated into the main file) before the snapshot so the copied file is consistent
     * without -wal / -shm sidecars.
     */
    suspend fun export(outputUri: Uri): BackupExportResult = withContext(Dispatchers.IO) {
        try {
            checkpointDatabase()

            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                return@withContext BackupExportResult.Failure("Database file not found")
            }

            val datastoreFile = File(context.filesDir, "datastore/settings.preferences_pb")
            val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")

            val sections = mutableListOf(SECTION_DATABASE)
            if (datastoreFile.exists()) sections.add(SECTION_DATASTORE)
            if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) sections.add(SECTION_SHARED_PREFS)

            val manifest = BackupManifest(
                exportedAt = System.currentTimeMillis(),
                packageName = context.packageName,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                databaseName = DATABASE_NAME,
                databaseVersion = database.openHelper.readableDatabase.version,
                sections = sections,
                includesCredentials = datastoreFile.exists() || sharedPrefsDir.exists()
            )

            val output = context.contentResolver.openOutputStream(outputUri, "w")
                ?: return@withContext BackupExportResult.Failure("Could not open archive for writing")

            var bytesWritten = 0L
            output.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    putString(zip, ENTRY_MANIFEST, codec.encode(manifest))
                    bytesWritten += putFile(zip, ENTRY_DATABASE, dbFile)
                    if (datastoreFile.exists()) {
                        bytesWritten += putFile(zip, ENTRY_DATASTORE, datastoreFile)
                    }
                    if (sharedPrefsDir.exists()) {
                        sharedPrefsDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }
                            ?.forEach { xml ->
                                bytesWritten += putFile(zip, "$ENTRY_SHARED_PREFS_DIR${xml.name}", xml)
                            }
                    }
                }
            }

            BackupExportResult.Success(manifest, bytesWritten)
        } catch (t: Throwable) {
            Log.e(TAG, "Export failed", t)
            BackupExportResult.Failure(t.message ?: "Unknown export error", t)
        }
    }

    /** Peek the manifest in [inputUri] without unpacking the rest of the archive. */
    suspend fun readManifest(inputUri: Uri): BackupManifest? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(inputUri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == ENTRY_MANIFEST) {
                            val json = zip.readBytes().decodeToString()
                            return@runCatching codec.decode(json)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * Stage [inputUri] into [PENDING_RESTORE_DIR] under filesDir. The caller is responsible for
     * prompting the user to restart; [BackupRestorePerformer.applyPendingIfAny] applies the
     * staged files on the next app launch before Room or DataStore open.
     */
    suspend fun stageRestore(inputUri: Uri, manifest: BackupManifest): BackupImportResult =
        withContext(Dispatchers.IO) {
            try {
                val stagingDir = File(context.filesDir, PENDING_RESTORE_DIR)
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                if (!stagingDir.mkdirs()) {
                    return@withContext BackupImportResult.Failure("Could not create staging directory")
                }

                val extracted = mutableSetOf<String>()
                context.contentResolver.openInputStream(inputUri)?.use { raw ->
                    ZipInputStream(raw.buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && isSafeEntryName(entry.name)) {
                                val target = File(stagingDir, entry.name)
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out -> zip.copyTo(out) }
                                extracted.add(entry.name)
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: return@withContext BackupImportResult.Failure("Could not open archive for reading")

                if (ENTRY_MANIFEST !in extracted) {
                    stagingDir.deleteRecursively()
                    return@withContext BackupImportResult.ValidationFailed("Archive is missing manifest")
                }
                if (ENTRY_DATABASE !in extracted) {
                    stagingDir.deleteRecursively()
                    return@withContext BackupImportResult.ValidationFailed("Archive is missing database")
                }

                File(stagingDir, PENDING_RESTORE_MARKER).writeText(codec.encode(manifest))

                BackupImportResult.Staged(manifest)
            } catch (t: Throwable) {
                Log.e(TAG, "Stage restore failed", t)
                BackupImportResult.Failure(t.message ?: "Unknown import error", t)
            }
        }

    private fun checkpointDatabase() {
        try {
            database.query("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { it.moveToFirst() }
        } catch (t: Throwable) {
            Log.w(TAG, "WAL checkpoint failed; proceeding with raw DB snapshot", t)
        }
    }

    private fun putString(zip: ZipOutputStream, name: String, contents: String): Long {
        val bytes = contents.encodeToByteArray()
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
        return bytes.size.toLong()
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File): Long {
        zip.putNextEntry(ZipEntry(name))
        val written = file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        return written
    }
}

/**
 * Reject path-traversal entries from untrusted archives. The Zip Slip class of bug:
 * an entry named "../../something" would otherwise write outside the staging directory.
 */
internal fun isSafeEntryName(name: String): Boolean {
    if (name.isBlank()) return false
    if (name.startsWith("/") || name.startsWith("\\")) return false
    if (name.contains("..")) return false
    if (name.contains(":")) return false
    return true
}
