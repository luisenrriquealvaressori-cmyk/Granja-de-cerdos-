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

    private val _serverUrl: MutableStateFlow<String>
    val serverUrlFlow: StateFlow<String>

    init {
        if (!prefs.getBoolean(KEY_CLOUD_MIGRATED, false)) {
            val storedServer = prefs.getString(KEY_SERVER, null)
            val migrated = if (storedServer.isNullOrBlank() || storedServer == LEGACY_LAN_SERVER) {
                DEFAULT_SERVER
            } else {
                normalizeUrl(storedServer)
            }
            prefs.edit()
                .putString(KEY_SERVER, migrated)
                .putBoolean(KEY_CLOUD_MIGRATED, true)
                .apply()
        }
        _serverUrl = MutableStateFlow(serverUrl())
        serverUrlFlow = _serverUrl.asStateFlow()
    }

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
        val json = gson.toJson(session)
        try {
            val encrypted = encrypt(json)
            prefs.edit().putString(KEY_SESSION, encrypted).apply()
        } catch (_: Exception) {
            try {
                deleteSecretKey()
                val encryptedRetry = encrypt(json)
                prefs.edit().putString(KEY_SESSION, encryptedRetry).apply()
            } catch (_: Exception) {
                prefs.edit().remove(KEY_SESSION).apply()
            }
        }
        _session.value = session
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
        _session.value = null
    }

    // ---- Dirección del servidor (no sensible) ----

    fun serverUrl(): String = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER

    fun setServerUrl(raw: String) {
        val normalized = normalizeUrl(raw)
        prefs.edit().putString(KEY_SERVER, normalized).apply()
        _serverUrl.value = normalized
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

    private fun deleteSecretKey() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
        }
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
        /** Servidor productivo por defecto en Render Cloud. */
        const val DEFAULT_SERVER = "https://agropig-web.onrender.com"

        /** Dirección LAN heredada para redes locales sin salida a Internet. */
        const val LEGACY_LAN_SERVER = "http://192.168.0.208:5153"

        private const val PREFS = "agropig_session"
        private const val KEY_SESSION = "session"
        private const val KEY_SERVER = "server_url"
        private const val KEY_CLOUD_MIGRATED = "cloud_server_migrated_v2"
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

        private fun isLocalOrLanHost(hostPort: String): Boolean {
            val host = hostPort.substringBefore(':').lowercase()
            if (host == "localhost" || host.endsWith(".local")) return true
            if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return true
            if (host.startsWith("172.")) {
                val secondOctet = host.removePrefix("172.").substringBefore('.').toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) return true
            }
            val portPart = hostPort.substringAfter(':', "")
            return portPart.isNotEmpty() && portPart.all { it.isDigit() }
        }

        /**
         * Normaliza lo que el usuario escribe:
         * - Dominios públicos (ej. *.onrender.com) usan https://
         * - Hosts LAN/emulador/con puerto explícito usan http://
         * - Si es *.onrender.com con http:// se fuerza a https://
         * - Recorta rutas posteriores al host y barras finales.
         */
        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trim('"', '\'', ' ')
            if (url.isEmpty()) return DEFAULT_SERVER
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                val candidateHostPort = url.substringBefore('/').lowercase()
                val scheme = if (isLocalOrLanHost(candidateHostPort)) "http" else "https"
                url = "$scheme://$url"
            }
            val schemeEnd = url.indexOf("://") + 3
            val scheme = url.substring(0, schemeEnd - 3).lowercase()
            val authority = url.substring(schemeEnd).substringBefore('/').trimEnd('/')
            if (authority.isEmpty()) return DEFAULT_SERVER
            val hostOnly = authority.substringBefore(':').lowercase()
            val finalScheme = if (scheme == "http" && hostOnly.endsWith(".onrender.com")) "https" else scheme
            return "$finalScheme://$authority"
        }
    }
}
