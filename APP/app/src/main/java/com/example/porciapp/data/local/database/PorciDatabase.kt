package com.example.porciapp.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.porciapp.data.local.MockData
import com.example.porciapp.data.local.dao.*
import com.example.porciapp.data.local.entities.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        CerdoEntity::class,
        TratamientoEntity::class,
        EventoReproductivoEntity::class,
        ConsumoAlimentoEntity::class,
        TareaEntity::class,
        SyncQueueEntity::class,
        OperacionGastoEntity::class,
        BajaMortalidadEntity::class,
        NotificacionEntity::class,
        CatGalponEntity::class,
        CatJaulaEntity::class,
        CatInsumoEntity::class,
        CatLaborEntity::class,
        CatHorarioEntity::class,
        CatOperarioEntity::class,
        CatClinicoEntity::class,
        PreferenciaUiEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class PorciDatabase : RoomDatabase() {

    abstract fun cerdoDao(): CerdoDao
    abstract fun tratamientoDao(): TratamientoDao
    abstract fun eventoReproductivoDao(): EventoReproductivoDao
    abstract fun consumoAlimentoDao(): ConsumoAlimentoDao
    abstract fun tareaDao(): TareaDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun operacionGastoDao(): OperacionGastoDao
    abstract fun bajaMortalidadDao(): BajaMortalidadDao
    abstract fun notificacionDao(): NotificacionDao
    abstract fun masterCatalogDao(): MasterCatalogDao

    companion object {
        @Volatile
        private var INSTANCE: PorciDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE eventos_reproductivos ADD COLUMN verracoCodigo TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE eventos_reproductivos ADD COLUMN tecnicaIa TEXT NOT NULL DEFAULT 'IA Tradicional'")
                db.execSQL("ALTER TABLE eventos_reproductivos ADD COLUMN turnoIa TEXT NOT NULL DEFAULT 'AM'")
                db.execSQL("ALTER TABLE eventos_reproductivos ADD COLUMN condicionCorporal REAL NOT NULL DEFAULT 3.0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE consumo_alimento ADD COLUMN siloCodigo TEXT")
                db.execSQL("ALTER TABLE consumo_alimento ADD COLUMN horarioToma TEXT")

                db.execSQL("CREATE TABLE IF NOT EXISTS cat_galpones (codigo TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, tipoSistema TEXT NOT NULL, capacidad INTEGER NOT NULL, temperaturaC REAL NOT NULL, humedadPct REAL NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cat_jaulas (codigo TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, galponCodigo TEXT NOT NULL, galponNombre TEXT NOT NULL, etapaZootecnica TEXT NOT NULL, capacidadMaxima INTEGER NOT NULL, ocupacionActual INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cat_insumos (sku TEXT NOT NULL PRIMARY KEY, id TEXT NOT NULL, nombre TEXT NOT NULL, categoriaCodigo TEXT NOT NULL, unidadMedida TEXT NOT NULL, stockActual REAL NOT NULL, stockMinimo REAL NOT NULL, costoUnitarioUsd REAL NOT NULL, siloCodigo TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cat_labores (codigo TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, tipoRegistro TEXT NOT NULL, categoria TEXT NOT NULL, etapaSugerida TEXT NOT NULL, galponSugerido TEXT NOT NULL, duracionSugerida TEXT NOT NULL, tarifaReferenciaNio REAL NOT NULL, centroCostoCodigo TEXT NOT NULL, rolSugerido TEXT NOT NULL, requiereInsumo INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cat_horarios (codigo TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, tipoHorario TEXT NOT NULL, horaInicio TEXT NOT NULL, horaFin TEXT NOT NULL, categoriaActividad TEXT NOT NULL, etapaAplicable TEXT NOT NULL, rolResponsable TEXT NOT NULL, prioridadDefault TEXT NOT NULL, descripcion TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cat_operarios (id TEXT NOT NULL PRIMARY KEY, nombreUsuario TEXT NOT NULL, nombre TEXT NOT NULL, rol TEXT NOT NULL, matricula TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cat_clinico (codigo TEXT NOT NULL PRIMARY KEY, tipoCatalogo TEXT NOT NULL, nombre TEXT NOT NULL, subcategoria TEXT NOT NULL, diasRetiroReferencia INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS preferencias_ui_usuario (userKey TEXT NOT NULL PRIMARY KEY, rolBase TEXT NOT NULL, modulosVisiblesJson TEXT NOT NULL, accionesRapidasJson TEXT NOT NULL, navInferiorJson TEXT NOT NULL, galponPredeterminado TEXT NOT NULL, turnoPredeterminado TEXT NOT NULL, monedaPreferida TEXT NOT NULL, modoCompacto INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)")

                seedDefaultCatalogsSql(db)
            }
        }

        private fun seedDefaultCatalogsSql(db: SupportSQLiteDatabase) {
            db.execSQL("""
                INSERT OR IGNORE INTO cat_galpones (codigo, nombre, tipoSistema, capacidad, temperaturaC, humedadPct) VALUES
                ('GALP-01', 'Galpón 1 - Maternidad & Lactancia', 'climatizado_automatizado', 450, 24.0, 60.0),
                ('GALP-02', 'Galpón 2 - Gestación Confirmada', 'ventilacion_natural', 500, 22.5, 62.0),
                ('GALP-03', 'Galpón 3 - Precebo / Destete', 'presion_negativa', 750, 26.0, 58.0),
                ('GALP-04', 'Galpón 4 - Crecimiento y Engorde', 'tunel_viento', 1500, 21.5, 65.0)
            """.trimIndent())

            db.execSQL("""
                INSERT OR IGNORE INTO cat_jaulas (codigo, nombre, galponCodigo, galponNombre, etapaZootecnica, capacidadMaxima, ocupacionActual) VALUES
                ('J-MAT-01', 'Jaula Maternidad 01', 'GALP-01', 'Galpón 1 - Maternidad & Lactancia', 'Maternidad', 1, 0),
                ('J-MAT-02', 'Jaula Maternidad 02', 'GALP-01', 'Galpón 1 - Maternidad & Lactancia', 'Maternidad', 1, 0),
                ('J-GEST-01', 'Jaula Gestación 01', 'GALP-02', 'Galpón 2 - Gestación Confirmada', 'Gestación', 1, 0),
                ('C-PREC-01', 'Corral Precebo 01', 'GALP-03', 'Galpón 3 - Precebo / Destete', 'Precebo', 35, 0),
                ('C-CREC-01', 'Corral Crecimiento 01', 'GALP-04', 'Galpón 4 - Crecimiento y Engorde', 'Crecimiento', 25, 0),
                ('C-ENG-01', 'Corral Engorde 01', 'GALP-04', 'Galpón 4 - Crecimiento y Engorde', 'Engorde', 25, 0)
            """.trimIndent())

            db.execSQL("""
                INSERT OR IGNORE INTO cat_horarios (codigo, nombre, tipoHorario, horaInicio, horaFin, categoriaActividad, etapaAplicable, rolResponsable, prioridadDefault, descripcion) VALUES
                ('TUR-MAN', 'Turno Mañana (06:00 - 14:00)', 'TURNO_JORNADA', '06:00', '14:00', 'Manejo', 'Todas', 'Operario', 'Media', 'Primera jornada operativa: ración AM y rondas.'),
                ('TUR-TAR', 'Turno Tarde (14:00 - 20:00)', 'TURNO_JORNADA', '14:00', '20:00', 'Manejo', 'Todas', 'Operario', 'Media', 'Segunda jornada operativa: ración PM y cierre.'),
                ('TUR-NOC', 'Turno Guardia Nocturna (20:00 - 06:00)', 'TURNO_JORNADA', '20:00', '06:00', 'Reproduccion', 'Maternidad', 'Operario', 'Alta', 'Vigilancia de partos inminentes.'),
                ('TUR-COMP', 'Jornada Técnica Diurna (07:00 - 16:00)', 'TURNO_JORNADA', '07:00', '16:00', 'Sanidad', 'Todas', 'Veterinario', 'Media', 'Horario dirección veterinaria y administración.'),
                ('TOMA-01', '1ra Toma Alimento AM (06:30)', 'TOMA_ALIMENTO', '06:30', '08:00', 'Alimentacion', 'Todas', 'Operario', 'Alta', 'Primera ración del día.'),
                ('TOMA-02', '2da Toma Alimento Mediodía (11:30)', 'TOMA_ALIMENTO', '11:30', '13:00', 'Alimentacion', 'Precebo', 'Operario', 'Media', 'Refuerzo de mediodía.'),
                ('TOMA-03', '3ra Toma Alimento PM (16:30)', 'TOMA_ALIMENTO', '16:30', '18:00', 'Alimentacion', 'Todas', 'Operario', 'Alta', 'Ración de tarde.'),
                ('IA-1-MAN', '1ra Dosis Inseminación · Mañana (07:00)', 'DOSIS_IA', '07:00', '09:00', 'Reproduccion', 'Gestación', 'Veterinario', 'Alta', 'Primera aplicación seminal.'),
                ('IA-2-TAR', '2da Dosis Inseminación · Tarde +12h (17:00)', 'DOSIS_IA', '17:00', '19:00', 'Reproduccion', 'Gestación', 'Veterinario', 'Alta', 'Segunda dosis de refuerzo.'),
                ('AGD-RACION-AM', 'Suministro Ración AM y Chequeo Bebederos', 'PLANTILLA_AGENDA', '06:30', '08:00', 'Alimentacion', 'Todas', 'Operario', 'Alta', 'Dispensar alimento y revisar bebederos.'),
                ('AGD-RONDA-MAT', 'Ronda de Maternidad, Calostro y Neonatos', 'PLANTILLA_AGENDA', '07:00', '08:30', 'Reproduccion', 'Maternidad', 'Veterinario', 'Urgente', 'Control de temperatura y vitalidad.'),
                ('AGD-ASEO-NAVE', 'Lavado, Desinfección y Recarga de Pediluvios', 'PLANTILLA_AGENDA', '09:30', '11:30', 'Bioseguridad', 'Todas', 'Operario', 'Media', 'Higienización de pasillos y corrales.')
            """.trimIndent())

            db.execSQL("""
                INSERT OR IGNORE INTO cat_labores (codigo, nombre, tipoRegistro, categoria, etapaSugerida, galponSugerido, duracionSugerida, tarifaReferenciaNio, centroCostoCodigo, rolSugerido, requiereInsumo) VALUES
                ('LAB-LIMP-GALP', 'Limpieza y lavado a presión de corrales', 'LABOR_CAMPO', 'Bioseguridad', 'Todas', 'GALP-04', 'Medio día (4h)', 160.0, 'CC-NOM', 'Operario', 0),
                ('LAB-DESINF-PED', 'Desinfección, calado y recarga de pediluvios', 'LABOR_CAMPO', 'Bioseguridad', 'Todas', 'GALP-01', '2 horas (0.25j)', 80.0, 'CC-SAN', 'Operario', 1),
                ('LAB-ALIM-MAN', 'Distribución y calibración de tolvas de comida', 'LABOR_CAMPO', 'Alimentación', 'Precebo', 'GALP-03', '2 horas (0.25j)', 80.0, 'CC-ALIM', 'Operario', 0),
                ('LAB-PESAJE-LOT', 'Pesaje muestral y control GMD de lote', 'LABOR_CAMPO', 'Manejo', 'Crecimiento', 'GALP-04', 'Medio día (4h)', 160.0, 'CC-NOM', 'Operario', 0),
                ('LAB-ASIST-PARTO', 'Vigilancia de partos y secado de lechones', 'LABOR_CAMPO', 'Maternidad', 'Maternidad', 'GALP-01', 'Jornal completo', 320.0, 'CC-NOM', 'Veterinario', 0),
                ('GAS-JORNAL-EXT', 'Pago de jornal temporal adicional de campo', 'GASTO_OPERATIVO', 'Mano de Obra', 'Todas', 'GALP-04', 'Jornal completo', 320.0, 'CC-NOM', 'Administrador', 0),
                ('GAS-ENERG-AGUA', 'Recibo energía eléctrica / bombeo de agua', 'GASTO_OPERATIVO', 'Servicios', 'Todas', 'GALP-01', '', 1850.0, 'CC-SERV', 'Administrador', 0)
            """.trimIndent())

            db.execSQL("""
                INSERT OR IGNORE INTO cat_clinico (codigo, tipoCatalogo, nombre, subcategoria, diasRetiroReferencia) VALUES
                ('DIAG-RESP', 'DIAGNOSTICO_CLINICO', 'Síndrome respiratorio agudo (tos / disnea)', 'Respiratorio', 14),
                ('DIAG-ENT', 'DIAGNOSTICO_CLINICO', 'Diarrea neonatal / enteritis colibacilar', 'Digestivo', 7),
                ('DIAG-MMA', 'DIAGNOSTICO_CLINICO', 'Mastitis-Metritis-Agalactia (MMA) posparto', 'Reproductivo', 7),
                ('DIAG-VAC', 'DIAGNOSTICO_CLINICO', 'Inmunización programada calendario oficial', 'Biológico', 0),
                ('VIA-IM', 'VIA_ADMINISTRACION', 'Intramuscular profunda (IM)', 'Parenteral', 0),
                ('VIA-SC', 'VIA_ADMINISTRACION', 'Subcutánea (SC retroauricular)', 'Parenteral', 0),
                ('VIA-VO', 'VIA_ADMINISTRACION', 'Vía Oral (VO / Agua de bebida)', 'Oral', 0),
                ('CAUSA-APL', 'CAUSA_MORTALIDAD', 'Aplastamiento perinatal en maternidad', 'Maternidad', 0),
                ('CAUSA-COLI', 'CAUSA_MORTALIDAD', 'Diarrea colibacilar / Enteritis severa', 'Digestivo', 0),
                ('CAUSA-RESP', 'CAUSA_MORTALIDAD', 'Complejo respiratorio porcino (CRP)', 'Respiratorio', 0),
                ('CAUSA-DESM', 'CAUSA_MORTALIDAD', 'Desmedro / Bajo peso al destete (PCV2)', 'Sanitario', 0),
                ('IA-PCAI', 'TECNICA_IA', 'Post-Cervical (PCAI)', 'inseminacion_post_cervical', 0),
                ('IA-TRAD', 'TECNICA_IA', 'Cervical Clásica Tradicional', 'inseminacion_cervical', 0),
                ('IA-MONTA', 'TECNICA_IA', 'Monta Natural Dirigida', 'monta_natural', 0),
                ('GEN-TN70', 'LINEA_GENETICA', 'Topigs Norsvin 70', 'Topigs Norsvin', 0),
                ('GEN-PIET', 'LINEA_GENETICA', 'Pietrain Belga', 'Belgian Genetics', 0),
                ('GEN-DUROC', 'LINEA_GENETICA', 'Duroc Talent', 'Topigs', 0),
                ('GEN-LAND', 'LINEA_GENETICA', 'Landrace DanBred', 'DanBred', 0)
            """.trimIndent())
        }

        fun getDatabase(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)): PorciDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PorciDatabase::class.java,
                    "agrofield_porcino_db"
                )
                    // OJO: la cola Outbox vive aqui. Desde la version 3 todo cambio de esquema debe traer
                    // su Migration; la destruccion solo se tolera para las versiones 1 y 2 (desarrollo).
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigrationFrom(true, 1, 2)
                    .addCallback(DatabaseCallback(scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                seedDefaultCatalogsSql(db)
            }
        }
    }
}
