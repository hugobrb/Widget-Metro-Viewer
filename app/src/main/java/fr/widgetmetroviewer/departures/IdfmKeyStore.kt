package fr.widgetmetroviewer.departures

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Clé PRIM chiffrée uniquement dans noBackupFilesDir ; clé AES non exportable du Keystore. */
internal class IdfmKeyStore(
    context: Context,
    private val alias: String = ALIAS,
    fileName: String = "idfm_key.enc"
) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))

    sealed interface State {
        data object Missing : State
        class Available(val value: String) : State {
            override fun toString(): String = "Available([REDACTED])"
        }
        data object Unreadable : State
    }

    fun read(): State = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized State.Missing
        try {
            val data = file.readFully()
            require(data.size in 30..16384 && data[0].toInt() == 1)
            val ivSize = data[1].toInt() and 255
            require(ivSize == 12 && data.size > ivSize + 18)
            val key = keyStore().getKey(alias, null) as? SecretKey ?: return@synchronized State.Unreadable
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(2, 2 + ivSize)))
            val plain = cipher.doFinal(data.copyOfRange(2 + ivSize, data.size))
            try {
                val value = String(plain, Charsets.UTF_8)
                if (value.isBlank()) State.Unreadable else State.Available(value)
            } finally { plain.fill(0) }
        } catch (_: Exception) {
            // Ni clé ni exception du fournisseur cryptographique dans les logs ou l'UI.
            State.Unreadable
        }
    }

    fun save(value: String): Boolean = synchronized(lock) {
        if (value.isBlank() || value.length > 4096 || value.any { it.code !in 33..126 }) return@synchronized false
        try {
            val store = keyStore()
            var key = store.getKey(alias, null) as? SecretKey ?: generateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key)
            } catch (_: InvalidKeyException) {
                // Une clé invalidée est remplacée lors de l'enregistrement explicite d'une nouvelle clé PRIM.
                store.deleteEntry(alias)
                key = generateKey()
                cipher.init(Cipher.ENCRYPT_MODE, key)
            }
            val plain = value.toByteArray(Charsets.UTF_8)
            val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
            val iv = cipher.iv
            require(iv.size == 12)
            val payload = byteArrayOf(1, iv.size.toByte()) + iv + encrypted
            val stream = file.startWrite()
            try {
                stream.write(payload)
                file.finishWrite(stream)
            } catch (_: Exception) {
                file.failWrite(stream)
                return@synchronized false
            }
            true
        } catch (_: Exception) { false }
    }

    fun delete(): Boolean = synchronized(lock) {
        try {
            file.delete()
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            keyStore().deleteEntry(alias)
            true
        } catch (_: Exception) { false }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build())
    }.generateKey()

    private companion object {
        const val ALIAS = "metro_viewer_idfm_aes_v1"
        val lock = Any()
    }
}
