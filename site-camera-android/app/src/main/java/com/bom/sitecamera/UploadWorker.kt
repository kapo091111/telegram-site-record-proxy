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
        val pin = inputData.getString("pin") ?: return Result.failure()
        val siteId = inputData.getString("siteId") ?: return Result.failure()
        val date = inputData.getString("date") ?: ""
        val remark = inputData.getString("remark") ?: ""
        val filePath = inputData.getString("filePath") ?: return Result.failure()
        val mimeType = inputData.getString("mimeType") ?: "application/octet-stream"
        val extension = inputData.getString("extension") ?: "bin"
        val clientFileId = inputData.getString("clientFileId") ?: filePath
        val file = File(filePath)
        if (!file.exists()) return Result.failure()

        return try {
            val query = listOf(
                "siteId=${encode(siteId)}",
                "date=${encode(date)}",
                "remark=${encode(remark)}",
                "extension=${encode(extension)}"
            ).joinToString("&")
            val connection = URL("$baseUrl/api/mobile/upload?$query").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("x-admin-pin", pin)
            connection.setRequestProperty("x-client-file-id", clientFileId)
            connection.setRequestProperty("Content-Type", mimeType)
            connection.setRequestProperty("Content-Length", file.length().toString())
            file.inputStream().use { input ->
                connection.outputStream.use { output -> input.copyTo(output) }
            }

            val code = connection.responseCode
            if (code in 200..299) {
                file.delete()
                Result.success()
            } else if (code in 500..599 || code == 408 || code == 429) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "site_camera_uploads"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(channelId, "工程相機上傳", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("工程相機上傳")
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
