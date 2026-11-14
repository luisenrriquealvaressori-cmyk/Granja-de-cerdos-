package com.example.porciapp.data.repository

import android.content.Context
import com.example.porciapp.data.net.ApiClient
import com.example.porciapp.data.net.ApiResult
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CatalogCerda(val caravana: String = "", val estado: String = "", val jaula: String = "", val galpon: String = "", val paridad: Int = 0)
data class CatalogVerraco(val codigo: String = "", val nombre: String = "")
data class CatalogInsumo(val nombre: String = "", val unidad: String = "", val stock: Double = 0.0)
data class CatalogLote(val codigo: String = "", val galpon: String = "", val etapa: String = "", val animales: Int = 0)
data class CatalogSilo(val codigo: String = "", val nombre: String = "", val capacidadKg: Double = 0.0, val nivelActualKg: Double = 0.0, val pctOcupacion: Double = 0.0, val insumo: String = "", val diasAutonomia: Int = 0)
data class CatalogTarea(val id: String = "", val titulo: String = "", val descripcion: String = "", val prioridad: String = "Media", val tipoActividad: String = "Manejo", val estado: String = "pendiente", val fechaProgramada: String = "", val horaProgramada: String = "", val galpon: String = "", val lote: String = "", val asignadoA: String = "")
data class CatalogNotificacion(val id: String = "", val titulo: String = "", val mensaje: String = "", val tipo: String = "alerta", val severidad: String = "media", val fechaHora: Long = 0L, val leida: Boolean = false)

/** Datos de referencia reales descargados del servidor (cerdas, verracos, medicamentos, alimentos, lotes, silos, tareas, notificaciones). */
data class Catalog(
    val generadoEn: Long = 0L,
    val cerdas: List<CatalogCerda> = emptyList(),
    val verracos: List<CatalogVerraco> = emptyList(),
    val medicamentos: List<CatalogInsumo> = emptyList(),
    val alimentos: List<CatalogInsumo> = emptyList(),
    val lotes: List<CatalogLote> = emptyList(),
    val silos: List<CatalogSilo> = emptyList(),
    val tareas: List<CatalogTarea> = emptyList(),
    val notificaciones: List<CatalogNotificacion> = emptyList()
) {
    val vacio: Boolean get() = generadoEn == 0L

    /** Gson puede inyectar nulls en campos nuevos cuando lee un JSON cacheado viejo. */
    fun sanitized(): Catalog = copy(
        cerdas = cerdas.orEmpty(),
        verracos = verracos.orEmpty(),
        medicamentos = medicamentos.orEmpty(),
        alimentos = alimentos.orEmpty(),
        lotes = lotes.orEmpty(),
        silos = silos.orEmpty(),
        tareas = tareas.orEmpty(),
        notificaciones = notificaciones.orEmpty()
    )
}

/** Copia local del catálogo para poder trabajar sin conexión. Se borra al cerrar sesión. */
class CatalogStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("agropig_catalog", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _catalog = MutableStateFlow(read())
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    private fun read(): Catalog = try {
        prefs.getString("json", null)?.let { gson.fromJson(it, Catalog::class.java)?.sanitized() } ?: Catalog()
    } catch (_: Exception) {
        Catalog()
    }

    /** Descarga y guarda el catálogo. Devuelve true si se actualizó. */
    suspend fun refresh(api: ApiClient): ApiResult<Catalog> = when (val r = api.fetchCatalog()) {
        is ApiResult.Fail -> r
        is ApiResult.Ok -> try {
            val parsed = (gson.fromJson(r.value, Catalog::class.java) ?: Catalog()).sanitized()
            prefs.edit().putString("json", gson.toJson(parsed)).apply()
            _catalog.value = parsed
            ApiResult.Ok(parsed)
        } catch (_: Exception) {
            ApiResult.Fail(ApiResult.Kind.SERVER, "El catálogo recibido no tiene el formato esperado.")
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
        _catalog.value = Catalog()
    }

    companion object {
        @Volatile
        private var instance: CatalogStore? = null

        fun get(context: Context): CatalogStore =
            instance ?: synchronized(this) {
                instance ?: CatalogStore(context).also { instance = it }
            }
    }
}
