package com.bom.sitecamera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class UploadDraft(
    val id: String,
    val filePath: String,
    val mimeType: String,
    val extension: String,
    val siteId: String,
    val siteName: String,
    val date: String,
    val remark: String,
    val createdAt: Long,
    val status: String = "pending"
)

object DraftStore {
    private const val PREFS = "drafts"
    private const val KEY = "items"

    fun draftsDir(context: Context): File {
        return File(context.filesDir, "draft_uploads").apply { mkdirs() }
    }

    fun add(
        context: Context,
        file: File,
        mimeType: String,
        extension: String,
        siteId: String,
        siteName: String,
        date: String,
        remark: String
    ): UploadDraft {
        val draft = UploadDraft(
            id = UUID.randomUUID().toString(),
            filePath = file.absolutePath,
            mimeType = mimeType,
            extension = extension,
            siteId = siteId,
            siteName = siteName,
            date = date,
            remark = remark,
            createdAt = System.currentTimeMillis()
        )
        save(context, list(context) + draft)
        return draft
    }

    fun list(context: Context): List<UploadDraft> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        val items = mutableListOf<UploadDraft>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val draft = UploadDraft(
                id = item.getString("id"),
                filePath = item.getString("filePath"),
                mimeType = item.optString("mimeType", "application/octet-stream"),
                extension = item.optString("extension", "bin"),
                siteId = item.optString("siteId"),
                siteName = item.optString("siteName"),
                date = item.optString("date"),
                remark = item.optString("remark"),
                createdAt = item.optLong("createdAt"),
                status = item.optString("status", "pending")
            )
            if (File(draft.filePath).exists()) items.add(draft)
        }
        if (items.size != array.length()) save(context, items)
        return items
    }

    fun find(context: Context, id: String): UploadDraft? {
        return list(context).firstOrNull { it.id == id }
    }

    fun updateFile(context: Context, id: String, file: File, mimeType: String = "image/jpeg", extension: String = "jpg") {
        save(context, list(context).map {
            if (it.id == id) it.copy(filePath = file.absolutePath, mimeType = mimeType, extension = extension) else it
        })
    }

    fun markUploading(context: Context, id: String) {
        updateStatus(context, id, "uploading")
    }

    fun markPending(context: Context, id: String) {
        updateStatus(context, id, "pending")
    }

    fun remove(context: Context, id: String, deleteFile: Boolean = true) {
        val current = list(context)
        val target = current.firstOrNull { it.id == id }
        if (deleteFile) target?.let { File(it.filePath).delete() }
        save(context, current.filterNot { it.id == id })
    }

    fun removeUploaded(context: Context, id: String) {
        remove(context, id, true)
    }

    private fun updateStatus(context: Context, id: String, status: String) {
        save(context, list(context).map { if (it.id == id) it.copy(status = status) else it })
    }

    private fun save(context: Context, items: List<UploadDraft>) {
        val array = JSONArray()
        items.forEach { draft ->
            array.put(JSONObject().apply {
                put("id", draft.id)
                put("filePath", draft.filePath)
                put("mimeType", draft.mimeType)
                put("extension", draft.extension)
                put("siteId", draft.siteId)
                put("siteName", draft.siteName)
                put("date", draft.date)
                put("remark", draft.remark)
                put("createdAt", draft.createdAt)
                put("status", draft.status)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
