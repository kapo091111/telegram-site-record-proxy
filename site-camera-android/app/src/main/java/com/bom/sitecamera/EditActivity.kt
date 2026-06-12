package com.bom.sitecamera

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.EditText
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class EditActivity : Activity() {
    private lateinit var editor: MarkupView
    private lateinit var modeLabel: TextView
    private lateinit var draftId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draftId = intent.getStringExtra("draftId") ?: run {
            finish()
            return
        }
        val draft = DraftStore.find(this, draftId) ?: run {
            finish()
            return
        }
        val bitmap = BitmapFactory.decodeFile(draft.filePath) ?: run {
            finish()
            return
        }

        editor = MarkupView(this, bitmap)
        modeLabel = TextView(this).apply {
            text = "畫筆"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 10, 10, 6)
            addView(button("←") { finish() })
            addView(modeLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("復原") { editor.undo() })
            addView(button("完成") { saveEdited() })
        }
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 12)
            addView(toolButton("筆", EditMode.BRUSH))
            addView(toolButton("線", EditMode.LINE))
            addView(toolButton("箭", EditMode.ARROW))
            addView(toolButton("圈", EditMode.CIRCLE))
            addView(toolButton("字", EditMode.TEXT))
            addView(toolButton("遮", EditMode.MOSAIC))
            addView(toolButton("尺寸", EditMode.DIMENSION))
            addView(button("字左") { editor.alignDimensionLabel(0.18f) })
            addView(button("字中") { editor.alignDimensionLabel(0.5f) })
            addView(button("字右") { editor.alignDimensionLabel(0.82f) })
            addView(toolButton("裁", EditMode.CROP))
            addView(button("旋") { editor.rotate90() })
        }
        val tools = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolRow)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(topRow)
            addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tools)
        }
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            minWidth = 0
            setPadding(8, 4, 8, 4)
            setOnClickListener { action() }
        }
    }

    private fun toolButton(label: String, mode: EditMode): Button {
        return button(label) {
            editor.mode = mode
            modeLabel.text = when (mode) {
                EditMode.BRUSH -> "畫筆"
                EditMode.LINE -> "直線"
                EditMode.ARROW -> "箭嘴"
                EditMode.CIRCLE -> "圈位"
                EditMode.DIMENSION -> "尺寸：拖出兩端，加入後可拖文字"
                EditMode.CROP -> "裁剪"
                EditMode.TEXT -> "文字"
                EditMode.MOSAIC -> "遮蓋"
            }
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

data class Shape(
    val mode: EditMode,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val text: String = "",
    val points: List<Float> = emptyList(),
    val textPosition: Float = 0.5f
)

class MarkupView(context: Activity, source: Bitmap) : View(context) {
    var mode: EditMode = EditMode.BRUSH
    private var bitmap: Bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    private val shapes = mutableListOf<Shape>()
    private var active: Shape? = null
    private var draggingLabelIndex = -1
    private var selectedDimensionIndex = -1
    private var activeScreenX = 0f
    private var activeScreenY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 120, 40)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        textSize = 44f
    }

    fun rotate90() {
        val matrix = Matrix().apply { postRotate(90f) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        shapes.clear()
        invalidate()
    }

    fun undo() {
        if (shapes.isNotEmpty()) {
            shapes.removeAt(shapes.lastIndex)
            selectedDimensionIndex = shapes.indexOfLast { it.mode == EditMode.DIMENSION }
            invalidate()
        }
    }

    fun alignDimensionLabel(position: Float) {
        val index = selectedDimensionIndex.takeIf { it in shapes.indices && shapes[it].mode == EditMode.DIMENSION }
            ?: shapes.indexOfLast { it.mode == EditMode.DIMENSION }
        if (index < 0) return
        selectedDimensionIndex = index
        shapes[index] = shapes[index].copy(textPosition = position.coerceIn(0.12f, 0.88f))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val rect = imageRect()
        canvas.drawBitmap(bitmap, null, rect, null)
        canvas.save()
        canvas.clipRect(rect)
        (shapes + listOfNotNull(active)).forEach { drawShape(canvas, it, rect) }
        canvas.restore()
        if (active != null) {
            drawReticle(canvas)
            if (mode == EditMode.DIMENSION || mode == EditMode.CROP || mode == EditMode.TEXT) drawMagnifier(canvas, rect)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = imageRect()
        val imageX = ((event.x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val imageY = ((event.y - rect.top) / rect.height()).coerceIn(0f, 1f)
        activeScreenX = event.x
        activeScreenY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingLabelIndex = if (mode == EditMode.DIMENSION) hitDimensionLabel(event.x, event.y, rect) else -1
                if (draggingLabelIndex < 0) {
                    active = Shape(mode, imageX, imageY, imageX, imageY, points = listOf(imageX, imageY))
                } else {
                    selectedDimensionIndex = draggingLabelIndex
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingLabelIndex >= 0) {
                    moveDimensionLabel(draggingLabelIndex, imageX, imageY)
                } else {
                    active = active?.let {
                        if (mode == EditMode.BRUSH) it.copy(endX = imageX, endY = imageY, points = it.points + listOf(imageX, imageY))
                        else it.copy(endX = imageX, endY = imageY)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (draggingLabelIndex >= 0) {
                    moveDimensionLabel(draggingLabelIndex, imageX, imageY)
                    draggingLabelIndex = -1
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
            crop(shape)
            return
        }
        if (shape.mode == EditMode.DIMENSION) {
            askDimensionText(shape)
            return
        }
        if (shape.mode == EditMode.TEXT) {
            askText(shape)
            return
        }
        if (shape.mode == EditMode.BRUSH && shape.points.size < 4) return
        shapes.add(shape)
    }

    private fun askDimensionText(shape: Shape) {
        val input = EditText(context).apply {
            hint = "輸入尺寸，例如 850mm"
        }
        var position = 0.5f
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(dialogButton("字靠左") { position = 0.18f })
                addView(dialogButton("字置中") { position = 0.5f })
                addView(dialogButton("字靠右") { position = 0.82f })
            })
        }
        AlertDialog.Builder(context)
            .setTitle("尺寸標註")
            .setView(panel)
            .setPositiveButton("加入") { _, _ ->
                shapes.add(shape.copy(text = input.text.toString().trim(), textPosition = position))
                selectedDimensionIndex = shapes.lastIndex
                invalidate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dialogButton(label: String, action: () -> Unit): Button {
        return Button(context).apply {
            text = label
            textSize = 12f
            setOnClickListener { action() }
        }
    }

    private fun askText(shape: Shape) {
        val input = EditText(context).apply {
            hint = "輸入文字，例如 未完成"
        }
        AlertDialog.Builder(context)
            .setTitle("加入文字")
            .setView(input)
            .setPositiveButton("加入") { _, _ ->
                shapes.add(shape.copy(text = input.text.toString().trim()))
                invalidate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun crop(shape: Shape) {
        val left = (min(shape.startX, shape.endX) * bitmap.width).toInt()
        val top = (min(shape.startY, shape.endY) * bitmap.height).toInt()
        val right = (max(shape.startX, shape.endX) * bitmap.width).toInt()
        val bottom = (max(shape.startY, shape.endY) * bitmap.height).toInt()
        val width = right - left
        val height = bottom - top
        if (width >= 80 && height >= 80 && left >= 0 && top >= 0 && right <= bitmap.width && bottom <= bitmap.height) {
            bitmap = Bitmap.createBitmap(bitmap, left, top, width, height)
            shapes.clear()
            invalidate()
        }
    }

    fun exportBitmap(): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val rect = RectF(0f, 0f, result.width.toFloat(), result.height.toFloat())
        shapes.forEach { drawShape(canvas, it, rect) }
        return result
    }

    private fun imageRect(): RectF {
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

    private fun drawShape(canvas: Canvas, shape: Shape, rect: RectF) {
        val sx = rect.left + shape.startX * rect.width()
        val sy = rect.top + shape.startY * rect.height()
        val ex = rect.left + shape.endX * rect.width()
        val ey = rect.top + shape.endY * rect.height()
        when (shape.mode) {
            EditMode.BRUSH -> drawBrush(canvas, shape, rect)
            EditMode.LINE -> canvas.drawLine(sx, sy, ex, ey, paint)
            EditMode.ARROW -> drawArrow(canvas, sx, sy, ex, ey)
            EditMode.CIRCLE -> canvas.drawOval(RectF(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey)), paint)
            EditMode.DIMENSION -> {
                canvas.drawLine(sx, sy, ex, ey, paint)
                drawEndpoint(canvas, sx, sy)
                drawEndpoint(canvas, ex, ey)
                val label = shape.text.ifBlank { "尺寸" }
                val textPaint = Paint(paint).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                    strokeWidth = 1f
                }
                val labelX = sx + (ex - sx) * shape.textPosition
                val labelY = sy + (ey - sy) * shape.textPosition
                val textWidth = textPaint.measureText(label)
                val pad = 14f
                val textBounds = RectF(labelX - textWidth / 2f - pad, labelY - 62f, labelX + textWidth / 2f + pad, labelY - 8f)
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.argb(180, 0, 0, 0)
                }
                canvas.drawRoundRect(textBounds, 14f, 14f, bg)
                canvas.drawText(label, labelX - textWidth / 2f, labelY - 22f, textPaint)
            }
            EditMode.CROP -> canvas.drawRect(RectF(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey)), paint)
            EditMode.TEXT -> {
                val textPaint = Paint(paint).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                    strokeWidth = 1f
                    textSize = 54f
                }
                canvas.drawText(shape.text.ifBlank { "文字" }, sx, sy, textPaint)
            }
            EditMode.MOSAIC -> {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.rgb(28, 34, 31)
                    alpha = 220
                }
                canvas.drawRect(RectF(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey)), fill)
            }
        }
    }

    private fun hitDimensionLabel(x: Float, y: Float, rect: RectF): Int {
        for (index in shapes.indices.reversed()) {
            val shape = shapes[index]
            if (shape.mode != EditMode.DIMENSION) continue
            val sx = rect.left + shape.startX * rect.width()
            val sy = rect.top + shape.startY * rect.height()
            val ex = rect.left + shape.endX * rect.width()
            val ey = rect.top + shape.endY * rect.height()
            val label = shape.text.ifBlank { "尺寸" }
            val textPaint = Paint(paint).apply {
                style = Paint.Style.FILL
                textSize = paint.textSize
            }
            val labelX = sx + (ex - sx) * shape.textPosition
            val labelY = sy + (ey - sy) * shape.textPosition - 35f
            val halfWidth = max(70f, textPaint.measureText(label) / 2f + 34f)
            if (x in (labelX - halfWidth)..(labelX + halfWidth) && y in (labelY - 42f)..(labelY + 28f)) return index
        }
        return -1
    }

    private fun moveDimensionLabel(index: Int, imageX: Float, imageY: Float) {
        val shape = shapes.getOrNull(index) ?: return
        val dx = shape.endX - shape.startX
        val dy = shape.endY - shape.startY
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0001f) return
        val projected = (((imageX - shape.startX) * dx + (imageY - shape.startY) * dy) / lengthSquared).coerceIn(0.12f, 0.88f)
        shapes[index] = shape.copy(textPosition = projected)
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
        canvas.drawCircle(x, y, 13f, fill)
        canvas.drawCircle(x, y, 13f, paint)
        canvas.drawLine(x - 24f, y, x + 24f, y, paint)
        canvas.drawLine(x, y - 24f, x, y + 24f, paint)
    }

    private fun drawMagnifier(canvas: Canvas, rect: RectF) {
        val shape = active ?: return
        val radius = 92f
        val cx = activeScreenX.coerceIn(radius + 10f, width - radius - 10f)
        val cy = (activeScreenY - 150f).coerceIn(radius + 10f, height - radius - 10f)
        val imagePx = (shape.endX * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val imagePy = (shape.endY * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
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
}
