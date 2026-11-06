package com.internetdeco.decocontrol

import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Durable AES-GCM storage; cached reads never weaken or replace encryption. */
class EncryptedVault(
    private val load: () -> String?,
    private val commit: (String) -> Boolean,
    private val key: () -> SecretKey,
) {
    private var lastSealed: String? = null
    private var lastValue: JSONObject? = null

    @Synchronized fun read(): JSONObject {
        val sealed = load() ?: return JSONObject()
        if (sealed == lastSealed && lastValue != null) return JSONObject(lastValue.toString())
        val parts = sealed.split(':')
        check(parts.size == 2) { "Encrypted storage is damaged" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        val value = JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8))
        lastSealed = sealed
        lastValue = value
        return JSONObject(value.toString())
    }

    @Synchronized fun update(block: (JSONObject) -> Unit) {
        val value = read()
        block(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = Base64.getEncoder().encodeToString(cipher.iv) + ":" +
            Base64.getEncoder().encodeToString(cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)))
        check(commit(sealed)) { "Could not save encrypted data" }
        lastSealed = sealed
        lastValue = JSONObject(value.toString())
    }
}
