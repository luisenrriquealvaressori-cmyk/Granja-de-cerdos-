package com.example.porciapp.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.porciapp.data.net.ApiResult
import com.example.porciapp.data.repository.FarmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val tag = "SyncWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repository = FarmRepository(applicationContext)

        if (repository.sessionManager.valid() == null) {
            Log.i(tag, "Sincronización en espera: inicia sesión para enviar registros pendientes.")
            return@withContext Result.success()
        }

        try {
            when (val res = repository.syncOutboxNow()) {
                is FarmRepository.DirectSyncResult.NoPending -> {
                    Log.d(tag, "No hay registros pendientes en la cola Outbox.")
                    Result.success()
                }
                is FarmRepository.DirectSyncResult.Success -> {
                    Log.i(tag, res.message)
                    Result.success()
                }
                is FarmRepository.DirectSyncResult.Partial -> {
                    Log.w(tag, res.message)
                    Result.success()
                }
                is FarmRepository.DirectSyncResult.Failure -> {
                    Log.w(tag, "Fallo al sincronizar lote Outbox: ${res.message} (Kind: ${res.kind})")
                    if (res.kind == ApiResult.Kind.NETWORK) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error crítico durante la sincronización: ${e.message}", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

