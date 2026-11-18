package p32929.easypasscodelock.Utils

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.lockscreenDataStore by preferencesDataStore(name = "lockscreen_storage")

/**
 * Stores the lockscreen PIN encrypted with Tink AES-256-GCM backed by the Android Keystore.
 * The Tink keyset is stored in a regular SharedPreferences file wrapped by the Keystore master
 * key; the ciphertext lives in DataStore.
 */
class LockscreenStorage(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dataStore = appContext.lockscreenDataStore

    companion object {
        private const val KEYSET_NAME = "lockscreen_keyset"
        private const val KEYSET_PREF_FILE = "lockscreen_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://lockscreen_master_key"
        private val PIN_KEY = stringPreferencesKey("encrypted_pin")
        // Velo: the panic PIN (VeloPanic), encrypted the same way
        private val PANIC_PIN_KEY = stringPreferencesKey("encrypted_panic_pin")
    }

    private fun aead(): Aead {
        AeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(AesGcmKeyManager.aes256GcmTemplate())
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        return handle.getPrimitive(Aead::class.java)
    }

    /** Returns the stored PIN, or null if none is set. */
    fun readPin(): String? = read(PIN_KEY)

    /** Encrypts and stores the PIN. Pass null to clear. */
    fun writePin(pin: String?) = write(PIN_KEY, pin)

    /** Velo: the panic PIN, or null if none is set. */
    fun readPanicPin(): String? = read(PANIC_PIN_KEY)

    /** Velo: encrypts and stores the panic PIN. Pass null to clear. */
    fun writePanicPin(pin: String?) = write(PANIC_PIN_KEY, pin)

    private fun read(key: androidx.datastore.preferences.core.Preferences.Key<String>): String? {
        val encoded: String = runBlocking {
            dataStore.data.map { it[key] }.first()
        } ?: return null
        val plainBytes = aead().decrypt(Base64.decode(encoded, Base64.NO_WRAP), null)
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun write(key: androidx.datastore.preferences.core.Preferences.Key<String>, pin: String?) {
        if (pin == null) {
            runBlocking { dataStore.edit { it.remove(key) } }
            return
        }
        val ciphertext = aead().encrypt(pin.toByteArray(Charsets.UTF_8), null)
        runBlocking { dataStore.edit { it[key] = Base64.encodeToString(ciphertext, Base64.NO_WRAP) } }
    }
}
