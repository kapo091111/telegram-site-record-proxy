package com.bom.sitecamera

import android.Manifest
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
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

private enum class IconKind {
    Menu,
    Bell,
    Cup,
    Building,
    Folder,
    Document,
    Sync,
    Camera,
    Image,
    Drop,
    Calendar,
    Save,
    Clear,
    Upload,
    Filter,
    Home,
    Settings,
    Tag
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
    private var showMenu by mutableStateOf(false)
    private var showRemarkDialog by mutableStateOf(false)

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
            reviewIndex = drafts.lastIndex.coerceAtLeast(0)
            message = "已加入待上傳，正在開下一張。按返回可停止連拍。"
            openSystemCamera()
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

    override fun onResume() {
        super.onResume()
        refreshDrafts()
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
            Surface(color = ComposeColor(0xfffaf7f2), modifier = Modifier.fillMaxSize()) {
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                HomeHeader()
            }
            item { SummaryCard() }
            item { QuickActionCard() }
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
            item { BottomNavCard() }
        }
        if (showAdvanced) AdvancedDialog()
        if (showMenu) MainMenuDialog()
        if (showRemarkDialog) RemarkDialog()
    }

    @Composable
    private fun HomeHeader() {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "地盤記錄",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = ComposeColor(0xff2b1711),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "先揀好地盤、日期、備注。\n再影相或揀相。",
                color = ComposeColor(0xff7d7169),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                SoftSquare(IconKind.Menu) { showMenu = true }
            }
        }
    }

    @Composable
    private fun SummaryCard() {
        Panel("今日概覽", IconKind.Cup) {
            DashboardTile(
                icon = IconKind.Building,
                label = "地盤",
                primary = selectedSiteName.ifBlank { "未選擇地盤" },
                secondary = siteSecondary(),
                onClick = { message = "請在地盤選擇更改地盤。" }
            )
            DashboardTile(
                icon = IconKind.Folder,
                label = "資料夾",
                primary = folderDateLabel(),
                secondary = remark.ifBlank { "未設定備注" },
                onClick = { showDatePicker() }
            )
            DashboardTile(
                icon = IconKind.Document,
                label = "今日檔案",
                primary = "${drafts.size} 個",
                secondary = "待上傳",
                onClick = { if (drafts.isNotEmpty()) screen = Screen.Review }
            )
            DashboardTile(
                icon = IconKind.Sync,
                label = "同步狀態",
                primary = if (isUploading) "上傳中" else "正常",
                secondary = if (drafts.isEmpty()) "已整理" else "${drafts.size} 個待處理",
                onClick = { refreshDrafts() }
            )
        }
    }

    @Composable
    private fun QuickActionCard() {
        Panel("快速操作", IconKind.Tag) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                QuickAction(IconKind.Camera, "拍照上傳", Modifier.weight(1f)) { openCamera() }
                QuickAction(IconKind.Image, "相簿選取", Modifier.weight(1f)) { openGallery() }
                QuickAction(IconKind.Drop, remark.ifBlank { "檔案備注" }, Modifier.weight(1f)) { showRemarkDialog = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                QuickAction(IconKind.Calendar, "選日期", Modifier.weight(1f)) { showDatePicker() }
                QuickAction(IconKind.Save, "儲存設定", Modifier.weight(1f)) {
                    saveSettings()
                    message = "設定已儲存。"
                }
                QuickAction(IconKind.Clear, "清除", Modifier.weight(1f), danger = true) {
                    remark = ""
                    saveSettings()
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
        Panel("地盤選擇", IconKind.Building, trailing = "更改地盤 ›") {
            Text("搜尋 25026 / 海怡 / 2401", color = ComposeColor(0xff7d7169), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 42.dp))
            Spacer(Modifier.height(8.dp))
            SearchBox()
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SoftButton("同步 Sheet", Modifier.weight(1f)) { syncSites() }
                SoftButton("篩選", Modifier.weight(1f), icon = IconKind.Filter) {
                    message = "可直接在搜尋欄輸入地盤關鍵字。"
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
        Panel("待上傳", IconKind.Upload, trailing = "${drafts.size} 個檔案 ›") {
            if (drafts.isEmpty()) {
                Text("暫時未有待上傳相片。", color = ComposeColor(0xff7d7169))
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
                    colors = brownButton(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineIcon(IconKind.Upload, ComposeColor.White, Modifier.size(20.dp))
                        Text(if (isUploading) "上傳中" else "上傳全部")
                    }
                }
                OutlinedButton(onClick = { refreshDrafts() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineIcon(IconKind.Sync, ComposeColor(0xff5a4034), Modifier.size(20.dp))
                        Text("重新整理", color = ComposeColor(0xff5a4034))
                    }
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
    private fun MainMenuDialog() {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text("選單") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("同步 Sheet") {
                        showMenu = false
                        syncSites()
                    }
                    SoftButton("選日期") {
                        showMenu = false
                        showDatePicker()
                    }
                    SoftButton("檔案備注") {
                        showMenu = false
                        showRemarkDialog = true
                    }
                    SoftButton("進階設定") {
                        showMenu = false
                        showAdvanced = true
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMenu = false }) { Text("關閉") }
            }
        )
    }

    @Composable
    private fun RemarkDialog() {
        var customRemark by mutableStateOf(remark)
        AlertDialog(
            onDismissRequest = { showRemarkDialog = false },
            title = { Text("檔案備注") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("之後拍攝或選取的檔案會放入：${folderNamePreview()}", color = ComposeColor(0xff6f625b), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SoftButton("打拆", Modifier.weight(1f)) { customRemark = "打拆" }
                        SoftButton("水電完成", Modifier.weight(1f)) { customRemark = "水電完成" }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SoftButton("泥水完成", Modifier.weight(1f)) { customRemark = "泥水完成" }
                        SoftButton("清除", Modifier.weight(1f)) { customRemark = "" }
                    }
                    OutlinedTextField(
                        value = customRemark,
                        onValueChange = { customRemark = it },
                        label = { Text("自訂備注") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    applyRemark(customRemark)
                    showRemarkDialog = false
                    message = "檔案備注已設定：${remark.ifBlank { "無" }}"
                }) { Text("套用") }
            },
            dismissButton = {
                TextButton(onClick = { showRemarkDialog = false }) { Text("取消") }
            }
        )
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
    private fun Panel(title: String, icon: IconKind, trailing: String = "", content: @Composable () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = ComposeColor(0xeeffffff)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LineIcon(icon, ComposeColor(0xffa96644), Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = ComposeColor(0xff2b1711), modifier = Modifier.weight(1f))
                    if (trailing.isNotBlank()) {
                        Text(trailing, color = ComposeColor(0xffa56343), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                content()
            }
        }
    }

    @Composable
    private fun DashboardTile(
        icon: IconKind,
        label: String,
        primary: String,
        secondary: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(88.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ComposeColor.White)
                .border(1.dp, ComposeColor(0xffeee4dc), RoundedCornerShape(16.dp))
                .clickable { onClick() }
                .padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(ComposeColor(0xfff6eee8)),
                contentAlignment = Alignment.Center
            ) {
                LineIcon(icon, ComposeColor(0xffb87856), Modifier.size(23.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(label, color = ComposeColor(0xff6f625b), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Text(primary, color = ComposeColor(0xff2b1711), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(secondary, color = ComposeColor(0xff6f625b), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = ComposeColor(0xff6f625b), style = MaterialTheme.typography.headlineSmall)
        }
    }

    @Composable
    private fun QuickAction(icon: IconKind, label: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
        Column(
            modifier = modifier
                .height(78.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(ComposeColor.White)
                .border(1.dp, ComposeColor(0xfff0e7df), RoundedCornerShape(18.dp))
                .clickable { onClick() }
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LineIcon(icon, if (danger) ComposeColor(0xffd44b40) else ComposeColor(0xffa96644), Modifier.size(27.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, color = if (danger) ComposeColor(0xffd44b40) else ComposeColor(0xff3f2b22), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun SoftSquare(icon: IconKind, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(ComposeColor.White)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            LineIcon(icon, ComposeColor(0xff5a4034), Modifier.size(30.dp))
        }
    }

    @Composable
    private fun SearchBox() {
        OutlinedTextField(
            value = siteSearch,
            onValueChange = { siteSearch = it },
            label = { Text("搜尋地盤") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        )
    }

    @Composable
    private fun BottomNavCard() {
        Card(
            colors = CardDefaults.cardColors(containerColor = ComposeColor(0xeeffffff)),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BottomNavItem(IconKind.Home, "首頁", active = true) { screen = Screen.Home }
                BottomNavItem(IconKind.Document, "記錄") { if (drafts.isNotEmpty()) screen = Screen.Review }
                BottomNavItem(IconKind.Image, "相簿") { openGallery() }
                BottomNavItem(IconKind.Settings, "設定") { showAdvanced = true }
            }
        }
    }

    @Composable
    private fun BottomNavItem(icon: IconKind, label: String, active: Boolean = false, onClick: () -> Unit) {
        Column(
            modifier = Modifier.clickable { onClick() },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LineIcon(icon, if (active) ComposeColor(0xffc07652) else ComposeColor(0xff5f5651), Modifier.size(26.dp))
            Text(label, color = if (active) ComposeColor(0xffc07652) else ComposeColor(0xff5f5651), style = MaterialTheme.typography.bodySmall)
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
    private fun SoftButton(label: String, modifier: Modifier = Modifier, icon: IconKind? = null, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xfff1e9df), contentColor = ComposeColor(0xff5a4034)),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (icon != null) {
                LineIcon(icon, ComposeColor(0xff5a4034), Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label)
        }
    }

    @Composable
    private fun LineIcon(kind: IconKind, color: ComposeColor, modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height
            val s = minOf(w, h)
            val sw = (s * 0.085f).coerceAtLeast(2.2f)
            fun p(x: Float, y: Float) = Offset(w * x, h * y)
            fun line(a: Offset, b: Offset) = drawLine(color, a, b, strokeWidth = sw, cap = StrokeCap.Round)
            fun rect(x: Float, y: Float, rw: Float, rh: Float) = drawRoundRect(
                color = color,
                topLeft = Offset(w * x, h * y),
                size = Size(w * rw, h * rh),
                cornerRadius = CornerRadius(s * 0.08f, s * 0.08f),
                style = Stroke(sw)
            )
            when (kind) {
                IconKind.Menu -> {
                    line(p(0.25f, 0.32f), p(0.75f, 0.32f)); line(p(0.25f, 0.50f), p(0.75f, 0.50f)); line(p(0.25f, 0.68f), p(0.75f, 0.68f))
                }
                IconKind.Bell -> {
                    drawArc(color, 205f, 130f, false, topLeft = Offset(w * .24f, h * .2f), size = Size(w * .52f, h * .6f), style = Stroke(sw, cap = StrokeCap.Round))
                    line(p(.25f, .72f), p(.75f, .72f)); line(p(.45f, .83f), p(.55f, .83f))
                    drawCircle(ComposeColor(0xffef5350), s * .08f, p(.78f, .22f))
                }
                IconKind.Cup -> {
                    rect(.22f, .34f, .42f, .3f); drawArc(color, -70f, 210f, false, topLeft = Offset(w * .55f, h * .37f), size = Size(w * .24f, h * .22f), style = Stroke(sw, cap = StrokeCap.Round)); line(p(.22f, .74f), p(.7f, .74f))
                }
                IconKind.Building -> {
                    rect(.28f, .18f, .38f, .66f); line(p(.18f, .84f), p(.78f, .84f))
                    listOf(.38f, .52f).forEach { x -> listOf(.32f, .48f, .64f).forEach { y -> drawCircle(color, sw * .55f, p(x, y)) } }
                }
                IconKind.Folder -> {
                    val path = ComposePath().apply { moveTo(w*.15f,h*.35f); lineTo(w*.38f,h*.35f); lineTo(w*.45f,h*.45f); lineTo(w*.85f,h*.45f); lineTo(w*.85f,h*.78f); lineTo(w*.15f,h*.78f); close() }
                    drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round))
                }
                IconKind.Document -> {
                    rect(.28f, .16f, .44f, .68f); line(p(.4f, .42f), p(.62f, .42f)); line(p(.4f, .55f), p(.62f, .55f)); line(p(.4f, .68f), p(.58f, .68f))
                }
                IconKind.Sync -> {
                    drawArc(color, 35f, 250f, false, topLeft = Offset(w*.18f,h*.18f), size = Size(w*.64f,h*.64f), style = Stroke(sw, cap = StrokeCap.Round))
                    line(p(.72f,.18f), p(.82f,.36f)); line(p(.72f,.18f), p(.55f,.2f))
                    drawCircle(ComposeColor(0xff4d9a68), s*.11f, p(.72f,.68f))
                }
                IconKind.Camera -> {
                    rect(.18f, .32f, .64f, .42f); line(p(.32f,.32f), p(.39f,.22f)); line(p(.39f,.22f), p(.58f,.22f)); line(p(.58f,.22f), p(.65f,.32f)); drawCircle(color, s*.15f, p(.5f,.53f), style = Stroke(sw))
                }
                IconKind.Image -> {
                    rect(.16f,.2f,.68f,.56f); drawCircle(color, s*.06f, p(.66f,.34f)); line(p(.24f,.66f), p(.42f,.48f)); line(p(.42f,.48f), p(.56f,.62f)); line(p(.56f,.62f), p(.72f,.46f))
                }
                IconKind.Drop -> {
                    val path = ComposePath().apply { moveTo(w*.5f,h*.12f); cubicTo(w*.28f,h*.38f,w*.25f,h*.55f,w*.5f,h*.82f); cubicTo(w*.75f,h*.55f,w*.72f,h*.38f,w*.5f,h*.12f) }
                    drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round))
                }
                IconKind.Calendar -> {
                    rect(.18f,.25f,.64f,.58f); line(p(.18f,.42f), p(.82f,.42f)); line(p(.34f,.16f), p(.34f,.32f)); line(p(.66f,.16f), p(.66f,.32f))
                }
                IconKind.Save -> {
                    rect(.22f,.16f,.56f,.68f); rect(.34f,.2f,.28f,.22f); line(p(.34f,.72f), p(.66f,.72f))
                }
                IconKind.Clear -> {
                    line(p(.25f,.25f), p(.75f,.75f)); line(p(.75f,.25f), p(.25f,.75f)); rect(.2f,.2f,.6f,.6f)
                }
                IconKind.Upload -> {
                    line(p(.5f,.72f), p(.5f,.22f)); line(p(.32f,.4f), p(.5f,.22f)); line(p(.68f,.4f), p(.5f,.22f)); line(p(.24f,.78f), p(.76f,.78f))
                }
                IconKind.Filter -> {
                    line(p(.22f,.3f), p(.78f,.3f)); line(p(.34f,.5f), p(.66f,.5f)); line(p(.45f,.7f), p(.55f,.7f))
                }
                IconKind.Home -> {
                    line(p(.2f,.48f), p(.5f,.22f)); line(p(.5f,.22f), p(.8f,.48f)); rect(.3f,.48f,.4f,.32f)
                }
                IconKind.Settings -> {
                    drawCircle(color, s*.25f, p(.5f,.5f), style = Stroke(sw)); drawCircle(color, s*.08f, p(.5f,.5f), style = Stroke(sw))
                    line(p(.5f,.1f), p(.5f,.22f)); line(p(.5f,.78f), p(.5f,.9f)); line(p(.1f,.5f), p(.22f,.5f)); line(p(.78f,.5f), p(.9f,.5f))
                }
                IconKind.Tag -> {
                    val path = ComposePath().apply { moveTo(w*.28f,h*.2f); lineTo(w*.7f,h*.2f); lineTo(w*.82f,h*.32f); lineTo(w*.42f,h*.78f); lineTo(w*.18f,h*.54f); close() }
                    drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round)); drawCircle(color, s*.04f, p(.62f,.33f))
                }
            }
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

    @Composable
    private fun brownButton() = ButtonDefaults.buttonColors(
        containerColor = ComposeColor(0xffc47a54),
        contentColor = ComposeColor.White
    )

    private fun sitePrimary(): String {
        val site = selectedSiteName.ifBlank { return "未選擇" }
        return site.substringBefore(' ').take(14)
    }

    private fun siteSecondary(): String {
        val site = selectedSiteName.ifBlank { return "請先選地盤" }
        return site.substringAfter(' ', "")
            .ifBlank { site.takeLast(12) }
    }

    private fun folderDateLabel(): String {
        return runCatching {
            LocalDate.parse(recordDate).format(ofPattern("ddMMyyyy"))
        }.getOrElse { recordDate.replace("-", "") }
    }

    private fun openCamera() {
        if (!hasRequiredSettings()) return
        saveSettings()
        screen = Screen.Camera
        message = ""
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
            putExtra("android.intent.extra.quickCapture", true)
            putExtra("android.intent.extra.USE_FRONT_CAMERA", false)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        systemCamera.launch(intent)
    }

    private fun openGallery() {
        if (!hasRequiredSettings()) return
        val gallery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        try {
            galleryPicker.launch(gallery)
        } catch (_: ActivityNotFoundException) {
            galleryPicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            })
        }
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
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val manager = WorkManager.getInstance(this)
        batch.forEach { draft ->
            DraftStore.markPending(this, draft.id)
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        "baseUrl" to baseUrl.trimEnd('/'),
                        "draftId" to draft.id
                    )
                )
                .addTag("site-camera-upload")
                .build()
            manager.enqueueUniqueWork("upload-${draft.id}", ExistingWorkPolicy.REPLACE, request)
        }
        refreshDrafts()
        isUploading = false
        uploadStatus = "已排入背景上傳：${batch.size} 個檔案。可返回主畫面或關閉 app，完成後會自動移除暫存。"
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

    private fun applyRemark(value: String) {
        remark = value
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
        val raw = runCatching { JSONObject(body).optString("error") }.getOrNull().takeUnless { it.isNullOrBlank() } ?: body
        return if (raw.contains("PIN", ignoreCase = true)) {
            "後端仍是舊版 Admin API，請在 Render 重新部署最新版本，或檢查 App 的 Render URL 是否填錯。"
        } else {
            raw
        }
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
