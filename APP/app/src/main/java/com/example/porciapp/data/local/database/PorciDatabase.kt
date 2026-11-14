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
        NotificacionEntity::class
    ],
    version = 4,
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

        fun getDatabase(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)): PorciDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PorciDatabase::class.java,
                    "agrofield_porcino_db"
                )
                    // OJO: la cola Outbox vive aqui. Desde la version 3 todo cambio de esquema debe traer
                    // su Migration; la destruccion solo se tolera para las versiones 1 y 2 (desarrollo).
                    .addMigrations(MIGRATION_3_4)
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
                INSTANCE?.let { database ->
                    scope.launch {
                        populateInitialData(database)
                    }
                }
            }

            private suspend fun populateInitialData(db: PorciDatabase) {
                // Base de datos local limpia sin datos ficticios.
                // Los datos reales se descargan desde la API central vía sincronización.
            }
        }
    }
}
