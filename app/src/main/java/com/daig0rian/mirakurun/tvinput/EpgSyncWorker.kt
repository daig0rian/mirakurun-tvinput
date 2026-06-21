package com.daig0rian.mirakurun.tvinput

import android.content.Context
import android.media.tv.TvInputManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class EpgSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val inputId = resolveInputId() ?: run {
            Log.w(TAG, "TIF inputId が解決できません")
            return Result.retry()
        }

        return try {
            val apiClient = MirakurunApiClient(MirakurunPreferences.getUrl(applicationContext))
            EpgSyncManager(applicationContext).syncEpg(inputId, apiClient)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "EPG 定期同期失敗: $e")
            Result.retry()
        }
    }

    private fun resolveInputId(): String? {
        val manager = applicationContext.getSystemService(TvInputManager::class.java) ?: return null
        return manager.tvInputList
            .firstOrNull { it.serviceInfo.packageName == applicationContext.packageName }
            ?.id
    }

    companion object {
        private const val TAG = "EpgSyncWorker"
        private const val WORK_NAME = "epg_periodic_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EpgSyncWorker>(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
