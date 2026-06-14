package com.bom.sitecamera

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class EditActivity : Activity() {
    private lateinit var editor: MarkupView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var draftId: String
    private val modeButtons = mutableListOf<EditorIconButton>()
    private var activeInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draftId = intent.getStringExtra("draftId") ?: return finish()
        val draft = DraftStore.find(this, draftId) ?: return finish()
        val bitmap = BitmapFactory.decodeFile(draft.filePath) ?: return finish()

        editor = MarkupView(
            context = this,
            source = bitmap,
            onStateChanged = { updateControls() },
            textProvider = { mode -> currentInlineText(mode) }
        )
        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.argb(178, 0, 0, 0))
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(editor, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(topBar(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP))
        root.addView(bottomPanel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        updateControls()
    }

    private fun topBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.argb(155, 0, 0, 0))
            addView(editorIconButton(EditIconKind.Close) { finish() }, LinearLayout.LayoutParams(dp(48), dp(44)))
            addView(TextView(context).apply {
                text = "編輯"
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(editorIconButton(EditIconKind.Undo) { editor.undo() }, LinearLayout.LayoutParams(dp(48), dp(44)))
            addView(doneButton(), LinearLayout.LayoutParams(dp(74), dp(44)))
        }
    }

    private fun updateControls() {
        bottomPanel.removeAllViews()
        modeButtons.clear()
        activeInput = null

        if (editor.mode == EditMode.TEXT || editor.mode == EditMode.DIMENSION) {
            bottomPanel.addView(inlineInput(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            bottomPanel.addView(spacer(6))
        }

        if (editor.mode == EditMode.CROP && editor.hasPendingCrop()) {
            bottomPanel.addView(cropActionRow(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            bottomPanel.addView(spacer(6))
        }

        if (editor.hasSelectedDimension()) {
            bottomPanel.addView(dimensionAlignRow(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
            bottomPanel.addView(spacer(6))
        }

        bottomPanel.addView(colorRow(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        bottomPanel.addView(spacer(8))
        bottomPanel.addView(toolRow(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        updateModeButtons()
    }

    private fun inlineInput(): EditText {
        return EditText(this).apply {
            activeInput = this
            hint = if (editor.mode == EditMode.DIMENSION) "例如 850mm" else "輸入文字"
            setSingleLine(true)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(180, 255, 255, 255))
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundColor(Color.argb(105, 255, 255, 255))
        }
    }

    private fun colorRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            listOf(0xffff7a2a.toInt(), Color.WHITE, 0xff25d366.toInt(), 0xff4aa3ff.toInt(), 0xffff4d6d.toInt(), 0xffffdf45.toInt()).forEach { color ->
                addView(colorButton(color), LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(8) })
            }
            addView(TextView(context).apply {
                text = modeHint()
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun toolRow(): View {
        val tools = listOf(
            EditIconKind.Brush to EditMode.BRUSH,
            EditIconKind.Text to EditMode.TEXT,
            EditIconKind.Arrow to EditMode.ARROW,
            EditIconKind.Circle to EditMode.CIRCLE,
            EditIconKind.Mosaic to EditMode.MOSAIC,
            EditIconKind.Dimension to EditMode.DIMENSION,
            EditIconKind.Crop to EditMode.CROP,
            EditIconKind.Line to EditMode.LINE
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tools.forEach { (icon, mode) ->
                val button = editorIconButton(icon) {
                    editor.mode = mode
                    editor.cancelPendingCrop()
                    updateControls()
                }
                button.tag = mode
                modeButtons.add(button)
                addView(button, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(8) })
            }
            addView(editorIconButton(EditIconKind.Rotate) { editor.rotate90() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun cropActionRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(smallButton("取消裁剪") { editor.cancelPendingCrop() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(smallButton("完成裁剪") { editor.confirmCrop() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun dimensionAlignRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(smallButton("字左") { editor.alignSelectedDimension(0.18f) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(smallButton("字中") { editor.alignSelectedDimension(0.5f) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(smallButton("字右") { editor.alignSelectedDimension(0.82f) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun modeHint(): String {
        return when (editor.mode) {
            EditMode.TEXT -> "點相片放文字，可再拖動位置"
            EditMode.DIMENSION -> "拖兩端做尺寸，文字可再拖"
            EditMode.CROP -> if (editor.hasPendingCrop()) "確認後才裁剪" else "拖出裁剪範圍"
            EditMode.MOSAIC -> "拖出遮蓋範圍"
            else -> "選工具後直接在相片上畫"
        }
    }

    private fun doneButton(): Button {
        return iconButton("完成") { saveEdited() }.apply {
            textSize = 15f
            setTextColor(Color.rgb(37, 211, 102))
        }
    }

    private fun editorIconButton(icon: EditIconKind, action: () -> Unit): EditorIconButton {
        return EditorIconButton(this, icon).apply { setOnClickListener { action() } }
    }

    private fun iconButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { action() }
        }
    }

    private fun smallButton(label: String, action: () -> Unit): Button {
        return iconButton(label, action).apply {
            textSize = 13f
            setBackgroundColor(Color.argb(85, 255, 255, 255))
        }
    }

    private fun colorButton(color: Int): Button {
        return Button(this).apply {
            text = ""
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            setBackgroundColor(color)
            setOnClickListener { editor.setMarkupColor(color) }
        }
    }

    private fun spacer(height: Int): View {
        return View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    }

    private fun currentInlineText(mode: EditMode): String {
        val fallback = if (mode == EditMode.DIMENSION) "尺寸" else "文字"
        return activeInput?.text?.toString()?.trim().orEmpty().ifBlank { fallback }
    }

    private fun updateModeButtons() {
        modeButtons.forEach { button ->
            button.active = button.tag == editor.mode
        }
    }

    private fun saveEdited() {
        val output = File(DraftStore.draftsDir(this), "edited_${System.currentTimeMillis()}.jpg")
        output.outputStream().use { stream ->
            editor.exportBitmap().compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        DraftStore.updateFile(this, draftId, output)
        setResult(RESULT_OK)
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

enum class EditMode {
    BRUSH,
    LINE,
    ARROW,
    CIRCLE,
    DIMENSION,
    CROP,
    TEXT,
    MOSAIC
}

private enum class EditIconKind {
    Close,
    Undo,
    Brush,
    Line,
    Arrow,
    Circle,
    Text,
    Mosaic,
    Dimension,
    Crop,
    Rotate
}

private class EditorIconButton(
    context: Activity,
    private val icon: EditIconKind
) : View(context) {
    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val accent = Color.rgb(37, 211, 102)
        fill.color = if (active) Color.argb(225, 255, 255, 255) else Color.argb(100, 0, 0, 0)
        canvas.drawRoundRect(RectF(3f, 3f, w - 3f, h - 3f), 18f, 18f, fill)
        stroke.color = if (active) Color.BLACK else Color.WHITE
        textPaint.color = if (active) Color.BLACK else Color.WHITE
        when (icon) {
            EditIconKind.Close -> {
                canvas.drawLine(w * 0.34f, h * 0.34f, w * 0.66f, h * 0.66f, stroke)
                canvas.drawLine(w * 0.66f, h * 0.34f, w * 0.34f, h * 0.66f, stroke)
            }
            EditIconKind.Undo -> {
                val path = Path().apply {
                    moveTo(w * 0.64f, h * 0.34f)
                    cubicTo(w * 0.34f, h * 0.28f, w * 0.28f, h * 0.50f, w * 0.44f, h * 0.64f)
                    cubicTo(w * 0.55f, h * 0.74f, w * 0.72f, h * 0.68f, w * 0.74f, h * 0.54f)
                }
                canvas.drawPath(path, stroke)
                canvas.drawLine(w * 0.36f, h * 0.30f, w * 0.50f, h * 0.22f, stroke)
                canvas.drawLine(w * 0.36f, h * 0.30f, w * 0.50f, h * 0.40f, stroke)
            }
            EditIconKind.Brush -> {
                canvas.drawLine(w * 0.32f, h * 0.68f, w * 0.68f, h * 0.30f, stroke)
                canvas.drawLine(w * 0.28f, h * 0.74f, w * 0.40f, h * 0.62f, stroke)
            }
            EditIconKind.Line -> canvas.drawLine(w * 0.28f, h * 0.68f, w * 0.72f, h * 0.32f, stroke)
            EditIconKind.Arrow -> {
                canvas.drawLine(w * 0.28f, h * 0.68f, w * 0.72f, h * 0.32f, stroke)
                canvas.drawLine(w * 0.72f, h * 0.32f, w * 0.66f, h * 0.52f, stroke)
                canvas.drawLine(w * 0.72f, h * 0.32f, w * 0.52f, h * 0.38f, stroke)
            }
            EditIconKind.Circle -> canvas.drawOval(RectF(w * 0.25f, h * 0.24f, w * 0.75f, h * 0.76f), stroke)
            EditIconKind.Text -> {
                textPaint.textSize = h * 0.46f
                canvas.drawText("T", w * 0.50f, h * 0.64f, textPaint)
            }
            EditIconKind.Mosaic -> {
                val size = w * 0.15f
                for (row in 0..2) {
                    for (col in 0..2) {
                        fill.color = if ((row + col) % 2 == 0) stroke.color else Color.argb(165, Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color))
                        canvas.drawRect(w * 0.31f + col * size, h * 0.30f + row * size, w * 0.31f + (col + 1) * size - 2f, h * 0.30f + (row + 1) * size - 2f, fill)
                    }
                }
            }
            EditIconKind.Dimension -> {
                canvas.drawLine(w * 0.26f, h * 0.50f, w * 0.74f, h * 0.50f, stroke)
                canvas.drawLine(w * 0.26f, h * 0.36f, w * 0.26f, h * 0.64f, stroke)
                canvas.drawLine(w * 0.74f, h * 0.36f, w * 0.74f, h * 0.64f, stroke)
            }
            EditIconKind.Crop -> {
                canvas.drawLine(w * 0.34f, h * 0.20f, w * 0.34f, h * 0.66f, stroke)
                canvas.drawLine(w * 0.34f, h * 0.66f, w * 0.80f, h * 0.66f, stroke)
                canvas.drawLine(w * 0.20f, h * 0.34f, w * 0.66f, h * 0.34f, stroke)
                canvas.drawLine(w * 0.66f, h * 0.34f, w * 0.66f, h * 0.80f, stroke)
            }
            EditIconKind.Rotate -> {
                stroke.color = accent
                val oval = RectF(w * 0.28f, h * 0.24f, w * 0.74f, h * 0.72f)
                canvas.drawArc(oval, 35f, 285f, false, stroke)
                canvas.drawLine(w * 0.72f, h * 0.28f, w * 0.72f, h * 0.48f, stroke)
                canvas.drawLine(w * 0.72f, h * 0.28f, w * 0.52f, h * 0.32f, stroke)
            }
        }
    }
}

data class Shape(
    val mode: EditMode,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val text: String = "",
    val points: List<Float> = emptyList(),
    val textPosition: Float = 0.5f,
    val labelX: Float? = null,
    val labelY: Float? = null,
    val color: Int = Color.rgb(255, 122, 42)
)

private enum class DragKind {
    Start,
    End,
    Label
}

private data class DragTarget(val index: Int, val kind: DragKind)

class MarkupView(
    context: Activity,
    source: Bitmap,
    private val onStateChanged: () -> Unit = {},
    private val textProvider: (EditMode) -> String = { if (it == EditMode.DIMENSION) "尺寸" else "文字" }
) : View(context) {
    var mode: EditMode = EditMode.BRUSH
    private var bitmap: Bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    private val shapes = mutableListOf<Shape>()
    private var active: Shape? = null
    private var pendingCrop: Shape? = null
    private var dragTarget: DragTarget? = null
    private var selectedIndex: Int? = null
    private var activeScreenX = 0f
    private var activeScreenY = 0f
    private var markupColor = Color.rgb(255, 122, 42)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        textSize = 46f
    }

    fun setMarkupColor(color: Int) {
        markupColor = color
        invalidate()
    }

    fun rotate90() {
        val matrix = Matrix().apply { postRotate(90f) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        shapes.clear()
        pendingCrop = null
        selectedIndex = null
        invalidate()
        onStateChanged()
    }

    fun undo() {
        when {
            pendingCrop != null -> pendingCrop = null
            shapes.isNotEmpty() -> shapes.removeAt(shapes.lastIndex)
        }
        selectedIndex = null
        invalidate()
        onStateChanged()
    }

    fun hasPendingCrop(): Boolean = pendingCrop != null

    fun cancelPendingCrop() {
        if (pendingCrop != null) {
            pendingCrop = null
            invalidate()
            onStateChanged()
        }
    }

    fun hasSelectedDimension(): Boolean {
        val index = selectedIndex ?: return false
        return index in shapes.indices && shapes[index].mode == EditMode.DIMENSION
    }

    fun alignSelectedDimension(position: Float) {
        val index = selectedIndex ?: shapes.indexOfLast { it.mode == EditMode.DIMENSION }
        if (index in shapes.indices && shapes[index].mode == EditMode.DIMENSION) {
            shapes[index] = shapes[index].copy(textPosition = position, labelX = null, labelY = null)
            selectedIndex = index
            invalidate()
            onStateChanged()
        }
    }

    fun confirmCrop() {
        val shape = pendingCrop ?: return
        crop(shape)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val rect = imageRect()
        canvas.drawBitmap(bitmap, null, rect, null)
        canvas.save()
        canvas.clipRect(rect)
        (shapes + listOfNotNull(pendingCrop, active)).forEachIndexed { index, shape ->
            drawShape(canvas, shape, rect, index == selectedIndex || shape == pendingCrop)
        }
        canvas.restore()
        if (active != null || dragTarget != null) {
            drawReticle(canvas)
            if (mode == EditMode.DIMENSION || mode == EditMode.CROP || mode == EditMode.TEXT || dragTarget != null) {
                drawMagnifier(canvas)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = imageRect()
        if (rect.width() <= 0f || rect.height() <= 0f) return true
        val imageX = ((event.x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val imageY = ((event.y - rect.top) / rect.height()).coerceIn(0f, 1f)
        activeScreenX = event.x
        activeScreenY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragTarget = hitExisting(event.x, event.y, rect)
                if (dragTarget == null) {
                    active = Shape(mode, imageX, imageY, imageX, imageY, points = listOf(imageX, imageY), color = markupColor)
                    selectedIndex = null
                    if (mode == EditMode.CROP) pendingCrop = null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val target = dragTarget
                if (target != null) {
                    moveExisting(target, imageX, imageY)
                } else {
                    active = active?.let {
                        if (mode == EditMode.BRUSH) {
                            it.copy(endX = imageX, endY = imageY, points = it.points + listOf(imageX, imageY))
                        } else {
                            it.copy(endX = imageX, endY = imageY)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragTarget != null) {
                    dragTarget = null
                    onStateChanged()
                } else {
                    val finished = active?.copy(endX = imageX, endY = imageY)
                    active = null
                    if (finished != null) handleFinished(finished)
                }
            }
        }
        invalidate()
        return true
    }

    private fun handleFinished(shape: Shape) {
        if (shape.mode == EditMode.CROP) {
            if (isMeaningful(shape)) {
                pendingCrop = shape
                onStateChanged()
            }
            return
        }
        if (shape.mode == EditMode.TEXT) {
            shapes.add(shape.copy(text = textProvider(EditMode.TEXT), labelX = shape.startX, labelY = shape.startY))
            selectedIndex = shapes.lastIndex
            onStateChanged()
            return
        }
        if (shape.mode != EditMode.BRUSH && !isMeaningful(shape)) return
        if (shape.mode == EditMode.BRUSH && shape.points.size < 4) return
        val finalShape = if (shape.mode == EditMode.DIMENSION) {
            shape.copy(text = textProvider(EditMode.DIMENSION))
        } else {
            shape
        }
        shapes.add(finalShape)
        selectedIndex = shapes.lastIndex
        onStateChanged()
    }

    private fun isMeaningful(shape: Shape): Boolean {
        return hypot((shape.endX - shape.startX).toDouble(), (shape.endY - shape.startY).toDouble()) > 0.018
    }

    private fun hitExisting(screenX: Float, screenY: Float, rect: RectF): DragTarget? {
        for (index in shapes.indices.reversed()) {
            val shape = shapes[index]
            val sx = rect.left + shape.startX * rect.width()
            val sy = rect.top + shape.startY * rect.height()
            val ex = rect.left + shape.endX * rect.width()
            val ey = rect.top + shape.endY * rect.height()
            if (shape.mode == EditMode.DIMENSION) {
                val label = dimensionLabelPoint(shape, rect)
                if (distance(screenX, screenY, label.first, label.second) < 58f) return DragTarget(index, DragKind.Label)
                if (distance(screenX, screenY, sx, sy) < 46f) return DragTarget(index, DragKind.Start)
                if (distance(screenX, screenY, ex, ey) < 46f) return DragTarget(index, DragKind.End)
            }
            if (shape.mode == EditMode.TEXT) {
                val labelX = rect.left + (shape.labelX ?: shape.startX) * rect.width()
                val labelY = rect.top + (shape.labelY ?: shape.startY) * rect.height()
                if (distance(screenX, screenY, labelX, labelY) < 76f) return DragTarget(index, DragKind.Label)
            }
        }
        return null
    }

    private fun moveExisting(target: DragTarget, x: Float, y: Float) {
        if (target.index !in shapes.indices) return
        val shape = shapes[target.index]
        shapes[target.index] = when (target.kind) {
            DragKind.Start -> shape.copy(startX = x, startY = y)
            DragKind.End -> shape.copy(endX = x, endY = y)
            DragKind.Label -> shape.copy(labelX = x, labelY = y)
        }
        selectedIndex = target.index
    }

    fun exportBitmap(): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val rect = RectF(0f, 0f, result.width.toFloat(), result.height.toFloat())
        shapes.forEach { drawShape(canvas, it, rect, false) }
        return result
    }

    private fun imageRect(): RectF {
        if (width == 0 || height == 0) return RectF()
        val viewRatio = width.toFloat() / height.toFloat()
        val imageRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        return if (imageRatio > viewRatio) {
            val imageHeight = width / imageRatio
            RectF(0f, (height - imageHeight) / 2f, width.toFloat(), (height + imageHeight) / 2f)
        } else {
            val imageWidth = height * imageRatio
            RectF((width - imageWidth) / 2f, 0f, (width + imageWidth) / 2f, height.toFloat())
        }
    }

    private fun drawShape(canvas: Canvas, shape: Shape, rect: RectF, selected: Boolean) {
        val sx = rect.left + shape.startX * rect.width()
        val sy = rect.top + shape.startY * rect.height()
        val ex = rect.left + shape.endX * rect.width()
        val ey = rect.top + shape.endY * rect.height()
        paint.color = shape.color
        when (shape.mode) {
            EditMode.BRUSH -> drawBrush(canvas, shape, rect)
            EditMode.LINE -> canvas.drawLine(sx, sy, ex, ey, paint)
            EditMode.ARROW -> drawArrow(canvas, sx, sy, ex, ey)
            EditMode.CIRCLE -> canvas.drawOval(RectF(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey)), paint)
            EditMode.DIMENSION -> drawDimension(canvas, shape, rect, selected)
            EditMode.CROP -> {
                val cropPaint = Paint(paint).apply {
                    color = Color.WHITE
                    strokeWidth = 6f
                }
                canvas.drawRect(RectF(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey)), cropPaint)
            }
            EditMode.TEXT -> drawTextShape(canvas, shape, rect, selected)
            EditMode.MOSAIC -> {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.rgb(28, 34, 31)
                    alpha = 230
                }
                canvas.drawRect(RectF(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey)), fill)
            }
        }
    }

    private fun drawDimension(canvas: Canvas, shape: Shape, rect: RectF, selected: Boolean) {
        val sx = rect.left + shape.startX * rect.width()
        val sy = rect.top + shape.startY * rect.height()
        val ex = rect.left + shape.endX * rect.width()
        val ey = rect.top + shape.endY * rect.height()
        paint.color = shape.color
        canvas.drawLine(sx, sy, ex, ey, paint)
        drawEndpoint(canvas, sx, sy)
        drawEndpoint(canvas, ex, ey)
        val label = shape.text.ifBlank { "尺寸" }
        val labelPoint = dimensionLabelPoint(shape, rect)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            textSize = 50f
            strokeWidth = 1f
        }
        val textWidth = textPaint.measureText(label)
        val bg = RectF(labelPoint.first - textWidth / 2f - 18f, labelPoint.second - 58f, labelPoint.first + textWidth / 2f + 18f, labelPoint.second + 10f)
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(if (selected) 220 else 155, 0, 0, 0)
            canvas.drawRoundRect(bg, 18f, 18f, this)
        }
        canvas.drawText(label, labelPoint.first - textWidth / 2f, labelPoint.second - 14f, textPaint)
    }

    private fun drawTextShape(canvas: Canvas, shape: Shape, rect: RectF, selected: Boolean) {
        val x = rect.left + (shape.labelX ?: shape.startX) * rect.width()
        val y = rect.top + (shape.labelY ?: shape.startY) * rect.height()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            textSize = 58f
            strokeWidth = 1f
        }
        if (selected) {
            val width = textPaint.measureText(shape.text.ifBlank { "文字" })
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(125, 0, 0, 0)
            }
            canvas.drawRoundRect(RectF(x - 14f, y - 58f, x + width + 14f, y + 12f), 16f, 16f, bg)
        }
        canvas.drawText(shape.text.ifBlank { "文字" }, x, y, textPaint)
    }

    private fun drawBrush(canvas: Canvas, shape: Shape, rect: RectF) {
        if (shape.points.size < 4) return
        val path = Path()
        path.moveTo(rect.left + shape.points[0] * rect.width(), rect.top + shape.points[1] * rect.height())
        var index = 2
        while (index + 1 < shape.points.size) {
            path.lineTo(rect.left + shape.points[index] * rect.width(), rect.top + shape.points[index + 1] * rect.height())
            index += 2
        }
        canvas.drawPath(path, paint)
    }

    private fun drawArrow(canvas: Canvas, sx: Float, sy: Float, ex: Float, ey: Float) {
        canvas.drawLine(sx, sy, ex, ey, paint)
        val angle = atan2((ey - sy).toDouble(), (ex - sx).toDouble())
        val head = 46.0
        val left = angle + Math.PI * 0.82
        val right = angle - Math.PI * 0.82
        canvas.drawLine(ex, ey, (ex + cos(left) * head).toFloat(), (ey + sin(left) * head).toFloat(), paint)
        canvas.drawLine(ex, ey, (ex + cos(right) * head).toFloat(), (ey + sin(right) * head).toFloat(), paint)
    }

    private fun drawEndpoint(canvas: Canvas, x: Float, y: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawCircle(x, y, 15f, fill)
        canvas.drawCircle(x, y, 15f, paint)
        canvas.drawLine(x - 28f, y, x + 28f, y, paint)
        canvas.drawLine(x, y - 28f, x, y + 28f, paint)
    }

    private fun drawMagnifier(canvas: Canvas) {
        val radius = 88f
        val cx = activeScreenX.coerceIn(radius + 12f, width - radius - 12f)
        val cy = (activeScreenY - 150f).coerceIn(radius + 12f, height - radius - 12f)
        val rect = imageRect()
        val imagePx = (((activeScreenX - rect.left) / rect.width()).coerceIn(0f, 1f) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val imagePy = (((activeScreenY - rect.top) / rect.height()).coerceIn(0f, 1f) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val sample = min(bitmap.width, bitmap.height) / 7
        val src = Rect(
            (imagePx - sample).coerceAtLeast(0),
            (imagePy - sample).coerceAtLeast(0),
            (imagePx + sample).coerceAtMost(bitmap.width),
            (imagePy + sample).coerceAtMost(bitmap.height)
        )
        val dst = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val clip = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, src, dst, null)
        canvas.restore()
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.WHITE
        }
        paint.color = markupColor
        canvas.drawCircle(cx, cy, radius, border)
        canvas.drawLine(cx - 28f, cy, cx + 28f, cy, paint)
        canvas.drawLine(cx, cy - 28f, cx, cy + 28f, paint)
    }

    private fun drawReticle(canvas: Canvas) {
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
            alpha = 230
        }
        canvas.drawCircle(activeScreenX, activeScreenY, 22f, guide)
        canvas.drawLine(activeScreenX - 34f, activeScreenY, activeScreenX + 34f, activeScreenY, guide)
        canvas.drawLine(activeScreenX, activeScreenY - 34f, activeScreenX, activeScreenY + 34f, guide)
    }

    private fun dimensionLabelPoint(shape: Shape, rect: RectF): Pair<Float, Float> {
        val x = shape.labelX ?: (shape.startX + (shape.endX - shape.startX) * shape.textPosition)
        val y = shape.labelY ?: (shape.startY + (shape.endY - shape.startY) * shape.textPosition)
        return Pair(rect.left + x * rect.width(), rect.top + y * rect.height())
    }

    private fun crop(shape: Shape) {
        val left = (min(shape.startX, shape.endX) * bitmap.width).toInt()
        val top = (min(shape.startY, shape.endY) * bitmap.height).toInt()
        val right = (max(shape.startX, shape.endX) * bitmap.width).toInt()
        val bottom = (max(shape.startY, shape.endY) * bitmap.height).toInt()
        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth >= 80 && cropHeight >= 80 && left >= 0 && top >= 0 && right <= bitmap.width && bottom <= bitmap.height) {
            bitmap = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
            shapes.clear()
            pendingCrop = null
            selectedIndex = null
            mode = EditMode.BRUSH
            invalidate()
            onStateChanged()
        }
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        return hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()
    }
}
