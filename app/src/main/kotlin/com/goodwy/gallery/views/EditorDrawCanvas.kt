package com.goodwy.gallery.views

import android.content.Context
import android.graphics.*
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.gallery.R
import com.goodwy.gallery.interfaces.CanvasListener
import com.goodwy.gallery.models.CanvasOp
import com.goodwy.gallery.models.MyParcelable
import com.goodwy.gallery.models.MyPath
import com.goodwy.gallery.models.PaintOptions

class EditorDrawCanvas(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private var mCurX = 0f
    private var mCurY = 0f
    private var mStartX = 0f
    private var mStartY = 0f
    private var mColor = 0
    private var mWasMultitouch = false

    private var mPaint = Paint()
    private var mPath = MyPath()
    private var mPaintOptions = PaintOptions()

    var isDrawingEnabled: Boolean = false
    private var imageContentWidth: Int = 0
    private var imageContentHeight: Int = 0

    private val MIN_ERASER_WIDTH = 20f
    private val MAX_HISTORY_COUNT = 1000
    var mListener: CanvasListener? = null
    var onBeforeStrokeListener: (() -> Unit)? = null
    var onStrokeCommittedListener: (() -> Unit)? = null

    private var mOperations = ArrayList<CanvasOp>()
    private var mUndoneOperations = ArrayList<CanvasOp>()
    private var mBackgroundColor = Color.TRANSPARENT

    private val workPath = Path()
    private val workMatrix = Matrix()

    init {
        mColor = context.getProperPrimaryColor()
        mPaint.apply {
            color = mPaintOptions.color
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 40f
            isAntiAlias = true
        }
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        updateUndoVisibility()
    }

    fun setImageDimensions(imgWidth: Int, imgHeight: Int) {
        if (imgWidth > 0 && imgHeight > 0 && (imageContentWidth != imgWidth || imageContentHeight != imgHeight)) {
            imageContentWidth = imgWidth
            imageContentHeight = imgHeight
            invalidate()
        }
    }

    fun getImageDisplayRect(): RectF {
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        if (imageContentWidth <= 0 || imageContentHeight <= 0) {
            return RectF(0f, 0f, vw, vh)
        }
        val bw = imageContentWidth.toFloat()
        val bh = imageContentHeight.toFloat()
        val scale = minOf(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    fun hasStrokes(): Boolean = mOperations.isNotEmpty()

    fun getOperationsSnapshot(): List<CanvasOp> = ArrayList(mOperations)

    fun restoreOperationsSnapshot(ops: List<CanvasOp>) {
        mOperations = ArrayList(ops)
        mUndoneOperations.clear()
        mPath.reset()
        updateUndoRedoVisibility()
        invalidate()
    }

    public override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        val savedState = MyParcelable(superState!!)
        savedState.operations = mOperations
        return savedState
    }

    public override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is MyParcelable) {
            super.onRestoreInstanceState(state)
            return
        }

        super.onRestoreInstanceState(state.superState)
        mOperations = state.operations
        updateUndoVisibility()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mOperations.isEmpty() && mPath.isEmpty) return

        val rect = getImageDisplayRect()
        val rectW = rect.width().coerceAtLeast(1f)
        val rectH = rect.height().coerceAtLeast(1f)

        val saveCount = canvas.save()
        canvas.clipRect(rect)

        workMatrix.reset()
        workMatrix.setScale(rectW, rectH)
        workMatrix.postTranslate(rect.left, rect.top)

        for (op in mOperations) {
            if (op is CanvasOp.PathOp) {
                changePaint(op.paintOptions, rectW)
                workPath.reset()
                op.path.transform(workMatrix, workPath)
                canvas.drawPath(workPath, mPaint)
            }
        }

        if (!mPath.isEmpty) {
            val currentNormWidth = (mPaintOptions.strokeWidth / rectW).coerceAtLeast(0.002f)
            changePaint(mPaintOptions.copy(strokeWidth = currentNormWidth), rectW)
            workPath.reset()
            mPath.transform(workMatrix, workPath)
            canvas.drawPath(workPath, mPaint)
        }

        canvas.restoreToCount(saveCount)
    }

    fun renderStrokesToCanvas(targetCanvas: Canvas, targetWidth: Int, targetHeight: Int) {
        if (mOperations.isEmpty()) return
        val hasEraser = mOperations.any { it is CanvasOp.PathOp && it.paintOptions.isEraser }
        val layerSave = if (hasEraser) {
            targetCanvas.saveLayer(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat(), null)
        } else {
            targetCanvas.save()
        }

        val mat = Matrix()
        mat.setScale(targetWidth.toFloat(), targetHeight.toFloat())
        val mapped = Path()
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        for (op in mOperations) {
            if (op is CanvasOp.PathOp) {
                if (op.paintOptions.isEraser) {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    paint.color = Color.TRANSPARENT
                } else {
                    paint.xfermode = null
                    paint.color = op.paintOptions.color
                }
                paint.strokeWidth = (op.paintOptions.strokeWidth * targetWidth).coerceAtLeast(1f)
                mapped.reset()
                op.path.transform(mat, mapped)
                targetCanvas.drawPath(mapped, paint)
            }
        }

        targetCanvas.restoreToCount(layerSave)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled) {
            return false
        }

        val rect = getImageDisplayRect()
        val rectW = rect.width().coerceAtLeast(1f)
        val rectH = rect.height().coerceAtLeast(1f)
        val nx = (event.x - rect.left) / rectW
        val ny = (event.y - rect.top) / rectH

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mWasMultitouch = false
                mStartX = nx
                mStartY = ny
                actionDown(nx, ny)
                mUndoneOperations.clear()
                updateRedoVisibility(false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !mWasMultitouch) {
                    actionMove(nx, ny)
                }
            }

            MotionEvent.ACTION_UP -> actionUp(rectW)
            MotionEvent.ACTION_CANCEL -> {
                mPath.reset()
            }
            MotionEvent.ACTION_POINTER_DOWN -> mWasMultitouch = true
        }

        invalidate()
        return true
    }

    private fun actionDown(nx: Float, ny: Float) {
        mPath.reset()
        mPath.moveTo(nx, ny)
        mCurX = nx
        mCurY = ny
    }

    private fun actionMove(nx: Float, ny: Float) {
        mPath.quadTo(mCurX, mCurY, (nx + mCurX) / 2f, (ny + mCurY) / 2f)
        mCurX = nx
        mCurY = ny
    }

    private fun actionUp(rectW: Float) {
        if (!mWasMultitouch) {
            mPath.lineTo(mCurX, mCurY)

            // draw a dot on tap
            if (mStartX == mCurX && mStartY == mCurY) {
                val dot = 2f / rectW
                mPath.lineTo(mCurX, mCurY + dot)
                mPath.lineTo(mCurX + dot / 2f, mCurY + dot)
                mPath.lineTo(mCurX + dot / 2f, mCurY)
            }
        }

        onBeforeStrokeListener?.invoke()
        val strokeWidthToUse = if (mPaintOptions.isEraser) {
            maxOf(mPaintOptions.strokeWidth, MIN_ERASER_WIDTH)
        } else {
            mPaintOptions.strokeWidth
        }
        val normalizedWidth = (strokeWidthToUse / rectW).coerceAtLeast(0.002f)
        val savedOpts = PaintOptions(mPaintOptions.color, normalizedWidth, mPaintOptions.isEraser)
        addOperation(CanvasOp.PathOp(mPath, savedOpts))

        updateUndoVisibility()
        mPath = MyPath()
        mPaintOptions = PaintOptions(mPaintOptions.color, mPaintOptions.strokeWidth, mPaintOptions.isEraser)
        onStrokeCommittedListener?.invoke()
    }

    private fun changePaint(paintOptions: PaintOptions, rectW: Float) {
        if (paintOptions.isEraser) {
            mPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            mPaint.color = Color.TRANSPARENT
        } else {
            mPaint.xfermode = null
            mPaint.color = paintOptions.color
        }
        mPaint.strokeWidth = (paintOptions.strokeWidth * rectW).coerceAtLeast(1f)
    }

    fun updateColor(newColor: Int) {
        mPaintOptions.color = newColor
    }

    fun updateBrushSize(newBrushSize: Int) {
        mPaintOptions.strokeWidth = resources.getDimension(R.dimen.full_brush_size) * (newBrushSize / 100f)
    }

    fun undo() {
        if (mOperations.isNotEmpty()) {
            val lastOp = mOperations.removeAt(mOperations.lastIndex)
            mUndoneOperations.add(lastOp)
            invalidate()
        }
        updateUndoRedoVisibility()
    }

    fun redo() {
        if (mUndoneOperations.isNotEmpty()) {
            val undoneOperation = mUndoneOperations.removeAt(mUndoneOperations.lastIndex)
            addOperation(undoneOperation)
            invalidate()
        }
        updateUndoRedoVisibility()
    }

    fun toggleEraser(isEraserOn: Boolean) {
        mPaintOptions.isEraser = isEraserOn
        invalidate()
    }

    private fun addOperation(operation: CanvasOp) {
        mOperations.add(operation)
        while (mOperations.size > MAX_HISTORY_COUNT) {
            val item = mOperations.removeAt(0)
            if (item is CanvasOp.BitmapOp && !item.bitmap.isRecycled) {
                item.bitmap.recycle()
            }
        }
    }

    private fun updateUndoRedoVisibility() {
        updateUndoVisibility()
        updateRedoVisibility()
    }

    private fun updateUndoVisibility() {
        mListener?.toggleUndoVisibility(mOperations.isNotEmpty())
    }

    private fun updateRedoVisibility(visible: Boolean = mUndoneOperations.isNotEmpty()) {
        mListener?.toggleRedoVisibility(visible)
    }

    fun clearCanvas() {
        mPath.reset()
        mOperations.clear()
        mUndoneOperations.clear()
        updateUndoRedoVisibility()
        invalidate()
    }

    fun setColor(newColor: Int) {
        mPaintOptions.color = newColor
    }

    fun updateBackgroundColor(newColor: Int) {
        mBackgroundColor = newColor
        invalidate()
    }
}
