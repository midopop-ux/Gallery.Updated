package com.goodwy.gallery.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

data class AdjustParameters(
    var brightness: Float = 0f,   // -100 to 100
    var highlights: Float = 0f,   // -100 to 100 (Selective bright areas)
    var shadows: Float = 0f,      // -100 to 100 (Selective dark areas)
    var contrast: Float = 0f,     // -100 to 100
    var saturation: Float = 0f,   // -100 to 100
    var warmth: Float = 0f,       // -100 to 100
    var vignette: Float = 0f      // 0 to 100
) {
    fun isDefault(): Boolean {
        return brightness == 0f && highlights == 0f && shadows == 0f &&
                contrast == 0f && saturation == 0f && warmth == 0f && vignette == 0f
    }

    fun reset() {
        brightness = 0f
        highlights = 0f
        shadows = 0f
        contrast = 0f
        saturation = 0f
        warmth = 0f
        vignette = 0f
    }
}

object ImageAdjustHelper {

    fun createAdjustColorMatrix(params: AdjustParameters): ColorMatrix {
        val totalMatrix = ColorMatrix()

        // 1. Overall Brightness
        val bOffset = (params.brightness / 100f) * 255f
        if (bOffset != 0f) {
            val bMatrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, bOffset,
                    0f, 1f, 0f, 0f, bOffset,
                    0f, 0f, 1f, 0f, bOffset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            totalMatrix.postConcat(bMatrix)
        }

        // 2. Contrast
        if (params.contrast != 0f) {
            val cScale = if (params.contrast > 0) {
                1f + (params.contrast / 100f) * 1.5f
            } else {
                1f + (params.contrast / 100f) * 0.8f
            }
            val translate = (-0.5f * cScale + 0.5f) * 255f
            val cMatrix = ColorMatrix(
                floatArrayOf(
                    cScale, 0f, 0f, 0f, translate,
                    0f, cScale, 0f, 0f, translate,
                    0f, 0f, cScale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            totalMatrix.postConcat(cMatrix)
        }

        // 3. Saturation
        if (params.saturation != 0f) {
            val sScale = if (params.saturation > 0) {
                1f + (params.saturation / 100f) * 1.0f
            } else {
                1f + (params.saturation / 100f) * 1.0f
            }
            val sMatrix = ColorMatrix()
            sMatrix.setSaturation(sScale.coerceAtLeast(0f))
            totalMatrix.postConcat(sMatrix)
        }

        // 4. Warmth / Temperature
        if (params.warmth != 0f) {
            val w = params.warmth / 100f
            val rScale = 1f + (w * 0.2f)
            val bScale = 1f - (w * 0.2f)
            val wMatrix = ColorMatrix(
                floatArrayOf(
                    rScale, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, bScale, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            totalMatrix.postConcat(wMatrix)
        }

        return totalMatrix
    }

    /**
     * Applies color matrix plus selective highlight/shadow curves and smooth vignette.
     */
    fun applyAdjustments(source: Bitmap, params: AdjustParameters): Bitmap {
        if (params.isDefault()) return source
        val width = source.width
        val height = source.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 1. First pass: hardware color matrix (Brightness, Contrast, Saturation, Warmth)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = createAdjustColorMatrix(params)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)

        // 2. Second pass: Selective Tone Curve for Highlights & Shadows
        if (params.highlights != 0f || params.shadows != 0f) {
            applySelectiveTone(result, params.highlights, params.shadows)
        }

        // 3. Third pass: Vignette overlay
        if (params.vignette > 0f) {
            applyVignette(canvas, width, height, params.vignette)
        }

        return result
    }

    private fun applySelectiveTone(bitmap: Bitmap, highlights: Float, shadows: Float) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val hlScale = (highlights / 100f) * 50f
        val shScale = (shadows / 100f) * 50f
        val deltaLut = IntArray(256) { lumIdx ->
            val lum = lumIdx / 255f
            val shadowWeight = 1.0f - lum
            val highlightWeight = lum
            (shScale * shadowWeight + hlScale * highlightWeight).toInt()
        }

        for (i in pixels.indices) {
            val color = pixels[i]
            val a = (color ushr 24) and 0xFF
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF

            val lumIdx = (77 * r + 150 * g + 29 * b) ushr 8
            val delta = deltaLut[lumIdx]

            val nr = (r + delta).coerceIn(0, 255)
            val ng = (g + delta).coerceIn(0, 255)
            val nb = (b + delta).coerceIn(0, 255)

            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun applyVignette(canvas: Canvas, width: Int, height: Int, vignetteAmount: Float) {
        val radius = Math.hypot(width.toDouble() / 2, height.toDouble() / 2).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val maxAlpha = ((vignetteAmount / 100f) * 190).toInt().coerceIn(0, 255)

        val colors = intArrayOf(Color.TRANSPARENT, Color.argb(maxAlpha, 0, 0, 0))
        val stops = floatArrayOf(0.45f, 1.0f)

        val gradient = RadialGradient(cx, cy, radius, colors, stops, Shader.TileMode.CLAMP)
        val vPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = gradient
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vPaint)
    }
}
