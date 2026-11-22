package com.goodwy.gallery.models

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import com.goodwy.gallery.helpers.AdjustParameters
import com.zomato.photofilters.imageprocessors.Filter

data class StickerLayerSnapshot(
    val originalFullBitmap: Bitmap,
    val baseUnadjustedBitmap: Bitmap,
    val bitmap: Bitmap,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotation: Float,
    val flippedH: Boolean,
    val opacity: Float,
    val adjustParameters: AdjustParameters,
    val lastCropRect: Rect?,
    val currentFilter: Filter? = null
)

class StickerLayer(
    var bitmap: Bitmap,
    var x: Float,
    var y: Float,
    maxWidth: Float,
    maxHeight: Float
) {
    var originalFullBitmap: Bitmap = bitmap
    var baseUnadjustedBitmap: Bitmap = bitmap
    var adjustParameters: AdjustParameters = AdjustParameters()
    var lastCropRect: Rect? = null
    var currentFilter: Filter? = null

    var width: Float = 0f
    var height: Float = 0f
    var scaleX: Float = 1.0f
    var scaleY: Float = 1.0f
    var rotation: Float = 0.0f
    var flippedH: Boolean = false
    var opacity: Float = 1.0f

    fun createSnapshot(): StickerLayerSnapshot {
        return StickerLayerSnapshot(
            originalFullBitmap = originalFullBitmap,
            baseUnadjustedBitmap = baseUnadjustedBitmap,
            bitmap = bitmap,
            x = x,
            y = y,
            width = width,
            height = height,
            scaleX = scaleX,
            scaleY = scaleY,
            rotation = rotation,
            flippedH = flippedH,
            opacity = opacity,
            adjustParameters = adjustParameters.copy(),
            lastCropRect = lastCropRect?.let { Rect(it) },
            currentFilter = currentFilter
        )
    }

    fun restoreFromSnapshot(snapshot: StickerLayerSnapshot) {
        originalFullBitmap = snapshot.originalFullBitmap
        baseUnadjustedBitmap = snapshot.baseUnadjustedBitmap
        bitmap = snapshot.bitmap
        x = snapshot.x
        y = snapshot.y
        width = snapshot.width
        height = snapshot.height
        scaleX = snapshot.scaleX
        scaleY = snapshot.scaleY
        rotation = snapshot.rotation
        flippedH = snapshot.flippedH
        opacity = snapshot.opacity
        adjustParameters = snapshot.adjustParameters.copy()
        lastCropRect = snapshot.lastCropRect?.let { Rect(it) }
        currentFilter = snapshot.currentFilter
    }

    fun duplicate(offsetX: Float = 40f, offsetY: Float = 40f): StickerLayer {
        val dup = StickerLayer(bitmap, x + offsetX, y + offsetY, width, height)
        dup.originalFullBitmap = originalFullBitmap
        dup.baseUnadjustedBitmap = baseUnadjustedBitmap
        dup.adjustParameters = adjustParameters.copy()
        dup.lastCropRect = lastCropRect?.let { Rect(it) }
        dup.currentFilter = currentFilter
        dup.width = width
        dup.height = height
        dup.scaleX = scaleX
        dup.scaleY = scaleY
        dup.rotation = rotation
        dup.flippedH = flippedH
        dup.opacity = opacity
        return dup
    }

    companion object {
        const val HANDLE_NONE = 0
        const val HANDLE_ROTATE = 1
        const val HANDLE_SCALE = 2
        const val HANDLE_FLIP = 3
        const val HANDLE_CROP = 4
        const val HANDLE_STRETCH_TOP = 5
        const val HANDLE_STRETCH_BOTTOM = 6
        const val HANDLE_STRETCH_LEFT = 7
        const val HANDLE_STRETCH_RIGHT = 8
    }

    init {
        val bW = bitmap.width.toFloat()
        val bH = bitmap.height.toFloat()
        var scale = minOf(maxWidth / bW, maxHeight / bH)
        if (scale > 1.0f) scale = 1.0f
        this.width = bW * scale
        this.height = bH * scale
    }

    fun getTransformMatrix(): Matrix {
        val matrix = Matrix()
        matrix.postTranslate(-width / 2f, -height / 2f)
        if (flippedH) {
            matrix.postScale(-1f, 1f)
        }
        matrix.postScale(scaleX, scaleY)
        matrix.postRotate(rotation)
        matrix.postTranslate(x, y)
        return matrix
    }

    fun draw(canvas: Canvas, paint: Paint) {
        if (bitmap.isRecycled) return
        val origAlpha = paint.alpha
        paint.alpha = (origAlpha * opacity).toInt().coerceIn(0, 255)
        val m = getTransformMatrix()
        val drawMatrix = Matrix()
        drawMatrix.postScale(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
        drawMatrix.postConcat(m)
        canvas.drawBitmap(bitmap, drawMatrix, paint)
        paint.alpha = origAlpha
    }

    fun getTransformedCorners(): Array<PointF> {
        val halfW = (width * scaleX) / 2f
        val halfH = (height * scaleY) / 2f
        val pts = floatArrayOf(
            -halfW, -halfH,
            halfW, -halfH,
            halfW, halfH,
            -halfW, halfH
        )
        val rot = Matrix()
        rot.postRotate(rotation)
        rot.mapPoints(pts)
        return Array(4) { i ->
            PointF(pts[i * 2] + x, pts[i * 2 + 1] + y)
        }
    }

    fun getTransformedEdgeMidpoints(): Array<PointF> {
        val halfW = (width * scaleX) / 2f
        val halfH = (height * scaleY) / 2f
        val pts = floatArrayOf(
            0f, -halfH,
            halfW, 0f,
            0f, halfH,
            -halfW, 0f
        )
        val rot = Matrix()
        rot.postRotate(rotation)
        rot.mapPoints(pts)
        return Array(4) { i ->
            PointF(pts[i * 2] + x, pts[i * 2 + 1] + y)
        }
    }

    fun contains(touchX: Float, touchY: Float): Boolean {
        val dx = touchX - x
        val dy = touchY - y
        val rad = Math.toRadians(-rotation.toDouble())
        val unrotX = (dx * Math.cos(rad) - dy * Math.sin(rad)).toFloat()
        val unrotY = (dx * Math.sin(rad) + dy * Math.cos(rad)).toFloat()
        val halfW = Math.abs((width * scaleX) / 2f)
        val halfH = Math.abs((height * scaleY) / 2f)
        return Math.abs(unrotX) <= halfW && Math.abs(unrotY) <= halfH
    }

    fun getTouchedHandle(touchX: Float, touchY: Float, handleRadius: Float): Int {
        val corners = getTransformedCorners()
        if (dist(touchX, touchY, corners[0].x, corners[0].y) <= handleRadius * 1.5f) return HANDLE_FLIP
        if (dist(touchX, touchY, corners[1].x, corners[1].y) <= handleRadius * 1.5f) return HANDLE_ROTATE
        if (dist(touchX, touchY, corners[2].x, corners[2].y) <= handleRadius * 1.5f) return HANDLE_SCALE
        if (dist(touchX, touchY, corners[3].x, corners[3].y) <= handleRadius * 1.5f) return HANDLE_CROP

        val edges = getTransformedEdgeMidpoints()
        if (dist(touchX, touchY, edges[0].x, edges[0].y) <= handleRadius * 1.3f) return HANDLE_STRETCH_TOP
        if (dist(touchX, touchY, edges[1].x, edges[1].y) <= handleRadius * 1.3f) return HANDLE_STRETCH_RIGHT
        if (dist(touchX, touchY, edges[2].x, edges[2].y) <= handleRadius * 1.3f) return HANDLE_STRETCH_BOTTOM
        if (dist(touchX, touchY, edges[3].x, edges[3].y) <= handleRadius * 1.3f) return HANDLE_STRETCH_LEFT

        return HANDLE_NONE
    }

    fun getBoundingBox(): RectF {
        val corners = getTransformedCorners()
        var minX = corners[0].x
        var maxX = corners[0].x
        var minY = corners[0].y
        var maxY = corners[0].y
        for (i in 1 until 4) {
            if (corners[i].x < minX) minX = corners[i].x
            if (corners[i].x > maxX) maxX = corners[i].x
            if (corners[i].y < minY) minY = corners[i].y
            if (corners[i].y > maxY) maxY = corners[i].y
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }
}
