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
    private var stretchFixedEdge = 0f
    private var stretchStartTouchX = 0f
    private var stretchStartTouchY = 0f
    private var stretchStartEdge = 0f
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
        undoStack.addLast(CanvasStateSnapshot(snapshots, activeIdx))
        if (undoStack.size > maxStackSize) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        onHistoryChangedListener?.invoke()
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

    private fun restoreCanvasState(state: CanvasStateSnapshot) {
        layers.clear()
        for (snap in state.layersSnapshots) {
            val layer = StickerLayer(snap.bitmap, snap.x, snap.y, width * 0.8f, height * 0.8f)
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
                touchDownSnapshot = CanvasStateSnapshot(layers.map { it.createSnapshot() }, layers.indexOf(activeLayer))

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
                            StickerLayer.HANDLE_CROP -> { // Bottom-left mapped to delete
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
                            StickerLayer.HANDLE_STRETCH_TOP -> {
                                currentMode = MODE_STRETCH_TOP
                                initialScaleY = layer.scaleY
                                stretchFixedEdge = layer.y + (layer.height * layer.scaleY) / 2f
                                stretchStartTouchY = touchY
                                stretchStartEdge = layer.y - (layer.height * layer.scaleY) / 2f
                                hasVibratedSnap = false
                                return true
                            }
                            StickerLayer.HANDLE_STRETCH_BOTTOM -> {
                                currentMode = MODE_STRETCH_BOTTOM
                                initialScaleY = layer.scaleY
                                stretchFixedEdge = layer.y - (layer.height * layer.scaleY) / 2f
                                stretchStartTouchY = touchY
                                stretchStartEdge = layer.y + (layer.height * layer.scaleY) / 2f
                                hasVibratedSnap = false
                                return true
                            }
                            StickerLayer.HANDLE_STRETCH_LEFT -> {
                                currentMode = MODE_STRETCH_LEFT
                                initialScaleX = layer.scaleX
                                stretchFixedEdge = layer.x + (layer.width * layer.scaleX) / 2f
                                stretchStartTouchX = touchX
                                stretchStartEdge = layer.x - (layer.width * layer.scaleX) / 2f
                                hasVibratedSnap = false
                                return true
                            }
                            StickerLayer.HANDLE_STRETCH_RIGHT -> {
                                currentMode = MODE_STRETCH_RIGHT
                                initialScaleX = layer.scaleX
                                stretchFixedEdge = layer.x - (layer.width * layer.scaleX) / 2f
                                stretchStartTouchX = touchX
                                stretchStartEdge = layer.x + (layer.width * layer.scaleX) / 2f
                                hasVibratedSnap = false
                                return true
                            }
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
                val dx = touchX - lastTouchX
                val dy = touchY - lastTouchY
                hasModifiedInTouch = true

                if (!hasMovedHandle && Math.hypot((touchX - handleTouchStartX).toDouble(), (touchY - handleTouchStartY).toDouble()) > 10f * density) {
                    hasMovedHandle = true
                }

                when (currentMode) {
                    MODE_DRAG -> {
                        val rawX = dragStartLayerX + (touchX - dragTouchStartX)
                        val rawY = dragStartLayerY + (touchY - dragTouchStartY)
                        checkAlignmentSnapping(layer, rawX, rawY)
                        invalidate()
                    }
                    MODE_ROTATE -> {
                        val currentAngle = Math.toDegrees(Math.atan2((touchY - layer.y).toDouble(), (touchX - layer.x).toDouble())).toFloat()
                        var newRot = (currentAngle - initialAngle) % 360f
                        if (newRot < 0) newRot += 360f

                        // Rotation snap at 0, 90, 180, 270
                        for (snap in floatArrayOf(0f, 90f, 180f, 270f, 360f)) {
                            if (Math.abs(newRot - snap) < ROTATION_SNAP_THRESHOLD) {
                                newRot = snap % 360f
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                break
                            }
                        }
                        layer.rotation = newRot
                        invalidate()
                    }
                    MODE_SCALE -> {
                        val currentDist = Math.hypot((touchX - layer.x).toDouble(), (touchY - layer.y).toDouble()).toFloat()
                        if (initialDist > 0) {
                            val ratio = currentDist / initialDist
                            layer.scaleX = (initialScaleX * ratio).coerceIn(0.2f, 5.0f)
                            layer.scaleY = (initialScaleY * ratio).coerceIn(0.2f, 5.0f)
                            checkAlignmentSnapping(layer, layer.x, layer.y)
                            invalidate()
                        }
                    }
                    MODE_STRETCH_RIGHT -> {
                        verticalGuideLines.clear()
                        val fixedLeft = stretchFixedEdge
                        val rawRight = stretchStartEdge + (touchX - stretchStartTouchX)
                        var currentRight = rawRight
                        var snapped = false

                        for (other in layers) {
                            if (other === layer) continue
                            val otherHalfW = (other.width * other.scaleX) / 2f
                            val otherRight = other.x + otherHalfW
                            val otherCenterX = other.x
                            val otherLeft = other.x - otherHalfW
                            val otherWidth = other.width * other.scaleX

                            if (Math.abs(rawRight - otherRight) < snapThreshold) {
                                currentRight = otherRight
                                addUniqueGuide(verticalGuideLines, otherRight)
                                snapped = true
                                break
                            } else if (Math.abs(rawRight - otherCenterX) < snapThreshold) {
                                currentRight = otherCenterX
                                addUniqueGuide(verticalGuideLines, otherCenterX)
                                snapped = true
                                break
                            } else if (Math.abs(rawRight - otherLeft) < snapThreshold) {
                                currentRight = otherLeft
                                addUniqueGuide(verticalGuideLines, otherLeft)
                                snapped = true
                                break
                            } else if (Math.abs((rawRight - fixedLeft) - otherWidth) < snapThreshold) {
                                currentRight = fixedLeft + otherWidth
                                addUniqueGuide(verticalGuideLines, currentRight)
                                snapped = true
                                break
                            }
                        }

                        val newWidth = (currentRight - fixedLeft).coerceIn(layer.width * 0.15f, layer.width * 5.0f)
                        layer.scaleX = newWidth / layer.width
                        layer.x = fixedLeft + newWidth / 2f
                        if (snapped) {
                            if (!hasVibratedSnap) {
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                hasVibratedSnap = true
                            }
                        } else {
                            hasVibratedSnap = false
                        }
                        invalidate()
                    }
                    MODE_STRETCH_LEFT -> {
                        verticalGuideLines.clear()
                        val fixedRight = stretchFixedEdge
                        val rawLeft = stretchStartEdge + (touchX - stretchStartTouchX)
                        var currentLeft = rawLeft
                        var snapped = false

                        for (other in layers) {
                            if (other === layer) continue
                            val otherHalfW = (other.width * other.scaleX) / 2f
                            val otherLeft = other.x - otherHalfW
                            val otherCenterX = other.x
                            val otherRight = other.x + otherHalfW
                            val otherWidth = other.width * other.scaleX

                            if (Math.abs(rawLeft - otherLeft) < snapThreshold) {
                                currentLeft = otherLeft
                                addUniqueGuide(verticalGuideLines, otherLeft)
                                snapped = true
                                break
                            } else if (Math.abs(rawLeft - otherCenterX) < snapThreshold) {
                                currentLeft = otherCenterX
                                addUniqueGuide(verticalGuideLines, otherCenterX)
                                snapped = true
                                break
                            } else if (Math.abs(rawLeft - otherRight) < snapThreshold) {
                                currentLeft = otherRight
                                addUniqueGuide(verticalGuideLines, otherRight)
                                snapped = true
                                break
                            } else if (Math.abs((fixedRight - rawLeft) - otherWidth) < snapThreshold) {
                                currentLeft = fixedRight - otherWidth
                                addUniqueGuide(verticalGuideLines, currentLeft)
                                snapped = true
                                break
                            }
                        }

                        val newWidth = (fixedRight - currentLeft).coerceIn(layer.width * 0.15f, layer.width * 5.0f)
                        layer.scaleX = newWidth / layer.width
                        layer.x = fixedRight - newWidth / 2f
                        if (snapped) {
                            if (!hasVibratedSnap) {
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                hasVibratedSnap = true
                            }
                        } else {
                            hasVibratedSnap = false
                        }
                        invalidate()
                    }
                    MODE_STRETCH_BOTTOM -> {
                        horizontalGuideLines.clear()
                        val fixedTop = stretchFixedEdge
                        val rawBottom = stretchStartEdge + (touchY - stretchStartTouchY)
                        var currentBottom = rawBottom
                        var snapped = false

                        for (other in layers) {
                            if (other === layer) continue
                            val otherHalfH = (other.height * other.scaleY) / 2f
                            val otherBottom = other.y + otherHalfH
                            val otherCenterY = other.y
                            val otherTop = other.y - otherHalfH
                            val otherHeight = other.height * other.scaleY

                            if (Math.abs(rawBottom - otherBottom) < snapThreshold) {
                                currentBottom = otherBottom
                                addUniqueGuide(horizontalGuideLines, otherBottom)
                                snapped = true
                                break
                            } else if (Math.abs(rawBottom - otherCenterY) < snapThreshold) {
                                currentBottom = otherCenterY
                                addUniqueGuide(horizontalGuideLines, otherCenterY)
                                snapped = true
                                break
                            } else if (Math.abs(rawBottom - otherTop) < snapThreshold) {
                                currentBottom = otherTop
                                addUniqueGuide(horizontalGuideLines, otherTop)
                                snapped = true
                                break
                            } else if (Math.abs((rawBottom - fixedTop) - otherHeight) < snapThreshold) {
                                currentBottom = fixedTop + otherHeight
                                addUniqueGuide(horizontalGuideLines, currentBottom)
                                snapped = true
                                break
                            }
                        }

                        val newHeight = (currentBottom - fixedTop).coerceIn(layer.height * 0.15f, layer.height * 5.0f)
                        layer.scaleY = newHeight / layer.height
                        layer.y = fixedTop + newHeight / 2f
                        if (snapped) {
                            if (!hasVibratedSnap) {
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                hasVibratedSnap = true
                            }
                        } else {
                            hasVibratedSnap = false
                        }
                        invalidate()
                    }
                    MODE_STRETCH_TOP -> {
                        horizontalGuideLines.clear()
                        val fixedBottom = stretchFixedEdge
                        val rawTop = stretchStartEdge + (touchY - stretchStartTouchY)
                        var currentTop = rawTop
                        var snapped = false

                        for (other in layers) {
                            if (other === layer) continue
                            val otherHalfH = (other.height * other.scaleY) / 2f
                            val otherTop = other.y - otherHalfH
                            val otherCenterY = other.y
                            val otherBottom = other.y + otherHalfH
                            val otherHeight = other.height * other.scaleY

                            if (Math.abs(rawTop - otherTop) < snapThreshold) {
                                currentTop = otherTop
                                addUniqueGuide(horizontalGuideLines, otherTop)
                                snapped = true
                                break
                            } else if (Math.abs(rawTop - otherCenterY) < snapThreshold) {
                                currentTop = otherCenterY
                                addUniqueGuide(horizontalGuideLines, otherCenterY)
                                snapped = true
                                break
                            } else if (Math.abs(rawTop - otherBottom) < snapThreshold) {
                                currentTop = otherBottom
                                addUniqueGuide(horizontalGuideLines, otherBottom)
                                snapped = true
                                break
                            } else if (Math.abs((fixedBottom - rawTop) - otherHeight) < snapThreshold) {
                                currentTop = fixedBottom - otherHeight
                                addUniqueGuide(horizontalGuideLines, currentTop)
                                snapped = true
                                break
                            }
                        }

                        val newHeight = (fixedBottom - currentTop).coerceIn(layer.height * 0.15f, layer.height * 5.0f)
                        layer.scaleY = newHeight / layer.height
                        layer.y = fixedBottom - newHeight / 2f
                        if (snapped) {
                            if (!hasVibratedSnap) {
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                hasVibratedSnap = true
                            }
                        } else {
                            hasVibratedSnap = false
                        }
                        invalidate()
                    }
                }

                lastTouchX = touchX
                lastTouchY = touchY
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeHandle == StickerLayer.HANDLE_SCALE && !hasMovedHandle) {
                    val layer = activeLayer
                    if (layer != null) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onCropRequestedListener?.invoke(layer)
                    }
                } else if (hasModifiedInTouch && touchDownSnapshot != null) {
                    undoStack.addLast(touchDownSnapshot!!)
                    if (undoStack.size > maxStackSize) {
                        undoStack.removeFirst()
                    }
                    redoStack.clear()
                    onHistoryChangedListener?.invoke()
                }

                currentMode = MODE_NONE
                activeHandle = StickerLayer.HANDLE_NONE
                hasVibratedSnap = false
                verticalGuideLines.clear()
                horizontalGuideLines.clear()
                onLayersChangedListener?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun checkAlignmentSnapping(layer: StickerLayer, rawX: Float, rawY: Float) {
        verticalGuideLines.clear()
        horizontalGuideLines.clear()

        val halfW = (layer.width * layer.scaleX) / 2f
        val halfH = (layer.height * layer.scaleY) / 2f

        val targetLeft = rawX - halfW
        val targetRight = rawX + halfW
        val targetTop = rawY - halfH
        val targetBottom = rawY + halfH

        var bestDiffX = snapThreshold
        var bestDiffY = snapThreshold
        var snappedCenterX: Float? = null
        var snappedCenterY: Float? = null

        // 1. Canvas Center
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

        // 2. Align with all other layers
        for (other in layers) {
            if (other === layer) continue

            val otherHalfW = (other.width * other.scaleX) / 2f
            val otherHalfH = (other.height * other.scaleY) / 2f
            val otherLeft = other.x - otherHalfW
            val otherRight = other.x + otherHalfW
            val otherTop = other.y - otherHalfH
            val otherBottom = other.y + otherHalfH

            // X-Alignments: Center, Left-Left, Right-Right, Left-Right, Right-Left
            val dcX = Math.abs(rawX - other.x)
            if (dcX < bestDiffX) {
                bestDiffX = dcX
                snappedCenterX = other.x
            }
            val dll = Math.abs(targetLeft - otherLeft)
            if (dll < bestDiffX) {
                bestDiffX = dll
                snappedCenterX = otherLeft + halfW
            }
            val drr = Math.abs(targetRight - otherRight)
            if (drr < bestDiffX) {
                bestDiffX = drr
                snappedCenterX = otherRight - halfW
            }
            val dlr = Math.abs(targetLeft - otherRight)
            if (dlr < bestDiffX) {
                bestDiffX = dlr
                snappedCenterX = otherRight + halfW
            }
            val drl = Math.abs(targetRight - otherLeft)
            if (drl < bestDiffX) {
                bestDiffX = drl
                snappedCenterX = otherLeft - halfW
            }

            // Y-Alignments: Center, Top-Top, Bottom-Bottom, Top-Bottom, Bottom-Top
            val dcY = Math.abs(rawY - other.y)
            if (dcY < bestDiffY) {
                bestDiffY = dcY
                snappedCenterY = other.y
            }
            val dtt = Math.abs(targetTop - otherTop)
            if (dtt < bestDiffY) {
                bestDiffY = dtt
                snappedCenterY = otherTop + halfH
            }
            val dbb = Math.abs(targetBottom - otherBottom)
            if (dbb < bestDiffY) {
                bestDiffY = dbb
                snappedCenterY = otherBottom - halfH
            }
            val dtb = Math.abs(targetTop - otherBottom)
            if (dtb < bestDiffY) {
                bestDiffY = dtb
                snappedCenterY = otherBottom + halfH
            }
            val dbt = Math.abs(targetBottom - otherTop)
            if (dbt < bestDiffY) {
                bestDiffY = dbt
                snappedCenterY = otherTop - halfH
            }
        }

        layer.x = snappedCenterX ?: rawX
        layer.y = snappedCenterY ?: rawY

        // Add guidelines
        val finalLeft = layer.x - halfW
        val finalRight = layer.x + halfW
        val finalTop = layer.y - halfH
        val finalBottom = layer.y + halfH

        if (snappedCenterX != null) {
            if (Math.abs(layer.x - canvasCenterX) < 1.5f) addUniqueGuide(verticalGuideLines, canvasCenterX)
            for (other in layers) {
                if (other === layer) continue
                val otherHalfW = (other.width * other.scaleX) / 2f
                val otherLeft = other.x - otherHalfW
                val otherRight = other.x + otherHalfW
                if (Math.abs(layer.x - other.x) < 1.5f) addUniqueGuide(verticalGuideLines, other.x)
                if (Math.abs(finalLeft - otherLeft) < 1.5f) addUniqueGuide(verticalGuideLines, otherLeft)
                if (Math.abs(finalRight - otherRight) < 1.5f) addUniqueGuide(verticalGuideLines, otherRight)
                if (Math.abs(finalLeft - otherRight) < 1.5f) addUniqueGuide(verticalGuideLines, otherRight)
                if (Math.abs(finalRight - otherLeft) < 1.5f) addUniqueGuide(verticalGuideLines, otherLeft)
            }
        }

        if (snappedCenterY != null) {
            if (Math.abs(layer.y - canvasCenterY) < 1.5f) addUniqueGuide(horizontalGuideLines, canvasCenterY)
            for (other in layers) {
                if (other === layer) continue
                val otherHalfH = (other.height * other.scaleY) / 2f
                val otherTop = other.y - otherHalfH
                val otherBottom = other.y + otherHalfH
                if (Math.abs(layer.y - other.y) < 1.5f) addUniqueGuide(horizontalGuideLines, other.y)
                if (Math.abs(finalTop - otherTop) < 1.5f) addUniqueGuide(horizontalGuideLines, otherTop)
                if (Math.abs(finalBottom - otherBottom) < 1.5f) addUniqueGuide(horizontalGuideLines, otherBottom)
                if (Math.abs(finalTop - otherBottom) < 1.5f) addUniqueGuide(horizontalGuideLines, otherBottom)
                if (Math.abs(finalBottom - otherTop) < 1.5f) addUniqueGuide(horizontalGuideLines, otherTop)
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
        if (layers.isEmpty()) return
        val currentW = if (width > 0) width.toFloat() else targetWidth.toFloat()
        val currentH = if (height > 0) height.toFloat() else targetHeight.toFloat()
        val scaleX = targetWidth / currentW
        val scaleY = targetHeight / currentH
        targetCanvas.save()
        targetCanvas.scale(scaleX, scaleY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (layer in layers) {
            layer.draw(targetCanvas, paint)
        }
        targetCanvas.restore()
    }
}
