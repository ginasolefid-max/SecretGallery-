package com.example.secretgallery

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val date: Long
)

class VaultCrypto(private val context: Context) {
    private val prefs = context.getSharedPreferences("vault_setup", Context.MODE_PRIVATE)
    private val vaultDir = File(context.filesDir, "encrypted_vault").apply { mkdirs() }
    private var key: SecretKey? = null
    private val random = SecureRandom()

    fun isSetup(): Boolean = prefs.contains("salt") && prefs.contains("check")

    private fun derive(pin: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin, salt, 600_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun encryptBytes(input: ByteArray, secret: SecretKey): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(input)
        return iv + encrypted
    }

    private fun decryptBytes(input: ByteArray, secret: SecretKey): ByteArray {
        require(input.size > 28) { "Encrypted file is incomplete." }
        val iv = input.copyOfRange(0, 12)
        val payload = input.copyOfRange(12, input.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
        return cipher.doFinal(payload)
    }

    fun create(pin: String) {
        require(pin.matches(Regex("[0-9]{8,12}"))) { "Choose an 8–12 digit PIN." }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val derived = derive(pin.toCharArray(), salt)
        val check = encryptBytes("secret-gallery-check".toByteArray(), derived)
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("check", Base64.encodeToString(check, Base64.NO_WRAP))
            .apply()
        key = derived
        saveItems(emptyList())
    }

    fun unlock(pin: String): Boolean {
        if (!pin.matches(Regex("[0-9]{8,12}"))) return false
        return try {
            val salt = Base64.decode(prefs.getString("salt", "")!!, Base64.NO_WRAP)
            val check = Base64.decode(prefs.getString("check", "")!!, Base64.NO_WRAP)
            val derived = derive(pin.toCharArray(), salt)
            val plain = decryptBytes(check, derived)
            if (String(plain) != "secret-gallery-check") false
            else { key = derived; loadItems(); true }
        } catch (_: Exception) { false }
    }

    fun lock() { key = null }
    fun isUnlocked() = key != null

    fun importStream(input: InputStream, item: VaultItem): File {
        val secret = key ?: error("Vault is locked.")
        val target = File(vaultDir, "${item.id}.sgv")
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
        FileOutputStream(target).use { fileOut ->
            fileOut.write(iv)
            CipherOutputStream(fileOut, cipher).use { encryptedOut ->
                input.copyTo(encryptedOut)
            }
        }
        // Verify the ciphertext by authenticating and reading it back before the source can be deleted.
        decryptToNull(target)
        return target
    }

    private fun decryptToNull(file: File) {
        val secret = key ?: error("Vault is locked.")
        FileInputStream(file).use { input ->
            val iv = ByteArray(12)
            if (input.read(iv) != 12) error("Encrypted copy is incomplete.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
            CipherInputStream(input, cipher).use { decrypted ->
                val buffer = ByteArray(8192)
                while (decrypted.read(buffer) != -1) { /* authenticate full stream */ }
            }
        }
    }

    fun decryptTo(fileId: String, output: OutputStream) {
        val secret = key ?: error("Vault is locked.")
        val file = File(vaultDir, "$fileId.sgv")
        require(file.exists()) { "Vault file is missing." }
        FileInputStream(file).use { input ->
            val iv = ByteArray(12)
            if (input.read(iv) != 12) error("Encrypted file is damaged.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
            CipherInputStream(input, cipher).use { it.copyTo(output) }
        }
    }

    fun deleteFile(id: String) {
        File(vaultDir, "$id.sgv").delete()
        saveItems(loadItems().filterNot { it.id == id })
    }

    fun loadItems(): List<VaultItem> {
        val secret = key ?: error("Vault is locked.")
        val file = File(vaultDir, "index.sgv")
        if (!file.exists()) return emptyList()
        val json = JSONObject(String(decryptBytes(file.readBytes(), secret)))
        val array = json.getJSONArray("items")
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            VaultItem(o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("date"))
        }
    }

    fun saveItems(items: List<VaultItem>) {
        val secret = key ?: error("Vault is locked.")
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name)
                .put("mime", it.mime).put("date", it.date))
        }
        val encrypted = encryptBytes(JSONObject().put("items", array).toString().toByteArray(), secret)
        File(vaultDir, "index.sgv").writeBytes(encrypted)
    }

    fun addItem(item: VaultItem) = saveItems(loadItems() + item)
}
