package eu.siacs.conversations.medialib.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import eu.siacs.conversations.R
import eu.siacs.conversations.medialib.models.PaintOptions

class EditorDrawCanvas(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private var mCurX = 0f
    private var mCurY = 0f
    private var mStartX = 0f
    private var mStartY = 0f
    private var mColor = 0
    private var mWasMultitouch = false

    private var mPaths = LinkedHashMap<Path, PaintOptions>()
    private var mPaint = Paint()
    private var mPath = Path()
    private var mPaintOptions = PaintOptions()

    private var backgroundBitmap: Bitmap? = null

    // Velo 0.22: text and emoji stickers, dragged with one finger and sized with two; drawn
    // into the picture, so they travel encrypted with it
    private class Sticker(val text: String, val color: Int, var x: Float, var y: Float, var size: Float)
    private val mStickers = ArrayList<Sticker>()
    private val mHistory = ArrayList<Any>()
    private var mActiveSticker: Sticker? = null
    private var mGrabDx = 0f
    private var mGrabDy = 0f
    private var mPinchStart = 0f
    private var mPinchSize = 0f
    // No shadow layer: when the picture is saved it is drawn on a software canvas, where colour
    // emoji with a shadow come out transparent (0.22.1)
    private val mTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        mColor = context.getColor(R.color.editor_draw_default_color)
        mPaint.apply {
            color = mColor
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 40f
            isAntiAlias = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()

        if (backgroundBitmap != null) {
            canvas.drawBitmap(backgroundBitmap!!, 0f, 0f, null)
        }

        for ((key, value) in mPaths) {
            changePaint(value)
            canvas.drawPath(key, mPaint)
        }

        changePaint(mPaintOptions)
        canvas.drawPath(mPath, mPaint)
        for (sticker in mStickers) {
            drawSticker(canvas, sticker)
        }
        canvas.restore()
    }

    private fun stickerLines(sticker: Sticker) = sticker.text.split("\n")

    private fun drawSticker(canvas: Canvas, sticker: Sticker) {
        mTextPaint.color = sticker.color
        mTextPaint.textSize = sticker.size
        val lines = stickerLines(sticker)
        val lineHeight = sticker.size * 1.15f
        var y = sticker.y - lineHeight * (lines.size - 1) / 2f + sticker.size / 3f
        for (line in lines) {
            canvas.drawText(line, sticker.x, y, mTextPaint)
            y += lineHeight
        }
    }

    private fun stickerAt(x: Float, y: Float): Sticker? {
        for (sticker in mStickers.asReversed()) {
            mTextPaint.textSize = sticker.size
            val lines = stickerLines(sticker)
            val halfWidth = (lines.maxOfOrNull { mTextPaint.measureText(it) } ?: 0f) / 2f + 24f
            val halfHeight = sticker.size * 1.15f * lines.size / 2f + 24f
            if (x in (sticker.x - halfWidth)..(sticker.x + halfWidth) && y in (sticker.y - halfHeight)..(sticker.y + halfHeight)) {
                return sticker
            }
        }
        return null
    }

    private fun distance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /** Velo 0.22: a text or emoji in the middle of the picture, ready to be moved. */
    fun addSticker(text: String, color: Int) {
        val sticker = Sticker(text, color, width / 2f, height / 2f, resources.displayMetrics.density * 42f)
        mStickers.add(sticker)
        mHistory.add(sticker)
        invalidate()
    }

    private fun stickerTouch(event: MotionEvent): Boolean {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                val sticker = stickerAt(event.x, event.y) ?: return false
                mActiveSticker = sticker
                mStickers.remove(sticker)
                mStickers.add(sticker)
                mGrabDx = sticker.x - event.x
                mGrabDy = sticker.y - event.y
                return true
            }
        }
        val sticker = mActiveSticker ?: return false
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                mPinchStart = distance(event)
                mPinchSize = sticker.size
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2 && mPinchStart > 0f) {
                val density = resources.displayMetrics.density
                sticker.size = (mPinchSize * distance(event) / mPinchStart).coerceIn(14f * density, 220f * density)
            } else if (event.pointerCount == 1) {
                sticker.x = event.x + mGrabDx
                sticker.y = event.y + mGrabDy
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mPinchStart = 0f
                val remaining = if (event.actionIndex == 0) 1 else 0
                mGrabDx = sticker.x - event.getX(remaining)
                mGrabDy = sticker.y - event.getY(remaining)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mActiveSticker = null
                mPinchStart = 0f
            }
        }
        invalidate()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (stickerTouch(event)) {
            return true
        }
        val x = event.x
        val y = event.y

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mWasMultitouch = false
                mStartX = x
                mStartY = y
                actionDown(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !mWasMultitouch) {
                    actionMove(x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> actionUp()
            MotionEvent.ACTION_POINTER_DOWN -> mWasMultitouch = true
        }

        invalidate()
        return true
    }

    private fun actionDown(x: Float, y: Float) {
        mPath.reset()
        mPath.moveTo(x, y)
        mCurX = x
        mCurY = y
    }

    private fun actionMove(x: Float, y: Float) {
        mPath.quadTo(mCurX, mCurY, (x + mCurX) / 2, (y + mCurY) / 2)
        mCurX = x
        mCurY = y
    }

    private fun actionUp() {
        if (!mWasMultitouch) {
            mPath.lineTo(mCurX, mCurY)

            // draw a dot on click
            if (mStartX == mCurX && mStartY == mCurY) {
                mPath.lineTo(mCurX, mCurY + 2)
                mPath.lineTo(mCurX + 1, mCurY + 2)
                mPath.lineTo(mCurX + 1, mCurY)
            }
        }

        mPaths[mPath] = mPaintOptions
        mHistory.add(mPath)
        mPath = Path()
        mPaintOptions = PaintOptions(mPaintOptions.color, mPaintOptions.strokeWidth)
    }

    private fun changePaint(paintOptions: PaintOptions) {
        mPaint.color = paintOptions.color
        mPaint.strokeWidth = paintOptions.strokeWidth
    }

    fun updateColor(newColor: Int) {
        mPaintOptions.color = newColor
    }

    fun updateBrushSize(newBrushSize: Int) {
        mPaintOptions.strokeWidth = resources.getDimension(R.dimen.full_brush_size) * (newBrushSize / 100f)
    }

    fun updateBackgroundBitmap(bitmap: Bitmap) {
        backgroundBitmap = bitmap
        invalidate()
    }

    fun getBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        draw(canvas)
        return bitmap
    }

    fun undo() {
        // Velo 0.22: strokes and stickers are undone in the order they were made
        val last = mHistory.removeLastOrNull() ?: return
        if (last is Sticker) {
            mStickers.remove(last)
        } else {
            mPaths.remove(last)
        }
        invalidate()
    }
}
