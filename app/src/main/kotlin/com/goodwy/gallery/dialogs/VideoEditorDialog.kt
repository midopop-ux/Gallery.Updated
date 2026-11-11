package com.goodwy.gallery.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.view.LayoutInflater
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
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
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_video, null)
        val startTimeInput = dialogView.findViewById<TextInputEditText>(R.id.videoTrimStart)
        val endTimeInput = dialogView.findViewById<TextInputEditText>(R.id.videoTrimEnd)
        val durationLabel = dialogView.findViewById<TextView>(R.id.videoTotalDurationLabel)

        durationLabel.text = "Original Duration: ${formatMs(durationMs)}"
        startTimeInput.setText("00:00.000")
        endTimeInput.setText(formatMs(durationMs))

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

                    val success = performKeyframeTrim(
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

    /**
     * Precision trim using MediaExtractor and MediaMuxer.
     * Ensures video starts from a valid sync frame and PTS offsets are accurately aligned.
     */
    private fun performKeyframeTrim(inputPath: String, outputPath: String, startUs: Long, endUs: Long): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val trackCount = extractor.trackCount
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val indexMap = HashMap<Int, Int>(trackCount)
            var bufferSize = 1024 * 1024
            var videoTrackIndex = -1

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i)
                    val dstIndex = muxer.addTrack(format)
                    indexMap[i] = dstIndex
                    videoTrackIndex = i
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val newSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (newSize > bufferSize) bufferSize = newSize
                    }
                } else if (mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val dstIndex = muxer.addTrack(format)
                    indexMap[i] = dstIndex
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val newSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (newSize > bufferSize) bufferSize = newSize
                    }
                }
            }

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var firstVideoPts = -1L
            var firstAudioPts = -1L
            var seenFirstVideoKeyframe = false

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) {
                    if (trackIndex == videoTrackIndex) {
                        break
                    }
                }

                val flags = extractor.sampleFlags
                val isKeyFrame = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                if (trackIndex == videoTrackIndex) {
                    if (!seenFirstVideoKeyframe) {
                        if (isKeyFrame && sampleTime >= startUs - 100_000L) {
                            seenFirstVideoKeyframe = true
                            firstVideoPts = sampleTime
                        } else if (isKeyFrame && sampleTime >= 0) {
                            seenFirstVideoKeyframe = true
                            firstVideoPts = sampleTime
                        }
                    }
                }

                val allowSample = if (trackIndex == videoTrackIndex) {
                    seenFirstVideoKeyframe && sampleTime <= endUs
                } else {
                    sampleTime in startUs..endUs
                }

                if (allowSample) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break

                    val basePts = if (trackIndex == videoTrackIndex) {
                        if (firstVideoPts >= 0) firstVideoPts else startUs
                    } else {
                        if (firstAudioPts < 0) firstAudioPts = sampleTime
                        firstAudioPts
                    }

                    bufferInfo.presentationTimeUs = (sampleTime - basePts).coerceAtLeast(0L)
                    bufferInfo.flags = flags

                    val dstTrack = indexMap[trackIndex]
                    if (dstTrack != null) {
                        muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                    }
                }

                if (!extractor.advance()) break
            }

            muxer.stop()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            try { File(outputPath).delete() } catch (_: Exception) {}
            return false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
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
