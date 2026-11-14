package com.example.porciapp.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Sesión del usuario autenticado contra el servidor. */
data class Session(
    val token: String,
    val expiresAtMillis: Long,
    val userId: String,
    val nombre: String,
    val email: String,
    val rolServidor: String,
    val matricula: String,
    val cargo: String,
    val debeCambiarPassword: Boolean
) {
    val rol: UserRole get() = UserRole.fromServer(rolServidor) ?: UserRole.OPERARIO

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAtMillis in 1..now

    fun toProfile(): UserProfile = UserProfile(
        id = userId,
        nombre = nombre,
        cargo = cargo,
        rol = rol,
        identificacion = matricula.ifBlank { email }
    )
}

/**
 * Guarda la sesión cifrada (AES-256-GCM con clave en Android Keystore, no exportable).
 * El token nunca se escribe en texto plano ni se incluye en copias de seguridad.
 */
class SessionManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun current(): Session? = _session.value

    /** Sesión vigente (existe y no ha vencido). Si venció se descarta. */
    fun valid(): Session? {
        val s = _session.value ?: return null
        if (s.isExpired()) {
            clear()
            return null
        }
        return s
    }

    fun save(session: Session) {
        val encrypted = encrypt(gson.toJson(session))
        prefs.edit().putString(KEY_SESSION, encrypted).apply()
        _session.value = session
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
        _session.value = null
    }

    // ---- Dirección del servidor (no sensible) ----

    fun serverUrl(): String = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER

    fun setServerUrl(raw: String) {
        prefs.edit().putString(KEY_SERVER, normalizeUrl(raw)).apply()
    }

    private fun readSession(): Session? {
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            gson.fromJson(decrypt(stored), Session::class.java)
        } catch (_: Exception) {
            // Clave invalidada o dato corrupto: se exige iniciar sesión de nuevo
            prefs.edit().remove(KEY_SESSION).apply()
            null
        }
    }

    // ---- Cifrado ----

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, GCM_IV_BYTES)
        val data = all.copyOfRange(GCM_IV_BYTES, all.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    companion object {
        /** Servidor por defecto en la red local de la granja/PC. */
        const val DEFAULT_SERVER = "http://192.168.0.208:5153"

        private const val PREFS = "agropig_session"
        private const val KEY_SESSION = "session"
        private const val KEY_SERVER = "server_url"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "agropig_session_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        @Volatile
        private var instance: SessionManager? = null

        fun get(context: Context): SessionManager =
            instance ?: synchronized(this) {
                instance ?: SessionManager(context).also { instance = it }
            }

        /** Limpia lo que el usuario escribe: agrega http:// si falta, quita rutas y la barra final. */
        fun normalizeUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return DEFAULT_SERVER
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "http://$url"
            val schemeEnd = url.indexOf("://") + 3
            val pathStart = url.indexOf('/', schemeEnd)
            if (pathStart > 0) url = url.substring(0, pathStart)
            return url.trimEnd('/')
        }
    }
}
