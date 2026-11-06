package com.internetdeco.decocontrol

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class EncryptedVaultTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun credentialsSurviveNewVaultAndKeepLegacyFormat() {
        val key = key(); var stored: String? = null
        EncryptedVault({ stored }, { stored = it; true }, { key }).update { it.put("root:10.0.0.2", "test-only-password") }
        assertNotNull(stored); assertFalse(stored!!.contains("test-only-password")); assertEquals(2, stored!!.split(':').size)
        assertEquals("test-only-password", EncryptedVault({ stored }, { false }, { key }).read().getString("root:10.0.0.2"))
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val old = Base64.getEncoder().encodeToString(c.iv) + ":" + Base64.getEncoder().encodeToString(c.doFinal("{\"root:10.0.0.3\":\"legacy-only-test\"}".toByteArray()))
        assertEquals("legacy-only-test", EncryptedVault({ old }, { false }, { key }).read().getString("root:10.0.0.3"))
    }
    @Test fun failedCommitKeepsLastDurableValue() {
        val key = key(); var stored: String? = null; var commit = true
        val vault = EncryptedVault({ stored }, { if (commit) stored = it; commit }, { key })
        vault.update { it.put("password", "old") }; val before = stored
        commit = false
        try { vault.update { it.put("password", "new") }; fail("Commit must fail") } catch (_: IllegalStateException) {}
        assertEquals(before, stored); assertEquals("old", vault.read().getString("password"))
    }
    @Test fun unreadableCredentialsCannotBeOverwrittenByAnUpdate() {
        var stored = "broken-ciphertext"; var commits = 0
        val vault = EncryptedVault({ stored }, { commits++; stored = it; true }, { key() })
        try { vault.update { it.put("password", "replacement") }; fail("Corruption must be reported") } catch (_: IllegalStateException) {}
        assertEquals(0, commits); assertEquals("broken-ciphertext", stored)
    }
    @Test fun cachedReadsReturnIndependentObjects() {
        val key = key(); var stored: String? = null; var keyReads = 0
        val vault = EncryptedVault({ stored }, { stored = it; true }, { keyReads++; key })
        vault.update { it.put("value", "durable") }; val keyCount = keyReads
        vault.read().put("value", "accidental mutation")
        repeat(20) { assertEquals("durable", vault.read().getString("value")) }
        assertEquals(keyCount, keyReads)
    }
}
