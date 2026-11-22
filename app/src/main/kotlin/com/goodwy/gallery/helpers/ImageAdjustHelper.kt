package com.goodwy.gallery.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

data class AdjustParameters(
    var brightness: Float = 0f,   // -100 to 100 (0 default)
    var contrast: Float = 0f,     // -100 to 100 (0 default)
    var saturation: Float = 0f,   // -100 to 100 (0 default)
    var warmth: Float = 0f,       // -100 to 100 (0 default)
    var vignette: Float = 0f      // 0 to 100 (0 default)
) {
    fun isDefault(): Boolean {
        return brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f && vignette == 0f
    }

    fun reset() {
        brightness = 0f
        contrast = 0f
        saturation = 0f
        warmth = 0f
        vignette = 0f
    }
}

object ImageAdjustHelper {

    fun createAdjustColorMatrix(params: AdjustParameters): ColorMatrix {
        val totalMatrix = ColorMatrix()

        // 1. Brightness: add scale to rgb offsets
        // brightness: -100..100 -> offset: -255..255
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

        // 2. Contrast:
        // contrast: -100..100 -> scale: 0..2.0
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

        // 3. Saturation:
        // saturation: -100..100 -> sat: 0..2.0
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

        // 4. Warmth / Temperature:
        // warmth > 0 -> more red, less blue (warm)
        // warmth < 0 -> more blue, less red (cool)
        if (params.warmth != 0f) {
            val w = params.warmth / 100f // -1..1
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

    fun applyAdjustments(source: Bitmap, params: AdjustParameters): Bitmap {
        if (params.isDefault()) return source
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = createAdjustColorMatrix(params)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }
}
