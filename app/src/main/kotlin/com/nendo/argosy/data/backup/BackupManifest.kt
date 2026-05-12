package com.nendo.argosy.data.backup

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

const val BACKUP_FORMAT_ID = "argosy-backup"
const val BACKUP_FORMAT_VERSION = 1

const val BACKUP_BASE_PACKAGE = "com.nendo.argosy"

const val SECTION_DATABASE = "database"
const val SECTION_DATASTORE = "datastore"
const val SECTION_SHARED_PREFS = "shared_prefs"

const val ENTRY_MANIFEST = "manifest.json"
const val ENTRY_DATABASE = "db/alauncher.db"
const val ENTRY_DATASTORE = "datastore/settings.preferences_pb"
const val ENTRY_SHARED_PREFS_DIR = "shared_prefs/"

@JsonClass(generateAdapter = true)
data class RomReference(
    val platformSlug: String?,
    val originalPath: String,
    val fileName: String,
    val sizeBytes: Long? = null
)

@JsonClass(generateAdapter = true)
data class BackupManifest(
    val format: String = BACKUP_FORMAT_ID,
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val exportedAt: Long,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val databaseName: String,
    val databaseVersion: Int,
    val sections: List<String>,
    val includesCredentials: Boolean,
    val romReferences: List<RomReference> = emptyList()
)

sealed class ManifestValidation {
    data object Ok : ManifestValidation()
    data class Invalid(val reason: String) : ManifestValidation()
    data class Incompatible(val reason: String) : ManifestValidation()
}

class BackupManifestCodec(moshi: Moshi) {
    private val adapter = moshi.adapter(BackupManifest::class.java).indent("  ")

    fun encode(manifest: BackupManifest): String = adapter.toJson(manifest)

    fun decode(json: String): BackupManifest? =
        runCatching { adapter.fromJson(json) }.getOrNull()
}

/**
 * Stateless validation: checks structure and compatibility against the *current* app build.
 *
 * - `currentPackageName`: e.g. "com.nendo.argosy.debug" — package suffixes are tolerated so a
 *   release-build export can be imported into a debug-build install as long as the base package
 *   ("com.nendo.argosy") matches.
 * - `currentDatabaseVersion`: must match the manifest's databaseVersion exactly. Lower or higher
 *   is rejected — Room migrations only run on a live DB, not on a file overwrite, so we refuse
 *   to overwrite when versions diverge.
 */
fun validateManifest(
    manifest: BackupManifest,
    currentPackageName: String,
    currentDatabaseVersion: Int
): ManifestValidation {
    if (manifest.format != BACKUP_FORMAT_ID) {
        return ManifestValidation.Invalid("Unknown archive format: ${manifest.format}")
    }
    if (manifest.formatVersion > BACKUP_FORMAT_VERSION) {
        return ManifestValidation.Incompatible(
            "Archive format v${manifest.formatVersion} is newer than supported v$BACKUP_FORMAT_VERSION"
        )
    }
    if (manifest.formatVersion < 1) {
        return ManifestValidation.Invalid("Invalid format version: ${manifest.formatVersion}")
    }

    val archiveBase = basePackage(manifest.packageName)
    val currentBase = basePackage(currentPackageName)
    if (archiveBase != currentBase) {
        return ManifestValidation.Incompatible(
            "Archive is for $archiveBase, this app is $currentBase"
        )
    }

    if (manifest.databaseVersion != currentDatabaseVersion) {
        return ManifestValidation.Incompatible(
            "Archive database v${manifest.databaseVersion} does not match app database v$currentDatabaseVersion"
        )
    }

    if (SECTION_DATABASE !in manifest.sections) {
        return ManifestValidation.Invalid("Archive is missing the database section")
    }

    return ManifestValidation.Ok
}

private fun basePackage(pkg: String): String =
    if (pkg.endsWith(".debug")) pkg.removeSuffix(".debug") else pkg
