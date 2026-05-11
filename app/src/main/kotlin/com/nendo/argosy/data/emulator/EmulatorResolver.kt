package com.nendo.argosy.data.emulator

import com.nendo.argosy.data.local.dao.EmulatorConfigDao
import com.nendo.argosy.data.platform.PlatformDefinitions
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmulatorResolver @Inject constructor(
    private val emulatorDetector: EmulatorDetector,
    private val emulatorConfigDao: EmulatorConfigDao
) {
    fun resolveEmulatorId(packageName: String): String? {
        EmulatorRegistry.getByPackage(packageName)?.let { return it.id }
        EmulatorRegistry.findFamilyForPackage(packageName)?.let { return it.baseId }
        return emulatorDetector.getByPackage(packageName)?.id
    }

    suspend fun getEmulatorPackageForGame(gameId: Long, platformId: Long, platformSlug: String): String? {
        emulatorConfigDao.getByGameId(gameId)?.packageName?.let { return it }
        emulatorConfigDao.getDefaultForPlatform(platformId)?.packageName?.let { return it }
        if (emulatorDetector.installedEmulators.value.isEmpty()) {
            emulatorDetector.detectEmulators()
        }
        val canonicalSlug = PlatformDefinitions.getCanonicalSlug(platformSlug)
        val globalDefault = emulatorConfigDao.getGlobalDefault()
        val globalEmulator = globalDefault?.packageName?.let { packageName ->
            emulatorDetector.installedEmulators.value.find { it.def.packageName == packageName }
        }
        if (globalEmulator != null && canonicalSlug in globalEmulator.def.supportedPlatforms) {
            return globalEmulator.def.packageName
        }
        return emulatorDetector.getPreferredEmulator(platformSlug)?.def?.packageName
    }

    suspend fun getEmulatorIdForGame(gameId: Long, platformId: Long, platformSlug: String): String? {
        return getEmulatorPackageForGame(gameId, platformId, platformSlug)?.let { resolveEmulatorId(it) }
    }

    fun getInstalledForPlatform(platformSlug: String): List<InstalledEmulator> {
        return emulatorDetector.getInstalledForPlatform(platformSlug)
    }

    fun getPreferredEmulator(platformSlug: String): InstalledEmulator? {
        return emulatorDetector.getPreferredEmulator(platformSlug)
    }
}
