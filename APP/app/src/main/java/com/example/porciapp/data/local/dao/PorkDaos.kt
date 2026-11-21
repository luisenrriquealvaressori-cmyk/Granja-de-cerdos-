package com.example.porciapp.data.local.dao

import androidx.room.*
import com.example.porciapp.data.local.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CerdoDao {
    @Query("SELECT * FROM cerdos ORDER BY codigoCrotal ASC")
    fun getAllFlow(): Flow<List<CerdoEntity>>

    @Query("SELECT * FROM cerdos WHERE codigoCrotal = :crotal LIMIT 1")
    suspend fun getByCrotal(crotal: String): CerdoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cerdo: CerdoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cerdos: List<CerdoEntity>)

    @Query("UPDATE cerdos SET sincronizado = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT * FROM cerdos WHERE sincronizado = 0")
    suspend fun getUnsynced(): List<CerdoEntity>

    @Query("DELETE FROM cerdos WHERE sincronizado = 1")
    suspend fun deleteSynced()
}

@Dao
interface TratamientoDao {
    @Query("SELECT * FROM tratamientos_medicos ORDER BY fechaAplicacion DESC")
    fun getAllFlow(): Flow<List<TratamientoEntity>>

    @Query("SELECT * FROM tratamientos_medicos WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TratamientoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tratamiento: TratamientoEntity)

    @Query("UPDATE tratamientos_medicos SET sincronizado = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT COUNT(*) FROM tratamientos_medicos WHERE fechaAplicacion >= :desde")
    fun countDesdeFlow(desde: Long): Flow<Int>
}

@Dao
interface EventoReproductivoDao {
    @Query("SELECT * FROM eventos_reproductivos ORDER BY fechaEvento DESC")
    fun getAllFlow(): Flow<List<EventoReproductivoEntity>>

    @Query("SELECT * FROM eventos_reproductivos WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EventoReproductivoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(evento: EventoReproductivoEntity)

    @Query("UPDATE eventos_reproductivos SET sincronizado = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT COUNT(*) FROM eventos_reproductivos WHERE tipoEvento = 'PARTO'")
    suspend fun countPartos(): Int

    @Query("SELECT COALESCE(SUM(lechonesVivos), 0) FROM eventos_reproductivos WHERE tipoEvento = 'PARTO'")
    suspend fun sumLechonesVivos(): Int

    @Query("SELECT COUNT(*) FROM eventos_reproductivos WHERE tipoEvento = 'PARTO' AND fechaEvento >= :desde")
    fun countPartosDesdeFlow(desde: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(lechonesVivos), 0) FROM eventos_reproductivos WHERE tipoEvento = 'PARTO' AND fechaEvento >= :desde")
    fun sumLechonesVivosDesdeFlow(desde: Long): Flow<Int>
}

@Dao
interface ConsumoAlimentoDao {
    @Query("SELECT * FROM consumo_alimento ORDER BY fechaRegistro DESC")
    fun getAllFlow(): Flow<List<ConsumoAlimentoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(consumo: ConsumoAlimentoEntity)

    @Query("UPDATE consumo_alimento SET sincronizado = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT COALESCE(SUM(cantidadKg), 0.0) FROM consumo_alimento")
    suspend fun sumTotalKg(): Double

    @Query("SELECT COALESCE(SUM(cantidadKg), 0.0) FROM consumo_alimento WHERE fechaRegistro >= :desde")
    fun sumKgDesdeFlow(desde: Long): Flow<Double>
}

@Dao
interface TareaDao {
    @Query("SELECT * FROM tareas ORDER BY completada ASC, horaProgramada ASC")
    fun getAllFlow(): Flow<List<TareaEntity>>

    @Query("SELECT * FROM tareas WHERE fechaProgramada >= :desde AND fechaProgramada <= :hasta ORDER BY horaProgramada ASC")
    fun getTareasRangoFlow(desde: Long, hasta: Long): Flow<List<TareaEntity>>

    @Query("SELECT COUNT(*) FROM tareas WHERE completada = 0")
    fun countPendientesFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tareas WHERE completada = 0 AND fechaProgramada >= :desde")
    fun countPendientesDesdeFlow(desde: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tarea: TareaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tareas: List<TareaEntity>)

    @Update
    suspend fun update(tarea: TareaEntity)

    @Query("UPDATE tareas SET completada = :completada, horaCompletada = :hora, observaciones = :obs, sincronizado = 0 WHERE id = :id")
    suspend fun updateCompletion(id: String, completada: Boolean, hora: String?, obs: String = "")

    @Query("UPDATE tareas SET sincronizado = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    /** Tareas de demostración sembradas por versiones anteriores de la app (ids no GUID). */
    @Query("DELETE FROM tareas WHERE id LIKE 'TASK-%'")
    suspend fun deleteLegacySeed()

    @Query("DELETE FROM tareas WHERE sincronizado = 1")
    suspend fun deleteSynced()
}

@Dao
interface BajaMortalidadDao {
    @Query("SELECT * FROM bajas_mortalidad ORDER BY fechaBaja DESC")
    fun getAllFlow(): Flow<List<BajaMortalidadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(baja: BajaMortalidadEntity)

    @Query("SELECT COUNT(*) FROM bajas_mortalidad WHERE fechaBaja >= :desde")
    fun countBajasDesdeFlow(desde: Long): Flow<Int>

    @Query("UPDATE bajas_mortalidad SET sincronizado = 1 WHERE id = :id")
    suspend fun markSynced(id: String)
}

@Dao
interface NotificacionDao {
    @Query("SELECT * FROM notificaciones ORDER BY leida ASC, fechaHora DESC")
    fun getAllFlow(): Flow<List<NotificacionEntity>>

    @Query("SELECT COUNT(*) FROM notificaciones WHERE leida = 0")
    fun countNoLeidasFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notificaciones: List<NotificacionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notificacion: NotificacionEntity)

    @Query("UPDATE notificaciones SET leida = 1 WHERE id = :id")
    suspend fun marcarLeida(id: String)

    @Query("DELETE FROM notificaciones")
    suspend fun deleteAll()
}

@Dao
interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun getPendingItems(): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status IN ('PENDING', 'IN_PROGRESS')")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status IN ('PENDING', 'IN_PROGRESS')")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM sync_queue WHERE status = 'FAILED' ORDER BY timestamp ASC")
    fun getFailedFlow(): Flow<List<SyncQueueEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncQueueEntity)

    @Query("UPDATE sync_queue SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: SyncStatus)

    /** Registros que quedaron "en curso" por un cierre inesperado vuelven a la cola. */
    @Query("UPDATE sync_queue SET status = 'PENDING' WHERE status = 'IN_PROGRESS'")
    suspend fun resetInProgress()

    @Query("UPDATE sync_queue SET status = 'FAILED', lastError = :error, retryCount = retryCount + 1 WHERE recordId = :recordId")
    suspend fun markFailed(recordId: String, error: String)

    @Query("UPDATE sync_queue SET status = 'PENDING', lastError = NULL WHERE status = 'FAILED'")
    suspend fun retryFailed()

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM sync_queue WHERE recordId = :recordId")
    suspend fun deleteByRecordId(recordId: String)

    /** Rechazo de un ítem concreto de la cola (cuando la clave de idempotencia es el id del ítem). */
    @Query("UPDATE sync_queue SET status = 'FAILED', lastError = :error, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markFailedByQueueId(id: String, error: String)

    @Query("SELECT * FROM sync_queue WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SyncQueueEntity?

    /** Tipos que el servidor aún no soporta: se marcan FAILED en lugar de quedar "en curso". */
    @Query("UPDATE sync_queue SET status = 'FAILED', lastError = :error WHERE id = :id")
    suspend fun markUnsupported(id: String, error: String)

    @Query("DELETE FROM sync_queue WHERE recordId LIKE 'TASK-%'")
    suspend fun deleteLegacyTaskItems()

    @Query("DELETE FROM sync_queue WHERE status = 'FAILED'")
    suspend fun deleteFailed()
}

@Dao
interface OperacionGastoDao {
    @Query("SELECT * FROM operaciones_gastos_campo ORDER BY fechaRegistro DESC")
    fun getAllFlow(): Flow<List<OperacionGastoEntity>>

    @Query("SELECT * FROM operaciones_gastos_campo WHERE tipoRegistro = :tipo ORDER BY fechaRegistro DESC")
    fun getByTipoFlow(tipo: String): Flow<List<OperacionGastoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(registro: OperacionGastoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(registros: List<OperacionGastoEntity>)

    @Query("UPDATE operaciones_gastos_campo SET sincronizado = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT COALESCE(SUM(montoCordobas), 0.0) FROM operaciones_gastos_campo")
    suspend fun sumTotalCordobas(): Double

    @Query("SELECT COALESCE(SUM(montoDolares), 0.0) FROM operaciones_gastos_campo")
    suspend fun sumTotalDolares(): Double

    @Query("DELETE FROM operaciones_gastos_campo WHERE sincronizado = 1")
    suspend fun deleteSynced()
}

@Dao
interface MasterCatalogDao {
    @Query("SELECT * FROM cat_galpones ORDER BY codigo ASC")
    fun getGalponesFlow(): Flow<List<CatGalponEntity>>

    @Query("SELECT * FROM cat_jaulas ORDER BY galponCodigo ASC, codigo ASC")
    fun getJaulasFlow(): Flow<List<CatJaulaEntity>>

    @Query("SELECT * FROM cat_insumos ORDER BY categoriaCodigo ASC, nombre ASC")
    fun getInsumosFlow(): Flow<List<CatInsumoEntity>>

    @Query("SELECT * FROM cat_labores ORDER BY tipoRegistro ASC, nombre ASC")
    fun getLaboresFlow(): Flow<List<CatLaborEntity>>

    @Query("SELECT * FROM cat_horarios ORDER BY horaInicio ASC, codigo ASC")
    fun getHorariosFlow(): Flow<List<CatHorarioEntity>>

    @Query("SELECT * FROM cat_operarios ORDER BY nombre ASC")
    fun getOperariosFlow(): Flow<List<CatOperarioEntity>>

    @Query("SELECT * FROM cat_clinico ORDER BY tipoCatalogo ASC, codigo ASC")
    fun getClinicosFlow(): Flow<List<CatClinicoEntity>>

    @Query("SELECT * FROM preferencias_ui_usuario WHERE userKey = :userKey LIMIT 1")
    fun getPreferenciaFlow(userKey: String): Flow<PreferenciaUiEntity?>

    @Query("SELECT * FROM preferencias_ui_usuario WHERE userKey = :userKey LIMIT 1")
    suspend fun getPreferenciaSync(userKey: String): PreferenciaUiEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreferenciaUi(pref: PreferenciaUiEntity)

    @Query("DELETE FROM cat_galpones")
    suspend fun clearGalpones()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGalpones(items: List<CatGalponEntity>)

    @Query("DELETE FROM cat_jaulas")
    suspend fun clearJaulas()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJaulas(items: List<CatJaulaEntity>)

    @Query("DELETE FROM cat_insumos")
    suspend fun clearInsumos()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInsumos(items: List<CatInsumoEntity>)

    @Query("DELETE FROM cat_labores")
    suspend fun clearLabores()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabores(items: List<CatLaborEntity>)

    @Query("DELETE FROM cat_horarios")
    suspend fun clearHorarios()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHorarios(items: List<CatHorarioEntity>)

    @Query("DELETE FROM cat_operarios")
    suspend fun clearOperarios()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperarios(items: List<CatOperarioEntity>)

    @Query("DELETE FROM cat_clinico")
    suspend fun clearClinicos()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClinicos(items: List<CatClinicoEntity>)

    @Transaction
    suspend fun clearAllCatalogs() {
        clearGalpones()
        clearJaulas()
        clearInsumos()
        clearLabores()
        clearHorarios()
        clearOperarios()
        clearClinicos()
    }

    @Transaction
    suspend fun replaceMasterCatalogs(
        galpones: List<CatGalponEntity>,
        jaulas: List<CatJaulaEntity>,
        insumos: List<CatInsumoEntity>,
        labores: List<CatLaborEntity>,
        horarios: List<CatHorarioEntity>,
        operarios: List<CatOperarioEntity>,
        clinicos: List<CatClinicoEntity>
    ) {
        clearGalpones()
        if (galpones.isNotEmpty()) insertGalpones(galpones)
        clearJaulas()
        if (jaulas.isNotEmpty()) insertJaulas(jaulas)
        clearInsumos()
        if (insumos.isNotEmpty()) insertInsumos(insumos)
        clearLabores()
        if (labores.isNotEmpty()) insertLabores(labores)
        clearHorarios()
        if (horarios.isNotEmpty()) insertHorarios(horarios)
        clearOperarios()
        if (operarios.isNotEmpty()) insertOperarios(operarios)
        clearClinicos()
        if (clinicos.isNotEmpty()) insertClinicos(clinicos)
    }
}
