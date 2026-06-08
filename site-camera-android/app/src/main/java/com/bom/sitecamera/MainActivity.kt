package com.bom.sitecamera

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ofPattern
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private enum class Screen {
    Home,
    Camera,
    Review
}

private enum class LensMode {
    Wide,
    Main,
    Tele
}

private data class SiteOption(
    val id: String,
    val name: String
)

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.Home)
    private var message by mutableStateOf("")
    private var selectedSiteId by mutableStateOf("")
    private var selectedSiteName by mutableStateOf("")
    private var recordDate by mutableStateOf(todayString(0))
    private var remark by mutableStateOf("")
    private var baseUrl by mutableStateOf("https://telegram-site-record-proxy.onrender.com")
    private var sites by mutableStateOf<List<SiteOption>>(emptyList())
    private var drafts by mutableStateOf<List<UploadDraft>>(emptyList())
    private var siteSearch by mutableStateOf("")
    private var showAdvanced by mutableStateOf(false)
    private var uploadStatus by mutableStateOf("")
    private var isUploading by mutableStateOf(false)
    private var reviewIndex by mutableIntStateOf(0)
    private var flashMode by mutableIntStateOf(ImageCapture.FLASH_MODE_OFF)
    private var lensMode by mutableStateOf(LensMode.Main)
    private var zoomValue by mutableFloatStateOf(0f)

    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var systemCameraFile: File? = null

    private val galleryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
        refreshDrafts()
        reviewIndex = (drafts.lastIndex).coerceAtLeast(0)
        screen = Screen.Review
        message = "已加入待上傳清單。"
    }

    private val systemCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = systemCameraFile ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK && file.exists() && file.length() > 0) {
            DraftStore.add(this, file, "image/jpeg", "jpg", selectedSiteId, selectedSiteName, recordDate, remark)
            refreshDrafts()
            message = "已用 HONOR 相機加入待上傳。"
        } else {
            file.delete()
        }
        systemCameraFile = null
    }

    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshDrafts()
        screen = Screen.Review
        message = "已更新編輯版本。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettings()
        refreshDrafts()
        setContent { SiteCameraApp() }
        fetchState(silent = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    @Composable
    private fun SiteCameraApp() {
        MaterialTheme {
            Surface(color = ComposeColor(0xfff4f6f2), modifier = Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.Home -> HomeScreen()
                    Screen.Camera -> CameraScreen()
                    Screen.Review -> ReviewScreen()
                }
            }
        }
    }

    @Composable
    private fun HomeScreen() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("工程現場記錄", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("選好設定後直接拍照，完成後一次過上傳。", color = ComposeColor(0xff64706a))
            }
            item { SummaryCard() }
            item { ActionCard() }
            item { SiteCard() }
            item { DraftCard() }
            if (message.isNotBlank() || uploadStatus.isNotBlank()) {
                item {
                    Text(
                        listOf(message, uploadStatus).filter { it.isNotBlank() }.joinToString("\n\n"),
                        color = ComposeColor(0xff355243),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
        if (showAdvanced) AdvancedDialog()
    }

    @Composable
    private fun SummaryCard() {
        Panel("目前設定") {
            InfoTile("地盤", selectedSiteName.ifBlank { "未選擇" })
            InfoTile("資料夾", folderNamePreview())
            InfoTile("待上傳", "${drafts.size} 個檔案")
            InfoTile("同步", if (isUploading) "上傳中" else "正常")
        }
    }

    @Composable
    private fun ActionCard() {
        Panel("拍攝設定") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showDatePicker() }, modifier = Modifier.weight(1f)) {
                    Text(displayDate(recordDate))
                }
                SoftButton("今日", Modifier.weight(1f)) { setDate(todayString(0)) }
                SoftButton("昨日", Modifier.weight(1f)) { setDate(todayString(-1)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                RemarkButton("打拆", Modifier.weight(1f))
                RemarkButton("水電完成", Modifier.weight(1f))
                RemarkButton("泥水完成", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = remark,
                onValueChange = {
                    remark = it.trim()
                    saveSettings()
                },
                label = { Text("備注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { openCamera() }, modifier = Modifier.weight(1f), colors = greenButton()) {
                    Text("拍照")
                }
                OutlinedButton(onClick = { openGallery() }, modifier = Modifier.weight(1f)) {
                    Text("相簿")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { screen = Screen.Review }, modifier = Modifier.weight(1f), enabled = drafts.isNotEmpty()) {
                    Text("查看待上傳")
                }
                TextButton(onClick = { showAdvanced = true }, modifier = Modifier.weight(1f)) {
                    Text("進階設定")
                }
            }
        }
    }

    @Composable
    private fun SiteCard() {
        val filtered = sites.filter { site ->
            val query = siteSearch.trim()
            query.isBlank() || site.name.contains(query, ignoreCase = true)
        }
        Panel("地盤") {
            OutlinedTextField(
                value = siteSearch,
                onValueChange = { siteSearch = it },
                label = { Text("搜尋 25026 / 海怡 / 2401") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SoftButton("同步 Sheet", Modifier.weight(1f)) { syncSites() }
                OutlinedButton(onClick = { fetchState() }, modifier = Modifier.weight(1f)) {
                    Text("重新整理")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                Text("未有地盤。請先同步 Sheet。", color = ComposeColor(0xff64706a))
            } else {
                filtered.take(30).forEach { site ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                selectedSiteId = site.id
                                selectedSiteName = site.name
                                saveSettings()
                                message = "已選擇：${site.name}"
                            },
                            colors = if (site.id == selectedSiteId) greenButton() else ButtonDefaults.buttonColors(
                                containerColor = ComposeColor.White,
                                contentColor = ComposeColor(0xff17231d)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(site.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(onClick = { deleteSite(site) }) {
                            Text("刪除", color = ComposeColor(0xffb42318))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    @Composable
    private fun DraftCard() {
        Panel("待上傳") {
            if (drafts.isEmpty()) {
                Text("未有待上傳檔案。", color = ComposeColor(0xff64706a))
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    drafts.take(12).forEachIndexed { index, draft ->
                        DraftThumb(draft, selected = false, modifier = Modifier.clickable {
                            reviewIndex = index
                            screen = Screen.Review
                        })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { uploadAllDrafts() },
                    enabled = drafts.isNotEmpty() && !isUploading,
                    modifier = Modifier.weight(1f),
                    colors = greenButton()
                ) {
                    Text(if (isUploading) "上傳中" else "上傳全部")
                }
                OutlinedButton(onClick = { refreshDrafts() }, modifier = Modifier.weight(1f)) {
                    Text("重新整理")
                }
            }
        }
    }

    @Composable
    private fun CameraScreen() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor.Black)
        ) {
            AndroidView(
                factory = { context ->
                    PreviewView(context).also {
                        previewView = it
                        it.setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_UP) focusAt(event.x, event.y)
                            true
                        }
                        requestCameraPermission()
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val next = (zoomValue + ((zoom - 1f) * 0.18f)).coerceIn(0f, 1f)
                            zoomValue = next
                            setLinearZoom(next)
                        }
                    }
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CameraPill("返回") { screen = Screen.Home }
                    CameraPill(flashLabel()) { toggleFlash() }
                    CameraPill("HONOR 相機") { openSystemCamera() }
                }
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    CameraPill("0.5x", active = lensMode == LensMode.Wide) {
                        lensMode = LensMode.Wide
                        zoomValue = 0f
                        startCamera()
                    }
                    Spacer(Modifier.width(8.dp))
                    CameraPill("1x", active = lensMode == LensMode.Main) {
                        lensMode = LensMode.Main
                        zoomValue = 0f
                        startCamera()
                    }
                    Spacer(Modifier.width(8.dp))
                    CameraPill("2x", active = lensMode == LensMode.Tele) {
                        lensMode = LensMode.Tele
                        zoomValue = 0.45f
                        startCamera()
                    }
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Slider(value = zoomValue, onValueChange = {
                    zoomValue = it
                    setLinearZoom(it)
                })
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DraftCounter()
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .border(4.dp, ComposeColor.White, CircleShape)
                            .clickable { capturePhoto() }
                    )
                    LatestThumb()
                }
            }
        }
    }

    @Composable
    private fun ReviewScreen() {
        val safeDrafts = drafts
        if (safeDrafts.isEmpty()) {
            screen = Screen.Home
            return
        }
        val index = reviewIndex.coerceIn(0, safeDrafts.lastIndex)
        val draft = safeDrafts[index]
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor.Black)
                .padding(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CameraPill("返回相機") { screen = Screen.Camera }
                CameraPill("主畫面") { screen = Screen.Home }
                Spacer(Modifier.weight(1f))
                CameraPill("${index + 1} / ${safeDrafts.size}") {}
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                LargePreview(draft)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                safeDrafts.forEachIndexed { itemIndex, item ->
                    DraftThumb(item, selected = itemIndex == index, modifier = Modifier.clickable { reviewIndex = itemIndex })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { reviewIndex = (index - 1).coerceAtLeast(0) }, modifier = Modifier.weight(1f)) {
                    Text("上一張")
                }
                OutlinedButton(onClick = { reviewIndex = (index + 1).coerceAtMost(safeDrafts.lastIndex) }, modifier = Modifier.weight(1f)) {
                    Text("下一張")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { openEditor(draft.id) }, enabled = draft.mimeType.startsWith("image/"), modifier = Modifier.weight(1f), colors = greenButton()) {
                    Text("編輯")
                }
                OutlinedButton(onClick = { removeDraft(draft.id) }, modifier = Modifier.weight(1f)) {
                    Text("刪除", color = ComposeColor(0xffffd3cc))
                }
                Button(onClick = { uploadAllDrafts() }, enabled = !isUploading, modifier = Modifier.weight(1f), colors = greenButton()) {
                    Text("上傳")
                }
            }
            if (uploadStatus.isNotBlank()) {
                Text(uploadStatus, color = ComposeColor.White, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    @Composable
    private fun AdvancedDialog() {
        var draftBaseUrl by mutableStateOf(baseUrl)
        AlertDialog(
            onDismissRequest = { showAdvanced = false },
            title = { Text("進階設定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("日常使用不需要填 PIN。App 會使用內置 mobile app key。")
                    OutlinedTextField(
                        value = draftBaseUrl,
                        onValueChange = { draftBaseUrl = it },
                        label = { Text("Render URL") },
                        singleLine = true
                    )
                    Text("目前 App key：已隱藏", color = ComposeColor(0xff64706a))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    baseUrl = draftBaseUrl.trim().trimEnd('/')
                    saveSettings()
                    showAdvanced = false
                }) { Text("儲存") }
            },
            dismissButton = {
                TextButton(onClick = { showAdvanced = false }) { Text("取消") }
            }
        )
    }

    @Composable
    private fun Panel(title: String, content: @Composable () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = ComposeColor.White),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    @Composable
    private fun InfoTile(label: String, value: String) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ComposeColor(0xffdce4dc), RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Text(label, color = ComposeColor(0xff64706a), style = MaterialTheme.typography.labelMedium)
            Text(value, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun RemarkButton(value: String, modifier: Modifier = Modifier) {
        val active = remark == value
        Button(
            onClick = {
                remark = if (active) "" else value
                saveSettings()
            },
            modifier = modifier,
            colors = if (active) greenButton() else ButtonDefaults.buttonColors(
                containerColor = ComposeColor(0xffdff2e7),
                contentColor = ComposeColor(0xff145b38)
            )
        ) {
            Text(value, maxLines = 1)
        }
    }

    @Composable
    private fun SoftButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xffdff2e7), contentColor = ComposeColor(0xff145b38))
        ) {
            Text(label)
        }
    }

    @Composable
    private fun CameraPill(label: String, active: Boolean = false, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) ComposeColor.White else ComposeColor(0xaa111111),
                contentColor = if (active) ComposeColor.Black else ComposeColor.White
            ),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text(label)
        }
    }

    @Composable
    private fun DraftCounter() {
        Text(
            "${drafts.size} 張",
            color = ComposeColor.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(ComposeColor(0x66000000), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }

    @Composable
    private fun LatestThumb() {
        val latest = drafts.lastOrNull()
        if (latest == null) {
            Box(Modifier.size(58.dp))
            return
        }
        DraftThumb(latest, selected = false, modifier = Modifier.clickable {
            reviewIndex = drafts.lastIndex
            screen = Screen.Review
        })
    }

    @Composable
    private fun DraftThumb(draft: UploadDraft, selected: Boolean, modifier: Modifier = Modifier) {
        val bitmap = if (draft.mimeType.startsWith("image/")) BitmapFactory.decodeFile(draft.filePath) else null
        Box(
            modifier = modifier
                .size(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ComposeColor(0xff27332d))
                .then(if (selected) Modifier.border(3.dp, ComposeColor.White, RoundedCornerShape(10.dp)) else Modifier)
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text(draft.extension.uppercase(Locale.ROOT), color = ComposeColor.White, modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    @Composable
    private fun LargePreview(draft: UploadDraft) {
        val bitmap = if (draft.mimeType.startsWith("image/")) BitmapFactory.decodeFile(draft.filePath) else null
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(draft.extension.uppercase(Locale.ROOT), color = ComposeColor.White, style = MaterialTheme.typography.headlineLarge)
                Text(draft.filePath.substringAfterLast(File.separator), color = ComposeColor(0xffcfd8d2))
            }
        }
    }

    @Composable
    private fun greenButton() = ButtonDefaults.buttonColors(
        containerColor = ComposeColor(0xff2f8f5b),
        contentColor = ComposeColor.White
    )

    private fun openCamera() {
        if (!hasRequiredSettings()) return
        saveSettings()
        screen = Screen.Camera
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
    }

    private fun startCamera() {
        if (!::previewView.isInitialized) return
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
            camera = try {
                cameraProvider?.bindToLifecycle(this, selector, preview, imageCapture)
            } catch (_: Exception) {
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }
            applyLensZoom()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun chooseCameraSelector(): CameraSelector {
        if (lensMode == LensMode.Wide) {
            wideCameraId()?.let { cameraId ->
                return CameraSelector.Builder()
                    .addCameraFilter { infos -> infos.filter { Camera2Id.fromCameraInfo(it) == cameraId } }
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

    private fun applyLensZoom() {
        val target = when (lensMode) {
            LensMode.Wide -> 0f
            LensMode.Main -> zoomValue
            LensMode.Tele -> 0.45f.coerceAtLeast(zoomValue)
        }
        zoomValue = target
        setLinearZoom(target)
    }

    private fun setLinearZoom(value: Float) {
        camera?.cameraControl?.setLinearZoom(value.coerceIn(0f, 1f))
    }

    private fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
    }

    private fun flashLabel(): String {
        return when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "閃光：自動"
            ImageCapture.FLASH_MODE_ON -> "閃光：開"
            else -> "閃光：關"
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(previewView.width.toFloat(), previewView.height.toFloat())
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
        Toast.makeText(this, "已對焦", Toast.LENGTH_SHORT).show()
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val outputFile = File(DraftStore.draftsDir(this), "capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        capture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                DraftStore.add(this@MainActivity, outputFile, "image/jpeg", "jpg", selectedSiteId, selectedSiteName, recordDate, remark)
                refreshDrafts()
                message = "已加入待上傳，可繼續拍照。"
            }

            override fun onError(exception: ImageCaptureException) {
                message = "拍照失敗：${exception.message}"
            }
        })
    }

    private fun openSystemCamera() {
        if (!hasRequiredSettings()) return
        val file = File(DraftStore.draftsDir(this), "honor_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        systemCameraFile = file
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        systemCamera.launch(intent)
    }

    private fun openGallery() {
        if (!hasRequiredSettings()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        galleryPicker.launch(intent)
    }

    private fun copyUriToDraft(uri: Uri) {
        val displayName = displayName(uri) ?: "picked_${System.currentTimeMillis()}"
        val extension = displayName.substringAfterLast('.', "bin").lowercase(Locale.ROOT)
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val outputFile = File(DraftStore.draftsDir(this), "${UUID.randomUUID()}.$extension")
        contentResolver.openInputStream(uri)?.use { input -> outputFile.outputStream().use { output -> input.copyTo(output) } }
        DraftStore.add(this, outputFile, mimeType, extension, selectedSiteId, selectedSiteName, recordDate, remark)
    }

    private fun displayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
    }

    private fun fetchState(silent: Boolean = false) {
        if (!silent) message = "同步地盤中..."
        Thread {
            try {
                val connection = URL("${baseUrl.trimEnd('/')}/api/mobile/state").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("x-mobile-app-key", BuildConfig.MOBILE_APP_KEY)
                val code = connection.responseCode
                val body = responseText(connection, code)
                if (code !in 200..299) throw IllegalStateException(errorMessage(body))
                val json = JSONObject(body)
                val loadedSites = parseSites(json.getJSONArray("sites"))
                runOnUiThread {
                    sites = loadedSites
                    json.optJSONObject("currentSite")?.let {
                        selectedSiteId = it.optString("id", selectedSiteId)
                        selectedSiteName = it.optString("name", selectedSiteName)
                    }
                    recordDate = json.optString("recordDate", recordDate)
                    remark = json.optString("remark", remark)
                    saveSettings()
                    if (!silent) message = "已同步地盤。"
                }
            } catch (error: Exception) {
                runOnUiThread { message = "同步失敗：${error.message}" }
            }
        }.start()
    }

    private fun syncSites() {
        message = "正在同步 Google Sheet..."
        Thread {
            try {
                val connection = URL("${baseUrl.trimEnd('/')}/api/mobile/sync-sites").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("x-mobile-app-key", BuildConfig.MOBILE_APP_KEY)
                val code = connection.responseCode
                val body = responseText(connection, code)
                if (code !in 200..299) throw IllegalStateException(errorMessage(body))
                val json = JSONObject(body)
                val loadedSites = parseSites(json.getJSONArray("sites"))
                runOnUiThread {
                    sites = loadedSites
                    message = "已同步 ${loadedSites.size} 個地盤。"
                }
            } catch (error: Exception) {
                runOnUiThread { message = "同步 Sheet 失敗：${error.message}" }
            }
        }.start()
    }

    private fun deleteSite(site: SiteOption) {
        message = "正在刪除地盤..."
        Thread {
            try {
                val body = JSONObject().put("siteId", site.id).toString()
                val connection = URL("${baseUrl.trimEnd('/')}/api/mobile/delete-site").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("x-mobile-app-key", BuildConfig.MOBILE_APP_KEY)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                val response = responseText(connection, code)
                if (code !in 200..299) throw IllegalStateException(errorMessage(response))
                val loadedSites = parseSites(JSONObject(response).getJSONArray("sites"))
                runOnUiThread {
                    sites = loadedSites
                    if (selectedSiteId == site.id) {
                        selectedSiteId = ""
                        selectedSiteName = ""
                        saveSettings()
                    }
                    message = "已刪除地盤。"
                }
            } catch (error: Exception) {
                runOnUiThread { message = "刪除失敗：${error.message}" }
            }
        }.start()
    }

    private fun uploadAllDrafts() {
        if (!hasRequiredSettings()) return
        val batch = DraftStore.list(this)
        if (batch.isEmpty()) {
            uploadStatus = "未有待上傳檔案。"
            return
        }
        isUploading = true
        uploadStatus = "開始上傳 ${batch.size} 個檔案..."
        Thread {
            var completed = 0
            val targets = linkedSetOf<String>()
            batch.forEach { draft ->
                try {
                    val result = uploadDraft(draft)
                    completed += 1
                    DraftStore.removeUploaded(this, draft.id)
                    val target = listOf(result.optString("siteName", draft.siteName), result.optString("folderName"))
                        .filter { it.isNotBlank() }
                        .joinToString(" / ")
                    if (target.isNotBlank()) targets.add(target)
                    runOnUiThread {
                        refreshDrafts()
                        uploadStatus = "已完成：$completed / ${batch.size}\n${targets.joinToString("\n")}"
                    }
                } catch (error: Exception) {
                    DraftStore.markPending(this, draft.id)
                    runOnUiThread {
                        refreshDrafts()
                        uploadStatus = "已完成：$completed / ${batch.size}\n失敗：${error.message}\n${targets.joinToString("\n")}"
                    }
                }
            }
            runOnUiThread {
                isUploading = false
                refreshDrafts()
                uploadStatus = "已完成：$completed / ${batch.size}\n${targets.joinToString("\n")}"
            }
        }.start()
    }

    private fun uploadDraft(draft: UploadDraft): JSONObject {
        val file = File(draft.filePath)
        if (!file.exists()) throw IllegalStateException("檔案不存在。")
        val query = listOf(
            "siteId=${encode(draft.siteId)}",
            "date=${encode(draft.date)}",
            "remark=${encode(draft.remark)}",
            "extension=${encode(draft.extension)}"
        ).joinToString("&")
        val connection = URL("${baseUrl.trimEnd('/')}/api/mobile/upload?$query").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 180_000
        connection.setRequestProperty("x-mobile-app-key", BuildConfig.MOBILE_APP_KEY)
        connection.setRequestProperty("x-client-file-id", draft.id)
        connection.setRequestProperty("Content-Type", draft.mimeType)
        connection.setRequestProperty("Content-Length", file.length().toString())
        file.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
        val code = connection.responseCode
        val body = responseText(connection, code)
        if (code !in 200..299) throw IllegalStateException(errorMessage(body))
        return JSONObject(body)
    }

    private fun openEditor(draftId: String) {
        editorLauncher.launch(Intent(this, EditActivity::class.java).putExtra("draftId", draftId))
    }

    private fun removeDraft(draftId: String) {
        DraftStore.remove(this, draftId)
        refreshDrafts()
        reviewIndex = reviewIndex.coerceAtMost((drafts.size - 1).coerceAtLeast(0))
        if (drafts.isEmpty()) screen = Screen.Home
    }

    private fun refreshDrafts() {
        drafts = DraftStore.list(this)
    }

    private fun hasRequiredSettings(): Boolean {
        if (baseUrl.isBlank()) {
            message = "請先到進階設定填 Render URL。"
            return false
        }
        if (selectedSiteId.isBlank()) {
            message = "請先同步並選擇地盤。"
            return false
        }
        return true
    }

    private fun parseSites(array: JSONArray): List<SiteOption> {
        val result = mutableListOf<SiteOption>()
        for (index in 0 until array.length()) {
            val site = array.getJSONObject(index)
            result.add(SiteOption(site.optString("id"), site.optString("name")))
        }
        return result.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    private fun showDatePicker() {
        val current = LocalDate.parse(recordDate)
        DatePickerDialog(this, { _, year, month, day ->
            setDate(LocalDate.of(year, month + 1, day).format(DateTimeFormatter.ISO_LOCAL_DATE))
        }, current.year, current.monthValue - 1, current.dayOfMonth).show()
    }

    private fun setDate(value: String) {
        recordDate = value
        saveSettings()
    }

    private fun folderNamePreview(): String {
        return recordDate.replace("-", "") + remark
    }

    private fun loadSettings() {
        val prefs = prefs()
        baseUrl = prefs.getString("baseUrl", baseUrl).orEmpty()
        selectedSiteId = prefs.getString("siteId", "").orEmpty()
        selectedSiteName = prefs.getString("siteName", "").orEmpty()
        recordDate = prefs.getString("date", todayString(0)).orEmpty()
        remark = prefs.getString("remark", "").orEmpty()
    }

    private fun saveSettings() {
        prefs().edit()
            .putString("baseUrl", baseUrl.trim().trimEnd('/'))
            .putString("siteId", selectedSiteId)
            .putString("siteName", selectedSiteName)
            .putString("date", recordDate)
            .putString("remark", remark.trim())
            .apply()
    }

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    private fun responseText(connection: HttpURLConnection, code: Int): String {
        return (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }
            .orEmpty()
    }

    private fun errorMessage(body: String): String {
        return runCatching { JSONObject(body).optString("error") }.getOrNull().takeUnless { it.isNullOrBlank() } ?: body
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
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

private fun todayString(offsetDays: Long): String {
    return LocalDate.now().plusDays(offsetDays).format(DateTimeFormatter.ISO_LOCAL_DATE)
}

private fun displayDate(value: String): String {
    return runCatching {
        LocalDate.parse(value).format(ofPattern("dd/MM/yyyy"))
    }.getOrElse { value }
}
