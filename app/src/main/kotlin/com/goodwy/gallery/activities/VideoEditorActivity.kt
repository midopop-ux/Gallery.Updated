package com.goodwy.gallery.activities

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.effect.Crop
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.canhub.cropper.CropImageView
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getFilenameFromPath
import com.goodwy.commons.extensions.getParentPath
import com.goodwy.commons.extensions.rescanPaths
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.gallery.R
import com.goodwy.gallery.databinding.ActivityVideoEditorBinding
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class VideoEditorActivity : SimpleActivity() {

    private lateinit var binding: ActivityVideoEditorBinding
    private var videoPath: String = ""
    private var exoPlayer: ExoPlayer? = null
    private var durationMs: Long = 0L
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    private var startCutMs: Long = 0L
    private var endCutMs: Long = 0L

    private var selectedCropRect: Rect? = null
    private var selectedSpeed: Float = 1.0f

    private var isMuted: Boolean = false
    private var isPlaying: Boolean = false

    private val progressHandler = Handler(Looper.getMainLooper())
    private var isDraggingStart = false
    private var isDraggingEnd = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        videoPath = intent.getStringExtra("video_path") ?: intent.data?.path ?: ""
        if (videoPath.isEmpty() || !File(videoPath).exists()) {
            toast(R.string.failed_to_save_video)
            finish()
            return
        }

        extractVideoMetadata()
        setupTopBar()
        setupPlayer()
        setupTimeline()
        setupPrecisionButtons()
        setupBottomTabs()
        setupSaveButton()
        generateFilmstripThumbnails()
    }

    private fun extractVideoMetadata() {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        startCutMs = 0L
        endCutMs = durationMs
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun setupPlayer() {
        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        binding.editorPlayerView.player = player

        val mediaItem = MediaItem.fromUri(Uri.fromFile(File(videoPath)))
        player.setMediaItem(mediaItem)
        player.prepare()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                binding.btnPlayPause.setImageResource(
                    if (playing) R.drawable.ic_pause_vector else R.drawable.ic_play_vector
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(startCutMs)
                    player.pause()
                }
            }
        })

        binding.btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.currentPosition < startCutMs || player.currentPosition >= endCutMs) {
                    player.seekTo(startCutMs)
                }
                player.play()
            }
        }

        binding.btnToggleMute.setOnClickListener {
            isMuted = !isMuted
            player.volume = if (isMuted) 0f else 1f
            binding.btnToggleMute.setImageResource(
                if (isMuted) R.drawable.ic_vector_no_sound else R.drawable.ic_volume_up_vector
            )
        }

        binding.btnFullscreen.setOnClickListener {
            val isFit = binding.editorPlayerView.resizeMode == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            binding.editorPlayerView.resizeMode = if (isFit) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }

        // Loop check inside playback range
        progressHandler.post(object : Runnable {
            override fun run() {
                val current = player.currentPosition
                if (player.isPlaying && current >= endCutMs) {
                    player.seekTo(startCutMs)
                }
                progressHandler.postDelayed(this, 100)
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTimeline() {
        updateTimeLabels()

        binding.timelineContainer.post {
            updateHandlePositions()
        }

        binding.handleStart.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingStart = true
                    exoPlayer?.pause()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingStart && durationMs > 0) {
                        val containerWidth = binding.timelineContainer.width - binding.handleStart.width - binding.handleEnd.width
                        val rawX = event.rawX - binding.timelineContainer.left - (binding.handleStart.width / 2)
                        val ratio = (rawX / containerWidth).coerceIn(0f, 1f)
                        val newStartMs = (ratio * durationMs).toLong()
                        if (newStartMs < endCutMs - 500L) {
                            startCutMs = newStartMs
                            updateHandlePositions()
                            updateTimeLabels()
                            exoPlayer?.seekTo(startCutMs)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingStart = false
                    true
                }
                else -> false
            }
        }

        binding.handleEnd.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingEnd = true
                    exoPlayer?.pause()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingEnd && durationMs > 0) {
                        val containerWidth = binding.timelineContainer.width - binding.handleStart.width - binding.handleEnd.width
                        val rawX = event.rawX - binding.timelineContainer.left - binding.handleStart.width - (binding.handleEnd.width / 2)
                        val ratio = (rawX / containerWidth).coerceIn(0f, 1f)
                        val newEndMs = (ratio * durationMs).toLong()
                        if (newEndMs > startCutMs + 500L) {
                            endCutMs = min(durationMs, newEndMs)
                            updateHandlePositions()
                            updateTimeLabels()
                            exoPlayer?.seekTo(endCutMs)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingEnd = false
                    true
                }
                else -> false
            }
        }
    }

    private fun updateHandlePositions() {
        val containerWidth = binding.timelineContainer.width
        if (containerWidth <= 0 || durationMs <= 0) return

        val handleW = binding.handleStart.width
        val trackWidth = containerWidth - (handleW * 2)

        val startRatio = (startCutMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val endRatio = (endCutMs.toFloat() / durationMs).coerceIn(0f, 1f)

        val startX = (startRatio * trackWidth).toInt()
        val endX = (handleW + (endRatio * trackWidth)).toInt()

        binding.handleStart.translationX = startX.toFloat()
        binding.handleEnd.translationX = endX.toFloat()

        val highlightParams = binding.trimHighlightRect.layoutParams as ViewGroup.MarginLayoutParams
        highlightParams.marginStart = startX
        highlightParams.width = max(0, endX + handleW - startX)
        binding.trimHighlightRect.layoutParams = highlightParams
    }

    private fun updateTimeLabels() {
        binding.tvStartTime.text = formatTimestamp(startCutMs)
        binding.tvEndTime.text = formatTimestamp(endCutMs)
        val total = max(0L, endCutMs - startCutMs)
        binding.tvTotalDuration.text = "Total ${formatTimestamp(total)}"
    }

    private fun setupPrecisionButtons() {
        val stepMs = 200L

        binding.btnStartMinus.setOnClickListener {
            startCutMs = max(0L, startCutMs - stepMs)
            updateHandlePositions()
            updateTimeLabels()
            exoPlayer?.seekTo(startCutMs)
        }

        binding.btnStartPlus.setOnClickListener {
            if (startCutMs + stepMs < endCutMs - 500L) {
                startCutMs += stepMs
                updateHandlePositions()
                updateTimeLabels()
                exoPlayer?.seekTo(startCutMs)
            }
        }

        binding.btnEndMinus.setOnClickListener {
            if (endCutMs - stepMs > startCutMs + 500L) {
                endCutMs -= stepMs
                updateHandlePositions()
                updateTimeLabels()
                exoPlayer?.seekTo(endCutMs)
            }
        }

        binding.btnEndPlus.setOnClickListener {
            endCutMs = min(durationMs, endCutMs + stepMs)
            updateHandlePositions()
            updateTimeLabels()
            exoPlayer?.seekTo(endCutMs)
        }
    }

    private fun setupBottomTabs() {
        binding.tabTrim.setOnClickListener {
            selectTab("trim")
        }

        binding.tabCrop.setOnClickListener {
            selectTab("crop")
            openInteractiveCropDialog()
        }

        binding.tabSpeed.setOnClickListener {
            selectTab("speed")
        }

        binding.speedOptionsGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedSpeed = when (checkedId) {
                R.id.speed05x -> 0.5f
                R.id.speed15x -> 1.5f
                R.id.speed2x -> 2.0f
                else -> 1.0f
            }
            exoPlayer?.setPlaybackSpeed(selectedSpeed)
        }
    }

    private fun selectTab(tab: String) {
        binding.tabTrim.setBackgroundResource(if (tab == "trim") R.drawable.bg_pill_button_selected else R.drawable.bg_pill_button)
        binding.tabCrop.setBackgroundResource(if (tab == "crop") R.drawable.bg_pill_button_selected else R.drawable.bg_pill_button)
        binding.tabSpeed.setBackgroundResource(if (tab == "speed") R.drawable.bg_pill_button_selected else R.drawable.bg_pill_button)

        binding.speedOptionsGroup.visibility = if (tab == "speed") View.VISIBLE else View.GONE
    }

    private fun openInteractiveCropDialog() {
        exoPlayer?.pause()
        val retriever = MediaMetadataRetriever()
        var frameBitmap: Bitmap? = null
        try {
            retriever.setDataSource(videoPath)
            frameBitmap = retriever.getFrameAtTime(startCutMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (videoWidth <= 0 || videoHeight <= 0) {
                videoWidth = frameBitmap?.width ?: 0
                videoHeight = frameBitmap?.height ?: 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        if (frameBitmap == null) {
            toast("Could not extract frame for cropping")
            return
        }

        val cropView = LayoutInflater.from(this).inflate(R.layout.dialog_interactive_crop, null)
        val cropImageView = cropView.findViewById<CropImageView>(R.id.videoInteractiveCropImageView)
        val btnCancel = cropView.findViewById<MaterialButton>(R.id.btnCancelInteractiveCrop)
        val btnApply = cropView.findViewById<MaterialButton>(R.id.btnApplyInteractiveCrop)

        cropImageView.setImageBitmap(frameBitmap)
        cropImageView.setFixedAspectRatio(false)

        val dialog = getAlertDialogBuilder()
            .setView(cropView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnApply.setOnClickListener {
            val rect = cropImageView.cropRect
            if (rect != null) {
                selectedCropRect = rect
                toast("Crop applied: ${rect.width()}x${rect.height()}")
            }
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun generateFilmstripThumbnails() {
        ensureBackgroundThread {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoPath)
                val thumbCount = 7
                val step = if (durationMs > 0) durationMs / thumbCount else 1000L
                val thumbs = mutableListOf<Bitmap>()

                for (i in 0 until thumbCount) {
                    val timeUs = (i * step) * 1000L
                    val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
                        thumbs.add(bmp)
                    }
                }

                runOnUiThread {
                    binding.filmstripLayout.removeAllViews()
                    for (bmp in thumbs) {
                        val iv = ImageView(this@VideoEditorActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setImageBitmap(bmp)
                        }
                        binding.filmstripLayout.addView(iv)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    private fun setupSaveButton() {
        binding.btnSaveEditedVideo.setOnClickListener {
            if (startCutMs >= endCutMs) {
                toast("Start time must be less than end time")
                return@setOnClickListener
            }

            exoPlayer?.pause()

            val parent = videoPath.getParentPath()
            val baseName = videoPath.getFilenameFromPath().substringBeforeLast('.')
            val ext = videoPath.substringAfterLast('.', "mp4")
            val outputPath = File(parent, "${baseName}_edited.$ext").absolutePath

            val progressDialog = ProgressDialog(this).apply {
                setMessage(getString(com.goodwy.commons.R.string.saving))
                setCancelable(false)
                show()
            }

            transformVideo(
                inputPath = videoPath,
                outputPath = outputPath,
                startMs = startCutMs,
                endMs = endCutMs,
                cropRect = selectedCropRect
            ) { success ->
                runOnUiThread {
                    try { progressDialog.dismiss() } catch (_: Exception) {}
                    if (success) {
                        rescanPaths(arrayListOf(outputPath)) {
                            toast(R.string.video_saved_successfully)
                            setResult(RESULT_OK, Intent().apply { putExtra("new_path", outputPath) })
                            finish()
                        }
                    } else {
                        toast(R.string.failed_to_save_video)
                    }
                }
            }
        }
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
            val transformer = Transformer.Builder(this)
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

    private fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
    }
}
