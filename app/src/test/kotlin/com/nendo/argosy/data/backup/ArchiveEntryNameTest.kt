package com.nendo.argosy.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveEntryNameTest {

    @Test
    fun `accepts expected archive paths`() {
        assertTrue(isSafeEntryName(ENTRY_MANIFEST))
        assertTrue(isSafeEntryName(ENTRY_DATABASE))
        assertTrue(isSafeEntryName(ENTRY_DATASTORE))
        assertTrue(isSafeEntryName("shared_prefs/argosy_prefs.xml"))
    }

    @Test
    fun `rejects path traversal`() {
        assertFalse(isSafeEntryName("../etc/passwd"))
        assertFalse(isSafeEntryName("db/../../foo"))
        assertFalse(isSafeEntryName(".."))
    }

    @Test
    fun `rejects absolute paths`() {
        assertFalse(isSafeEntryName("/etc/hosts"))
        assertFalse(isSafeEntryName("\\windows\\system32"))
    }

    @Test
    fun `rejects windows drive-letter paths`() {
        assertFalse(isSafeEntryName("C:\\Windows"))
    }

    @Test
    fun `rejects empty names`() {
        assertFalse(isSafeEntryName(""))
        assertFalse(isSafeEntryName("   "))
    }
}
