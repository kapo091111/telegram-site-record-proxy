package com.bom.sitecamera

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var selectedSiteText: TextView
    private lateinit var selectedFolderText: TextView
    private lateinit var baseUrlInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var siteIdInput: EditText
    private lateinit var dateInput: EditText
    private lateinit var remarkInput: EditText
    private lateinit var siteList: LinearLayout
    private var imageCapture: ImageCapture? = null
    private var selectedSiteName: String = ""

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val clipData = data.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                copyUriToCacheAndEnqueue(clipData.getItemAt(index).uri)
            }
            return@registerForActivityResult
        }
        data.data?.let { copyUriToCacheAndEnqueue(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettingsValues()
        showMainScreen()
    }

    private fun showMainScreen() {
        imageCapture = null

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(246, 248, 245))
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(45, 55, 50))
            text = "先選地盤、日期、備注，再拍照或選相。"
        }
        selectedSiteText = sectionValue("目前地盤", selectedSiteName.ifBlank { "未選擇" })
        selectedFolderText = sectionValue("資料夾", folderNamePreview())

        baseUrlInput = EditText(this).apply {
            hint = "Render URL"
            setText(savedBaseUrl())
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        pinInput = EditText(this).apply {
            hint = "WEB_ADMIN_PIN"
            setText(savedPin())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        siteIdInput = EditText(this).apply {
            hint = "先按同步地盤，再選地盤"
            setText(savedSiteId())
        }
        dateInput = EditText(this).apply {
            hint = "日期，例如 20260608"
            setText(savedDate().ifBlank { todayString(0) })
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        remarkInput = EditText(this).apply {
            hint = "備注，例如 泥水完成"
            setText(savedRemark())
        }
        siteList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addGap()
            addView(selectedSiteText)
            addView(selectedFolderText)
            addGap()
            addView(baseUrlInput)
            addView(pinInput)
            addView(siteIdInput)
            addGap()
            addView(dateRow())
            addGap()
            addView(remarkInput)
            addView(remarkButtons())
            addGap()
            addView(primaryButton("同步地盤") {
                saveSettings()
                fetchSites()
            })
            addView(siteList)
            addGap()
            addView(primaryButton("拍照") {
                saveSettings()
                if (hasRequiredUploadSettings()) showCameraScreen()
            })
            addView(secondaryButton("相簿選取") {
                saveSettings()
                openGallery()
            })
            addView(secondaryButton("儲存設定") {
                saveSettings()
                statusText.text = "設定已儲存。"
                refreshSummary()
            })
        }

        root.addView(
            ScrollView(this).apply { addView(scrollContent) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private fun showCameraScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this)
        val captureButton = Button(this).apply {
            text = "影相並上傳"
            textSize = 18f
            setOnClickListener { capturePhoto() }
        }
        val backButton = Button(this).apply {
            text = "返回設定"
            setOnClickListener { showMainScreen() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            addView(backButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(captureButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom)
        setContentView(root)
        requestCameraPermission()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val outputFile = File(cacheDir, "site_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    enqueueUpload(outputFile, "image/jpeg", "jpg")
                    showMainScreen()
                    statusText.text = "已加入背景上傳：${outputFile.name}"
                }

                override fun onError(exception: ImageCaptureException) {
                    showMainScreen()
                    statusText.text = "拍照失敗：${exception.message}"
                }
            }
        )
    }

    private fun openGallery() {
        if (!hasRequiredUploadSettings()) return

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "application/pdf"))
        }
        picker.launch(intent)
    }

    private fun copyUriToCacheAndEnqueue(uri: Uri) {
        val displayName = displayName(uri) ?: "picked_${System.currentTimeMillis()}"
        val extension = displayName.substringAfterLast('.', "bin").lowercase()
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val outputFile = File(cacheDir, "${UUID.randomUUID()}.$extension")
        contentResolver.openInputStream(uri)?.use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        }
        enqueueUpload(outputFile, mimeType, extension)
        statusText.text = "已加入背景上傳：$displayName"
    }

    private fun fetchSites() {
        val baseUrl = baseUrlInput.text.toString().trim().trimEnd('/')
        val pin = pinInput.text.toString().trim()
        if (baseUrl.isBlank()) {
            statusText.text = "請先填 Render URL。"
            return
        }
        if (pin.isBlank()) {
            statusText.text = "請先填 WEB_ADMIN_PIN，否則不能同步地盤。"
            return
        }

        statusText.text = "同步地盤中..."
        Thread {
            try {
                val connection = URL("$baseUrl/api/mobile/state").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("x-admin-pin", pin)

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code：${errorMessage(body)}")
                }

                val json = JSONObject(body)
                val currentSite = json.optJSONObject("currentSite")
                val sites = json.getJSONArray("sites")
                val recordDate = json.optString("recordDate")
                val remark = json.optString("remark")
                runOnUiThread {
                    if (recordDate.isNotBlank()) dateInput.setText(recordDate.replace("-", ""))
                    if (remark.isNotBlank() && remark != "null") remarkInput.setText(remark)
                    currentSite?.let {
                        siteIdInput.setText(it.getString("id"))
                        selectedSiteName = it.getString("name")
                    }
                    renderSiteButtons(sites)
                    saveSettings()
                    refreshSummary()
                    statusText.text = "已同步地盤，可直接選擇。"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "同步地盤失敗：${error.message}"
                }
            }
        }.start()
    }

    private fun renderSiteButtons(sites: JSONArray) {
        siteList.removeAllViews()
        for (index in 0 until sites.length()) {
            val site = sites.getJSONObject(index)
            val id = site.getString("id")
            val name = site.getString("name")
            siteList.addView(siteButton(name) {
                siteIdInput.setText(id)
                selectedSiteName = name
                saveSettings()
                refreshSummary()
                statusText.text = "已選地盤：$name"
            })
        }
    }

    private fun enqueueUpload(file: File, mimeType: String, extension: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(
                Data.Builder()
                    .putString("baseUrl", savedBaseUrl())
                    .putString("pin", savedPin())
                    .putString("siteId", savedSiteId())
                    .putString("date", savedDate().ifBlank { todayString(0) })
                    .putString("remark", savedRemark())
                    .putString("filePath", file.absolutePath)
                    .putString("mimeType", mimeType)
                    .putString("extension", extension)
                    .putString("clientFileId", UUID.randomUUID().toString())
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }

    private fun dateRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(dateInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(smallButton("選日期") { showDatePicker() })
            addView(smallButton("今日") {
                dateInput.setText(todayString(0))
                saveSettings()
                refreshSummary()
            })
            addView(smallButton("昨日") {
                dateInput.setText(todayString(-1))
                saveSettings()
                refreshSummary()
            })
        }
    }

    private fun remarkButtons(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallButton("打拆") { setRemark("打拆") })
            addView(smallButton("水電完成") { setRemark("水電完成") })
            addView(smallButton("泥水完成") { setRemark("泥水完成") })
            addView(smallButton("清除") { setRemark("") })
        }
    }

    private fun setRemark(value: String) {
        remarkInput.setText(value)
        saveSettings()
        refreshSummary()
    }

    private fun sectionValue(label: String, value: String): TextView {
        return TextView(this).apply {
            text = "$label：$value"
            textSize = 18f
            setTextColor(Color.rgb(20, 65, 45))
            setPadding(0, 8, 0, 8)
        }
    }

    private fun primaryButton(textValue: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(35, 145, 85))
            setOnClickListener { action() }
        }
    }

    private fun secondaryButton(textValue: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            textSize = 18f
            setOnClickListener { action() }
        }
    }

    private fun smallButton(textValue: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            setOnClickListener { action() }
        }
    }

    private fun siteButton(name: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = name
            gravity = Gravity.CENTER_VERTICAL
            setAllCaps(false)
            setOnClickListener { action() }
        }
    }

    private fun LinearLayout.addGap() {
        addView(View(this@MainActivity), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 18))
    }

    private fun refreshSummary() {
        selectedSiteText.text = "目前地盤：${selectedSiteName.ifBlank { "未選擇" }}"
        selectedFolderText.text = "資料夾：${folderNamePreview()}"
    }

    private fun folderNamePreview(): String {
        val date = savedDate().ifBlank { todayString(0) }
        val remark = savedRemark()
        return if (remark.isBlank()) date else "$date$remark"
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) return cursor.getString(index)
        }
        return null
    }

    private fun loadSettingsValues() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        selectedSiteName = prefs.getString("siteName", "") ?: ""
    }

    private fun saveSettings() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("baseUrl", baseUrlInput.text.toString().trim())
            .putString("pin", pinInput.text.toString().trim())
            .putString("siteId", siteIdInput.text.toString().trim())
            .putString("siteName", selectedSiteName)
            .putString("date", dateInput.text.toString().trim())
            .putString("remark", remarkInput.text.toString().trim())
            .apply()
    }

    private fun savedBaseUrl(): String {
        return getSharedPreferences("settings", MODE_PRIVATE)
            .getString("baseUrl", "https://telegram-site-record-proxy.onrender.com")
            .orEmpty()
    }

    private fun savedPin(): String {
        return getSharedPreferences("settings", MODE_PRIVATE).getString("pin", "").orEmpty()
    }

    private fun savedSiteId(): String {
        return getSharedPreferences("settings", MODE_PRIVATE).getString("siteId", "").orEmpty()
    }

    private fun savedDate(): String {
        return getSharedPreferences("settings", MODE_PRIVATE).getString("date", todayString(0)).orEmpty()
    }

    private fun savedRemark(): String {
        return getSharedPreferences("settings", MODE_PRIVATE).getString("remark", "").orEmpty()
    }

    private fun hasRequiredUploadSettings(): Boolean {
        if (savedBaseUrl().isBlank()) {
            statusText.text = "請先填 Render URL。"
            return false
        }
        if (savedPin().isBlank()) {
            statusText.text = "請先填 WEB_ADMIN_PIN。"
            return false
        }
        if (savedSiteId().isBlank()) {
            statusText.text = "請先同步並選擇地盤。"
            return false
        }
        return true
    }

    private fun errorMessage(body: String): String {
        return try {
            JSONObject(body).optString("error", body).ifBlank { body }
        } catch (_: Exception) {
            body.ifBlank { "沒有錯誤內容" }
        }
    }

    private fun todayString(offsetDays: Long): String {
        return LocalDate.now().plusDays(offsetDays).format(DateTimeFormatter.BASIC_ISO_DATE)
    }

    private fun showDatePicker() {
        val current = parseDateInput() ?: LocalDate.now()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selected = LocalDate.of(year, month + 1, dayOfMonth)
                dateInput.setText(selected.format(DateTimeFormatter.BASIC_ISO_DATE))
                saveSettings()
                refreshSummary()
            },
            current.year,
            current.monthValue - 1,
            current.dayOfMonth
        ).show()
    }

    private fun parseDateInput(): LocalDate? {
        return try {
            LocalDate.parse(dateInput.text.toString().trim(), DateTimeFormatter.BASIC_ISO_DATE)
        } catch (_: Exception) {
            null
        }
    }
}
