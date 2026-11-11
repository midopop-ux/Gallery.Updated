package com.goodwy.gallery.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.view.LayoutInflater
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import java.nio.ByteBuffer
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

                ensureBackgroundThread {
                    val parent = videoPath.getParentPath()
                    val baseName = videoPath.getFilenameFromPath().substringBeforeLast('.')
                    val ext = videoPath.substringAfterLast('.', "mp4")
                    val outputPath = File(parent, "${baseName}_trimmed.$ext").absolutePath

                    val success = performSynchronizedTrim(
                        inputPath = videoPath,
                        outputPath = outputPath,
                        startUs = startMs * 1000L,
                        endUs = endMs * 1000L
                    )

                    activity.runOnUiThread {
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
    }

    /**
     * Precision synchronized trim for Video and Audio tracks.
     * Uses independent extractors for audio and video, seeks to synchronization keyframe,
     * guarantees aligned zero PTS, and stops immediately at the cut-off boundary.
     */
    private fun performSynchronizedTrim(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        endUs: Long
    ): Boolean {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(inputPath)

            audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(inputPath)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoDstIndex = -1
            var audioDstIndex = -1
            var videoMaxBufferSize = 1024 * 1024
            var audioMaxBufferSize = 256 * 1024

            val totalTracks = videoExtractor.trackCount
            for (i in 0 until totalTracks) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (size > videoMaxBufferSize) videoMaxBufferSize = size
                    }
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (size > audioMaxBufferSize) audioMaxBufferSize = size
                    }
                }
            }

            if (videoTrackIndex == -1 && audioTrackIndex == -1) {
                return false
            }

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            if (videoTrackIndex != -1) {
                videoExtractor.selectTrack(videoTrackIndex)
                videoDstIndex = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))
            }

            if (audioTrackIndex != -1) {
                audioExtractor.selectTrack(audioTrackIndex)
                audioDstIndex = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIndex))
            }

            muxer.start()

            // Find synchronization keyframe for video
            var effectiveStartUs = startUs
            if (videoTrackIndex != -1) {
                videoExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val syncVideoPts = videoExtractor.sampleTime
                if (syncVideoPts in 0..startUs) {
                    effectiveStartUs = syncVideoPts
                }
            }

            // Write Video track
            var firstVideoPts = -1L
            if (videoTrackIndex != -1) {
                val videoBuffer = ByteBuffer.allocateDirect(videoMaxBufferSize)
                val videoBufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    val sampleTime = videoExtractor.sampleTime
                    if (sampleTime < 0 || sampleTime > endUs) {
                        break
                    }

                    val flags = videoExtractor.sampleFlags
                    val isKeyFrame = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                    if (firstVideoPts < 0) {
                        if (isKeyFrame && sampleTime >= effectiveStartUs) {
                            firstVideoPts = sampleTime
                        } else {
                            if (!videoExtractor.advance()) break
                            continue
                        }
                    }

                    videoBufferInfo.offset = 0
                    videoBufferInfo.size = videoExtractor.readSampleData(videoBuffer, 0)
                    if (videoBufferInfo.size < 0) break

                    videoBufferInfo.presentationTimeUs = (sampleTime - firstVideoPts).coerceAtLeast(0L)
                    videoBufferInfo.flags = flags

                    muxer.writeSampleData(videoDstIndex, videoBuffer, videoBufferInfo)

                    if (!videoExtractor.advance()) break
                }
            }

            // Write Audio track aligned with the video's start
            if (audioTrackIndex != -1) {
                val audioBuffer = ByteBuffer.allocateDirect(audioMaxBufferSize)
                val audioBufferInfo = MediaCodec.BufferInfo()

                val audioStartTarget = if (firstVideoPts >= 0) firstVideoPts else effectiveStartUs
                audioExtractor.seekTo(audioStartTarget, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                var firstAudioPts = -1L
                while (true) {
                    val sampleTime = audioExtractor.sampleTime
                    if (sampleTime < 0 || sampleTime > endUs) {
                        break
                    }

                    if (sampleTime < audioStartTarget) {
                        if (!audioExtractor.advance()) break
                        continue
                    }

                    if (firstAudioPts < 0) {
                        firstAudioPts = sampleTime
                    }

                    audioBufferInfo.offset = 0
                    audioBufferInfo.size = audioExtractor.readSampleData(audioBuffer, 0)
                    if (audioBufferInfo.size < 0) break

                    audioBufferInfo.presentationTimeUs = (sampleTime - firstAudioPts).coerceAtLeast(0L)
                    audioBufferInfo.flags = audioExtractor.sampleFlags

                    muxer.writeSampleData(audioDstIndex, audioBuffer, audioBufferInfo)

                    if (!audioExtractor.advance()) break
                }
            }

            muxer.stop()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            try { File(outputPath).delete() } catch (_: Exception) {}
            return false
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
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
