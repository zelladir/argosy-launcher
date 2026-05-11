package com.nendo.argosy.data.repository

import android.content.Context
import com.nendo.argosy.data.emulator.BiosPathRegistry
import com.nendo.argosy.data.emulator.EmulatorRegistry
import com.nendo.argosy.data.emulator.SwitchKeyManager
import com.nendo.argosy.data.local.dao.FirmwareDao
import com.nendo.argosy.data.platform.PlatformDefinitions
import com.nendo.argosy.data.local.dao.PlatformDao
import com.nendo.argosy.data.local.entity.FirmwareEntity
import com.nendo.argosy.data.preferences.UserPreferencesRepository
import com.nendo.argosy.data.remote.romm.RomMApi
import com.nendo.argosy.data.remote.romm.RomMFirmware
import com.nendo.argosy.data.remote.romm.RomMResult
import com.nendo.argosy.data.storage.StoragePathUtils
import com.nendo.argosy.util.AppPaths
import com.nendo.argosy.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BiosRepository"
private const val BIOS_INTERNAL_DIR = "bios"
private const val MAX_BIOS_SCAN_DEPTH = 4
private const val MAX_BIOS_SCAN_FILES = 5000
private const val MAX_BIOS_HASH_BYTES = 64L * 1024L * 1024L

data class BiosPlatformStatus(
    val platformSlug: String,
    val platformName: String,
    val totalFiles: Int,
    val downloadedFiles: Int,
    val missingFiles: Int
)

data class BiosDownloadProgress(
    val firmwareId: Long,
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long
) {
    val progress: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
}

sealed class BiosDownloadResult {
    data class Success(val localPath: String) : BiosDownloadResult()
    data class Error(val message: String) : BiosDownloadResult()
}

data class LocalBiosScanResult(
    val scannedFiles: Int,
    val matchedFiles: Int,
    val importedFiles: Int
)

@Singleton
class BiosRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firmwareDao: FirmwareDao,
    private val platformDao: PlatformDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val switchKeyManager: SwitchKeyManager
) {
    private var api: RomMApi? = null

    private data class BiosScanTarget(
        val platformId: Long,
        val platformSlug: String,
        val fileName: String,
        val md5Hash: String?,
        val existingId: Long?
    ) {
        val key: String = "$platformId:${fileName.lowercase()}"
    }

    fun setApi(api: RomMApi?) {
        this.api = api
    }

    private fun getInternalBiosDir(): File {
        val dir = File(context.filesDir, BIOS_INTERNAL_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun resolveBiosDir(basePath: String): File {
        val baseDir = File(basePath)
        return if (baseDir.name.equals("bios", ignoreCase = true)) {
            baseDir
        } else {
            File(basePath, "bios")
        }
    }

    private fun getInternalBiosPlatformDir(platformSlug: String): File {
        val dir = File(getInternalBiosDir(), platformSlug)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun copyBiosForPlatformTo(platformSlug: String, targetPath: String): Int = withContext(Dispatchers.IO) {
        val sourceDir = getInternalBiosPlatformDir(platformSlug)
        val targetDir = File(targetPath)
        if (!targetDir.exists()) targetDir.mkdirs()
        val sourceFiles = sourceDir.listFiles()?.filter { it.isFile } ?: return@withContext 0
        var copied = 0
        for (file in sourceFiles) {
            try {
                file.copyTo(File(targetDir, file.name), overwrite = true)
                copied++
            } catch (e: Exception) {
                Logger.error(TAG, "Failed to copy BIOS file ${file.name} to $targetPath", e)
            }
        }
        Logger.info(TAG, "Copied $copied BIOS files for $platformSlug to $targetPath")
        copied
    }

    fun getLibretroSystemDir(): File {
        val dir = AppPaths.libretroSystemDir(context.filesDir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun scanLocalBiosFiles(
        onProgress: ((current: Int, total: Int, fileName: String) -> Unit)? = null
    ): LocalBiosScanResult = withContext(Dispatchers.IO) {
        val targets = buildLocalBiosScanTargets()
        if (targets.isEmpty()) {
            Logger.info(TAG, "Local BIOS scan skipped: no known BIOS targets")
            return@withContext LocalBiosScanResult(scannedFiles = 0, matchedFiles = 0, importedFiles = 0)
        }

        val expectedNames = targets.flatMap { it.matchNames() }.toSet()
        val expectedMd5s = targets.mapNotNull { it.md5Hash?.lowercase() }.toSet()
        val candidates = collectLocalBiosCandidates(getLocalBiosScanRoots(), expectedNames)

        var matched = 0
        var imported = 0
        val importedTargets = mutableSetOf<String>()

        candidates.forEachIndexed { index, file ->
            onProgress?.invoke(index + 1, candidates.size, file.name)

            val lowerName = file.name.lowercase()
            var md5: String? = null
            fun fileMd5(): String? {
                if (md5 == null && file.length() in 1L..MAX_BIOS_HASH_BYTES) {
                    md5 = try {
                        calculateMd5(file)
                    } catch (e: Exception) {
                        Logger.debug(TAG, "Could not hash candidate BIOS file ${file.absolutePath}: ${e.message}")
                        ""
                    }
                }
                return md5?.takeIf { it.isNotEmpty() }
            }

            val nameMatches = targets.filter { target ->
                target.key !in importedTargets &&
                    lowerName in target.matchNames() &&
                    (target.md5Hash == null || fileMd5()?.equals(target.md5Hash, ignoreCase = true) == true)
            }

            val md5Matches = if (expectedMd5s.isNotEmpty()) {
                fileMd5()?.let { actualMd5 ->
                    targets.filter { target ->
                        target.key !in importedTargets &&
                            target.md5Hash?.equals(actualMd5, ignoreCase = true) == true
                    }
                }.orEmpty()
            } else {
                emptyList()
            }

            val matches = (nameMatches + md5Matches).distinctBy { it.key }
            if (matches.isEmpty()) return@forEachIndexed

            for (target in matches) {
                matched++
                if (importLocalBiosFile(target, file)) {
                    imported++
                    importedTargets.add(target.key)
                }
            }
        }

        Logger.info(
            TAG,
            "Local BIOS scan complete: scanned=${candidates.size}, matched=$matched, imported=$imported"
        )
        LocalBiosScanResult(
            scannedFiles = candidates.size,
            matchedFiles = matched,
            importedFiles = imported
        )
    }

    private suspend fun buildLocalBiosScanTargets(): List<BiosScanTarget> {
        val targets = LinkedHashMap<String, BiosScanTarget>()
        val existingFirmware = firmwareDao.getSyncEnabledAll()
        for (firmware in existingFirmware) {
            val target = BiosScanTarget(
                platformId = firmware.platformId,
                platformSlug = firmware.platformSlug,
                fileName = firmware.fileName,
                md5Hash = firmware.md5Hash,
                existingId = firmware.id
            )
            targets[target.key] = target
        }

        val existingKeys = targets.keys.toSet()
        val platforms = platformDao.getAllPlatforms()
        for (platform in platforms) {
            val canonicalSlug = PlatformDefinitions.getCanonicalSlug(platform.slug)
            val requirements = BiosPathRegistry.getBiosRequirements(canonicalSlug)
            for (requirement in requirements) {
                val target = BiosScanTarget(
                    platformId = platform.id,
                    platformSlug = platform.slug,
                    fileName = requirement.fileName,
                    md5Hash = requirement.md5Hash,
                    existingId = null
                )
                if (target.key !in existingKeys) {
                    targets.putIfAbsent(target.key, target)
                }
            }
        }

        return targets.values.toList()
    }

    private suspend fun getLocalBiosScanRoots(): List<File> {
        val prefs = userPreferencesRepository.preferences.first()
        val primaryRoot = StoragePathUtils.primaryExternalRoot
        val roots = buildList {
            prefs.customBiosPath?.let { path ->
                add(resolveBiosDir(path))
                add(File(path))
            }
            add(getInternalBiosDir())
            add(getLibretroSystemDir())
            add(File(primaryRoot, "RetroArch/system"))
            add(File(primaryRoot, "BIOS"))
            add(File(primaryRoot, "bios"))
            add(File(primaryRoot, "Roms/BIOS"))
            add(File(primaryRoot, "Roms/bios"))
            add(File(primaryRoot, "ROMs/BIOS"))
            add(File(primaryRoot, "ROMs/bios"))
            add(File(primaryRoot, "Download"))
            add(File(primaryRoot, "Downloads"))
            BiosPathRegistry.getAllBiosConfigs().values.forEach { config ->
                config.defaultPaths.forEach { add(File(it)) }
            }
        }

        return roots
            .filter { it.exists() }
            .distinctBy { StoragePathUtils.canonicalize(it.absolutePath).lowercase() }
    }

    private fun collectLocalBiosCandidates(
        roots: List<File>,
        expectedNames: Set<String>
    ): List<File> {
        val candidates = LinkedHashMap<String, File>()
        for (root in roots) {
            if (candidates.size >= MAX_BIOS_SCAN_FILES) break
            if (root.isFile) {
                if (isLikelyBiosCandidate(root, expectedNames)) {
                    candidates[StoragePathUtils.canonicalize(root.absolutePath)] = root
                }
                continue
            }
            if (!root.isDirectory) continue

            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && candidates.size < MAX_BIOS_SCAN_FILES) {
                val (dir, depth) = queue.removeFirst()
                val children = try {
                    dir.listFiles()
                } catch (e: Exception) {
                    Logger.debug(TAG, "Could not scan BIOS directory ${dir.absolutePath}: ${e.message}")
                    null
                } ?: continue

                for (child in children) {
                    if (child.isDirectory) {
                        if (depth < MAX_BIOS_SCAN_DEPTH && !shouldSkipBiosScanDir(child)) {
                            queue.add(child to depth + 1)
                        }
                    } else if (child.isFile && isLikelyBiosCandidate(child, expectedNames)) {
                        candidates[StoragePathUtils.canonicalize(child.absolutePath)] = child
                        if (candidates.size >= MAX_BIOS_SCAN_FILES) break
                    }
                }
            }
        }
        return candidates.values.toList()
    }

    private fun isLikelyBiosCandidate(file: File, expectedNames: Set<String>): Boolean {
        val lowerName = file.name.lowercase()
        if (lowerName in expectedNames) return true
        val extension = file.extension.lowercase()
        return extension in setOf(
            "bin", "rom", "img", "zip", "7z", "keys", "pce", "ic1", "dat", "nca"
        )
    }

    private fun shouldSkipBiosScanDir(dir: File): Boolean {
        val name = dir.name.lowercase()
        return name.startsWith(".") ||
            name in setOf(
                "cache", "code_cache", "shader", "shaders", "screenshots",
                "thumbnails", "movies", "music", "podcasts"
            )
    }

    private suspend fun importLocalBiosFile(target: BiosScanTarget, sourceFile: File): Boolean {
        return try {
            val platformDir = getInternalBiosPlatformDir(target.platformSlug)
            val targetFile = File(platformDir, target.fileName)
            targetFile.parentFile?.mkdirs()
            if (StoragePathUtils.canonicalize(sourceFile.absolutePath) != StoragePathUtils.canonicalize(targetFile.absolutePath)) {
                sourceFile.copyTo(targetFile, overwrite = true)
            }

            val now = Instant.now()
            val existingId = target.existingId
                ?: firmwareDao.getByPlatformAndFileName(target.platformId, target.fileName)?.id
            if (existingId != null) {
                firmwareDao.updateLocalPath(existingId, targetFile.absolutePath, now)
            } else {
                firmwareDao.upsert(
                    FirmwareEntity(
                        platformId = target.platformId,
                        platformSlug = target.platformSlug,
                        rommId = localBiosRommId(target.platformId, target.fileName),
                        fileName = target.fileName,
                        filePath = targetFile.absolutePath,
                        fileSizeBytes = targetFile.length(),
                        md5Hash = target.md5Hash,
                        sha1Hash = null,
                        localPath = targetFile.absolutePath,
                        downloadedAt = now,
                        lastVerifiedAt = now
                    )
                )
            }
            Logger.info(TAG, "Imported local BIOS ${sourceFile.name} for ${target.platformSlug}")
            true
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to import local BIOS ${sourceFile.absolutePath}", e)
            false
        }
    }

    private fun localBiosRommId(platformId: Long, fileName: String): Long {
        val hash = "$platformId:${fileName.lowercase()}".hashCode().toLong()
        return -kotlin.math.abs(hash) - 1L
    }

    private fun BiosScanTarget.matchNames(): Set<String> = buildSet {
        add(fileName.lowercase())
        add(File(fileName).name.lowercase())
        BiosPathRegistry.getRetroArchBiosName(md5Hash)?.let { retroArchName ->
            add(retroArchName.lowercase())
            add(File(retroArchName).name.lowercase())
        }
    }

    suspend fun syncPlatformFirmware(
        platformId: Long,
        platformSlug: String,
        firmware: List<RomMFirmware>
    ) = withContext(Dispatchers.IO) {
        if (firmware.isEmpty()) {
            Logger.debug(TAG, "No firmware for platform $platformSlug")
            return@withContext
        }

        Logger.info(TAG, "Syncing ${firmware.size} firmware files for $platformSlug")

        val entities = firmware.map { fw ->
            val existing = firmwareDao.getByRommId(fw.id)
            FirmwareEntity(
                id = existing?.id ?: 0,
                platformId = platformId,
                platformSlug = platformSlug,
                rommId = fw.id,
                fileName = fw.fileName,
                filePath = fw.filePath,
                fileSizeBytes = fw.fileSizeBytes,
                md5Hash = fw.md5Hash,
                sha1Hash = fw.sha1Hash,
                localPath = existing?.localPath,
                downloadedAt = existing?.downloadedAt,
                lastVerifiedAt = existing?.lastVerifiedAt
            )
        }

        firmwareDao.upsertAll(entities)
        firmwareDao.deleteRemovedFirmware(platformId, firmware.map { it.id })
    }

    suspend fun downloadFirmware(
        firmwareId: Long,
        onProgress: ((BiosDownloadProgress) -> Unit)? = null
    ): BiosDownloadResult = withContext(Dispatchers.IO) {
        val currentApi = api
        if (currentApi == null) {
            Logger.error(TAG, "Download failed: API not connected")
            return@withContext BiosDownloadResult.Error("Not connected")
        }
        val firmware = firmwareDao.getByRommId(firmwareId)
        if (firmware == null) {
            Logger.error(TAG, "Download failed: Firmware not found for rommId=$firmwareId")
            return@withContext BiosDownloadResult.Error("Firmware not found")
        }

        val platformDir = getInternalBiosPlatformDir(firmware.platformSlug)
        val targetFile = File(platformDir, firmware.fileName)

        try {
            Logger.info(TAG, "Downloading firmware: ${firmware.fileName} (id=${firmware.rommId})")

            val response = currentApi.downloadFirmware(firmware.rommId, firmware.fileName)
            if (!response.isSuccessful) {
                Logger.error(TAG, "Download failed for ${firmware.fileName}: HTTP ${response.code()}")
                return@withContext BiosDownloadResult.Error("Download failed: HTTP ${response.code()}")
            }

            val body = response.body()
                ?: return@withContext BiosDownloadResult.Error("Empty response")

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    val totalBytes = body.contentLength()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        onProgress?.invoke(
                            BiosDownloadProgress(
                                firmwareId = firmware.rommId,
                                fileName = firmware.fileName,
                                bytesDownloaded = totalBytesRead,
                                totalBytes = totalBytes
                            )
                        )
                    }
                }
            }

            if (firmware.md5Hash != null) {
                val actualMd5 = calculateMd5(targetFile)
                if (!actualMd5.equals(firmware.md5Hash, ignoreCase = true)) {
                    targetFile.delete()
                    return@withContext BiosDownloadResult.Error("MD5 mismatch: expected ${firmware.md5Hash}, got $actualMd5")
                }
            }

            firmwareDao.updateLocalPath(firmware.id, targetFile.absolutePath, Instant.now())
            Logger.info(TAG, "Downloaded firmware: ${firmware.fileName}")

            BiosDownloadResult.Success(targetFile.absolutePath)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to download firmware: ${e.message}", e)
            targetFile.delete()
            BiosDownloadResult.Error(e.message ?: "Download failed")
        }
    }

    suspend fun downloadAllMissing(
        onProgress: ((current: Int, total: Int, fileName: String) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val missing = firmwareDao.getSyncEnabledMissing()
        if (missing.isEmpty()) return@withContext 0

        Logger.info(TAG, "Downloading ${missing.size} missing firmware files")
        var downloaded = 0

        missing.forEachIndexed { index, firmware ->
            onProgress?.invoke(index + 1, missing.size, firmware.fileName)
            val result = downloadFirmware(firmware.rommId)
            if (result is BiosDownloadResult.Success) {
                downloaded++
            }
        }

        Logger.info(TAG, "Downloaded $downloaded of ${missing.size} firmware files")
        downloaded
    }

    suspend fun redownloadAll(
        onProgress: ((current: Int, total: Int, fileName: String) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        cleanupDisabledPlatformBios()

        val all = firmwareDao.getSyncEnabledAll()
        if (all.isEmpty()) return@withContext 0

        Logger.info(TAG, "Redownloading all ${all.size} firmware files")
        var downloaded = 0

        all.forEachIndexed { index, firmware ->
            onProgress?.invoke(index + 1, all.size, firmware.fileName)
            val result = downloadFirmware(firmware.rommId)
            if (result is BiosDownloadResult.Success) {
                downloaded++
            }
        }

        Logger.info(TAG, "Redownloaded $downloaded of ${all.size} firmware files")
        downloaded
    }

    private suspend fun cleanupDisabledPlatformBios() {
        val toClean = firmwareDao.getDownloadedForDisabledPlatforms()
        if (toClean.isEmpty()) return

        val platformSlugs = toClean.map { it.platformSlug }.distinct()
        Logger.info(TAG, "Cleaning up BIOS for disabled platforms: $platformSlugs")

        var cleaned = 0
        for (firmware in toClean) {
            firmware.localPath?.let { path ->
                val file = File(path)
                if (file.exists()) file.delete()
            }
            firmwareDao.updateLocalPath(firmware.id, null, null)
            cleaned++
        }

        for (slug in platformSlugs) {
            cleanupDistributedCopies(slug)
            val platformDir = File(getInternalBiosDir(), slug)
            if (platformDir.exists()) platformDir.deleteRecursively()
        }

        Logger.info(TAG, "Cleaned up $cleaned firmware files for disabled platforms")
    }

    private suspend fun cleanupDistributedCopies(platformSlug: String) {
        val firmwareFiles = firmwareDao.getByPlatformSlug(platformSlug)
        val emulators = BiosPathRegistry.getEmulatorsForPlatform(
            PlatformDefinitions.getCanonicalSlug(platformSlug)
        )

        for (config in emulators) {
            val dirs = if (config.emulatorId == EmulatorRegistry.BUILTIN_PACKAGE) {
                listOf(getLibretroSystemDir())
            } else {
                config.defaultPaths.map { File(it) }
            }

            for (dir in dirs) {
                if (!dir.exists()) continue
                for (firmware in firmwareFiles) {
                    try {
                        File(dir, firmware.fileName).let { if (it.exists()) it.delete() }
                        firmware.md5Hash?.let { md5 ->
                            BiosPathRegistry.getRetroArchBiosName(md5)?.let { raName ->
                                File(dir, raName).let { if (it.exists()) it.delete() }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.debug(TAG, "Could not clean distributed BIOS ${firmware.fileName} from $dir: ${e.message}")
                    }
                }
            }
        }
    }

    suspend fun distributeBiosToEmulator(
        platformSlug: String,
        emulatorId: String
    ): Int = withContext(Dispatchers.IO) {
        val config = BiosPathRegistry.getEmulatorBiosPaths(emulatorId) ?: return@withContext 0
        val canonicalSlug = PlatformDefinitions.getCanonicalSlug(platformSlug)
        if (canonicalSlug !in config.supportedPlatforms) return@withContext 0

        val downloaded = firmwareDao.getByPlatformSlug(platformSlug).filter { it.localPath != null }
        if (downloaded.isEmpty()) return@withContext 0

        val requiresExactFilenames = emulatorId.startsWith("retroarch") ||
            emulatorId == "melonds" ||
            emulatorId == EmulatorRegistry.BUILTIN_PACKAGE

        val targetPaths = if (emulatorId == EmulatorRegistry.BUILTIN_PACKAGE) {
            listOf(getLibretroSystemDir().absolutePath)
        } else {
            config.defaultPaths
        }

        var copiedCount = 0
        for (targetPath in targetPaths) {
            val targetDir = File(targetPath)
            if (!targetDir.exists()) {
                if (!targetDir.mkdirs()) continue
            }
            if (!targetDir.canWrite()) continue

            for (firmware in downloaded) {
                val sourceFile = File(firmware.localPath!!)
                if (!sourceFile.exists()) continue

                val targetFileName = if (requiresExactFilenames) {
                    val md5 = firmware.md5Hash ?: calculateMd5(sourceFile)
                    BiosPathRegistry.getRetroArchBiosName(md5) ?: firmware.fileName
                } else {
                    firmware.fileName
                }

                val targetFile = File(targetDir, targetFileName)
                try {
                    targetFile.parentFile?.mkdirs()
                    sourceFile.copyTo(targetFile, overwrite = true)
                    Logger.debug(TAG, "Copied ${firmware.fileName} -> $targetFileName to $targetPath")
                    copiedCount++
                } catch (e: Exception) {
                    Logger.error(TAG, "Failed to copy ${firmware.fileName}: ${e.message}")
                }
            }

            if (copiedCount > 0) break
        }

        copiedCount
    }

    suspend fun distributeAllBiosToEmulators(): Map<String, Int> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Int>()
        val platformSlugs = firmwareDao.getPlatformSlugsWithDownloadedFirmware()

        for (slug in platformSlugs) {
            val emulators = BiosPathRegistry.getEmulatorsForPlatform(slug)
            for (config in emulators) {
                val count = distributeBiosToEmulator(slug, config.emulatorId)
                if (count > 0) {
                    results[config.emulatorId] = (results[config.emulatorId] ?: 0) + count
                }
            }
        }

        results
    }

    data class DetailedDistributeResult(
        val emulatorId: String,
        val platformResults: Map<String, Int>
    )

    suspend fun distributeAllBiosToEmulatorsDetailed(): List<DetailedDistributeResult> = withContext(Dispatchers.IO) {
        coroutineScope {
            val results = mutableMapOf<String, MutableMap<String, Int>>()
            val platformSlugs = firmwareDao.getPlatformSlugsWithDownloadedFirmware()

            val switchJob = if (isSwitchFirmwareReady() && isEdenInstalled()) {
                async {
                    val result = installSwitchFirmware()
                    if (result is SwitchInstallResult.Success) {
                        Pair("eden", "switch" to 2)
                    } else null
                }
            } else null

            val regularSlugs = platformSlugs.filter { it != "switch" }
            for (slug in regularSlugs) {
                val emulators = BiosPathRegistry.getEmulatorsForPlatform(slug)
                for (config in emulators) {
                    val count = distributeBiosToEmulator(slug, config.emulatorId)
                    if (count > 0) {
                        val emulatorResults = results.getOrPut(config.emulatorId) { mutableMapOf() }
                        emulatorResults[slug] = count
                    }
                }
            }

            switchJob?.await()?.let { (emulatorId, platformResult) ->
                val emulatorResults = results.getOrPut(emulatorId) { mutableMapOf() }
                emulatorResults[platformResult.first] = platformResult.second
            }

            results.map { (emulatorId, platformResults) ->
                DetailedDistributeResult(emulatorId, platformResults)
            }
        }
    }

    suspend fun getStatusByPlatform(): List<BiosPlatformStatus> = withContext(Dispatchers.IO) {
        val allFirmware = firmwareDao.observeAll().first()
        val grouped = allFirmware.groupBy { it.platformSlug }

        grouped.map { (slug, files) ->
            val downloaded = files.count { it.localPath != null }
            val platform = platformDao.getBySlug(slug)
            BiosPlatformStatus(
                platformSlug = slug,
                platformName = platform?.name ?: slug,
                totalFiles = files.size,
                downloadedFiles = downloaded,
                missingFiles = files.size - downloaded
            )
        }.sortedBy { it.platformName }
    }

    fun observeFirmware(): Flow<List<FirmwareEntity>> = firmwareDao.observeAll()

    fun observeFirmwareByPlatform(platformSlug: String): Flow<List<FirmwareEntity>> =
        firmwareDao.observeByPlatformSlug(platformSlug)

    fun observeMissingCount(): Flow<Int> = firmwareDao.observeMissingCount()

    fun observeDownloadedCount(): Flow<Int> = firmwareDao.observeDownloadedCount()

    fun observeTotalAndDownloaded(): Flow<Pair<Int, Int>> {
        return firmwareDao.observeAll().map { list ->
            val total = list.size
            val downloaded = list.count { it.localPath != null }
            total to downloaded
        }
    }

    suspend fun verifyBiosFiles(): Int = withContext(Dispatchers.IO) {
        val downloaded = firmwareDao.getDownloaded()
        var verifiedCount = 0

        for (firmware in downloaded) {
            val file = File(firmware.localPath!!)
            if (!file.exists()) {
                firmwareDao.updateLocalPath(firmware.id, null, null)
                continue
            }

            if (firmware.md5Hash != null) {
                val actualMd5 = calculateMd5(file)
                if (!actualMd5.equals(firmware.md5Hash, ignoreCase = true)) {
                    Logger.warn(TAG, "MD5 mismatch for ${firmware.fileName}")
                    file.delete()
                    firmwareDao.updateLocalPath(firmware.id, null, null)
                    continue
                }
            }

            firmwareDao.updateVerifiedAt(firmware.id, Instant.now())
            verifiedCount++
        }

        verifiedCount
    }

    suspend fun migrateToCustomPath(newPath: String?): Boolean = withContext(Dispatchers.IO) {
        val prefs = userPreferencesRepository.preferences.first()
        val oldPath = prefs.customBiosPath
        val internalDir = getInternalBiosDir()

        // Determine source directory (old custom path or internal)
        val oldBiosDir = if (oldPath != null) resolveBiosDir(oldPath) else null
        val sourceDir = oldBiosDir ?: internalDir

        if (newPath != null) {
            val newDir = resolveBiosDir(newPath)
            if (!newDir.exists() && !newDir.mkdirs()) {
                Logger.error(TAG, "Failed to create new BIOS directory: $newPath")
                return@withContext false
            }

            try {
                // Copy from source (old custom or internal) to new custom path
                if (sourceDir.exists()) {
                    sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relativePath = file.relativeTo(sourceDir)
                        val targetFile = File(newDir, relativePath.path)
                        targetFile.parentFile?.mkdirs()
                        file.copyTo(targetFile, overwrite = true)
                    }
                    Logger.info(TAG, "Copied BIOS files from ${sourceDir.absolutePath} to ${newDir.absolutePath}")
                }

                // Update localPath in firmware database
                val allFirmware = firmwareDao.getDownloaded()
                allFirmware.forEach { firmware ->
                    val oldLocalPath = firmware.localPath ?: return@forEach
                    val oldFile = File(oldLocalPath)
                    val relativePath = try {
                        oldFile.relativeTo(sourceDir)
                    } catch (e: IllegalArgumentException) {
                        // File not under source dir, skip
                        return@forEach
                    }
                    val newLocalPath = File(newDir, relativePath.path).absolutePath
                    firmwareDao.updateLocalPath(firmware.id, newLocalPath, firmware.downloadedAt)
                }
                Logger.info(TAG, "Updated firmware database paths")
            } catch (e: Exception) {
                Logger.error(TAG, "Failed to copy BIOS files: ${e.message}", e)
                return@withContext false
            }
        } else {
            // Moving back to internal - update database paths
            try {
                if (sourceDir.exists() && sourceDir != internalDir) {
                    sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relativePath = file.relativeTo(sourceDir)
                        val targetFile = File(internalDir, relativePath.path)
                        targetFile.parentFile?.mkdirs()
                        file.copyTo(targetFile, overwrite = true)
                    }
                    Logger.info(TAG, "Copied BIOS files back to internal directory")
                }

                val allFirmware = firmwareDao.getDownloaded()
                allFirmware.forEach { firmware ->
                    val oldLocalPath = firmware.localPath ?: return@forEach
                    val oldFile = File(oldLocalPath)
                    val relativePath = try {
                        oldFile.relativeTo(sourceDir)
                    } catch (e: IllegalArgumentException) {
                        return@forEach
                    }
                    val newLocalPath = File(internalDir, relativePath.path).absolutePath
                    firmwareDao.updateLocalPath(firmware.id, newLocalPath, firmware.downloadedAt)
                }
                Logger.info(TAG, "Updated firmware database paths to internal")
            } catch (e: Exception) {
                Logger.error(TAG, "Failed to migrate back to internal: ${e.message}", e)
                return@withContext false
            }
        }

        // Delete old custom directory if different from new
        if (oldBiosDir != null && oldBiosDir.absolutePath != (if (newPath != null) resolveBiosDir(newPath).absolutePath else null)) {
            try {
                if (oldBiosDir.exists()) {
                    oldBiosDir.deleteRecursively()
                    Logger.info(TAG, "Deleted old BIOS directory: ${oldBiosDir.absolutePath}")
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Failed to delete old BIOS directory: ${e.message}")
            }
        }

        userPreferencesRepository.setCustomBiosPath(newPath)
        true
    }

    sealed class SwitchInstallResult {
        data object Success : SwitchInstallResult()
        data class ValidationFailed(val message: String) : SwitchInstallResult()
        data class Error(val message: String) : SwitchInstallResult()
        data object EdenNotInstalled : SwitchInstallResult()
        data object MissingFiles : SwitchInstallResult()
    }

    fun findInstalledEdenPackage(): String? {
        val pm = context.packageManager
        return BiosPathRegistry.EDEN_PACKAGES.firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun isEdenInstalled(): Boolean = findInstalledEdenPackage() != null

    suspend fun installSwitchFirmware(
        onProgress: ((current: Int, total: Int, fileName: String) -> Unit)? = null
    ): SwitchInstallResult = withContext(Dispatchers.IO) {
        val edenPackage = findInstalledEdenPackage()
        if (edenPackage == null) {
            Logger.info(TAG, "Switch firmware install skipped: Eden not installed")
            return@withContext SwitchInstallResult.EdenNotInstalled
        }
        val edenBasePath = BiosPathRegistry.getEdenDataPath(edenPackage)

        val switchFirmware = firmwareDao.getByPlatformSlug("switch")
        val prodKeysFile = switchFirmware.find {
            it.fileName.matches(Regex("prod.*\\.keys", RegexOption.IGNORE_CASE))
        }
        val firmwareZipFile = switchFirmware.find {
            it.fileName.endsWith(".zip", ignoreCase = true)
        }

        if (prodKeysFile?.localPath == null || firmwareZipFile?.localPath == null) {
            Logger.warn(TAG, "Switch firmware install: missing required files | prodKeys=${prodKeysFile?.localPath}, firmwareZip=${firmwareZipFile?.localPath}")
            return@withContext SwitchInstallResult.MissingFiles
        }

        val prodKeysPath = prodKeysFile.localPath!!
        val firmwareZipPath = firmwareZipFile.localPath!!

        Logger.info(TAG, "Installing Switch firmware | prodKeys=$prodKeysPath, firmwareZip=$firmwareZipPath, eden=$edenBasePath")

        try {
            FileInputStream(firmwareZipPath).use { firmwareStream ->
                val isValid = switchKeyManager.validateKeysForFirmware(prodKeysPath, firmwareStream)
                if (!isValid) {
                    Logger.warn(TAG, "Switch firmware validation failed: keys incompatible with firmware")
                    return@withContext SwitchInstallResult.ValidationFailed("prod.keys incompatible with firmware")
                }
            }

            // Create all Eden expected directories
            val edenDirs = listOf(
                "amiibo", "cache", "config", "crash_dumps", "dump",
                "keys", "load", "log", "play_time", "screenshots",
                "sdmc", "shader", "tas", "icons"
            )
            edenDirs.forEach { dir ->
                File(edenBasePath, dir).mkdirs()
            }

            val keysDir = File(edenBasePath, "keys")
            val targetProdKeys = File(keysDir, "prod.keys")
            File(prodKeysPath).copyTo(targetProdKeys, overwrite = true)
            Logger.info(TAG, "Copied prod.keys to ${targetProdKeys.absolutePath}")

            val internalKeysDir = File(context.filesDir, "bios/switch")
            internalKeysDir.mkdirs()
            val internalProdKeys = File(internalKeysDir, "prod.keys")
            File(prodKeysPath).copyTo(internalProdKeys, overwrite = true)
            Logger.info(TAG, "Copied prod.keys to internal fallback | path=${internalProdKeys.absolutePath}")

            val registeredDir = File(edenBasePath, "nand/system/Contents/registered")
            registeredDir.mkdirs()

            val ncaFiles = mutableListOf<String>()
            ZipInputStream(FileInputStream(firmwareZipPath)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".nca", ignoreCase = true)) {
                        ncaFiles.add(entry.name)
                    }
                    entry = zip.nextEntry
                }
            }

            val totalFiles = ncaFiles.size
            var extractedCount = 0

            ZipInputStream(FileInputStream(firmwareZipPath)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".nca", ignoreCase = true)) {
                        val ncaFileName = File(entry.name).name
                        onProgress?.invoke(extractedCount + 1, totalFiles, ncaFileName)

                        val outFile = File(registeredDir, ncaFileName)
                        outFile.outputStream().use { out ->
                            zip.copyTo(out)
                        }
                        extractedCount++
                    }
                    entry = zip.nextEntry
                }
            }

            Logger.info(TAG, "Extracted $extractedCount NCA files to ${registeredDir.absolutePath}")

            createDefaultEdenProfile(edenBasePath)

            SwitchInstallResult.Success
        } catch (e: Exception) {
            Logger.error(TAG, "Switch firmware install failed", e)
            SwitchInstallResult.Error(e.message ?: "Installation failed")
        }
    }

    private fun createDefaultEdenProfile(edenBasePath: String) {
        val profileDir = File(edenBasePath, "nand/system/save/8000000000000010/su/avators")
        val profileFile = File(profileDir, "profiles.dat")

        if (profileFile.exists()) {
            Logger.debug(TAG, "Eden profile already exists, skipping creation")
            return
        }

        profileDir.mkdirs()

        // profiles.dat format (0x650 bytes total):
        // [0x00-0x0F] 16 bytes header padding
        // [0x10-0xD7] User 0 (0xC8 bytes per user, 8 users max)
        //   [0x10-0x1F] UUID (16 bytes)
        //   [0x20-0x2F] UUID2 (16 bytes, same as UUID)
        //   [0x30-0x37] timestamp (8 bytes, little-endian)
        //   [0x38-0x57] username (32 bytes, null-padded UTF-8)
        //   [0x58-0xD7] extra_data (128 bytes)

        val uuid = UUID.randomUUID()
        val uuidBytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(uuid.leastSignificantBits)
            .putLong(uuid.mostSignificantBits)
            .array()

        val timestamp = System.currentTimeMillis() / 1000
        val timestampBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(timestamp)
            .array()

        val username = "Argosy".toByteArray(Charsets.UTF_8)
        val usernameBytes = ByteArray(32)
        System.arraycopy(username, 0, usernameBytes, 0, minOf(username.size, 31))

        val profileData = ByteArray(0x650)
        System.arraycopy(uuidBytes, 0, profileData, 0x10, 16)
        System.arraycopy(uuidBytes, 0, profileData, 0x20, 16)
        System.arraycopy(timestampBytes, 0, profileData, 0x30, 8)
        System.arraycopy(usernameBytes, 0, profileData, 0x38, 32)

        profileFile.writeBytes(profileData)

        val folderName = String.format("%016X%016X", uuid.mostSignificantBits, uuid.leastSignificantBits)
        Logger.info(TAG, "Created default Eden profile | uuid=$folderName, path=${profileFile.absolutePath}")

        // Create user save directory structure so save sync works immediately
        val userSaveDir = File(edenBasePath, "nand/user/save/0000000000000000/$folderName")
        userSaveDir.mkdirs()
        Logger.info(TAG, "Created Eden user save directory | path=${userSaveDir.absolutePath}")
    }

    suspend fun isSwitchFirmwareReady(): Boolean = withContext(Dispatchers.IO) {
        val switchFirmware = firmwareDao.getByPlatformSlug("switch")
        val hasProdKeys = switchFirmware.any {
            it.fileName.matches(Regex("prod.*\\.keys", RegexOption.IGNORE_CASE)) && it.localPath != null
        }
        val hasFirmwareZip = switchFirmware.any {
            it.fileName.endsWith(".zip", ignoreCase = true) && it.localPath != null
        }
        hasProdKeys && hasFirmwareZip
    }

    private fun calculateMd5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
