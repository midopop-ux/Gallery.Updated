package com.goodwy.gallery.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.canhub.cropper.CropImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getFilenameFromPath
import com.goodwy.commons.extensions.getParentPath
import com.goodwy.commons.extensions.rescanPaths
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.toast
import com.goodwy.gallery.R
import java.io.File
import java.util.Locale

class VideoEditorDialog(
    private val activity: Activity,
    private val videoPath: String,
    private val onSaved: ((newPath: String) -> Unit)? = null
) {
    private var selectedCropRect: Rect? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        show()
    }

    @SuppressLint("SetTextI18n")
    private fun show() {
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        try {
            retriever.setDataSource(videoPath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_video, null)
        val startTimeInput = dialogView.findViewById<TextInputEditText>(R.id.videoTrimStart)
        val endTimeInput = dialogView.findViewById<TextInputEditText>(R.id.videoTrimEnd)
        val durationLabel = dialogView.findViewById<TextView>(R.id.videoTotalDurationLabel)
        val cropDimensionsLabel = dialogView.findViewById<TextView>(R.id.videoCropDimensionsLabel)
        val btnFreeInteractiveCrop = dialogView.findViewById<MaterialButton>(R.id.btnFreeInteractiveCrop)

        durationLabel.text = "Original Duration: ${formatMs(durationMs)}"
        startTimeInput.setText("00:00.000")
        endTimeInput.setText(formatMs(durationMs))

        if (videoWidth > 0 && videoHeight > 0) {
            cropDimensionsLabel.text = "Crop: Full (${videoWidth}x${videoHeight})"
        }

        btnFreeInteractiveCrop.setOnClickListener {
            openInteractiveCropDialog(cropDimensionsLabel)
        }

        val cropRadioGroup = dialogView.findViewById<RadioGroup>(R.id.videoCropRadioGroup)
        cropRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (videoWidth <= 0 || videoHeight <= 0) return@setOnCheckedChangeListener
            selectedCropRect = when (checkedId) {
                R.id.crop1x1 -> {
                    val side = minOf(videoWidth, videoHeight)
                    val left = (videoWidth - side) / 2
                    val top = (videoHeight - side) / 2
                    Rect(left, top, left + side, top + side)
                }
                R.id.crop16x9 -> {
                    var targetW = videoWidth
                    var targetH = (videoWidth * 9) / 16
                    if (targetH > videoHeight) {
                        targetH = videoHeight
                        targetW = (videoHeight * 16) / 9
                    }
                    val left = (videoWidth - targetW) / 2
                    val top = (videoHeight - targetH) / 2
                    Rect(left, top, left + targetW, top + targetH)
                }
                R.id.crop9x16 -> {
                    var targetW = (videoHeight * 9) / 16
                    var targetH = videoHeight
                    if (targetW > videoWidth) {
                        targetW = videoWidth
                        targetH = (videoWidth * 16) / 9
                    }
                    val left = (videoWidth - targetW) / 2
                    val top = (videoHeight - targetH) / 2
                    Rect(left, top, left + targetW, top + targetH)
                }
                else -> null
            }
            if (selectedCropRect != null) {
                cropDimensionsLabel.text = "Crop: ${selectedCropRect!!.width()}x${selectedCropRect!!.height()}"
            } else {
                cropDimensionsLabel.text = "Crop: Full (${videoWidth}x${videoHeight})"
            }
        }

        val contrastSlider = dialogView.findViewById<Slider>(R.id.videoContrastSlider)
        val contrastLabel = dialogView.findViewById<TextView>(R.id.videoContrastLabel)
        contrastSlider.addOnChangeListener { _, value, _ ->
            contrastLabel.text = "Contrast (${String.format(Locale.US, "%.1fx", value)})"
        }

        val brightnessSlider = dialogView.findViewById<Slider>(R.id.videoBrightnessSlider)
        val brightnessLabel = dialogView.findViewById<TextView>(R.id.videoBrightnessLabel)
        brightnessSlider.addOnChangeListener { _, value, _ ->
            brightnessLabel.text = "Brightness (${value.toInt()})"
        }

        val saturationSlider = dialogView.findViewById<Slider>(R.id.videoSaturationSlider)
        val saturationLabel = dialogView.findViewById<TextView>(R.id.videoSaturationLabel)
        saturationSlider.addOnChangeListener { _, value, _ ->
            saturationLabel.text = "Saturation (${String.format(Locale.US, "%.1fx", value)})"
        }

        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.save) { _, _ ->
                val startMs = parseMs(startTimeInput.text?.toString().orEmpty())
                val endMs = parseMs(endTimeInput.text?.toString().orEmpty()).coerceAtMost(durationMs)
                if (startMs >= endMs) {
                    activity.toast("Start time must be less than end time")
                    return@setPositiveButton
                }

                val parent = videoPath.getParentPath()
                val baseName = videoPath.getFilenameFromPath().substringBeforeLast('.')
                val ext = videoPath.substringAfterLast('.', "mp4")
                val outputPath = File(parent, "${baseName}_edited.$ext").absolutePath

                val progressDialog = ProgressDialog(activity).apply {
                    setMessage(activity.getString(R.string.saving))
                    setCancelable(false)
                    show()
                }

                transformVideo(
                    inputPath = videoPath,
                    outputPath = outputPath,
                    startMs = startMs,
                    endMs = endMs,
                    cropRect = selectedCropRect
                ) { success ->
                    activity.runOnUiThread {
                        try {
                            progressDialog.dismiss()
                        } catch (_: Exception) {}

                        if (success) {
                            activity.rescanPaths(arrayListOf(outputPath)) {
                                activity.toast(R.string.video_saved_successfully)
                                onSaved?.invoke(outputPath)
                            }
                        } else {
                            activity.toast(R.string.failed_to_save_video)
                        }
                    }
                }
            }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)

        activity.setupDialogStuff(dialogView, builder, R.string.edit)
    }

    private fun openInteractiveCropDialog(dimensionLabel: TextView) {
        val retriever = MediaMetadataRetriever()
        var frameBitmap: Bitmap? = null
        try {
            retriever.setDataSource(videoPath)
            frameBitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (videoWidth <= 0 || videoHeight <= 0) {
                videoWidth = frameBitmap?.width ?: 0
                videoHeight = frameBitmap?.height ?: 0
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        if (frameBitmap == null) {
            activity.toast("Could not extract video frame for cropping")
            return
        }

        val cropView = LayoutInflater.from(activity).inflate(R.layout.dialog_interactive_crop, null)
        val cropImageView = cropView.findViewById<CropImageView>(R.id.videoInteractiveCropImageView)
        val btnCancel = cropView.findViewById<MaterialButton>(R.id.btnCancelInteractiveCrop)
        val btnApply = cropView.findViewById<MaterialButton>(R.id.btnApplyInteractiveCrop)

        cropImageView.setImageBitmap(frameBitmap)
        cropImageView.setFixedAspectRatio(false)

        val dialog = activity.getAlertDialogBuilder()
            .setView(cropView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnApply.setOnClickListener {
            val rect = cropImageView.cropRect
            if (rect != null) {
                selectedCropRect = rect
                dimensionLabel.text = "Crop: ${rect.width()}x${rect.height()} (Custom Free)"
            }
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun transformVideo(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
        cropRect: Rect?,
        onComplete: (Boolean) -> Unit
    ) {
        try {
            val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(File(inputPath)))
                .setClippingConfiguration(clippingConfig)
                .build()

            val videoEffects = mutableListOf<Effect>()
            if (cropRect != null && videoWidth > 0 && videoHeight > 0) {
                val left = (cropRect.left.toFloat() / videoWidth) * 2f - 1f
                val right = (cropRect.right.toFloat() / videoWidth) * 2f - 1f
                val top = 1f - (cropRect.top.toFloat() / videoHeight) * 2f
                val bottom = 1f - (cropRect.bottom.toFloat() / videoHeight) * 2f
                videoEffects.add(Crop(left, right, bottom, top))
            }

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val outputFile = File(outputPath)
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val mainHandler = Handler(Looper.getMainLooper())
            val transformer = Transformer.Builder(activity)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        mainHandler.post { onComplete(true) }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        exportException.printStackTrace()
                        mainHandler.post { onComplete(false) }
                    }
                })
                .build()

            mainHandler.post {
                try {
                    transformer.start(editedMediaItem, outputPath)
                } catch (e: Exception) {
                    e.printStackTrace()
                    onComplete(false)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(false)
        }
    }

    companion object {
        fun formatMs(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val millis = ms % 1000
            return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
        }

        fun parseMs(text: String): Long {
            return try {
                val clean = text.trim()
                if (clean.contains(":")) {
                    val parts = clean.split(":")
                    val minutes = parts[0].toLong()
                    val secParts = parts[1].split(".")
                    val seconds = secParts[0].toLong()
                    val millis = if (secParts.size > 1) {
                        secParts[1].padEnd(3, '0').take(3).toLong()
                    } else 0L
                    (minutes * 60 + seconds) * 1000 + millis
                } else {
                    clean.toLong()
                }
            } catch (_: Exception) {
                0L
            }
        }
    }
}
