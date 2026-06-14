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
    val status: String = "pending",
    val sourceUri: String = "",
    val sourceDisplayName: String = "",
    val sourceSize: Long = 0L,
    val sourceHash: String = "",
    val sourceTrashStatus: String = "not_applicable"
)

data class SourceTrashItem(
    val id: String,
    val sourceUri: String,
    val sourceDisplayName: String,
    val sourceSize: Long,
    val sourceHash: String,
    val status: String,
    val createdAt: Long
)

object DraftStore {
    private const val PREFS = "drafts"
    private const val KEY = "items"
    private const val TRASH_KEY = "trash_items"

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
        remark: String,
        sourceUri: String = "",
        sourceDisplayName: String = "",
        sourceSize: Long = 0L,
        sourceHash: String = "",
        sourceTrashStatus: String = "not_applicable"
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
            createdAt = System.currentTimeMillis(),
            sourceUri = sourceUri,
            sourceDisplayName = sourceDisplayName,
            sourceSize = sourceSize,
            sourceHash = sourceHash,
            sourceTrashStatus = sourceTrashStatus
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
                status = item.optString("status", "pending"),
                sourceUri = item.optString("sourceUri"),
                sourceDisplayName = item.optString("sourceDisplayName"),
                sourceSize = item.optLong("sourceSize"),
                sourceHash = item.optString("sourceHash"),
                sourceTrashStatus = item.optString("sourceTrashStatus", "not_applicable")
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
        val current = list(context)
        val target = current.firstOrNull { it.id == id }
        if (target != null && target.sourceTrashStatus == "eligible" && target.sourceUri.isNotBlank()) {
            enqueueTrash(context, target)
        }
        if (target != null) File(target.filePath).delete()
        save(context, current.filterNot { it.id == id })
    }

    fun listTrash(context: Context): List<SourceTrashItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TRASH_KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        val items = mutableListOf<SourceTrashItem>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            items.add(
                SourceTrashItem(
                    id = item.getString("id"),
                    sourceUri = item.optString("sourceUri"),
                    sourceDisplayName = item.optString("sourceDisplayName"),
                    sourceSize = item.optLong("sourceSize"),
                    sourceHash = item.optString("sourceHash"),
                    status = item.optString("status", "pending_trash"),
                    createdAt = item.optLong("createdAt")
                )
            )
        }
        return items.filter { it.sourceUri.isNotBlank() }
    }

    fun pendingTrash(context: Context): List<SourceTrashItem> {
        return listTrash(context).filter { it.status == "pending_trash" }
    }

    fun removeTrash(context: Context, ids: Set<String>) {
        saveTrash(context, listTrash(context).filterNot { ids.contains(it.id) })
    }

    fun markTrashSkipped(context: Context, ids: Set<String>) {
        saveTrash(context, listTrash(context).map {
            if (ids.contains(it.id)) it.copy(status = "skipped") else it
        })
    }

    private fun updateStatus(context: Context, id: String, status: String) {
        save(context, list(context).map { if (it.id == id) it.copy(status = status) else it })
    }

    private fun enqueueTrash(context: Context, draft: UploadDraft) {
        val current = listTrash(context)
        val duplicate = current.any { it.sourceUri == draft.sourceUri && it.sourceHash == draft.sourceHash && it.status == "pending_trash" }
        if (duplicate) return
        saveTrash(
            context,
            current + SourceTrashItem(
                id = draft.id,
                sourceUri = draft.sourceUri,
                sourceDisplayName = draft.sourceDisplayName,
                sourceSize = draft.sourceSize,
                sourceHash = draft.sourceHash,
                status = "pending_trash",
                createdAt = System.currentTimeMillis()
            )
        )
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
                put("sourceUri", draft.sourceUri)
                put("sourceDisplayName", draft.sourceDisplayName)
                put("sourceSize", draft.sourceSize)
                put("sourceHash", draft.sourceHash)
                put("sourceTrashStatus", draft.sourceTrashStatus)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun saveTrash(context: Context, items: List<SourceTrashItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("sourceUri", item.sourceUri)
                put("sourceDisplayName", item.sourceDisplayName)
                put("sourceSize", item.sourceSize)
                put("sourceHash", item.sourceHash)
                put("status", item.status)
                put("createdAt", item.createdAt)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TRASH_KEY, array.toString())
            .apply()
    }
}
