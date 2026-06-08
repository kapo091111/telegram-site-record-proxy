package com.bom.sitecamera

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
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
import java.time.format.DateTimeFormatter.ofPattern
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var currentSiteText: TextView
    private lateinit var folderText: TextView
    private lateinit var countsText: TextView
    private lateinit var syncText: TextView
    private lateinit var baseUrlInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var siteIdInput: EditText
    private lateinit var dateInput: EditText
    private lateinit var remarkInput: EditText
    private lateinit var siteSearchInput: EditText
    private lateinit var siteList: LinearLayout
    private lateinit var draftList: LinearLayout
    private lateinit var previewView: PreviewView

    private var selectedSiteName: String = ""
    private var sites = JSONArray()
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var zoomSlider: SeekBar? = null
    private var lensMode = LensMode.MAIN

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val clipData = data.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                copyUriToDraft(clipData.getItemAt(index).uri)
            }
        } else {
            data.data?.let { copyUriToDraft(it) }
        }
        showMainScreen("已加入待上傳清單。")
    }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        showMainScreen("已更新編輯版本。")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedSiteName = prefs().getString("siteName", "") ?: ""
        showMainScreen()
    }

    private fun showMainScreen(message: String = "") {
        cameraProvider?.unbindAll()
        imageCapture = null
        camera = null

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
            setBackgroundColor(Color.rgb(245, 247, 244))
        }
        val root = ScrollView(this).apply { addView(content) }

        statusText = TextView(this).apply {
            text = message.ifBlank { "先選好地盤、日期、備注，再拍照或選相。" }
            textSize = 14f
            setTextColor(Color.rgb(90, 104, 96))
            setPadding(0, 0, 0, dp(10))
        }
        content.addView(title("工程現場記錄"))
        content.addView(statusText)
        content.addView(currentCard())
        content.addView(uploadSettingsCard())
        content.addView(siteCard())
        content.addView(draftsCard())
        setContentView(root)
        refreshSites()
        refreshDrafts()
        refreshSummary()
    }

    private fun currentCard(): View {
        currentSiteText = metric("地盤", selectedSiteName.ifBlank { "未選擇" })
        folderText = metric("資料夾", folderNamePreview())
        countsText = metric("今日檔案", "${DraftStore.list(this).size} 個待上傳")
        syncText = metric("同步", "正常")
        return card("目前設定").apply {
            addView(currentSiteText)
            addView(folderText)
            addView(countsText)
            addView(syncText)
        }
    }

    private fun uploadSettingsCard(): LinearLayout {
        baseUrlInput = edit("Render URL", savedBaseUrl(), InputType.TYPE_TEXT_VARIATION_URI)
        pinInput = edit("WEB_ADMIN_PIN", savedPin(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        siteIdInput = edit("地盤 ID", savedSiteId(), InputType.TYPE_CLASS_TEXT)
        dateInput = edit("日期", displayDate(savedDate()), InputType.TYPE_CLASS_TEXT)
        remarkInput = edit("檔案備注", savedRemark(), InputType.TYPE_CLASS_TEXT)

        return card("上傳設定").apply {
            addView(baseUrlInput)
            addView(pinInput)
            addView(siteIdInput)
            addView(row(
                button("選日期", false) { showDatePicker() },
                button("今日", false) { setDate(todayString(0)) },
                button("昨日", false) { setDate(todayString(-1)) }
            ))
            addView(remarkInput)
            addView(row(
                button("打拆", false) { setRemark("打拆") },
                button("水電完成", false) { setRemark("水電完成") },
                button("泥水完成", false) { setRemark("泥水完成") },
                button("清除", danger = true) { setRemark("") }
            ))
            addView(row(
                button("儲存設定", false) {
                    saveSettings()
                    refreshSummary()
                    statusText.text = "設定已儲存。"
                },
                button("拍照上傳", true) {
                    saveSettings()
                    if (hasRequiredSettings()) showCameraScreen()
                },
                button("相簿選取", false) {
                    saveSettings()
                    openGallery()
                }
            ))
        }
    }

    private fun siteCard(): LinearLayout {
        siteSearchInput = edit("搜尋 25026 / 海怡 / 2401", "", InputType.TYPE_CLASS_TEXT)
        siteList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        siteSearchInput.setOnEditorActionListener { _, _, _ ->
            refreshSites()
            true
        }
        return card("地盤").apply {
            addView(siteSearchInput)
            addView(row(
                button("同步 Sheet", false) { fetchSites() },
                button("篩選", false) { refreshSites() }
            ))
            addView(siteList)
        }
    }

    private fun draftsCard(): LinearLayout {
        draftList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        return card("待上傳").apply {
            addView(draftList)
            addView(row(
                button("上傳全部", true) { uploadAllDrafts() },
                button("重新整理", false) { refreshDrafts() }
            ))
        }
    }

    private fun showCameraScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this)
        root.addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(8))
            addView(cameraButton("返回") { showMainScreen() })
            addView(cameraButton("0.5x") {
                lensMode = LensMode.WIDE
                startCamera()
            })
            addView(cameraButton("1x") {
                lensMode = LensMode.MAIN
                startCamera()
            })
            addView(cameraButton("閃光") { toggleFlash() })
        }
        root.addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(8), dp(20), dp(24))
            zoomSlider = SeekBar(this@MainActivity).apply {
                max = 100
                progress = 0
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) setLinearZoom(progress / 100f)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            addView(zoomSlider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(cameraButton("拍照加入待上傳") { capturePhoto() })
        }
        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) focusAt(event.x, event.y)
            true
        }

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
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
                .build()
            val selector = chooseCameraSelector()
            cameraProvider?.unbindAll()
            camera = cameraProvider?.bindToLifecycle(this, selector, preview, imageCapture)
            applyWideFallback()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun chooseCameraSelector(): CameraSelector {
        if (lensMode == LensMode.WIDE) {
            wideCameraId()?.let { cameraId ->
                return CameraSelector.Builder()
                    .addCameraFilter { infos ->
                        infos.filter { Camera2Id.fromCameraInfo(it) == cameraId }
                    }
                    .build()
            }
        }
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun wideCameraId(): String? {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.mapNotNull { id ->
            val c = manager.getCameraCharacteristics(id)
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (facing == CameraCharacteristics.LENS_FACING_BACK && focalLengths != null && focalLengths.isNotEmpty()) {
                id to focalLengths.minOrNull()!!
            } else null
        }.minByOrNull { it.second }?.first
    }

    private fun applyWideFallback() {
        if (lensMode == LensMode.WIDE) {
            camera?.cameraControl?.setLinearZoom(0f)
        }
    }

    private fun setLinearZoom(value: Float) {
        camera?.cameraControl?.setLinearZoom(value.coerceIn(0f, 1f))
    }

    private fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
    }

    private fun focusAt(x: Float, y: Float) {
        val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(previewView.width.toFloat(), previewView.height.toFloat())
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val outputFile = File(DraftStore.draftsDir(this), "capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        capture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                DraftStore.add(this@MainActivity, outputFile, "image/jpeg", "jpg", savedSiteId(), selectedSiteName, savedDate(), savedRemark())
                showMainScreen("已加入待上傳清單，可繼續拍照或檢查後上傳。")
            }
            override fun onError(exception: ImageCaptureException) {
                showMainScreen("拍照失敗：${exception.message}")
            }
        })
    }

    private fun fetchSites() {
        saveSettings()
        val baseUrl = savedBaseUrl().trimEnd('/')
        val pin = savedPin()
        if (baseUrl.isBlank()) {
            statusText.text = "請先填 Render URL。"
            return
        }
        if (pin.isBlank()) {
            statusText.text = "請先填 WEB_ADMIN_PIN。"
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
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("HTTP $code：${errorMessage(body)}")
                val json = JSONObject(body)
                sites = json.getJSONArray("sites")
                json.optJSONObject("currentSite")?.let {
                    siteIdInput.setText(it.getString("id"))
                    selectedSiteName = it.getString("name")
                }
                runOnUiThread {
                    saveSettings()
                    refreshSites()
                    refreshSummary()
                    statusText.text = "已同步地盤。"
                }
            } catch (error: Exception) {
                runOnUiThread { statusText.text = "同步地盤失敗：${error.message}" }
            }
        }.start()
    }

    private fun refreshSites() {
        if (!::siteList.isInitialized) return
        siteList.removeAllViews()
        val query = if (::siteSearchInput.isInitialized) siteSearchInput.text.toString().trim() else ""
        for (index in 0 until sites.length()) {
            val site = sites.getJSONObject(index)
            val name = site.getString("name")
            val id = site.getString("id")
            if (query.isNotBlank() && !name.contains(query, true)) continue
            siteList.addView(row(
                button(name, id == savedSiteId()) {
                    siteIdInput.setText(id)
                    selectedSiteName = name
                    saveSettings()
                    refreshSummary()
                    refreshSites()
                },
                button("刪除", danger = true) { deleteSite(id, name) }
            ))
        }
    }

    private fun deleteSite(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("刪除地盤")
            .setMessage("確定刪除「$name」？只有無檔案地盤可以刪除。")
            .setPositiveButton("刪除") { _, _ -> deleteSiteRequest(id) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSiteRequest(id: String) {
        Thread {
            try {
                val body = JSONObject().put("siteId", id).toString()
                val connection = URL("${savedBaseUrl().trimEnd('/')}/api/admin/delete-site").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("x-admin-pin", savedPin())
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException(errorMessage(response))
                runOnUiThread {
                    statusText.text = "已刪除地盤。請重新同步 Sheet。"
                    fetchSites()
                }
            } catch (error: Exception) {
                runOnUiThread { statusText.text = "刪除失敗：${error.message}" }
            }
        }.start()
    }

    private fun openGallery() {
        saveSettings()
        if (!hasRequiredSettings()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "application/pdf"))
        }
        picker.launch(intent)
    }

    private fun copyUriToDraft(uri: Uri) {
        val displayName = displayName(uri) ?: "picked_${System.currentTimeMillis()}"
        val extension = displayName.substringAfterLast('.', "bin").lowercase(Locale.ROOT)
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val outputFile = File(DraftStore.draftsDir(this), "${UUID.randomUUID()}.$extension")
        contentResolver.openInputStream(uri)?.use { input -> outputFile.outputStream().use { output -> input.copyTo(output) } }
        DraftStore.add(this, outputFile, mimeType, extension, savedSiteId(), selectedSiteName, savedDate(), savedRemark())
    }

    private fun refreshDrafts() {
        if (!::draftList.isInitialized) return
        draftList.removeAllViews()
        val drafts = DraftStore.list(this)
        countsText.text = "今日檔案：${drafts.size} 個待上傳"
        if (drafts.isEmpty()) {
            draftList.addView(TextView(this).apply {
                text = "暫時未有待上傳相片。"
                setTextColor(Color.rgb(90, 104, 96))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        drafts.forEach { draft ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(230, 236, 230))
                val bitmap = BitmapFactory.decodeFile(draft.filePath)
                if (bitmap != null) setImageBitmap(bitmap)
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(70), dp(70)))
            row.addView(TextView(this).apply {
                text = "${draft.date}${draft.remark}\n${draft.siteName}\n${draft.status}"
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("編輯", false) { openEditor(draft.id) })
            row.addView(button("刪除", danger = true) {
                DraftStore.remove(this, draft.id)
                refreshDrafts()
                refreshSummary()
            })
            draftList.addView(row)
        }
    }

    private fun openEditor(draftId: String) {
        editLauncher.launch(Intent(this, EditActivity::class.java).putExtra("draftId", draftId))
    }

    private fun uploadAllDrafts() {
        saveSettings()
        if (!hasRequiredSettings()) return
        val drafts = DraftStore.list(this)
        if (drafts.isEmpty()) {
            statusText.text = "未有待上傳檔案。"
            return
        }
        drafts.forEach { draft ->
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString("baseUrl", savedBaseUrl())
                        .putString("pin", savedPin())
                        .putString("draftId", draft.id)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueue(request)
        }
        statusText.text = "已開始背景上傳 ${drafts.size} 個檔案。"
        refreshDrafts()
    }

    private fun hasRequiredSettings(): Boolean {
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

    private fun saveSettings() {
        prefs().edit()
            .putString("baseUrl", baseUrlInput.text.toString().trim())
            .putString("pin", pinInput.text.toString().trim())
            .putString("siteId", siteIdInput.text.toString().trim())
            .putString("siteName", selectedSiteName)
            .putString("date", apiDate(dateInput.text.toString()).ifBlank { todayString(0) })
            .putString("remark", remarkInput.text.toString().trim())
            .apply()
    }

    private fun savedBaseUrl() = prefs().getString("baseUrl", "https://telegram-site-record-proxy.onrender.com").orEmpty()
    private fun savedPin() = prefs().getString("pin", "").orEmpty()
    private fun savedSiteId() = prefs().getString("siteId", "").orEmpty()
    private fun savedDate() = prefs().getString("date", todayString(0)).orEmpty()
    private fun savedRemark() = prefs().getString("remark", "").orEmpty()

    private fun setDate(value: String) {
        dateInput.setText(displayDate(value))
        saveSettings()
        refreshSummary()
    }

    private fun setRemark(value: String) {
        remarkInput.setText(value)
        saveSettings()
        refreshSummary()
    }

    private fun refreshSummary() {
        if (::currentSiteText.isInitialized) currentSiteText.text = "地盤\n${selectedSiteName.ifBlank { "未選擇" }}"
        if (::folderText.isInitialized) folderText.text = "資料夾\n${folderNamePreview()}"
        if (::syncText.isInitialized) syncText.text = "同步\n正常"
    }

    private fun folderNamePreview(): String {
        val date = savedDate()
        val remark = savedRemark()
        return if (remark.isBlank()) date else "$date$remark"
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTextColor(Color.rgb(18, 30, 24))
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(12))
    }

    private fun card(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, Color.rgb(220, 228, 220))
                cornerRadius = dp(8).toFloat()
            }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            })
        }.withMargins()
    }

    private fun LinearLayout.withMargins(): LinearLayout {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(12))
        }
        return this
    }

    private fun metric(label: String, value: String) = TextView(this).apply {
        text = "$label\n$value"
        textSize = 17f
        setTextColor(Color.rgb(20, 34, 27))
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(1, Color.rgb(220, 228, 220))
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun edit(hint: String, value: String, inputTypeValue: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = inputTypeValue
        setSingleLine(false)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach {
            addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(4), dp(3), dp(4))
            })
        }
    }

    private fun button(label: String, primary: Boolean = false, danger: Boolean = false, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else if (danger) Color.rgb(170, 35, 24) else Color.rgb(20, 92, 56))
            setBackgroundColor(if (primary) Color.rgb(47, 143, 91) else if (danger) Color.rgb(255, 244, 242) else Color.rgb(223, 242, 231))
            setOnClickListener { action() }
        }
    }

    private fun cameraButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(35, 35, 35))
        setOnClickListener { action() }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) return cursor.getString(index)
        }
        return null
    }

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    private fun showDatePicker() {
        val current = parseDate(apiDate(dateInput.text.toString())) ?: LocalDate.now()
        DatePickerDialog(this, { _, year, month, day ->
            setDate(LocalDate.of(year, month + 1, day).format(DateTimeFormatter.BASIC_ISO_DATE))
        }, current.year, current.monthValue - 1, current.dayOfMonth).show()
    }

    private fun todayString(offsetDays: Long) = LocalDate.now().plusDays(offsetDays).format(DateTimeFormatter.BASIC_ISO_DATE)

    private fun displayDate(value: String): String {
        return parseDate(value)?.format(ofPattern("dd/MM/yyyy")) ?: value
    }

    private fun apiDate(value: String): String {
        val trimmed = value.trim()
        parseDate(trimmed)?.let { return it.format(DateTimeFormatter.BASIC_ISO_DATE) }
        return trimmed.replace("-", "").replace("/", "")
    }

    private fun parseDate(value: String): LocalDate? {
        return try {
            val digits = value.replace("-", "").replace("/", "")
            LocalDate.parse(digits, DateTimeFormatter.BASIC_ISO_DATE)
        } catch (_: Exception) {
            null
        }
    }

    private fun errorMessage(body: String): String {
        return try {
            JSONObject(body).optString("error", body).ifBlank { body }
        } catch (_: Exception) {
            body.ifBlank { "沒有錯誤內容" }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

enum class LensMode {
    MAIN,
    WIDE
}

object Camera2Id {
    fun fromCameraInfo(info: CameraInfo): String? {
        return try {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId
        } catch (_: Exception) {
            null
        }
    }
}
