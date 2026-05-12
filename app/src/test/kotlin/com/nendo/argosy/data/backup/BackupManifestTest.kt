package com.nendo.argosy.data.backup

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {

    private val codec = BackupManifestCodec(Moshi.Builder().build())

    private fun sample(
        packageName: String = "com.nendo.argosy",
        databaseVersion: Int = 110,
        sections: List<String> = listOf(SECTION_DATABASE, SECTION_DATASTORE, SECTION_SHARED_PREFS),
        formatVersion: Int = BACKUP_FORMAT_VERSION
    ) = BackupManifest(
        format = BACKUP_FORMAT_ID,
        formatVersion = formatVersion,
        exportedAt = 1_700_000_000_000L,
        packageName = packageName,
        versionName = "1.4.0",
        versionCode = 254,
        databaseName = "alauncher.db",
        databaseVersion = databaseVersion,
        sections = sections,
        includesCredentials = true
    )

    @Test
    fun `encode and decode round-trips`() {
        val manifest = sample()
        val json = codec.encode(manifest)
        val decoded = codec.decode(json)
        assertEquals(manifest, decoded)
    }

    @Test
    fun `decode tolerates default romReferences`() {
        val json = """
            {
              "format": "argosy-backup",
              "formatVersion": 1,
              "exportedAt": 1700000000000,
              "packageName": "com.nendo.argosy",
              "versionName": "1.4.0",
              "versionCode": 254,
              "databaseName": "alauncher.db",
              "databaseVersion": 110,
              "sections": ["database"],
              "includesCredentials": false
            }
        """.trimIndent()
        val decoded = codec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded!!.romReferences.isEmpty())
    }

    @Test
    fun `validate accepts matching package and database version`() {
        val result = validateManifest(
            manifest = sample(packageName = "com.nendo.argosy", databaseVersion = 110),
            currentPackageName = "com.nendo.argosy",
            currentDatabaseVersion = 110
        )
        assertEquals(ManifestValidation.Ok, result)
    }

    @Test
    fun `validate treats debug suffix as matching base package`() {
        val result = validateManifest(
            manifest = sample(packageName = "com.nendo.argosy"),
            currentPackageName = "com.nendo.argosy.debug",
            currentDatabaseVersion = 110
        )
        assertEquals(ManifestValidation.Ok, result)
    }

    @Test
    fun `validate accepts debug-to-release direction`() {
        val result = validateManifest(
            manifest = sample(packageName = "com.nendo.argosy.debug"),
            currentPackageName = "com.nendo.argosy",
            currentDatabaseVersion = 110
        )
        assertEquals(ManifestValidation.Ok, result)
    }

    @Test
    fun `validate rejects unrelated package`() {
        val result = validateManifest(
            manifest = sample(packageName = "com.example.other"),
            currentPackageName = "com.nendo.argosy",
            currentDatabaseVersion = 110
        )
        assertTrue(result is ManifestValidation.Incompatible)
    }

    @Test
    fun `validate rejects mismatched database version`() {
        val result = validateManifest(
            manifest = sample(databaseVersion = 109),
            currentPackageName = "com.nendo.argosy",
            currentDatabaseVersion = 110
        )
        assertTrue(result is ManifestValidation.Incompatible)
    }

    @Test
    fun `validate rejects newer format version`() {
        val result = validateManifest(
            manifest = sample(formatVersion = BACKUP_FORMAT_VERSION + 1),
            currentPackageName = "com.nendo.argosy",
            currentDatabaseVersion = 110
        )
        assertTrue(result is ManifestValidation.Incompatible)
    }

    @Test
    fun `validate rejects missing database section`() {
        val result = validateManifest(
            manifest = sample(sections = listOf(SECTION_DATASTORE)),
            currentPackageName = "com.nendo.argosy",
            currentDatabaseVersion = 110
        )
        assertTrue(result is ManifestValidation.Invalid)
    }

    @Test
    fun `validate rejects unknown format id`() {
        val manifest = sample().copy(format = "not-an-argosy-backup")
        val result = validateManifest(
            manifest = manifest,
            currentPackageName = "com.nendo.argosy",
            currentDatabaseVersion = 110
        )
        assertTrue(result is ManifestValidation.Invalid)
    }
}
