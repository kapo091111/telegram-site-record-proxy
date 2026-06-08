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
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class EditActivity : Activity() {
    private lateinit var editor: MarkupView
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
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            addView(button("裁剪") { editor.mode = EditMode.CROP })
            addView(button("旋轉") { editor.rotate90() })
            addView(button("畫筆") { editor.mode = EditMode.LINE })
            addView(button("箭嘴") { editor.mode = EditMode.ARROW })
            addView(button("圈位") { editor.mode = EditMode.CIRCLE })
            addView(button("文字") { editor.mode = EditMode.TEXT })
            addView(button("遮蓋") { editor.mode = EditMode.MOSAIC })
            addView(button("尺寸") { editor.mode = EditMode.DIMENSION })
            addView(button("復原") { editor.undo() })
            addView(button("保存") { saveEdited() })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(controls)
        }
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setOnClickListener { action() }
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
    val text: String = ""
)

class MarkupView(context: Activity, source: Bitmap) : View(context) {
    var mode: EditMode = EditMode.LINE
    private var bitmap: Bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    private val shapes = mutableListOf<Shape>()
    private var active: Shape? = null
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
            invalidate()
        }
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
        if (active != null && mode == EditMode.DIMENSION) drawMagnifier(canvas, rect)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = imageRect()
        val imageX = ((event.x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val imageY = ((event.y - rect.top) / rect.height()).coerceIn(0f, 1f)
        activeScreenX = event.x
        activeScreenY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> active = Shape(mode, imageX, imageY, imageX, imageY)
            MotionEvent.ACTION_MOVE -> active = active?.copy(endX = imageX, endY = imageY)
            MotionEvent.ACTION_UP -> {
                val finished = active?.copy(endX = imageX, endY = imageY)
                active = null
                if (finished != null) handleFinished(finished)
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
        shapes.add(shape)
    }

    private fun askDimensionText(shape: Shape) {
        val input = android.widget.EditText(context).apply {
            hint = "輸入尺寸，例如 850mm"
        }
        AlertDialog.Builder(context)
            .setTitle("尺寸標註")
            .setView(input)
            .setPositiveButton("加入") { _, _ ->
                shapes.add(shape.copy(text = input.text.toString().trim()))
                invalidate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askText(shape: Shape) {
        val input = android.widget.EditText(context).apply {
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
        val width = (right - left).coerceAtLeast(50)
        val height = (bottom - top).coerceAtLeast(50)
        if (left + width <= bitmap.width && top + height <= bitmap.height) {
            bitmap = Bitmap.createBitmap(bitmap, left, top, width, height)
            shapes.clear()
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
            EditMode.LINE -> canvas.drawLine(sx, sy, ex, ey, paint)
            EditMode.ARROW -> drawArrow(canvas, sx, sy, ex, ey)
            EditMode.CIRCLE -> canvas.drawOval(RectF(min(sx, ex), min(sy, ey), max(sx, ex), max(sy, ey)), paint)
            EditMode.DIMENSION -> {
                canvas.drawLine(sx, sy, ex, ey, paint)
                drawEndpoint(canvas, sx, sy)
                drawEndpoint(canvas, ex, ey)
                val path = Path().apply {
                    moveTo(sx, sy)
                    lineTo(ex, ey)
                }
                val label = shape.text.ifBlank { "尺寸" }
                val textPaint = Paint(paint).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                    strokeWidth = 1f
                }
                canvas.drawTextOnPath(label, path, 20f, -18f, textPaint)
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
}
