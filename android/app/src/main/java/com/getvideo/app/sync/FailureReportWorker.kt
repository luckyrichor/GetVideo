package com.getvideo.app.sync

import android.content.Context
import androidx.work.*
import com.getvideo.app.data.repo.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class FailureReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val box = FailureOutbox(File(applicationContext.filesDir, "failure-outbox"))
        val settings = Settings(applicationContext)
        val base = settings.serverUrlNow()
        val account = AccountStore.forServer(base) ?: return@withContext Result.success()
        for (entry in box.eligible(base, account.userId).take(10)) {
            try {
                if (settings.serverUrlNow() != base || AccountStore.current.value != account) break
                val owner = entry.optString("owner")
                if (owner.isNotEmpty() && owner != account.userId) continue
                AccountStore.requireSecureServer(base)
                val report = entry.getJSONObject("report")
                if (!box.bindOwner(report.getString("id"), account.userId)) continue
                val url = base.toHttpUrl().resolve("v2/diagnostics/extractions") ?: continue
                val request = Request.Builder().url(url).post(report.toString().toRequestBody("application/json".toMediaType())).build()
                ApiProvider.okHttp(account).newBuilder().followRedirects(false).followSslRedirects(false).readTimeout(20, TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val ack = JSONObject(response.body?.string().orEmpty())
                            if (ack.optBoolean("stored") && ack.optString("id") == report.getString("id"))
                                box.acknowledge(report.getString("id"))
                        }
                    }
                box.defer(report.getString("id"))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { box.defer(entry.optJSONObject("report")?.optString("id").orEmpty()) }
        }
        if (box.eligible(base, account.userId).isEmpty()) Result.success() else Result.retry()
    }
    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<FailureReportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("getvideo-failure-reports", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
