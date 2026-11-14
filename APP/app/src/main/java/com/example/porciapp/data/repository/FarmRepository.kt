package com.example.porciapp.data.repository

import android.content.Context
import com.example.porciapp.data.auth.SessionManager
import com.example.porciapp.data.local.database.PorciDatabase
import com.example.porciapp.data.local.entities.*
import com.example.porciapp.data.model.FeedSupplyRecord
import com.example.porciapp.data.model.MedicalTreatmentRecord
import com.example.porciapp.data.model.ReproductiveEventRecord
import com.example.porciapp.data.net.ApiClient
import com.example.porciapp.data.net.ApiResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import java.util.UUID

class FarmRepository(private val context: Context) {

    private val db = PorciDatabase.getDatabase(context)
    private val cerdoDao = db.cerdoDao()
    private val tratamientoDao = db.tratamientoDao()
    private val eventoReproductivoDao = db.eventoReproductivoDao()
    private val consumoAlimentoDao = db.consumoAlimentoDao()
    private val tareaDao = db.tareaDao()
    private val syncQueueDao = db.syncQueueDao()
    private val operacionGastoDao = db.operacionGastoDao()
    private val bajaMortalidadDao = db.bajaMortalidadDao()
    private val notificacionDao = db.notificacionDao()
    private val gson = Gson()

    val sessionManager: SessionManager = SessionManager.get(context)
    val apiClient: ApiClient = ApiClient(sessionManager)
    val catalogStore: CatalogStore = CatalogStore.get(context)

    // Timestamp de inicio del día local (00:00:00) para métricas precisas
    private fun getStartOfDayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // Flujos observables para la UI
    val pendingSyncCount: Flow<Int> = syncQueueDao.getPendingCountFlow()
    val failedSyncItems: Flow<List<SyncQueueEntity>> = syncQueueDao.getFailedFlow()
    val tareasFlow: Flow<List<TareaEntity>> = tareaDao.getAllFlow()
    val cerdosFlow: Flow<List<CerdoEntity>> = cerdoDao.getAllFlow()
    val tratamientosFlow: Flow<List<TratamientoEntity>> = tratamientoDao.getAllFlow()
    val eventosFlow: Flow<List<EventoReproductivoEntity>> = eventoReproductivoDao.getAllFlow()
    val consumosFlow: Flow<List<ConsumoAlimentoEntity>> = consumoAlimentoDao.getAllFlow()
    val operacionesGastosFlow: Flow<List<OperacionGastoEntity>> = operacionGastoDao.getAllFlow()
    val bajasFlow: Flow<List<BajaMortalidadEntity>> = bajaMortalidadDao.getAllFlow()
    val notificacionesFlow: Flow<List<NotificacionEntity>> = notificacionDao.getAllFlow()
    val notificacionesNoLeidasCount: Flow<Int> = notificacionDao.countNoLeidasFlow()
    val tareasPendientesCount: Flow<Int> = tareaDao.countPendientesFlow()

    // Métricas reactivas dinámicas calculadas de Room para la jornada de hoy
    val partosHoyFlow: Flow<Int> = eventoReproductivoDao.countPartosDesdeFlow(getStartOfDayMillis())
    val lechonesHoyFlow: Flow<Int> = eventoReproductivoDao.sumLechonesVivosDesdeFlow(getStartOfDayMillis())
    val alimentoKgHoyFlow: Flow<Double> = consumoAlimentoDao.sumKgDesdeFlow(getStartOfDayMillis())
    val tratamientosHoyFlow: Flow<Int> = tratamientoDao.countDesdeFlow(getStartOfDayMillis())
    val bajasHoyCount: Flow<Int> = bajaMortalidadDao.countBajasDesdeFlow(getStartOfDayMillis())

    suspend fun getCerdoByCrotal(crotal: String): CerdoEntity? = cerdoDao.getByCrotal(crotal)

    suspend fun saveCerdo(cerdo: CerdoEntity) {
        cerdoDao.insert(cerdo)
        val queueItem = SyncQueueEntity(
            id = UUID.randomUUID().toString(),
            recordId = cerdo.id,
            entityType = "CERDO",
            operation = SyncOperation.CREATE,
            payloadJson = gson.toJson(cerdo),
            timestamp = System.currentTimeMillis(),
            status = SyncStatus.PENDING
        )
        syncQueueDao.insert(queueItem)
    }

    suspend fun toggleTask(id: String, completed: Boolean, observaciones: String = "") {
        val hora = if (completed) {
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            sdf.format(java.util.Date())
        } else null
        tareaDao.updateCompletion(id, completed, hora, observaciones)
        val queueId = UUID.randomUUID().toString()

        val taskUpdate = mapOf(
            "id" to id,
            "completada" to completed,
            "estado" to if (completed) "completada" else "pendiente",
            "completadoPor" to (sessionManager.current()?.nombre ?: "Operario"),
            "observaciones" to observaciones,
            // Clave de idempotencia por acción: marcar/desmarcar la misma tarea dos veces no se pisa
            "syncId" to queueId
        )
        val queueItem = SyncQueueEntity(
            id = queueId,
            recordId = id,
            entityType = "TAREA_ESTADO",
            operation = SyncOperation.UPDATE,
            payloadJson = gson.toJson(taskUpdate),
            timestamp = System.currentTimeMillis(),
            status = SyncStatus.PENDING
        )
        syncQueueDao.insert(queueItem)
    }

    suspend fun scheduleTask(tarea: TareaEntity): String {
        tareaDao.insert(tarea)
        val queueItem = SyncQueueEntity(
            id = UUID.randomUUID().toString(),
            recordId = tarea.id,
            entityType = "TAREA",
            operation = SyncOperation.CREATE,
            payloadJson = gson.toJson(tarea),
            timestamp = System.currentTimeMillis(),
            status = SyncStatus.PENDING
        )
        syncQueueDao.insert(queueItem)
        return tarea.id
    }

    suspend fun marcarNotificacionLeida(id: String) {
        notificacionDao.marcarLeida(id)
    }

    suspend fun getPendingCount(): Int = syncQueueDao.getPendingCount()

    suspend fun retryFailedSync() {
        syncQueueDao.retryFailed()
    }

    // 1. Guardar Tratamiento Médico con Patrón Outbox y Auditoría Clínica Completa
    suspend fun saveTreatment(record: MedicalTreatmentRecord): String {
        val activeSession = sessionManager.current()
        val responsable = if (record.veterinarioResponsable.isNotBlank()) {
            record.veterinarioResponsable
        } else {
            activeSession?.nombre ?: record.operario
        }
        val matricula = if (record.matriculaVeterinario.isNotBlank()) {
            record.matriculaVeterinario
        } else {
            activeSession?.matricula ?: ""
        }

        val recordAudit = record.copy(
            veterinarioResponsable = responsable,
            matriculaVeterinario = matricula
        )

        val auditDetail = buildString {
            if (recordAudit.diagnostico.isNotBlank()) append("[${recordAudit.diagnostico}] ")
            append("Dosis: ${recordAudit.dosis} ${recordAudit.unidad} (Frasco: ${recordAudit.volumenFrascoInicial} -> ${recordAudit.volumenFrascoRestante} ml). ")
            if (recordAudit.insumosUtilizados.isNotEmpty()) {
                append("Insumos: ${recordAudit.insumosUtilizados.joinToString(", ")}. ")
            }
            append("Vía: ${recordAudit.viaAdministracion}. ")
            append("Responsable: $responsable")
            if (matricula.isNotBlank()) append(" ($matricula)")
            append(". Ubicación: ${recordAudit.galponJaula}.")
            if (recordAudit.costoTotalEstimadoUsd > 0) {
                append(" Costo est: $${String.format(java.util.Locale.US, "%.2f", recordAudit.costoTotalEstimadoUsd)} USD.")
            }
            if (recordAudit.observaciones.isNotBlank()) {
                append(" Nota: ${recordAudit.observaciones}")
            }
        }

        val entity = TratamientoEntity(
            id = recordAudit.id,
            idCerdo = recordAudit.crotal,
            crotal = recordAudit.crotal,
            medicamento = recordAudit.medicamento,
            dosis = recordAudit.dosis,
            unidadMedida = recordAudit.unidad,
            fechaAplicacion = System.currentTimeMillis(),
            fechaHoraDisplay = recordAudit.fechaHora,
            idOperario = activeSession?.userId ?: responsable,
            observaciones = auditDetail,
            sincronizado = false
        )
        tratamientoDao.insert(entity)

        // Insertar en la bandeja de salida (Outbox Queue) con el objeto completo de auditoría
        // + la fecha real de aplicación (si no, el servidor registraría la hora de sincronización)
        val payloadTratamiento = gson.toJsonTree(recordAudit).asJsonObject.apply {
            addProperty("fechaAplicacion", entity.fechaAplicacion)
        }
        val queueItem = SyncQueueEntity(
            id = UUID.randomUUID().toString(),
            recordId = recordAudit.id,
            entityType = "TRATAMIENTO",
            operation = SyncOperation.CREATE,
            payloadJson = payloadTratamiento.toString(),
            timestamp = System.currentTimeMillis(),
            status = SyncStatus.PENDING
        )
        syncQueueDao.insert(queueItem)
        return recordAudit.id
    }

    // 2. Guardar Evento Reproductivo con Patrón Outbox
    suspend fun saveReproductiveEvent(record: ReproductiveEventRecord): String {
        val entity = EventoReproductivoEntity(
            id = record.id,
            idCerdo = record.crotalCerda,
            crotalCerda = record.crotalCerda,
            tipoEvento = record.tipoEvento.name,
            fechaEvento = System.currentTimeMillis(),
            fechaHoraDisplay = record.fechaEvento,
            lechonesVivos = record.lechonesVivos,
            lechonesMuertos = record.lechonesMuertos,
            momificados = record.momificados,
            lechonesDestetados = record.lechonesDestetados,
            pesoCamadaKg = record.pesoCamadaKg,
            tipoAsistencia = record.tipoAsistencia,
            oxitocinaAplicada = record.oxitocinaAplicada,
            madreEnObservacion = record.madreEnObservacion,
            verracoCodigo = record.verracoCodigo,
            tecnicaIa = record.tecnicaIa,
            turnoIa = record.turnoIa,
            condicionCorporal = record.condicionCorporal,
            sincronizado = false
        )
        eventoReproductivoDao.insert(entity)

        // Encolar en Outbox
        val queueItem = SyncQueueEntity(
            id = UUID.randomUUID().toString(),
            recordId = record.id,
            entityType = "EVENTO_REPRODUCTIVO",
            operation = SyncOperation.CREATE,
            payloadJson = gson.toJson(entity),
            timestamp = System.currentTimeMillis(),
            status = SyncStatus.PENDING
        )
        syncQueueDao.insert(queueItem)
        return record.id
    }

    // 3. Guardar Suministro de Alimento con Patrón Outbox
    suspend fun saveFeedSupply(record: FeedSupplyRecord): String {
        val activeSession = sessionManager.current()
        val operarioNombre = activeSession?.nombre ?: record.operario

        val entity = ConsumoAlimentoEntity(
            id = record.id,
            idGalpon = record.galponLote,
            galponNombre = record.galponLote,
            loteNombre = record.loteNumero,
            animalesCount = record.animalesCount,
            tipoAlimento = record.tipoAlimento,
            siloOrigen = record.siloOrigen,
            cantidadKg = record.cantidadKg,
            fechaRegistro = System.currentTimeMillis(),
            fechaHoraDisplay = record.fechaHora,
            idOperario = operarioNombre,
            notasConsumo = gson.toJson(record.notasConsumo),
            observacionAdicional = record.observacionAdicional,
            sincronizado = false
        )
        consumoAlimentoDao.insert(entity)

        // Encolar en Outbox
        val queueItem = SyncQueueEntity(
            id = UUID.randomUUID().toString(),
            recordId = record.id,
            entityType = "CONSUMO_ALIMENTO",
            operation = SyncOperation.CREATE,
            payloadJson = gson.toJson(entity),
            timestamp = System.currentTimeMillis(),
            status = SyncStatus.PENDING
        )
        syncQueueDao.insert(queueItem)
        return record.id
    }

    // 4. Guardar Registro de Mano de Obra o Gasto Operativo con Patrón Outbox
    suspend fun saveOperacionGasto(registro: OperacionGastoEntity): String {
        operacionGastoDao.insert(registro)
        val queueItem = SyncQueueEntity(
            id = UUID.randomUUID().toString(),
            recordId = registro.id,
            entityType = "OPERACION_GASTO",
            operation = SyncOperation.CREATE,
            payloadJson = gson.toJson(registro),
            timestamp = System.currentTimeMillis(),
            status = SyncStatus.PENDING
        )
        syncQueueDao.insert(queueItem)
        return registro.id
    }

    // 5. Guardar Registro de Baja / Mortalidad Clínica con Patrón Outbox
    suspend fun saveBajaMortalidad(baja: BajaMortalidadEntity): String {
        bajaMortalidadDao.insert(baja)
        val queueItem = SyncQueueEntity(
            id = UUID.randomUUID().toString(),
            recordId = baja.id,
            entityType = "BAJA_MORTALIDAD",
            operation = SyncOperation.CREATE,
            payloadJson = gson.toJson(baja),
            timestamp = System.currentTimeMillis(),
            status = SyncStatus.PENDING
        )
        syncQueueDao.insert(queueItem)
        return baja.id
    }

    // Métricas calculadas locales instantáneas
    suspend fun getFieldMetrics(): FieldMetrics {
        val partos = eventoReproductivoDao.countPartos()
        val lechones = eventoReproductivoDao.sumLechonesVivos()
        val alimento = consumoAlimentoDao.sumTotalKg()
        return FieldMetrics(
            partosHoy = if (partos > 0) partos else 0,
            lechonesVivosHoy = if (lechones > 0) lechones else 0,
            alimentoKgHoy = if (alimento > 0.0) alimento else 0.0
        )
    }

    // Procesar confirmación del servidor (Sync completion)
    suspend fun markRecordsSynced(savedIds: List<String>) {
        for (id in savedIds) {
            // Confirmación por id de ítem de cola (acciones repetibles como TAREA_ESTADO)
            syncQueueDao.getById(id)?.let { queued ->
                if (queued.entityType == "TAREA_ESTADO") tareaDao.markSynced(queued.recordId)
                syncQueueDao.deleteById(id)
            }
            tratamientoDao.markSynced(id)
            eventoReproductivoDao.markSynced(id)
            consumoAlimentoDao.markSynced(id)
            operacionGastoDao.markSynced(id)
            bajaMortalidadDao.markSynced(id)
            tareaDao.markSynced(id)
            cerdoDao.markSynced(id)
            syncQueueDao.deleteByRecordId(id)
        }
    }

    // Marcar rechazos del servidor con su motivo
    suspend fun markRecordsRejected(rejectedItems: Map<String, String>) {
        for ((id, motivo) in rejectedItems) {
            // El id rechazado puede ser el recordId del registro o el id del ítem de cola (tareas)
            syncQueueDao.markFailed(id, motivo)
            syncQueueDao.markFailedByQueueId(id, motivo)
        }
    }

    // Actualizar catálogo de animales y productos desde el servidor y sembrar tareas/notificaciones
    suspend fun refreshCatalog(): ApiResult<Catalog> {
        val res = catalogStore.refresh(apiClient)
        if (res is ApiResult.Ok) {
            val cat = res.value
            // Las tareas de demostración (TASK-0x) de versiones anteriores ya no existen en el servidor
            tareaDao.deleteLegacySeed()
            syncQueueDao.deleteLegacyTaskItems()
            if (cat.notificaciones.isNotEmpty()) {
                val notifs = cat.notificaciones.map { n ->
                    NotificacionEntity(
                        id = n.id,
                        titulo = n.titulo,
                        mensaje = n.mensaje,
                        tipo = n.tipo,
                        severidad = n.severidad,
                        fechaHora = if (n.fechaHora > 0) n.fechaHora else System.currentTimeMillis(),
                        leida = n.leida
                    )
                }
                notificacionDao.insertAll(notifs)
            }
            if (cat.tareas.isNotEmpty()) {
                val tareas = cat.tareas.map { t ->
                    TareaEntity(
                        id = t.id,
                        titulo = t.titulo,
                        descripcion = t.descripcion,
                        prioridad = t.prioridad,
                        tipoActividad = t.tipoActividad,
                        galponEtapa = t.galpon,
                        loteCodigo = t.lote.ifBlank { null },
                        completada = t.estado.equals("completada", ignoreCase = true),
                        horaProgramada = t.horaProgramada.ifBlank { "08:00" },
                        asignadoA = t.asignadoA.ifBlank { "Operario" },
                        sincronizado = true
                    )
                }
                tareaDao.insertAll(tareas)
            }
        }
        return res
    }

    sealed class DirectSyncResult {
        object NoPending : DirectSyncResult()
        data class Success(val savedCount: Int, val message: String) : DirectSyncResult()
        data class Partial(val savedCount: Int, val rejectedCount: Int, val message: String) : DirectSyncResult()
        data class Failure(val kind: ApiResult.Kind, val message: String) : DirectSyncResult()
    }

    suspend fun checkServerConnection(): Pair<Boolean, String> {
        val base = sessionManager.serverUrl()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = java.net.URL("$base/api/v1/sync/status").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code == 200) Pair(true, "Servidor conectado ($base)")
                else Pair(false, "Servidor respondió HTTP $code")
            } catch (e: Exception) {
                Pair(false, "Servidor no responde en $base")
            }
        }
    }

    suspend fun syncOutboxNow(): DirectSyncResult {
        syncQueueDao.resetInProgress()
        val pendingItems = syncQueueDao.getPendingItems()
        if (pendingItems.isEmpty()) {
            return DirectSyncResult.NoPending
        }

        // 1. Verificar sesión autenticada vigente
        val currentSession = sessionManager.valid()
        if (currentSession == null || currentSession.token.startsWith("OFFLINE_BEARER")) {
            sessionManager.clear()
            return DirectSyncResult.Failure(
                ApiResult.Kind.UNAUTHORIZED,
                "Inicia sesión con tu usuario y contraseña para sincronizar los registros pendientes."
            )
        }

        val deviceId = "ANDROID-" + (try {
            android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )
        } catch (_: Exception) { null } ?: "TERMINAL-01")

        val tratamientosList = mutableListOf<JsonObject>()
        val eventosList = mutableListOf<JsonObject>()
        val consumosList = mutableListOf<JsonObject>()
        val operacionesList = mutableListOf<JsonObject>()
        val bajasList = mutableListOf<JsonObject>()
        val tareasList = mutableListOf<JsonObject>()
        val tareasNuevasList = mutableListOf<JsonObject>()
        val cerdosList = mutableListOf<JsonObject>()
        val inProgressIds = mutableListOf<String>()

        for (item in pendingItems) {
            val jsonElement = try {
                JsonParser.parseString(item.payloadJson).asJsonObject
            } catch (_: Exception) {
                JsonObject()
            }
            var supported = true
            when (item.entityType) {
                "TRATAMIENTO" -> tratamientosList.add(jsonElement)
                "EVENTO_REPRODUCTIVO" -> eventosList.add(jsonElement)
                "CONSUMO_ALIMENTO" -> consumosList.add(jsonElement)
                "OPERACION_GASTO" -> operacionesList.add(jsonElement)
                "BAJA_MORTALIDAD" -> bajasList.add(jsonElement)
                "TAREA_ESTADO" -> tareasList.add(jsonElement)
                "TAREA" -> tareasNuevasList.add(jsonElement)
                "CERDO" -> cerdosList.add(jsonElement)
                else -> {
                    supported = false
                    syncQueueDao.markUnsupported(item.id, "Tipo '${item.entityType}' aún no soportado por el servidor")
                }
            }
            if (supported) {
                syncQueueDao.updateStatus(item.id, SyncStatus.IN_PROGRESS)
                inProgressIds.add(item.id)
            }
        }

        if (inProgressIds.isEmpty()) {
            return DirectSyncResult.NoPending
        }

        val requestBatch = JsonObject().apply {
            addProperty("deviceId", deviceId)
            addProperty("clientTimestamp", System.currentTimeMillis())
            add("tratamientos", gson.toJsonTree(tratamientosList))
            add("eventosReproductivos", gson.toJsonTree(eventosList))
            add("consumosAlimento", gson.toJsonTree(consumosList))
            add("operacionesGasto", gson.toJsonTree(operacionesList))
            add("bajasMortalidad", gson.toJsonTree(bajasList))
            add("tareasEstado", gson.toJsonTree(tareasList))
            add("tareasNuevas", gson.toJsonTree(tareasNuevasList))
            add("cerdos", gson.toJsonTree(cerdosList))
        }

        return when (val result = apiClient.postSyncBatch(requestBatch)) {
            is ApiResult.Ok -> {
                val responseJson = result.value
                val savedIds = mutableListOf<String>()
                if (responseJson.has("savedIds") && responseJson.get("savedIds").isJsonArray) {
                    for (el in responseJson.getAsJsonArray("savedIds")) {
                        savedIds.add(el.asString)
                    }
                }
                if (savedIds.isNotEmpty()) {
                    markRecordsSynced(savedIds)
                }

                var rejectedCount = 0
                if (responseJson.has("rejected") && responseJson.get("rejected").isJsonObject) {
                    val rejectedObj = responseJson.getAsJsonObject("rejected")
                    val map = mutableMapOf<String, String>()
                    for (key in rejectedObj.keySet()) {
                        map[key] = rejectedObj.get(key).asString
                    }
                    if (map.isNotEmpty()) {
                        markRecordsRejected(map)
                        rejectedCount = map.size
                    }
                }

                try { refreshCatalog() } catch (_: Exception) {}

                if (rejectedCount > 0) {
                    DirectSyncResult.Partial(
                        savedIds.size,
                        rejectedCount,
                        "Sincronizados: ${savedIds.size} confirmados, $rejectedCount rechazados por el servidor."
                    )
                } else {
                    DirectSyncResult.Success(
                        savedIds.size,
                        "Sincronización exitosa: ${savedIds.size} registros guardados en la API del servidor."
                    )
                }
            }
            is ApiResult.Fail -> {
                for (id in inProgressIds) {
                    syncQueueDao.updateStatus(id, SyncStatus.PENDING)
                }
                DirectSyncResult.Failure(result.kind, result.message)
            }
        }
    }
}

data class FieldMetrics(
    val partosHoy: Int,
    val lechonesVivosHoy: Int,
    val alimentoKgHoy: Double
)
