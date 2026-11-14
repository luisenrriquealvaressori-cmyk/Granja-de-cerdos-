package com.example.porciapp.data.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncManager {

    private const val UNIQUE_PERIODIC_SYNC_WORK = "PorciAppPeriodicSyncWork"
    private const val UNIQUE_ONE_TIME_SYNC_WORK = "PorciAppOneTimeSyncWork"

    /**
     * Configura la sincronización periódica cada 15 minutos solo cuando haya conexión de red
     */
    fun setupPeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicSyncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_SYNC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicSyncRequest
        )
    }

    /**
     * Dispara una sincronización inmediata (al pulsar 'Sincronizar Ahora' en el Dashboard)
     */
    fun syncImmediately(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeSyncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_ONE_TIME_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            oneTimeSyncRequest
        )
    }
}
