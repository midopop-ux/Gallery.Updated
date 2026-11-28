package com.goodwy.gallery.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.goodwy.gallery.R
import com.goodwy.gallery.models.StickerLayer
import com.goodwy.gallery.models.StickerLayerSnapshot
import java.util.ArrayDeque

data class CanvasStateSnapshot(
    val layersSnapshots: List<StickerLayerSnapshot>,
    val activeIndex: Int
)

class FreeStyleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val layers = ArrayList<StickerLayer>()
    var activeLayer: StickerLayer? = null

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stretchDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stretchDotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var rotateDrawable: Drawable? = null
    private var scaleDrawable: Drawable? = null
    private var flipDrawable: Drawable? = null
    private var deleteDrawable: Drawable? = null

    private var handleRadius = 40f
    private var stretchDotRadius = 20f

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAG = 1
        private const val MODE_ROTATE = 2
        private const val MODE_SCALE = 3
        private const val MODE_STRETCH_TOP = 4
        private const val MODE_STRETCH_BOTTOM = 5
        private const val MODE_STRETCH_LEFT = 6
        private const val MODE_STRETCH_RIGHT = 7

        private const val ROTATION_SNAP_THRESHOLD = 2.5f
    }

    private var currentMode = MODE_NONE
    private var activeHandle = StickerLayer.HANDLE_NONE

    private var snapThreshold = 35f
    private var initialLayerX = 0f
    private var initialLayerY = 0f
    private var stretchStartTouchX = 0f
    private var stretchStartTouchY = 0f
    private var dragTouchStartX = 0f
    private var dragTouchStartY = 0f
    private var dragStartLayerX = 0f
    private var dragStartLayerY = 0f
    private var hasVibratedSnap = false
    private var lastRotateHandleTapTime = 0L

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialAngle = 0f
    private var initialDist = 0f
    private var initialScaleX = 1f
    private var initialScaleY = 1f

    private val verticalGuideLines = ArrayList<Float>()
    private val horizontalGuideLines = ArrayList<Float>()

    var onLayerSelectedListener: ((StickerLayer?) -> Unit)? = null
    var onLayersChangedListener: (() -> Unit)? = null
    var onCropRequestedListener: ((StickerLayer) -> Unit)? = null
    var onHistoryChangedListener: (() -> Unit)? = null
    var onCommitUnifiedHistoryListener: ((CanvasStateSnapshot) -> Unit)? = null

    private val undoStack = ArrayDeque<CanvasStateSnapshot>()
    private val redoStack = ArrayDeque<CanvasStateSnapshot>()
    private val maxStackSize = 30
    private var handleTouchStartX = 0f
    private var handleTouchStartY = 0f
    private var hasMovedHandle = false
    private var touchDownSnapshot: CanvasStateSnapshot? = null
    private var hasModifiedInTouch = false

    init {
        val density = resources.displayMetrics.density
        handleRadius = 22f * density
        stretchDotRadius = 9f * density
        snapThreshold = 14f * density

        borderPaint.color = Color.parseColor("#2196F3")
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 2.5f * density
        borderPaint.pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)

        handlePaint.color = Color.parseColor("#1976D2")
        handlePaint.style = Paint.Style.FILL
        handlePaint.setShadowLayer(4f * density, 0f, 2f * density, 0x55000000)

        handleStrokePaint.color = Color.WHITE
        handleStrokePaint.style = Paint.Style.STROKE
        handleStrokePaint.strokeWidth = 2f * density

        stretchDotPaint.color = Color.parseColor("#03A9F4")
        stretchDotPaint.style = Paint.Style.FILL

        stretchDotStrokePaint.color = Color.WHITE
        stretchDotStrokePaint.style = Paint.Style.STROKE
        stretchDotStrokePaint.strokeWidth = 1.5f * density

        guideLinePaint.color = Color.parseColor("#FF5722")
        guideLinePaint.style = Paint.Style.STROKE
        guideLinePaint.strokeWidth = 2f * density
        guideLinePaint.pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)

        rotateDrawable = ContextCompat.getDrawable(context, R.drawable.ic_rotate_right_vector)?.mutate()
        rotateDrawable?.setTint(Color.WHITE)

        scaleDrawable = ContextCompat.getDrawable(context, R.drawable.ic_size_select)?.mutate()
        scaleDrawable?.setTint(Color.WHITE)

        flipDrawable = ContextCompat.getDrawable(context, R.drawable.ic_flip_horizontally_vector)?.mutate()
        flipDrawable?.setTint(Color.WHITE)

        deleteDrawable = ContextCompat.getDrawable(context, R.drawable.ic_close_vector)?.mutate()
        deleteDrawable?.setTint(Color.WHITE)
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun recordHistorySnapshot() {
        val snapshots = layers.map { it.createSnapshot() }
        val activeIdx = activeLayer?.let { layers.indexOf(it) } ?: -1
        val snap = CanvasStateSnapshot(snapshots, activeIdx)
        if (onCommitUnifiedHistoryListener != null) {
            onCommitUnifiedHistoryListener?.invoke(snap)
            return
        }
        undoStack.addLast(snap)
        if (undoStack.size > maxStackSize) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        onHistoryChangedListener?.invoke()
    }

    fun captureCanvasSnapshot(): CanvasStateSnapshot {
        val snapshots = layers.map { it.createSnapshot() }
        val activeIdx = activeLayer?.let { layers.indexOf(it) } ?: -1
        return CanvasStateSnapshot(snapshots, activeIdx)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val currentSnapshots = layers.map { it.createSnapshot() }
        val currentActiveIdx = activeLayer?.let { layers.indexOf(it) } ?: -1
        redoStack.addLast(CanvasStateSnapshot(currentSnapshots, currentActiveIdx))

        val previous = undoStack.removeLast()
        restoreCanvasState(previous)
        onHistoryChangedListener?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val currentSnapshots = layers.map { it.createSnapshot() }
        val currentActiveIdx = activeLayer?.let { layers.indexOf(it) } ?: -1
        undoStack.addLast(CanvasStateSnapshot(currentSnapshots, currentActiveIdx))

        val next = redoStack.removeLast()
        restoreCanvasState(next)
        onHistoryChangedListener?.invoke()
    }

    fun restoreCanvasState(state: CanvasStateSnapshot) {
        layers.clear()
        val safeW = if (width > 10) width * 0.8f else 600f
        val safeH = if (height > 10) height * 0.8f else 800f
        for (snap in state.layersSnapshots) {
            val layer = StickerLayer(snap.bitmap, snap.x, snap.y, safeW, safeH)
            layer.restoreFromSnapshot(snap)
            layers.add(layer)
        }
        activeLayer = if (state.activeIndex in layers.indices) layers[state.activeIndex] else null
        onLayerSelectedListener?.invoke(activeLayer)
        onLayersChangedListener?.invoke()
        invalidate()
    }

    fun getLayersBoundingBox(): RectF? {
        if (layers.isEmpty()) return null
        var total: RectF? = null
        for (layer in layers) {
            val box = layer.getBoundingBox()
            if (total == null) {
                total = RectF(box)
            } else {
                total.union(box)
            }
        }
        return total
    }

    fun addSticker(bitmap: Bitmap) {
        recordHistorySnapshot()
        val density = resources.displayMetrics.density
        val dmW = resources.displayMetrics.widthPixels.toFloat()
        val dmH = resources.displayMetrics.heightPixels.toFloat() * 0.7f
        val viewW = if (width > 50) width.toFloat() else dmW
        val viewH = if (height > 50) height.toFloat() else dmH

        val offset = (layers.size % 6) * (20f * density) - (2f * 20f * density)
        val cX = viewW / 2f + offset
        val cY = viewH / 2f + offset
        val maxW = (viewW * 0.65f).coerceAtLeast(200f)
        val maxH = (viewH * 0.65f).coerceAtLeast(200f)
        val layer = StickerLayer(bitmap, cX, cY, maxW, maxH)
        layers.add(layer)
        activeLayer = layer
        onLayerSelectedListener?.invoke(layer)
        onLayersChangedListener?.invoke()
        invalidate()
    }

    fun duplicateActiveLayer() {
        val layer = activeLayer ?: return
        recordHistorySnapshot()
        val density = resources.displayMetrics.density
        val dup = layer.duplicate(24f * density, 24f * density)
        layers.add(dup)
        activeLayer = dup
        onLayerSelectedListener?.invoke(dup)
        onLayersChangedListener?.invoke()
        invalidate()
    }

    fun removeActiveLayer() {
        val layer = activeLayer ?: return
        recordHistorySnapshot()
        layers.remove(layer)
        activeLayer = layers.lastOrNull()
        onLayerSelectedListener?.invoke(activeLayer)
        onLayersChangedListener?.invoke()
        invalidate()
    }

    fun bringActiveLayerForward() {
        val layer = activeLayer ?: return
        val idx = layers.indexOf(layer)
        if (idx >= 0 && idx < layers.size - 1) {
            recordHistorySnapshot()
            layers.removeAt(idx)
            layers.add(idx + 1, layer)
            onLayersChangedListener?.invoke()
            invalidate()
        }
    }

    fun bringActiveLayerToFront() {
        val layer = activeLayer ?: return
        val idx = layers.indexOf(layer)
        if (idx >= 0 && idx < layers.size - 1) {
            recordHistorySnapshot()
            layers.removeAt(idx)
            layers.add(layer)
            onLayersChangedListener?.invoke()
            invalidate()
        }
    }

    fun sendActiveLayerBackward() {
        val layer = activeLayer ?: return
        val idx = layers.indexOf(layer)
        if (idx > 0) {
            recordHistorySnapshot()
            layers.removeAt(idx)
            layers.add(idx - 1, layer)
            onLayersChangedListener?.invoke()
            invalidate()
        }
    }

    fun sendActiveLayerToBack() {
        val layer = activeLayer ?: return
        val idx = layers.indexOf(layer)
        if (idx > 0) {
            recordHistorySnapshot()
            layers.removeAt(idx)
            layers.add(0, layer)
            onLayersChangedListener?.invoke()
            invalidate()
        }
    }

    fun clearAllLayers() {
        recordHistorySnapshot()
        layers.clear()
        activeLayer = null
        onLayerSelectedListener?.invoke(null)
        onLayersChangedListener?.invoke()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (layer in layers) {
            layer.draw(canvas, paint)
        }

        // Draw selection frame for activeLayer
        activeLayer?.let { layer ->
            drawLayerControls(canvas, layer)
        }

        // Draw guidelines
        for (gx in verticalGuideLines) {
            canvas.drawLine(gx, 0f, gx, height.toFloat(), guideLinePaint)
        }
        for (gy in horizontalGuideLines) {
            canvas.drawLine(0f, gy, width.toFloat(), gy, guideLinePaint)
        }
    }

    private fun drawLayerControls(canvas: Canvas, layer: StickerLayer) {
        val corners = layer.getTransformedCorners()

        // Border polygon
        val path = android.graphics.Path()
        path.moveTo(corners[0].x, corners[0].y)
        path.lineTo(corners[1].x, corners[1].y)
        path.lineTo(corners[2].x, corners[2].y)
        path.lineTo(corners[3].x, corners[3].y)
        path.close()
        canvas.drawPath(path, borderPaint)

        // Draw corner handles
        drawCircleHandle(canvas, corners[0].x, corners[0].y, flipDrawable) // Top-Left: Flip
        drawCircleHandle(canvas, corners[1].x, corners[1].y, rotateDrawable) // Top-Right: Rotate
        drawCircleHandle(canvas, corners[2].x, corners[2].y, scaleDrawable) // Bottom-Right: Scale
        drawCircleHandle(canvas, corners[3].x, corners[3].y, deleteDrawable) // Bottom-Left: Delete

        // Draw edge stretch dots
        val edges = layer.getTransformedEdgeMidpoints()
        for (edge in edges) {
            canvas.drawCircle(edge.x, edge.y, stretchDotRadius, stretchDotPaint)
            canvas.drawCircle(edge.x, edge.y, stretchDotRadius, stretchDotStrokePaint)
        }
    }

    private fun drawCircleHandle(canvas: Canvas, cx: Float, cy: Float, icon: Drawable?) {
        canvas.drawCircle(cx, cy, handleRadius, handlePaint)
        canvas.drawCircle(cx, cy, handleRadius, handleStrokePaint)
        icon?.let {
            val iconSize = (handleRadius * 1.1f).toInt()
            it.setBounds((cx - iconSize / 2).toInt(), (cy - iconSize / 2).toInt(), (cx + iconSize / 2).toInt(), (cy + iconSize / 2).toInt())
            it.draw(canvas)
        }
    }

    private fun addUniqueGuide(list: ArrayList<Float>, valToAdd: Float) {
        for (v in list) {
            if (Math.abs(v - valToAdd) < 1.5f) return
        }
        list.add(valToAdd)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x
        val touchY = event.y
        val density = resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                verticalGuideLines.clear()
                horizontalGuideLines.clear()
                lastTouchX = touchX
                lastTouchY = touchY
                hasModifiedInTouch = false
                touchDownSnapshot = captureCanvasSnapshot()

                // Check active layer handles first
                activeLayer?.let { layer ->
                    val handle = layer.getTouchedHandle(touchX, touchY, handleRadius)
                    if (handle != StickerLayer.HANDLE_NONE) {
                        activeHandle = handle
                        when (handle) {
                            StickerLayer.HANDLE_FLIP -> {
                                recordHistorySnapshot()
                                layer.flippedH = !layer.flippedH
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                onLayersChangedListener?.invoke()
                                invalidate()
                                return true
                            }
                            StickerLayer.HANDLE_DELETE -> {
                                removeActiveLayer()
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                return true
                            }
                            StickerLayer.HANDLE_ROTATE -> {
                                val now = System.currentTimeMillis()
                                if (now - lastRotateHandleTapTime < 350) {
                                    recordHistorySnapshot()
                                    layer.rotation = 0f
                                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    invalidate()
                                    lastRotateHandleTapTime = 0
                                    return true
                                }
                                lastRotateHandleTapTime = now
                                currentMode = MODE_ROTATE
                                initialAngle = Math.toDegrees(Math.atan2((touchY - layer.y).toDouble(), (touchX - layer.x).toDouble())).toFloat() - layer.rotation
                                return true
                            }
                            StickerLayer.HANDLE_SCALE -> {
                                currentMode = MODE_SCALE
                                initialDist = Math.hypot((touchX - layer.x).toDouble(), (touchY - layer.y).toDouble()).toFloat()
                                initialScaleX = layer.scaleX
                                initialScaleY = layer.scaleY
                                handleTouchStartX = touchX
                                handleTouchStartY = touchY
                                hasMovedHandle = false
                                return true
                            }
                            StickerLayer.HANDLE_STRETCH_TOP -> return initStretchMode(layer, MODE_STRETCH_TOP, touchX, touchY)
                            StickerLayer.HANDLE_STRETCH_BOTTOM -> return initStretchMode(layer, MODE_STRETCH_BOTTOM, touchX, touchY)
                            StickerLayer.HANDLE_STRETCH_LEFT -> return initStretchMode(layer, MODE_STRETCH_LEFT, touchX, touchY)
                            StickerLayer.HANDLE_STRETCH_RIGHT -> return initStretchMode(layer, MODE_STRETCH_RIGHT, touchX, touchY)
                        }
                    }
                }

                // Check if touched any layer body (topmost first)
                var touchedLayer: StickerLayer? = null
                for (i in layers.indices.reversed()) {
                    if (layers[i].contains(touchX, touchY)) {
                        touchedLayer = layers[i]
                        break
                    }
                }

                if (touchedLayer != null) {
                    activeLayer = touchedLayer
                    currentMode = MODE_DRAG
                    dragTouchStartX = touchX
                    dragTouchStartY = touchY
                    dragStartLayerX = touchedLayer.x
                    dragStartLayerY = touchedLayer.y
                    hasVibratedSnap = false
                    onLayerSelectedListener?.invoke(touchedLayer)
                    invalidate()
                    return true
                } else {
                    activeLayer = null
                    currentMode = MODE_NONE
                    onLayerSelectedListener?.invoke(null)
                    invalidate()
                    return false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val layer = activeLayer ?: return false

                if (!hasMovedHandle && Math.hypot((touchX - handleTouchStartX).toDouble(), (touchY - handleTouchStartY).toDouble()) > 10f * density) {
                    hasMovedHandle = true
                }

                when (currentMode) {
                    MODE_DRAG -> {
                        hasModifiedInTouch = true
                        val rawX = dragStartLayerX + (touchX - dragTouchStartX)
                        val rawY = dragStartLayerY + (touchY - dragTouchStartY)
                        checkAlignmentSnapping(layer, rawX, rawY)
                        invalidate()
                    }
                    MODE_ROTATE -> {
                        hasModifiedInTouch = true
                        val currentAngle = Math.toDegrees(Math.atan2((touchY - layer.y).toDouble(), (touchX - layer.x).toDouble())).toFloat()
                        var newRot = (currentAngle - initialAngle) % 360f
                        if (newRot < 0) newRot += 360f

                        // Rotation snap at 0, 90, 180, 270
                        for (snap in floatArrayOf(0f, 90f, 180f, 270f, 360f)) {
                            if (Math.abs(newRot - snap) < ROTATION_SNAP_THRESHOLD) {
                                newRot = snap % 360f
                                if (!hasVibratedSnap) {
                                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    hasVibratedSnap = true
                                }
                                break
                            } else {
                                hasVibratedSnap = false
                            }
                        }
                        layer.rotation = newRot
                        invalidate()
                    }
                    MODE_SCALE -> {
                        if (hasMovedHandle && initialDist > 0f) {
                            hasModifiedInTouch = true
                            val currentDist = Math.hypot((touchX - layer.x).toDouble(), (touchY - layer.y).toDouble()).toFloat()
                            val ratio = currentDist / initialDist
                            layer.scaleX = (initialScaleX * ratio).coerceIn(0.2f, 5.0f)
                            layer.scaleY = (initialScaleY * ratio).coerceIn(0.2f, 5.0f)
                            invalidate()
                        }
                    }
                    MODE_STRETCH_RIGHT, MODE_STRETCH_LEFT, MODE_STRETCH_BOTTOM, MODE_STRETCH_TOP -> {
                        hasModifiedInTouch = true
                        applyStretch(layer, currentMode, touchX, touchY)
                        invalidate()
                    }
                }

                lastTouchX = touchX
                lastTouchY = touchY
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (activeHandle == StickerLayer.HANDLE_SCALE && !hasMovedHandle) {
                    activeLayer?.let { layer ->
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onCropRequestedListener?.invoke(layer)
                    }
                } else if (hasModifiedInTouch && touchDownSnapshot != null) {
                    val downSnap = touchDownSnapshot!!
                    if (onCommitUnifiedHistoryListener != null) {
                        onCommitUnifiedHistoryListener?.invoke(downSnap)
                    } else {
                        undoStack.addLast(downSnap)
                        if (undoStack.size > maxStackSize) {
                            undoStack.removeFirst()
                        }
                        redoStack.clear()
                        onHistoryChangedListener?.invoke()
                    }
                }

                resetTouchState()
                onLayersChangedListener?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (hasModifiedInTouch && touchDownSnapshot != null) {
                    restoreCanvasState(touchDownSnapshot!!)
                }
                resetTouchState()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resetTouchState() {
        currentMode = MODE_NONE
        activeHandle = StickerLayer.HANDLE_NONE
        hasVibratedSnap = false
        hasModifiedInTouch = false
        touchDownSnapshot = null
        verticalGuideLines.clear()
        horizontalGuideLines.clear()
    }

    private fun initStretchMode(layer: StickerLayer, mode: Int, touchX: Float, touchY: Float): Boolean {
        currentMode = mode
        initialScaleX = layer.scaleX
        initialScaleY = layer.scaleY
        initialLayerX = layer.x
        initialLayerY = layer.y
        stretchStartTouchX = touchX
        stretchStartTouchY = touchY
        hasVibratedSnap = false
        return true
    }

    private fun applyStretch(layer: StickerLayer, mode: Int, touchX: Float, touchY: Float) {
        verticalGuideLines.clear()
        horizontalGuideLines.clear()

        val rad = Math.toRadians(layer.rotation.toDouble())
        val cosR = Math.cos(rad).toFloat()
        val sinR = Math.sin(rad).toFloat()
        val isHorizontal = mode == MODE_STRETCH_RIGHT || mode == MODE_STRETCH_LEFT
        val axisX = if (isHorizontal) cosR else -sinR
        val axisY = if (isHorizontal) sinR else cosR
        val sign = if (mode == MODE_STRETCH_RIGHT || mode == MODE_STRETCH_BOTTOM) 1f else -1f

        val dx = touchX - stretchStartTouchX
        val dy = touchY - stretchStartTouchY
        val projDelta = (dx * axisX + dy * axisY) * sign

        val origSize = if (isHorizontal) layer.width * initialScaleX else layer.height * initialScaleY
        val minSize = (if (isHorizontal) layer.width else layer.height) * 0.15f
        val maxSize = (if (isHorizontal) layer.width else layer.height) * 5.0f
        var newSize = (origSize + projDelta).coerceIn(minSize, maxSize)

        var snapped = false
        val normRot = ((layer.rotation % 180f) + 180f) % 180f
        val isAxisAligned = normRot < 0.5f || Math.abs(normRot - 180f) < 0.5f || Math.abs(normRot - 90f) < 0.5f

        if (isAxisAligned && Math.abs(sinR) < 0.01f) {
            val dir = sign * if (cosR >= 0f) 1f else -1f
            val fixedCoord = (if (isHorizontal) initialLayerX else initialLayerY) - dir * (origSize / 2f)
            val rawMovingCoord = fixedCoord + dir * newSize
            var bestDiff = snapThreshold
            var bestMovingCoord = rawMovingCoord

            for (other in layers) {
                if (other === layer) continue
                val otherBox = other.getBoundingBox()
                val oStart = if (isHorizontal) otherBox.left else otherBox.top
                val oEnd = if (isHorizontal) otherBox.right else otherBox.bottom
                val oMid = (oStart + oEnd) / 2f
                val oSize = oEnd - oStart

                for (target in floatArrayOf(oStart, oMid, oEnd, fixedCoord + dir * oSize)) {
                    val diff = Math.abs(rawMovingCoord - target)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestMovingCoord = target
                    }
                }
            }

            if (bestDiff < snapThreshold) {
                val snappedSize = ((bestMovingCoord - fixedCoord) * dir).coerceIn(minSize, maxSize)
                newSize = snappedSize
                snapped = true
                val guides = if (isHorizontal) verticalGuideLines else horizontalGuideLines
                addUniqueGuide(guides, fixedCoord + dir * newSize)
            }
        }

        val actualDelta = newSize - origSize
        val shiftCenter = sign * (actualDelta / 2f)
        if (isHorizontal) {
            layer.scaleX = newSize / layer.width
        } else {
            layer.scaleY = newSize / layer.height
        }
        layer.x = initialLayerX + shiftCenter * axisX
        layer.y = initialLayerY + shiftCenter * axisY

        if (snapped) {
            if (!hasVibratedSnap) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                hasVibratedSnap = true
            }
        } else {
            hasVibratedSnap = false
        }
    }

    private fun checkAlignmentSnapping(layer: StickerLayer, rawX: Float, rawY: Float) {
        verticalGuideLines.clear()
        horizontalGuideLines.clear()

        // Account for layer rotation in screen-space bounding box size
        val curBox = layer.getBoundingBox()
        val halfW = curBox.width() / 2f
        val halfH = curBox.height() / 2f

        val targetLeft = rawX - halfW
        val targetRight = rawX + halfW
        val targetTop = rawY - halfH
        val targetBottom = rawY + halfH

        var bestDiffX = snapThreshold
        var bestDiffY = snapThreshold
        var snappedCenterX: Float? = null
        var snappedCenterY: Float? = null

        val canvasCenterX = width / 2f
        val canvasCenterY = height / 2f

        val dCenterX = Math.abs(rawX - canvasCenterX)
        if (dCenterX < bestDiffX) {
            bestDiffX = dCenterX
            snappedCenterX = canvasCenterX
        }

        val dCenterY = Math.abs(rawY - canvasCenterY)
        if (dCenterY < bestDiffY) {
            bestDiffY = dCenterY
            snappedCenterY = canvasCenterY
        }

        for (other in layers) {
            if (other === layer) continue

            val otherBox = other.getBoundingBox()
            val otherCenterX = otherBox.centerX()
            val otherCenterY = otherBox.centerY()
            val otherLeft = otherBox.left
            val otherRight = otherBox.right
            val otherTop = otherBox.top
            val otherBottom = otherBox.bottom

            val dcX = Math.abs(rawX - otherCenterX)
            if (dcX < bestDiffX) { bestDiffX = dcX; snappedCenterX = otherCenterX }
            val dll = Math.abs(targetLeft - otherLeft)
            if (dll < bestDiffX) { bestDiffX = dll; snappedCenterX = otherLeft + halfW }
            val drr = Math.abs(targetRight - otherRight)
            if (drr < bestDiffX) { bestDiffX = drr; snappedCenterX = otherRight - halfW }
            val dlr = Math.abs(targetLeft - otherRight)
            if (dlr < bestDiffX) { bestDiffX = dlr; snappedCenterX = otherRight + halfW }
            val drl = Math.abs(targetRight - otherLeft)
            if (drl < bestDiffX) { bestDiffX = drl; snappedCenterX = otherLeft - halfW }

            val dcY = Math.abs(rawY - otherCenterY)
            if (dcY < bestDiffY) { bestDiffY = dcY; snappedCenterY = otherCenterY }
            val dtt = Math.abs(targetTop - otherTop)
            if (dtt < bestDiffY) { bestDiffY = dtt; snappedCenterY = otherTop + halfH }
            val dbb = Math.abs(targetBottom - otherBottom)
            if (dbb < bestDiffY) { bestDiffY = dbb; snappedCenterY = otherBottom - halfH }
            val dtb = Math.abs(targetTop - otherBottom)
            if (dtb < bestDiffY) { bestDiffY = dtb; snappedCenterY = otherBottom + halfH }
            val dbt = Math.abs(targetBottom - otherTop)
            if (dbt < bestDiffY) { bestDiffY = dbt; snappedCenterY = otherTop - halfH }
        }

        layer.x = snappedCenterX ?: rawX
        layer.y = snappedCenterY ?: rawY

        val finalLeft = layer.x - halfW
        val finalRight = layer.x + halfW
        val finalTop = layer.y - halfH
        val finalBottom = layer.y + halfH

        if (snappedCenterX != null) {
            if (Math.abs(layer.x - canvasCenterX) < 1.5f) addUniqueGuide(verticalGuideLines, canvasCenterX)
            for (other in layers) {
                if (other === layer) continue
                val ob = other.getBoundingBox()
                if (Math.abs(layer.x - ob.centerX()) < 1.5f) addUniqueGuide(verticalGuideLines, ob.centerX())
                if (Math.abs(finalLeft - ob.left) < 1.5f) addUniqueGuide(verticalGuideLines, ob.left)
                if (Math.abs(finalRight - ob.right) < 1.5f) addUniqueGuide(verticalGuideLines, ob.right)
                if (Math.abs(finalLeft - ob.right) < 1.5f) addUniqueGuide(verticalGuideLines, ob.right)
                if (Math.abs(finalRight - ob.left) < 1.5f) addUniqueGuide(verticalGuideLines, ob.left)
            }
        }

        if (snappedCenterY != null) {
            if (Math.abs(layer.y - canvasCenterY) < 1.5f) addUniqueGuide(horizontalGuideLines, canvasCenterY)
            for (other in layers) {
                if (other === layer) continue
                val ob = other.getBoundingBox()
                if (Math.abs(layer.y - ob.centerY()) < 1.5f) addUniqueGuide(horizontalGuideLines, ob.centerY())
                if (Math.abs(finalTop - ob.top) < 1.5f) addUniqueGuide(horizontalGuideLines, ob.top)
                if (Math.abs(finalBottom - ob.bottom) < 1.5f) addUniqueGuide(horizontalGuideLines, ob.bottom)
                if (Math.abs(finalTop - ob.bottom) < 1.5f) addUniqueGuide(horizontalGuideLines, ob.bottom)
                if (Math.abs(finalBottom - ob.top) < 1.5f) addUniqueGuide(horizontalGuideLines, ob.top)
            }
        }

        if (snappedCenterX != null || snappedCenterY != null) {
            if (!hasVibratedSnap) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                hasVibratedSnap = true
            }
        } else {
            hasVibratedSnap = false
        }
    }

    fun renderLayersToCanvas(targetCanvas: Canvas, targetWidth: Int, targetHeight: Int) {
        renderLayersToCanvasAligned(targetCanvas, targetWidth, targetHeight, null)
    }

    fun renderLayersToCanvasAligned(targetCanvas: Canvas, targetWidth: Int, targetHeight: Int, sourceRect: RectF? = null) {
        if (layers.isEmpty()) return
        val srcLeft = sourceRect?.left ?: 0f
        val srcTop = sourceRect?.top ?: 0f
        val srcW = sourceRect?.width()?.takeIf { it > 1f } ?: (if (width > 0) width.toFloat() else targetWidth.toFloat())
        val srcH = sourceRect?.height()?.takeIf { it > 1f } ?: (if (height > 0) height.toFloat() else targetHeight.toFloat())

        val scaleX = targetWidth / srcW
        val scaleY = targetHeight / srcH
        targetCanvas.save()
        targetCanvas.scale(scaleX, scaleY)
        targetCanvas.translate(-srcLeft, -srcTop)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (layer in layers) {
            layer.draw(targetCanvas, paint)
        }
        targetCanvas.restore()
    }
}
