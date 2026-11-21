package com.example.porciapp.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "cerdos")
data class CerdoEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val codigoCrotal: String,
    val sexo: String, // "HEMBRA", "MACHO"
    val raza: String,
    val idGalpon: String,
    val jaula: String,
    val estado: String, // "GESTANTE", "LACTANTE", "DESTETADA", "ACTIVO", "BAJA"
    val fechaNacimiento: Long = System.currentTimeMillis(),
    val gestacionDias: Int = 0,
    val partoHistorico: String = "Parto #1",
    val sincronizado: Boolean = false
)

@Entity(tableName = "tratamientos_medicos")
data class TratamientoEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val idCerdo: String,
    val crotal: String,
    val medicamento: String,
    val dosis: Double,
    val unidadMedida: String, // "ml", "mg", "dosis", "pastilla"
    val fechaAplicacion: Long = System.currentTimeMillis(),
    val fechaHoraDisplay: String,
    val idOperario: String,
    val observaciones: String,
    val sincronizado: Boolean = false
)

@Entity(tableName = "eventos_reproductivos")
data class EventoReproductivoEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val idCerdo: String,
    val crotalCerda: String,
    val tipoEvento: String, // "SERVICIO_IA", "PARTO", "DESTETE"
    val fechaEvento: Long = System.currentTimeMillis(),
    val fechaHoraDisplay: String,
    val lechonesVivos: Int = 0,
    val lechonesMuertos: Int = 0,
    val momificados: Int = 0,
    val lechonesDestetados: Int = 0,
    val pesoCamadaKg: Double = 0.0,
    val tipoAsistencia: String = "Natural", // "Natural", "Asistido Leve", "Distocico"
    val oxitocinaAplicada: Boolean = false,
    val madreEnObservacion: Boolean = false,
    val verracoCodigo: String = "",
    val tecnicaIa: String = "IA Tradicional",
    val turnoIa: String = "AM",
    val condicionCorporal: Double = 3.0,
    val sincronizado: Boolean = false
)

@Entity(tableName = "consumo_alimento")
data class ConsumoAlimentoEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val idGalpon: String,
    val galponNombre: String,
    val loteNombre: String,
    val animalesCount: Int,
    val tipoAlimento: String,
    val siloOrigen: String,
    val cantidadKg: Double,
    val fechaRegistro: Long = System.currentTimeMillis(),
    val fechaHoraDisplay: String,
    val idOperario: String,
    val notasConsumo: String, // JSON o lista separada por comas
    val observacionAdicional: String,
    val sincronizado: Boolean = false,
    val siloCodigo: String? = null,
    val horarioToma: String? = null
)

@Entity(tableName = "tareas")
data class TareaEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val titulo: String,
    val descripcion: String = "",
    val prioridad: String = "media", // "baja", "media", "alta", "urgente"
    val tipoActividad: String = "Sanidad", // "Sanidad", "Reproduccion", "Alimentacion", "Manejo", "Bioseguridad", "Mantenimiento"
    val galponEtapa: String = "Área General",
    val loteCodigo: String? = null,
    val cerdaCaravana: String? = null,
    val completada: Boolean = false,
    val fechaProgramada: Long = System.currentTimeMillis(),
    val horaProgramada: String = "08:00",
    val horaCompletada: String? = null,
    val asignadoA: String = "Operario",
    val verificadoPor: String? = null,
    val observaciones: String = "",
    val sincronizado: Boolean = false
)

@Entity(tableName = "bajas_mortalidad")
data class BajaMortalidadEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val crotal: String,
    val loteCodigo: String = "",
    val galponLote: String = "",
    val causaMuerte: String, // "Aplastamiento perinatal", "Diarrea colibacilar", "Complejo respiratorio", "Desmedro (PCV2)", "Trauma", "Otra patología"
    val pesoKg: Double = 0.0,
    val etapaZootecnica: String = "Maternidad",
    val necropsiaRealizada: Boolean = false,
    val hallazgosNecropsia: String = "",
    val fechaBaja: Long = System.currentTimeMillis(),
    val fechaDisplay: String,
    val registradoPor: String,
    val observaciones: String = "",
    val sincronizado: Boolean = false
)

@Entity(tableName = "notificaciones")
data class NotificacionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val titulo: String,
    val mensaje: String,
    val tipo: String = "info", // "sanitaria", "reproduccion", "alimento", "tarea"
    val severidad: String = "preventiva", // "info", "preventiva", "critica"
    val fechaHora: Long = System.currentTimeMillis(),
    val leida: Boolean = false,
    val urlDestino: String? = null
)

@Entity(tableName = "operaciones_gastos_campo")
data class OperacionGastoEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val tipoRegistro: String, // "MANO_OBRA", "GASTO_OPERATIVO", "SANITARIO_INSUMO"
    val concepto: String,
    val categoria: String, // "Mano de Obra", "Sanitario", "Mantenimiento", "Combustible", "Alimentación", "Servicios", "Otros"
    val responsableNombre: String,
    val galponLote: String,
    val montoCordobas: Double,
    val montoDolares: Double,
    val horasOJornal: Double = 1.0,
    val fechaRegistro: Long = System.currentTimeMillis(),
    val fechaDisplay: String,
    val numeroComprobante: String = "",
    val observaciones: String = "",
    val sincronizado: Boolean = false
)

enum class SyncStatus {
    PENDING,
    IN_PROGRESS,
    SYNCED,
    FAILED
}

enum class SyncOperation {
    CREATE,
    UPDATE,
    DELETE
}

@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val recordId: String,
    val entityType: String, // "TRATAMIENTO", "EVENTO_REPRODUCTIVO", "CONSUMO_ALIMENTO", "TAREA"
    val operation: SyncOperation = SyncOperation.CREATE,
    val payloadJson: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: SyncStatus = SyncStatus.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null
)

// =========================================================================
// CATÁLOGOS MAESTROS SINCRONIZADOS DESDE POSTGRESQL & PREFERENCIAS UI POR ROL
// =========================================================================

@Entity(tableName = "cat_galpones")
data class CatGalponEntity(
    @PrimaryKey val codigo: String,
    val nombre: String,
    val tipoSistema: String,
    val capacidad: Int,
    val temperaturaC: Double,
    val humedadPct: Double
)

@Entity(tableName = "cat_jaulas")
data class CatJaulaEntity(
    @PrimaryKey val codigo: String,
    val nombre: String,
    val galponCodigo: String,
    val galponNombre: String,
    val etapaZootecnica: String,
    val capacidadMaxima: Int,
    val ocupacionActual: Int
)

@Entity(tableName = "cat_insumos")
data class CatInsumoEntity(
    @PrimaryKey val sku: String,
    val id: String,
    val nombre: String,
    val categoriaCodigo: String,
    val unidadMedida: String,
    val stockActual: Double,
    val stockMinimo: Double,
    val costoUnitarioUsd: Double,
    val siloCodigo: String
)

@Entity(tableName = "cat_labores")
data class CatLaborEntity(
    @PrimaryKey val codigo: String,
    val nombre: String,
    val tipoRegistro: String,
    val categoria: String,
    val etapaSugerida: String,
    val galponSugerido: String,
    val duracionSugerida: String,
    val tarifaReferenciaNio: Double,
    val centroCostoCodigo: String,
    val rolSugerido: String,
    val requiereInsumo: Boolean
)

@Entity(tableName = "cat_horarios")
data class CatHorarioEntity(
    @PrimaryKey val codigo: String,
    val nombre: String,
    val tipoHorario: String,
    val horaInicio: String,
    val horaFin: String,
    val categoriaActividad: String,
    val etapaAplicable: String,
    val rolResponsable: String,
    val prioridadDefault: String,
    val descripcion: String
)

@Entity(tableName = "cat_operarios")
data class CatOperarioEntity(
    @PrimaryKey val id: String,
    val nombreUsuario: String,
    val nombre: String,
    val rol: String,
    val matricula: String
)

@Entity(tableName = "cat_clinico")
data class CatClinicoEntity(
    @PrimaryKey val codigo: String,
    val tipoCatalogo: String,
    val nombre: String,
    val subcategoria: String,
    val diasRetiroReferencia: Int
)

@Entity(tableName = "preferencias_ui_usuario")
data class PreferenciaUiEntity(
    @PrimaryKey val userKey: String,
    val rolBase: String,
    val modulosVisiblesJson: String,
    val accionesRapidasJson: String,
    val navInferiorJson: String,
    val galponPredeterminado: String,
    val turnoPredeterminado: String,
    val monedaPreferida: String,
    val modoCompacto: Boolean,
    val updatedAtMs: Long
)
