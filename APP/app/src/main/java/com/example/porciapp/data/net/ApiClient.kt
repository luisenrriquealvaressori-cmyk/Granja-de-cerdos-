package com.example.porciapp.data.net

import android.util.Log
import com.example.porciapp.data.auth.Session
import com.example.porciapp.data.auth.SessionManager
import com.example.porciapp.data.model.UserRole
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Fail(
        val kind: Kind,
        val message: String,
        val retryAfterSeconds: Int? = null
    ) : ApiResult<Nothing>()

    enum class Kind { NETWORK, UNAUTHORIZED, FORBIDDEN, PASSWORD_CHANGE_REQUIRED, LOCKED, BAD_REQUEST, SERVER }
}

/** Cliente HTTP mínimo hacia el backend AgroPig (JWT Bearer). Sin reintentos ocultos ni servidores alternos. */
class ApiClient(private val sessions: SessionManager) {

    private data class Raw(val code: Int, val body: String)

    suspend fun login(email: String, password: String): ApiResult<Session> {
        val body = JsonObject().apply {
            addProperty("email", email.trim())
            addProperty("password", password)
        }
        return when (val r = call("POST", "/api/v1/auth/login", body, authenticated = false)) {
            is ApiResult.Fail -> r
            is ApiResult.Ok -> {
                val json = r.value
                val token = json.str("token")
                val userElement = json.get("user")
                if (token.isBlank() || userElement == null || !userElement.isJsonObject) {
                    Log.w(TAG, "Respuesta de login incompleta: falta token u objeto user.")
                    return ApiResult.Fail(
                        ApiResult.Kind.SERVER,
                        "Respuesta de autenticación incompleta del servidor."
                    )
                }
                val user = userElement.asJsonObject
                val rolServidor = user.str("rol")
                if (UserRole.fromServer(rolServidor) == null) {
                    return ApiResult.Fail(ApiResult.Kind.FORBIDDEN, "Tu cuenta tiene un rol que la app móvil no admite.")
                }
                val session = Session(
                    token = token,
                    expiresAtMillis = parseExpiry(json.str("expiresAt")),
                    userId = user.str("id"),
                    nombre = user.str("nombre"),
                    email = user.str("email"),
                    rolServidor = rolServidor,
                    matricula = user.str("matricula"),
                    cargo = user.str("cargo"),
                    debeCambiarPassword = user.has("debeCambiarPassword") && !user.get("debeCambiarPassword").isJsonNull &&
                        user.get("debeCambiarPassword").asBoolean
                )
                sessions.save(session)
                ApiResult.Ok(session)
            }
        }
    }

    /** Cambia la contraseña y reemplaza el token de la sesión por uno sin la marca de cambio obligatorio. */
    suspend fun changePassword(actual: String, nueva: String): ApiResult<Session> {
        val current = sessions.current()
            ?: return ApiResult.Fail(ApiResult.Kind.UNAUTHORIZED, "La sesión terminó. Inicia sesión de nuevo.")
        val body = JsonObject().apply {
            addProperty("actual", actual)
            addProperty("nueva", nueva)
        }
        return when (val r = call("POST", "/api/v1/auth/change-password", body, authenticated = true)) {
            is ApiResult.Fail -> r
            is ApiResult.Ok -> {
                val updated = current.copy(
                    token = r.value.str("token"),
                    expiresAtMillis = parseExpiry(r.value.str("expiresAt")),
                    debeCambiarPassword = false
                )
                sessions.save(updated)
                ApiResult.Ok(updated)
            }
        }
    }

    suspend fun fetchCatalog(): ApiResult<String> = when (val r = callRaw("GET", "/api/v1/movil/catalogo", null, true)) {
        is ApiResult.Fail -> r
        is ApiResult.Ok -> ApiResult.Ok(r.value.body)
    }

    suspend fun saveUiPreferences(body: JsonObject): ApiResult<JsonObject> =
        call("PUT", "/api/v1/movil/preferencias-ui", body, authenticated = true)

    suspend fun postSyncBatch(batch: JsonObject): ApiResult<JsonObject> =
        call("POST", "/api/v1/sync/batch", batch, authenticated = true)

    // ------------------------------------------------------------------------

    private suspend fun call(method: String, path: String, body: JsonObject?, authenticated: Boolean): ApiResult<JsonObject> =
        when (val r = callRaw(method, path, body, authenticated)) {
            is ApiResult.Fail -> r
            is ApiResult.Ok -> try {
                ApiResult.Ok(JsonParser.parseString(r.value.body).asJsonObject)
            } catch (e: Exception) {
                Log.w(TAG, "JSON inválido recibido de $method $path: ${e.message}")
                ApiResult.Fail(ApiResult.Kind.SERVER, "El servidor respondió con un formato inesperado.")
            }
        }

    private suspend fun callRaw(method: String, path: String, body: JsonObject?, authenticated: Boolean): ApiResult<Raw> =
        withContext(Dispatchers.IO) {
            val base = sessions.serverUrl()
            val token = if (authenticated) sessions.current()?.token else null
            if (authenticated && token == null) {
                return@withContext ApiResult.Fail(ApiResult.Kind.UNAUTHORIZED, "La sesión terminó. Inicia sesión de nuevo.")
            }
            var currentUrlStr = base + path
            var redirects = 0
            while (true) {
                val currentUrl = try {
                    URL(currentUrlStr)
                } catch (e: Exception) {
                    Log.w(TAG, "URL inválida para $method $path: $currentUrlStr (${e.message})")
                    return@withContext ApiResult.Fail(ApiResult.Kind.NETWORK, "La dirección del servidor no es válida.")
                }
                val connection = try {
                    currentUrl.openConnection() as HttpURLConnection
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo abrir conexión a $currentUrlStr: ${e.message}")
                    return@withContext ApiResult.Fail(ApiResult.Kind.NETWORK, "La dirección del servidor no es válida.")
                }
                try {
                    val isHttps = currentUrlStr.startsWith("https://", ignoreCase = true)
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = method
                    connection.connectTimeout = if (isHttps) 25_000 else 10_000
                    connection.readTimeout = if (isHttps) 60_000 else 25_000
                    connection.setRequestProperty("Accept", "application/json")
                    if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                    if (body != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                    val code = connection.responseCode
                    if (code in REDIRECT_CODES && redirects < MAX_REDIRECTS) {
                        val location = connection.getHeaderField("Location")?.trim()
                        if (!location.isNullOrEmpty()) {
                            val resolved = try {
                                URL(currentUrl, location).toString()
                            } catch (_: Exception) {
                                location
                            }
                            Log.i(TAG, "Redirección HTTP $code en $method $path -> $resolved")
                            currentUrlStr = resolved
                            redirects++
                            continue
                        }
                    }
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (code in 200..299) {
                        return@withContext ApiResult.Ok(Raw(code, text))
                    } else {
                        Log.w(TAG, "HTTP $code en $method $path ($currentUrlStr): ${text.take(200)}")
                        return@withContext mapError(code, text)
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Error de red en $method $path ($currentUrlStr): ${e.javaClass.simpleName}: ${e.message}")
                    return@withContext ApiResult.Fail(ApiResult.Kind.NETWORK, "No hay conexión con el servidor ($base).")
                } finally {
                    connection.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ApiResult.Fail(ApiResult.Kind.NETWORK, "No hay conexión con el servidor ($base).")
        }

    private fun mapError(code: Int, text: String): ApiResult.Fail {
        val json = try { JsonParser.parseString(text).asJsonObject } catch (_: Exception) { null }
        val message = json?.str("error").orEmpty()
        return when (code) {
            400 -> ApiResult.Fail(ApiResult.Kind.BAD_REQUEST, message.ifBlank { "Solicitud inválida." })
            401 -> ApiResult.Fail(ApiResult.Kind.UNAUTHORIZED, message.ifBlank { "Credenciales inválidas." })
            403 -> if (json?.str("code") == "PASSWORD_CHANGE_REQUIRED")
                ApiResult.Fail(ApiResult.Kind.PASSWORD_CHANGE_REQUIRED, message.ifBlank { "Debes cambiar tu contraseña." })
            else ApiResult.Fail(ApiResult.Kind.FORBIDDEN, message.ifBlank { "Tu rol no tiene permiso para esta acción." })
            429 -> ApiResult.Fail(
                ApiResult.Kind.LOCKED,
                message.ifBlank { "Demasiados intentos. Espera unos minutos." },
                json?.get("retryAfterSeconds")?.takeIf { !it.isJsonNull }?.asInt
            )
            else -> ApiResult.Fail(
                ApiResult.Kind.SERVER,
                message.ifBlank { "El servidor no pudo procesar la solicitud (código $code)." }
            )
        }
    }

    private fun JsonObject.str(name: String): String =
        if (has(name) && !get(name).isJsonNull) get(name).asString else ""

    private fun parseExpiry(raw: String): Long = try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis() + 12 * 60 * 60 * 1000L
    }

    companion object {
        private const val TAG = "ApiClient"
        private const val MAX_REDIRECTS = 2
        private val REDIRECT_CODES = setOf(301, 302, 307, 308)
    }
}
