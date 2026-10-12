package com.getvideo.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.getvideo.app.data.repo.TrackRepository
import java.util.concurrent.TimeUnit

/**
 * 定期把服务器上的新视频同步下来。
 *
 * 分两步：先拉元数据（轻，永远做），再补下载视频文件（重，只在
 * 不计费网络下做）。这样列表能及时更新，而流量由系统约束把关。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = TrackRepository(applicationContext)
        return try {
            com.getvideo.app.data.repo.DiagnosticsStore.operation("后台同步") {
                repo.sync()
                repo.downloadPending(limit = DOWNLOAD_BATCH)
            }
            Result.success()
        } catch (e: Exception) {
            // 网络抖动是常态，交给 WorkManager 退避重试
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "getvideo-sync"
        private const val DOWNLOAD_BATCH = 20

        fun schedule(context: Context, intervalHours: Long = 6) {
            val constraints = Constraints.Builder()
                // 只在不计费网络同步 —— 视频文件不小，别偷跑流量
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // KEEP：重复调度不该把已有周期重置掉
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
