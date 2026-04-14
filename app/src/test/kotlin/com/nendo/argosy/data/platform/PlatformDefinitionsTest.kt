package com.nendo.argosy.data.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformDefinitionsTest {

    @Test
    fun `resolveImportSlug remaps RomM pico folder to pico8 by name`() {
        assertEquals("pico8", PlatformDefinitions.resolveImportSlug("pico", "PICO-8"))
        assertEquals("pico8", PlatformDefinitions.resolveImportSlug("pico", "Pico-8"))
        assertEquals("pico8", PlatformDefinitions.resolveImportSlug("pico", "pico 8"))
        assertEquals("pico8", PlatformDefinitions.resolveImportSlug("PICO", "PICO-8"))
    }

    @Test
    fun `resolveImportSlug leaves Sega Pico alone`() {
        assertEquals("pico", PlatformDefinitions.resolveImportSlug("pico", "Pico"))
        assertEquals("pico", PlatformDefinitions.resolveImportSlug("pico", "Sega Pico"))
        assertEquals("pico", PlatformDefinitions.resolveImportSlug("pico", null))
    }

    @Test
    fun `resolveImportSlug only touches the ambiguous pico slug`() {
        assertEquals("pico-8", PlatformDefinitions.resolveImportSlug("pico-8", "PICO-8"))
        assertEquals("psx", PlatformDefinitions.resolveImportSlug("psx", "PlayStation"))
        assertEquals("snes", PlatformDefinitions.resolveImportSlug("snes", "Super Nintendo"))
    }

    @Test
    fun `pico8 resolves to its own emulators, sega pico stays distinct`() {
        assertEquals("pico8", PlatformDefinitions.getCanonicalSlug(PlatformDefinitions.resolveImportSlug("pico", "PICO-8")))
        assertEquals("pico8", PlatformDefinitions.getCanonicalSlug("pico-8"))
        assertEquals("pico8", PlatformDefinitions.getCanonicalSlug("pico8"))
        assertEquals("pico", PlatformDefinitions.getCanonicalSlug("pico"))
    }

    @Test
    fun `hacks and staging folders resolve to their parent platform`() {
        assertEquals("snes", PlatformDefinitions.getCanonicalSlug("snes-hacks"))
        assertEquals("snes", PlatformDefinitions.getCanonicalSlug("sfam-hacks"))
        assertEquals("gc", PlatformDefinitions.getCanonicalSlug("ngc-hacks"))
        assertEquals("3ds", PlatformDefinitions.getCanonicalSlug("3ds-staging"))
        assertEquals("pc9800", PlatformDefinitions.getCanonicalSlug("pc98-hacks"))
        assertEquals("neogeocd", PlatformDefinitions.getCanonicalSlug("neo-geo-cd-hacks"))
        assertEquals("tgcd", PlatformDefinitions.getCanonicalSlug("turbografx-cd-hacks"))
        assertEquals("unknown-hacks", PlatformDefinitions.getCanonicalSlug("unknown-hacks"))
    }

    @Test
    fun `hacks folders derive a display name from the longest known parent`() {
        assertEquals("SNES Hacks" to "SNES Hacks", PlatformDefinitions.deriveDisplayName("snes-hacks"))
        assertEquals("NGCD Hacks" to "NGCD Hacks", PlatformDefinitions.deriveDisplayName("neo-geo-cd-hacks"))
        assertEquals("TG-CD Hacks" to "TG-CD Hacks", PlatformDefinitions.deriveDisplayName("turbografx-cd-hacks"))
        assertEquals(null, PlatformDefinitions.deriveDisplayName("neo-geo-cd"))
        assertEquals(null, PlatformDefinitions.deriveDisplayName("snes-"))
    }
}
