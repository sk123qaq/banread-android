package app.banread.ai

import android.content.Context
import android.util.Base64
import app.banread.model.SecretVaultPort
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class InMemorySecretVaultAdapter : SecretVaultPort {
    private val secrets = ConcurrentHashMap<String, String>()

    override suspend fun putSecret(alias: String, plaintext: String) {
        secrets[alias] = plaintext
    }

    override suspend fun getSecret(alias: String): String? = secrets[alias]

    override suspend fun deleteSecret(alias: String) {
        secrets.remove(alias)
    }
}

/**
 * Google Tink (`AndroidKeysetManager`, AES-256-GCM) secret vault backed by
 * `android-keystore://banread_byok_master_key` and a private encrypted file store.
 */
class TinkKeystoreVaultAdapter(
    context: Context,
    private val storageDir: File = File((context.applicationContext ?: context).filesDir, "banread_byok_vault"),
    private val keysetName: String = "banread_tink_keyset",
    private val prefFileName: String = "banread_byok_prefs",
    private val masterKeyUri: String = DEFAULT_MASTER_KEY_URI,
) : SecretVaultPort {

    private val appContext: Context = context.applicationContext ?: context
    private val mutex = Mutex()

    @Volatile
    private var cachedAead: Aead? = null

    private fun getOrCreateAead(): Aead {
        cachedAead?.let { return it }
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, keysetName, prefFileName)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(masterKeyUri)
            .build()
            .keysetHandle
        val aead = keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        cachedAead = aead
        return aead
    }

    override suspend fun putSecret(alias: String, plaintext: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            storageDir.mkdirs()
            val aead = getOrCreateAead()
            val ciphertext = aead.encrypt(
                plaintext.toByteArray(Charsets.UTF_8),
                alias.toByteArray(Charsets.UTF_8),
            )
            val encoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            val target = secretFile(alias)
            val tmp = File(storageDir, "${target.name}.tmp")
            tmp.writeText(encoded, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(encoded, Charsets.UTF_8)
                tmp.delete()
            }
        }
        Unit
    }

    override suspend fun getSecret(alias: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = secretFile(alias)
            if (!target.isFile) return@withLock null
            val encoded = target.readText(Charsets.UTF_8).trim()
            if (encoded.isEmpty()) return@withLock null
            val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
            val aead = getOrCreateAead()
            val plaintext = aead.decrypt(ciphertext, alias.toByteArray(Charsets.UTF_8))
            String(plaintext, Charsets.UTF_8)
        }
    }

    override suspend fun deleteSecret(alias: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = secretFile(alias)
            if (target.exists()) {
                target.delete()
            }
        }
        Unit
    }

    private fun secretFile(alias: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(alias.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(storageDir, "$digest.enc")
    }

    companion object {
        const val DEFAULT_MASTER_KEY_URI = "android-keystore://banread_byok_master_key"
    }
}
