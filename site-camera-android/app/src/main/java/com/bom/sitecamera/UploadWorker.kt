package com.bom.sitecamera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class UploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())

        val baseUrl = inputData.getString("baseUrl")?.trimEnd('/') ?: return Result.failure()
        val draftId = inputData.getString("draftId") ?: return Result.failure()
        val draft = DraftStore.find(applicationContext, draftId) ?: return Result.success()
        val file = File(draft.filePath)
        if (!file.exists()) {
            DraftStore.remove(applicationContext, draftId, deleteFile = false)
            return Result.success()
        }

        DraftStore.markUploading(applicationContext, draftId)
        return try {
            val query = listOf(
                "siteId=${encode(draft.siteId)}",
                "date=${encode(draft.date)}",
                "remark=${encode(draft.remark)}",
                "extension=${encode(draft.extension)}"
            ).joinToString("&")
            val connection = URL("$baseUrl/api/mobile/upload?$query").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("x-mobile-app-key", BuildConfig.MOBILE_APP_KEY)
            connection.setRequestProperty("x-client-file-id", draft.id)
            connection.setRequestProperty("Content-Type", draft.mimeType)
            connection.setRequestProperty("Content-Length", file.length().toString())
            file.inputStream().use { input ->
                connection.outputStream.use { output -> input.copyTo(output) }
            }

            val code = connection.responseCode
            if (code in 200..299) {
                DraftStore.removeUploaded(applicationContext, draftId)
                Result.success()
            } else if (code in 500..599 || code == 408 || code == 429) {
                DraftStore.markPending(applicationContext, draftId)
                Result.retry()
            } else {
                DraftStore.markPending(applicationContext, draftId)
                Result.failure()
            }
        } catch (_: Exception) {
            DraftStore.markPending(applicationContext, draftId)
            Result.retry()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "site_camera_uploads"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(channelId, "地盤記錄上傳", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("地盤記錄上傳")
            .setContentText("正在背景上傳檔案。")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1001, notification)
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
