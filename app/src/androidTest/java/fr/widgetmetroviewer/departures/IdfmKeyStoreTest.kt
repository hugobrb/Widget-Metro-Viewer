package fr.widgetmetroviewer.departures

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class IdfmKeyStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "metro_idfm_instrumentation_test_only"
    private val fileName = "idfm_instrumentation_test_only.enc"
    private val sample = "test_only_not_a_real_api_credential"
    private fun store() = IdfmKeyStore(context, alias, fileName)

    @Test fun encryptedKeySurvivesNewInstanceAndCanBeDeleted() {
        val store = store()
        store.delete()
        try {
            assertTrue(store.read() is IdfmKeyStore.State.Missing)
            assertTrue(store.save(sample))
            val read = store().read()
            assertTrue(read is IdfmKeyStore.State.Available)
            assertEquals(sample, (read as IdfmKeyStore.State.Available).value)
            val file = File(context.noBackupFilesDir, fileName)
            assertTrue(file.isFile)
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(sample))
            val firstCiphertext = file.readBytes()
            assertTrue(store.save(sample))
            assertFalse(firstCiphertext.contentEquals(file.readBytes()))
            assertTrue(store.delete())
            assertTrue(store().read() is IdfmKeyStore.State.Missing)
        } finally { store.delete() }
    }

    @Test fun tamperedCiphertextIsReportedWithoutExposingSecret() {
        val store = store()
        try {
            assertTrue(store.save(sample))
            val file = File(context.noBackupFilesDir, fileName)
            val data = file.readBytes()
            data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
            file.writeBytes(data)
            assertTrue(store.read() is IdfmKeyStore.State.Unreadable)
            assertTrue(store.save(sample))
            assertTrue(store.read() is IdfmKeyStore.State.Available)
        } finally { store.delete() }
    }

    @Test fun missingKeystoreKeyIsRecoverableAndInvalidInputIsRejected() {
        val store = store()
        try {
            assertFalse(store.save(""))
            assertFalse(store.save("invalid key"))
            assertTrue(store.save(sample))
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            assertTrue(store.read() is IdfmKeyStore.State.Unreadable)
            assertTrue(store.save(sample))
            assertTrue(store.read() is IdfmKeyStore.State.Available)
        } finally { store.delete() }
    }
}
