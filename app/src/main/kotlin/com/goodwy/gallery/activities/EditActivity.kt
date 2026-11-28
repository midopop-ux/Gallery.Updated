package com.goodwy.gallery.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.canhub.cropper.CropImageView
import com.goodwy.commons.dialogs.ColorPickerDialog
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.FileDirItem
import com.goodwy.gallery.BuildConfig
import com.goodwy.gallery.R
import com.goodwy.gallery.adapters.FiltersAdapter
import com.goodwy.gallery.databinding.ActivityEditBinding
import com.goodwy.gallery.dialogs.OtherAspectRatioDialog
import com.goodwy.gallery.dialogs.ResizeDialog
import com.goodwy.gallery.dialogs.SaveAsDialog
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.extensions.writeExif
import com.goodwy.gallery.extensions.ensureWritablePath
import com.goodwy.gallery.extensions.getCompressionFormatFromUri
import com.goodwy.gallery.extensions.readExif
import com.goodwy.gallery.extensions.proposeNewFilePath
import com.goodwy.gallery.extensions.resolveUriScheme
import com.goodwy.gallery.extensions.rescanFolderMediaSync
import com.goodwy.gallery.extensions.showContentDescriptionOnLongClick
import com.goodwy.gallery.extensions.writeBitmapToCache
import com.goodwy.gallery.extensions.fixDateTaken
import com.goodwy.gallery.extensions.mediaDB
import com.goodwy.gallery.extensions.openEditor
import com.goodwy.gallery.extensions.updateDateTimeForPaths
import com.goodwy.gallery.extensions.tweakTimestampByOneSecond
import com.goodwy.gallery.extensions.backupToRecycleBin
import com.goodwy.gallery.models.CanvasOp
import com.goodwy.gallery.models.Medium
import com.goodwy.gallery.models.StickerLayer
import com.goodwy.gallery.helpers.*
import com.goodwy.gallery.interfaces.CanvasListener
import com.goodwy.gallery.models.FilterItem
import com.goodwy.gallery.views.CanvasStateSnapshot
import com.zomato.photofilters.FilterPack
import com.zomato.photofilters.imageprocessors.Filter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.max

class EditActivity : BaseCropActivity(), CanvasListener {
    companion object {
        private const val ASPECT_X = "aspectX"
        private const val ASPECT_Y = "aspectY"
        private const val CROP = "crop"

        // constants for bottom primary action groups
        private const val PRIMARY_ACTION_NONE = 0
        private const val PRIMARY_ACTION_FILTER = 1
        private const val PRIMARY_ACTION_CROP_ROTATE = 2
        private const val PRIMARY_ACTION_DRAW = 3
        private const val PRIMARY_ACTION_ADJUST = 4
        private const val PRIMARY_ACTION_STICKER = 5

        private const val CROP_ROTATE_NONE = 0
        private const val CROP_ROTATE_ASPECT_RATIO = 1

        private const val PICK_STICKER_PHOTO_INTENT = 9912
        private const val PICK_CANVAS_PHOTO_INTENT = 9913
        const val EXTRA_IS_COLLAGE = "is_collage"
        const val EXTRA_COLLAGE_PATHS = "collage_paths"
        private const val MAX_PREVIEW_DIM = 1080
        private const val MAX_SESSION_HISTORY = 25
    }

    private data class EditorSessionSnapshot(
        val baseBitmap: Bitmap?,
        val previewBitmap: Bitmap?,
        val appliedFilter: Filter?,
        val adjustParams: AdjustParameters,
        val canvasOpacity: Float,
        val drawOperations: List<CanvasOp>,
        val stickerSnapshot: CanvasStateSnapshot,
        val collageBgColor: Int,
        val collageBgPhoto: Bitmap?,
        val hasBaseCropApplied: Boolean,
        val resizeWidth: Int,
        val resizeHeight: Int
    )

    private lateinit var eyeDropper: EyeDropper
    private lateinit var saveUri: Uri
    private var uri: Uri? = null
    private var isCollageMode = false
    private var collageBgColor = Color.WHITE
    private var collageBgPhotoBitmap: Bitmap? = null
    private var resizeWidth = 0
    private var resizeHeight = 0
    private var drawColor = 0
    private var lastOtherAspectRatio: Pair<Float, Float>? = null
    private var currPrimaryAction = PRIMARY_ACTION_NONE
    private var currCropRotateAction = CROP_ROTATE_ASPECT_RATIO
    private var currAspectRatio = ASPECT_RATIO_FREE
    private var isCropIntent = false
    private var isEditingWithThirdParty = false
    private var isSharingBitmap = false
    private var oldExif: ExifInterface? = null
    private var initialUntouchedBitmap: Bitmap? = null
    private var filterInitialBitmap: Bitmap? = null
    private var previewBaseBitmap: Bitmap? = null
    private var adjustedPreviewBitmap: Bitmap? = null
    private var currentAppliedFilter: Filter? = null
    private var adjustParameters = AdjustParameters()
    private var hasBaseCropApplied = false
    private var currentAdjustTab = 0 // 0: Brightness..6: Vignette
    private var originalUri: Uri? = null
    private var isEraserOn = false
    private var isEyeDropperOn = false
    private val binding by viewBinding(ActivityEditBinding::inflate)

    private var overwriteRequested = false
    private var originalLastModified = 0L
    private var originalDateTaken = 0L
    private var originalFilePath = ""
    private var croppingStickerLayer: StickerLayer? = null

    private var cropInitialRect: Rect? = null
    private var cropWasModified = false
    private var cropSnapshotBaked = false

    private val sessionUndoStack = ArrayDeque<EditorSessionSnapshot>()
    private val sessionRedoStack = ArrayDeque<EditorSessionSnapshot>()
    private val adjustExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var adjustRenderPending = false
    @Volatile private var adjustRenderRunning = false
    @Volatile private var pendingLayerToRender: StickerLayer? = null
    private var adjustTrackingSnapshot: EditorSessionSnapshot? = null
    private var opacityTrackingSnapshot: EditorSessionSnapshot? = null

    override val cropImageView: CropImageView
        get() = binding.cropImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        isCropIntent = intent.extras?.get(CROP) == "true"
        isCollageMode = intent.getBooleanExtra(EXTRA_IS_COLLAGE, false)
        setupEdgeToEdge(
            padBottomSystem = listOf(
                if (isCropIntent) {
                    binding.bottomEditorCropRotateActions.root
                } else {
                    binding.bottomEditorPrimaryActions.root
                }
            )
        )

        binding.editorCoordinator.background = Color.BLACK.toDrawable()
        binding.editorDrawCanvas.mListener = this
        binding.editorDrawCanvas.onBeforeStrokeListener = {
            pushSessionSnapshot()
        }
        binding.editorDrawCanvas.onStrokeCommittedListener = {
            refreshMenuItems()
        }

        eyeDropper = EyeDropper(binding.editorDrawCanvas) { selectedColor ->
            setColor(selectedColor)
        }

        setupOptionsMenu()
        refreshMenuItems()
        handlePermission(getPermissionToRequest()) {
            if (!it) {
                toast(com.goodwy.commons.R.string.no_storage_permissions)
                finish()
            }
            initEditActivity()
        }
    }

    override fun onResume() {
        super.onResume()
        isEditingWithThirdParty = false
        val getProperPrimaryColor = getProperPrimaryColor()
        binding.bottomEditorDrawActions.bottomDrawWidth.setColors(getProperTextColor(), getProperPrimaryColor, Color.WHITE)
        setupToolbar(binding.editorToolbar, NavigationIcon.Arrow, Color.BLACK)
        setupTopAppBar(binding.editorAppbar, NavigationIcon.Arrow, topBarColor = Color.BLACK)

        binding.editorToolbar.setNavigationOnClickListener {
            if (croppingStickerLayer != null) {
                cancelStickerCrop(keepActive = true)
            } else {
                finish()
            }
        }

        if (baseConfig.topAppBarColorIcon) {
            binding.bottomEditorPrimaryActions.bottomPrimaryCancel.setTextColor(getProperPrimaryColor)
            binding.bottomEditorPrimaryActions.bottomPrimarySave.setTextColor(getProperPrimaryColor)
        }
    }

    override fun onBackPressed() {
        if (croppingStickerLayer != null) {
            cancelStickerCrop(keepActive = true)
            return
        }
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        if (isEditingWithThirdParty) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.editorDrawCanvas.mListener = null
        binding.editorDrawCanvas.onBeforeStrokeListener = null
        binding.editorDrawCanvas.onStrokeCommittedListener = null
        adjustExecutor.shutdownNow()
        ColorModeHelper.resetColorMode(this)
    }

    private fun setupOptionsMenu() {
        binding.editorToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.undo -> {
                    undoSession()
                }
                R.id.redo -> {
                    redoSession()
                }
                R.id.crop_canvas -> {
                    val bounds = binding.freestyleOverlayView.getLayersBoundingBox()
                    if (bounds == null || binding.freestyleOverlayView.layers.isEmpty()) {
                        toast(R.string.no_images_on_canvas)
                        return@setOnMenuItemClickListener true
                    }
                    ensureBackgroundThread {
                        val croppedCollage = getCollageComposedBitmap(autoCropToBounds = true)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                resolveSaveAsPath { path ->
                                    saveBitmapToPath(croppedCollage, path, showSavingToast = true)
                                }
                            }
                        }
                    }
                }
                R.id.save_as -> startSaveFlow(overwrite = false)
                R.id.overwrite_original -> startSaveFlow(overwrite = true)
                R.id.edit -> editWith()
                R.id.share -> shareImage()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.editorToolbar.menu.apply {
            val canUndo = sessionUndoStack.isNotEmpty()
            val canRedo = sessionRedoStack.isNotEmpty()

            findItem(R.id.undo)?.isVisible = !isCropIntent
            findItem(R.id.redo)?.isVisible = !isCropIntent
            toggleUndoVisibility(canUndo)
            toggleRedoVisibility(canRedo)
            findItem(R.id.crop_canvas)?.isVisible = isCollageMode && binding.freestyleOverlayView.layers.isNotEmpty() && croppingStickerLayer == null && currPrimaryAction != PRIMARY_ACTION_CROP_ROTATE
            findItem(R.id.save_as)?.isVisible = isCropIntent || isCollageMode
            findItem(R.id.overwrite_original)?.isVisible = !isCropIntent && !isCollageMode
        }
    }

    private fun initEditActivity() {
        if (isCollageMode) {
            initCollageStudio()
            return
        }

        if (intent.data == null) {
            toast(R.string.invalid_image_path)
            finish()
            return
        }

        uri = intent.data!!
        originalUri = uri
        if (uri!!.scheme != "file" && uri!!.scheme != "content") {
            toast(R.string.unknown_file_location)
            finish()
            return
        }

        val extras = intent.extras
        if (extras?.containsKey(REAL_FILE_PATH) == true) {
            val realPath = intent.extras!!.getString(REAL_FILE_PATH)
            uri = when {
                isPathOnOTG(realPath!!) -> uri
                realPath.startsWith("file:/") -> realPath.toUri()
                else -> Uri.fromFile(File(realPath))
            }
        } else {
            (getRealPathFromURI(uri!!))?.apply {
                uri = Uri.fromFile(File(this))
            }
        }

        saveUri = when {
            extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true
                && extras.get(MediaStore.EXTRA_OUTPUT) is Uri -> extras.get(MediaStore.EXTRA_OUTPUT) as Uri
            else -> uri!!
        }

        try {
            val srcPath = if (extras?.containsKey(REAL_FILE_PATH) == true) {
                extras.getString(REAL_FILE_PATH) ?: ""
            } else {
                getRealPathFromURI(uri!!) ?: uri?.path ?: ""
            }
            if (srcPath.isNotEmpty()) {
                originalFilePath = srcPath
                val file = File(srcPath)
                if (file.exists()) {
                    originalLastModified = file.lastModified()
                }
                val exif = readExif(uri!!)
                val dateString = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
                if (dateString != null) {
                    val format = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.ENGLISH)
                    originalDateTaken = format.parse(dateString)?.time ?: 0L
                }
                if (originalDateTaken == 0L) {
                    originalDateTaken = originalLastModified
                }
            }
        } catch (_: Exception) {}

        if (isCropIntent) {
            binding.bottomEditorPrimaryActions.root.beGone()

            val params = binding.bottomEditorCropRotateActions.root.layoutParams as? ConstraintLayout.LayoutParams
            if (params != null) {
                params.bottomToBottom = binding.activityEditHolder.id
                binding.bottomEditorCropRotateActions.root.layoutParams = params
            }

            binding.editorToolbar.menu.findItem(R.id.overwrite_original).isVisible = false
        }

        loadDefaultImageView()
        setupBottomActions()

        if (config.lastEditorCropAspectRatio == ASPECT_RATIO_OTHER) {
            if (config.lastEditorCropOtherAspectRatioX == 0f) {
                config.lastEditorCropOtherAspectRatioX = 1f
            }

            if (config.lastEditorCropOtherAspectRatioY == 0f) {
                config.lastEditorCropOtherAspectRatioY = 1f
            }

            lastOtherAspectRatio = Pair(config.lastEditorCropOtherAspectRatioX, config.lastEditorCropOtherAspectRatioY)
        }
        updateAspectRatio(config.lastEditorCropAspectRatio)
        binding.cropImageView.guidelines = CropImageView.Guidelines.ON
        binding.bottomAspectRatios.root.beVisible()
    }

    private fun isUriValid(uri: Uri?): Boolean {
        if (uri == null) return false

        return try {
            when (uri.scheme) {
                "file" -> {
                    val file = File(uri.path ?: "")
                    file.exists() && file.isFile && file.length() > 0
                }
                "content" -> {
                    contentResolver.openInputStream(uri)?.use { true } ?: false
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun makePreviewBitmap(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= MAX_PREVIEW_DIM && h <= MAX_PREVIEW_DIM) return source
        val scale = minOf(MAX_PREVIEW_DIM.toFloat() / w, MAX_PREVIEW_DIM.toFloat() / h)
        val pw = (w * scale).roundToInt().coerceAtLeast(1)
        val ph = (h * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, pw, ph, true)
    }

    private fun renderWorkingBaseToView() {
        val previewSrc = previewBaseBitmap ?: filterInitialBitmap ?: return
        binding.editorDrawCanvas.setImageDimensions(
            filterInitialBitmap?.width ?: previewSrc.width,
            filterInitialBitmap?.height ?: previewSrc.height
        )

        var working = previewSrc
        val filter = currentAppliedFilter
        if (filter != null && filter.name != getString(com.goodwy.commons.R.string.none)) {
            val copy = Bitmap.createBitmap(previewSrc)
            working = filter.processFilter(copy) ?: previewSrc
        }
        if (!adjustParameters.isDefault()) {
            working = ImageAdjustHelper.applyAdjustments(working, adjustParameters)
        }
        adjustedPreviewBitmap = working
        binding.defaultImageView.setImageBitmap(working)
        binding.defaultImageView.alpha = canvasOpacity
    }

    private fun loadDefaultImageView() {
        if (isCollageMode) {
            binding.defaultImageView.beVisible()
            binding.cropImageView.beGone()
            renderWorkingBaseToView()
            refreshMenuItems()
            return
        }

        if (filterInitialBitmap != null && !filterInitialBitmap!!.isRecycled) {
            binding.defaultImageView.beVisible()
            binding.cropImageView.beGone()
            renderWorkingBaseToView()
            refreshMenuItems()
            return
        }

        if (!isUriValid(uri)) {
            showErrorToast(getString(R.string.invalid_image_path))
            finish()
            return
        }

        binding.defaultImageView.beVisible()
        binding.cropImageView.beGone()
        refreshMenuItems()

        val options = RequestOptions()
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)

        Glide.with(this)
            .asBitmap()
            .load(uri)
            .apply(options)
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean): Boolean {
                    ColorModeHelper.resetColorMode(this@EditActivity)
                    if (uri != originalUri) {
                        uri = originalUri
                        Handler(Looper.getMainLooper()).postDelayed({
                            loadDefaultImageView()
                        }, 100)
                    }
                    return false
                }

                override fun onResourceReady(
                    bitmap: Bitmap,
                    model: Any,
                    target: Target<Bitmap>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    ColorModeHelper.setColorModeForImage(this@EditActivity, bitmap, config.ultraHdrRendering)
                    initialUntouchedBitmap = bitmap
                    filterInitialBitmap = bitmap
                    previewBaseBitmap = makePreviewBitmap(bitmap)
                    binding.editorDrawCanvas.setImageDimensions(bitmap.width, bitmap.height)

                    if (isCropIntent) {
                        binding.bottomEditorPrimaryActions.bottomPrimaryFilter.beGone()
                        binding.bottomEditorPrimaryActions.bottomPrimaryDraw.beGone()
                        binding.bottomEditorPrimaryActions.bottomPrimaryAdjust.beGone()
                        binding.bottomEditorPrimaryActions.bottomPrimarySticker.beGone()
                        loadCropImageView()
                        bottomCropRotateClicked()
                    } else {
                        binding.defaultImageView.onGlobalLayout {
                            updateFloatingLayerBar(binding.freestyleOverlayView.activeLayer)
                            if (currPrimaryAction == PRIMARY_ACTION_NONE) {
                                bottomFilterClicked()
                            } else {
                                renderWorkingBaseToView()
                            }
                        }
                    }

                    return false
                }
            }).into(binding.defaultImageView)
    }

    private fun hasAnyOverlays(): Boolean {
        return binding.editorDrawCanvas.hasStrokes() || binding.freestyleOverlayView.layers.isNotEmpty()
    }

    private fun loadCropImageView() {
        binding.defaultImageView.beGone()
        binding.editorDrawCanvas.beGone()
        binding.freestyleOverlayView.beGone()
        binding.floatingLayerOpacityBar.beGone()
        binding.btnCompareBeforeAfter.beGone()
        refreshMenuItems()

        val activeLayer = binding.freestyleOverlayView.activeLayer
        if (activeLayer != null) {
            startCroppingSticker(activeLayer)
            return
        }

        cropWasModified = false
        cropInitialRect = null
        val overlaysPresent = hasAnyOverlays() || isCollageMode || currentAppliedFilter != null || !adjustParameters.isDefault() || canvasOpacity < 1.0f
        cropSnapshotBaked = overlaysPresent

        binding.cropImageView.apply {
            beVisible()
            if (overlaysPresent || hasBaseCropApplied) {
                setImageBitmap(renderFinalBitmapSync(fullResolution = false, autoCropToBounds = false))
            } else if (isUriValid(uri)) {
                setImageUriAsync(uri)
            } else if (filterInitialBitmap != null) {
                setImageBitmap(filterInitialBitmap)
            }
            guidelines = CropImageView.Guidelines.ON

            if (isCropIntent && shouldCropSquare()) {
                currAspectRatio = ASPECT_RATIO_ONE_ONE
                setFixedAspectRatio(true)
                binding.bottomEditorCropRotateActions.bottomAspectRatio.beGone()
            }

            post {
                cropInitialRect = cropRect?.let { Rect(it) }
            }
        }
    }

    private fun commitBaseCropIfModified(onDone: () -> Unit) {
        if (isCropIntent || croppingStickerLayer != null || !binding.cropImageView.isVisible()) {
            binding.cropImageView.beGone()
            binding.cropImageView.setImageBitmap(null)
            onDone()
            return
        }

        val currentRect = binding.cropImageView.cropRect
        val rotChanged = binding.cropImageView.rotatedDegrees != 0
        val rectChanged = cropInitialRect != null && currentRect != null && currentRect != cropInitialRect

        if (!cropWasModified && !rotChanged && !rectChanged) {
            binding.cropImageView.beGone()
            binding.cropImageView.setImageBitmap(null)
            onDone()
            return
        }

        lifecycleScope.launch {
            val cropped = withContext(Dispatchers.Default) {
                try {
                    binding.cropImageView.getCroppedImage()
                } catch (_: Exception) {
                    null
                }
            }
            if (!isFinishing && !isDestroyed) {
                if (cropped != null) {
                    pushSessionSnapshot()
                    filterInitialBitmap = cropped
                    previewBaseBitmap = makePreviewBitmap(cropped)
                    hasBaseCropApplied = true
                    resizeWidth = 0
                    resizeHeight = 0

                    if (cropSnapshotBaked) {
                        currentAppliedFilter = null
                        adjustParameters = AdjustParameters()
                        canvasOpacity = 1.0f
                        binding.editorDrawCanvas.clearCanvas()
                        binding.freestyleOverlayView.layers.clear()
                        binding.freestyleOverlayView.activeLayer = null
                        if (isCollageMode) {
                            collageBgPhotoBitmap = cropped
                        }
                        // Reset filter thumbnails adapter to reflect newly cropped base
                        binding.bottomEditorFilterActions.bottomActionsFilterList.adapter = null
                    }
                    binding.editorDrawCanvas.setImageDimensions(cropped.width, cropped.height)
                }
                binding.cropImageView.beGone()
                binding.cropImageView.setImageBitmap(null)
                onDone()
            }
        }
    }

    private fun loadDrawCanvas() {
        binding.cropImageView.beGone()
        binding.defaultImageView.beVisible()
        binding.editorDrawCanvas.beVisible()
        binding.editorDrawCanvas.isDrawingEnabled = true

        val baseW = filterInitialBitmap?.width ?: previewBaseBitmap?.width ?: 1440
        val baseH = filterInitialBitmap?.height ?: previewBaseBitmap?.height ?: 1920
        binding.editorDrawCanvas.setImageDimensions(baseW, baseH)
        binding.editorDrawCanvas.invalidate()
        refreshMenuItems()
    }

    private fun setOldExif() {
        if (!isCollageMode && uri != null) {
            oldExif = readExif(uri!!)
        }
    }

    private fun startSaveFlow(overwrite: Boolean) {
        if (croppingStickerLayer != null) {
            cropImage()
            return
        }
        overwriteRequested = overwrite
        setOldExif()
        when {
            binding.cropImageView.isVisible() -> cropImage()
            else -> saveFilteredImage(overwrite)
        }
    }

    override fun onImageCropped(bitmap: Bitmap?, error: Exception?) {
        if (isFinishing || isDestroyed) return
        if (error != null || bitmap == null) {
            toast("${getString(R.string.image_editing_failed)}: ${error?.message}")
            return
        }

        if (croppingStickerLayer != null) {
            val targetLayer = croppingStickerLayer!!
            targetLayer.lastCropRect = binding.cropImageView.cropRect
            croppingStickerLayer = null

            pushSessionSnapshot()

            targetLayer.baseUnadjustedBitmap = bitmap
            val filtered = if (targetLayer.currentFilter == null) {
                bitmap
            } else {
                val copy = Bitmap.createBitmap(bitmap)
                targetLayer.currentFilter!!.processFilter(copy) ?: bitmap
            }
            if (targetLayer.adjustParameters.isDefault()) {
                targetLayer.bitmap = filtered
            } else {
                targetLayer.bitmap = ImageAdjustHelper.applyAdjustments(filtered, targetLayer.adjustParameters)
            }

            val newRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            targetLayer.height = targetLayer.width / newRatio

            cancelStickerCrop(keepActive = true)
            return
        }

        val finalBitmap = if (resizeWidth > 0 && resizeHeight > 0) {
            try {
                Bitmap.createScaledBitmap(bitmap, resizeWidth, resizeHeight, true)
            } catch (e: Exception) {
                bitmap
            }
        } else {
            bitmap
        }

        setOldExif()

        if (isSharingBitmap) {
            isSharingBitmap = false
            shareBitmap(finalBitmap)
            return
        }

        if (isCropIntent) {
            resolveUriScheme(
                uri = saveUri,
                onPath = { saveBitmapToPath(finalBitmap, it, true) },
                onContentUri = {
                    saveBitmapToContentUri(finalBitmap, it, showSavingToast = true, isCropCommit = true)
                }
            )
            return
        }

        saveBitmap(overwriteRequested, finalBitmap, showSavingToast = true)
    }

    private fun getOriginalBitmap(): Bitmap {
        if (isCollageMode) {
            val base = Bitmap.createBitmap(1440, 1920, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(base)
            val bgPhoto = collageBgPhotoBitmap
            if (bgPhoto != null && !bgPhoto.isRecycled) {
                val scaled = Bitmap.createScaledBitmap(bgPhoto, 1440, 1920, true)
                canvas.drawBitmap(scaled, 0f, 0f, null)
            } else if (collageBgColor != Color.TRANSPARENT) {
                canvas.drawColor(collageBgColor)
            }
            return base
        }

        if (hasBaseCropApplied && filterInitialBitmap != null && !filterInitialBitmap!!.isRecycled) {
            return filterInitialBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
        }

        return Glide.with(applicationContext)
            .asBitmap()
            .load(uri)
            .submit(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
            .get()
    }

    private fun getBaseDisplayRectInOverlay(): RectF? {
        val overlay = binding.freestyleOverlayView
        val vw = overlay.width.toFloat()
        val vh = overlay.height.toFloat()
        val bw = (filterInitialBitmap?.width ?: previewBaseBitmap?.width ?: 0).toFloat()
        val bh = (filterInitialBitmap?.height ?: previewBaseBitmap?.height ?: 0).toFloat()
        if (vw <= 1f || vh <= 1f || bw <= 1f || bh <= 1f) return null
        val scale = minOf(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    private fun renderFinalBitmapSync(fullResolution: Boolean, autoCropToBounds: Boolean = false): Bitmap {
        if (isCollageMode && autoCropToBounds && binding.freestyleOverlayView.layers.isNotEmpty()) {
            return getCollageComposedBitmap(autoCropToBounds = true)
        }

        var original = if (fullResolution) {
            getOriginalBitmap()
        } else {
            val src = previewBaseBitmap ?: filterInitialBitmap ?: getOriginalBitmap()
            src.copy(Bitmap.Config.ARGB_8888, true)
        }

        val activeFilter = currentAppliedFilter ?: getFiltersAdapter()?.getCurrentFilter()?.filter
        if (activeFilter != null && activeFilter.name != getString(com.goodwy.commons.R.string.none)) {
            if (!original.isMutable) {
                original = original.copy(Bitmap.Config.ARGB_8888, true)
            }
            activeFilter.processFilter(original)
        }

        if (!adjustParameters.isDefault()) {
            original = ImageAdjustHelper.applyAdjustments(original, adjustParameters)
        }

        val hasCanvasImage = !isCollageMode || (collageBgPhotoBitmap != null)
        if (hasCanvasImage && canvasOpacity < 1.0f) {
            val transparentBase = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(transparentBase)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            p.alpha = (canvasOpacity * 255).toInt().coerceIn(0, 255)
            c.drawBitmap(original, 0f, 0f, p)
            original = transparentBase
        }

        if (!original.isMutable) {
            original = original.copy(Bitmap.Config.ARGB_8888, true)
        }
        val canvas = Canvas(original)

        if (binding.freestyleOverlayView.layers.isNotEmpty()) {
            val sourceRect = getBaseDisplayRectInOverlay()
            binding.freestyleOverlayView.renderLayersToCanvasAligned(canvas, original.width, original.height, sourceRect)
        }

        if (binding.editorDrawCanvas.hasStrokes()) {
            binding.editorDrawCanvas.renderStrokesToCanvas(canvas, original.width, original.height)
        }

        return original
    }

    private fun withFilteredImage(callback: (Bitmap) -> Unit) {
        freeMemory()
        ensureBackgroundThread {
            try {
                val composed = renderFinalBitmapSync(fullResolution = true, autoCropToBounds = false)
                callback(composed)
            } catch (_: OutOfMemoryError) {
                runOnUiThread { toast(com.goodwy.commons.R.string.out_of_memory_error) }
            }
        }
    }

    private fun saveFilteredImage(overwrite: Boolean) {
        if (overwrite) {
            withFilteredImage {
                saveBitmap(true, it)
            }
        } else {
            resolveSaveAsPath { path ->
                withFilteredImage {
                    saveBitmapToPath(it, path, showSavingToast = true)
                }
            }
        }
    }

    private fun shareImage() {
        if (croppingStickerLayer != null || binding.cropImageView.isVisible()) {
            isSharingBitmap = true
            cropImage()
            return
        }

        ensureBackgroundThread {
            try {
                val bitmapToShare = renderFinalBitmapSync(fullResolution = true, autoCropToBounds = false)
                shareBitmap(bitmapToShare)
            } catch (_: OutOfMemoryError) {
                runOnUiThread { toast(com.goodwy.commons.R.string.out_of_memory_error) }
            } catch (e: Exception) {
                runOnUiThread { showErrorToast(e) }
            }
        }
    }

    private fun shareBitmap(bitmap: Bitmap) {
        writeBitmapToCache(saveUri, bitmap) {
            if (it != null) {
                sharePathIntent(it, BuildConfig.APPLICATION_ID)
            } else {
                toast(com.goodwy.commons.R.string.unknown_error_occurred)
            }
        }
    }

    private fun getFiltersAdapter(): FiltersAdapter? {
        return binding.bottomEditorFilterActions.bottomActionsFilterList.adapter as? FiltersAdapter
    }

    private fun setupBottomActions() {
        setupPrimaryActionButtons()
        setupCropRotateActionButtons()
        setupAspectRatioButtons()
        setupDrawButtons()
        setupFloatingLayerBar()
    }

    private fun setupPrimaryActionButtons() {
        binding.bottomEditorPrimaryActions.bottomPrimaryFilter.setOnClickListener {
            bottomFilterClicked()
        }

        binding.bottomEditorPrimaryActions.bottomPrimaryCropRotate.setOnClickListener {
            bottomCropRotateClicked()
        }

        binding.bottomEditorPrimaryActions.bottomPrimaryDraw.setOnClickListener {
            bottomDrawClicked()
        }

        binding.bottomEditorPrimaryActions.bottomPrimaryAdjust.setOnClickListener {
            bottomAdjustClicked()
        }

        binding.bottomEditorPrimaryActions.bottomPrimarySticker.setOnClickListener {
            bottomStickerClicked()
        }

        binding.bottomEditorPrimaryActions.bottomPrimaryCancel.setOnClickListener {
            if (croppingStickerLayer != null) {
                cancelStickerCrop(keepActive = true)
            } else {
                finish()
            }
        }

        binding.bottomEditorPrimaryActions.bottomPrimarySave.setOnClickListener {
            if (croppingStickerLayer != null) {
                cropImage()
            } else {
                startSaveFlow(overwrite = false)
            }
        }

        arrayOf(
            binding.bottomEditorPrimaryActions.bottomPrimaryFilter,
            binding.bottomEditorPrimaryActions.bottomPrimaryCropRotate,
            binding.bottomEditorPrimaryActions.bottomPrimaryDraw,
            binding.bottomEditorPrimaryActions.bottomPrimaryAdjust,
            binding.bottomEditorPrimaryActions.bottomPrimarySticker
        ).forEach {
            it.showContentDescriptionOnLongClick()
        }

        setupAdjustControls()
        setupStickerControls()
        setupCompareButton()
    }

    private fun switchPrimaryAction(targetAction: Int) {
        if (croppingStickerLayer != null) {
            cancelStickerCrop(keepActive = targetAction == PRIMARY_ACTION_STICKER || targetAction == PRIMARY_ACTION_CROP_ROTATE)
        }

        if (currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE && targetAction != PRIMARY_ACTION_CROP_ROTATE) {
            commitBaseCropIfModified {
                currPrimaryAction = targetAction
                updatePrimaryActionButtons()
            }
            return
        }

        currPrimaryAction = targetAction
        updatePrimaryActionButtons()
    }

    private fun bottomFilterClicked() {
        val next = if (currPrimaryAction == PRIMARY_ACTION_FILTER) PRIMARY_ACTION_NONE else PRIMARY_ACTION_FILTER
        if (next == PRIMARY_ACTION_FILTER && isCollageMode && collageBgPhotoBitmap == null &&
            binding.freestyleOverlayView.activeLayer == null && binding.freestyleOverlayView.layers.isNotEmpty()
        ) {
            binding.freestyleOverlayView.activeLayer = binding.freestyleOverlayView.layers.last()
            binding.freestyleOverlayView.invalidate()
        }
        switchPrimaryAction(next)
    }

    private fun bottomCropRotateClicked() {
        if (croppingStickerLayer != null) {
            cancelStickerCrop(keepActive = true)
            return
        }
        val activeLayer = binding.freestyleOverlayView.activeLayer
        if (activeLayer != null && currPrimaryAction != PRIMARY_ACTION_CROP_ROTATE) {
            startCroppingSticker(activeLayer)
            return
        }
        val next = if (currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE) PRIMARY_ACTION_NONE else PRIMARY_ACTION_CROP_ROTATE
        switchPrimaryAction(next)
    }

    private fun bottomDrawClicked() {
        val next = if (currPrimaryAction == PRIMARY_ACTION_DRAW) PRIMARY_ACTION_NONE else PRIMARY_ACTION_DRAW
        switchPrimaryAction(next)
    }

    private fun bottomAdjustClicked() {
        val next = if (currPrimaryAction == PRIMARY_ACTION_ADJUST) PRIMARY_ACTION_NONE else PRIMARY_ACTION_ADJUST
        if (next == PRIMARY_ACTION_ADJUST && isCollageMode && collageBgPhotoBitmap == null &&
            binding.freestyleOverlayView.activeLayer == null && binding.freestyleOverlayView.layers.isNotEmpty()
        ) {
            binding.freestyleOverlayView.activeLayer = binding.freestyleOverlayView.layers.last()
            binding.freestyleOverlayView.invalidate()
        }
        switchPrimaryAction(next)
        if (next == PRIMARY_ACTION_ADJUST) {
            updateAdjustSliderUI()
        }
    }

    private fun bottomStickerClicked() {
        val next = if (currPrimaryAction == PRIMARY_ACTION_STICKER) PRIMARY_ACTION_NONE else PRIMARY_ACTION_STICKER
        if (next == PRIMARY_ACTION_STICKER && isCollageMode && collageBgPhotoBitmap == null &&
            binding.freestyleOverlayView.activeLayer == null && binding.freestyleOverlayView.layers.isNotEmpty()
        ) {
            binding.freestyleOverlayView.activeLayer = binding.freestyleOverlayView.layers.last()
            binding.freestyleOverlayView.invalidate()
        }
        switchPrimaryAction(next)
    }

    private fun setupCropRotateActionButtons() {
        binding.bottomEditorCropRotateActions.bottomRotate.setOnClickListener {
            cropWasModified = true
            binding.cropImageView.rotateImage(90)
        }

        binding.bottomEditorCropRotateActions.bottomResize.beGoneIf(isCropIntent)
        binding.bottomEditorCropRotateActions.bottomResize.setOnClickListener {
            resizeImage()
        }

        binding.bottomEditorCropRotateActions.bottomFlipHorizontally.setOnClickListener {
            cropWasModified = true
            binding.cropImageView.flipImageHorizontally()
        }

        binding.bottomEditorCropRotateActions.bottomFlipVertically.setOnClickListener {
            cropWasModified = true
            binding.cropImageView.flipImageVertically()
        }

        binding.bottomEditorCropRotateActions.bottomAspectRatio.setOnClickListener {
            currCropRotateAction = if (currCropRotateAction == CROP_ROTATE_ASPECT_RATIO) {
                binding.cropImageView.guidelines = CropImageView.Guidelines.OFF
                binding.bottomAspectRatios.root.beGone()
                CROP_ROTATE_NONE
            } else {
                binding.cropImageView.guidelines = CropImageView.Guidelines.ON
                binding.bottomAspectRatios.root.beVisible()
                CROP_ROTATE_ASPECT_RATIO
            }
            updateCropRotateActionButtons()
        }

        arrayOf(
            binding.bottomEditorCropRotateActions.bottomRotate,
            binding.bottomEditorCropRotateActions.bottomResize,
            binding.bottomEditorCropRotateActions.bottomFlipHorizontally,
            binding.bottomEditorCropRotateActions.bottomFlipVertically,
            binding.bottomEditorCropRotateActions.bottomAspectRatio
        ).forEach {
            it.showContentDescriptionOnLongClick()
        }
    }

    private fun setupAspectRatioButtons() {
        binding.bottomAspectRatios.bottomAspectRatioFree.setOnClickListener {
            updateAspectRatio(ASPECT_RATIO_FREE)
        }

        binding.bottomAspectRatios.bottomAspectRatioOneOne.setOnClickListener {
            updateAspectRatio(ASPECT_RATIO_ONE_ONE)
        }

        binding.bottomAspectRatios.bottomAspectRatioFourThree.setOnClickListener {
            updateAspectRatio(ASPECT_RATIO_FOUR_THREE)
        }

        binding.bottomAspectRatios.bottomAspectRatioSixteenNine.setOnClickListener {
            updateAspectRatio(ASPECT_RATIO_SIXTEEN_NINE)
        }

        binding.bottomAspectRatios.bottomAspectRatioOther.setOnClickListener {
            OtherAspectRatioDialog(this, lastOtherAspectRatio) {
                lastOtherAspectRatio = it
                config.lastEditorCropOtherAspectRatioX = it.first
                config.lastEditorCropOtherAspectRatioY = it.second
                updateAspectRatio(ASPECT_RATIO_OTHER)
            }
        }

        updateAspectRatioButtons()
    }

    private fun setupDrawButtons() {
        updateDrawColor(config.lastEditorDrawColor)
        binding.bottomEditorDrawActions.bottomDrawWidth.progress = config.lastEditorBrushSize
        updateBrushSize(config.lastEditorBrushSize)

        binding.bottomEditorDrawActions.bottomDrawColorClickable.setOnClickListener {
            ColorPickerDialog(this, drawColor) { wasPositivePressed, color, _ ->
                if (wasPositivePressed) {
                    updateDrawColor(color)
                }
            }
        }

        binding.bottomEditorDrawActions.bottomDrawWidth.onSeekBarChangeListener {
            config.lastEditorBrushSize = it
            updateBrushSize(it)
        }

        binding.bottomEditorDrawActions.bottomDrawUndo.setOnClickListener {
            undoSession()
        }

        binding.bottomEditorDrawActions.bottomDrawRedo.setOnClickListener {
            redoSession()
        }

        binding.bottomEditorDrawActions.bottomDrawEraser.setOnClickListener { eraserClicked() }
        binding.bottomEditorDrawActions.bottomDrawEraser.setOnLongClickListener {
            toast(R.string.eraser)
            true
        }

        binding.bottomEditorDrawActions.bottomDrawEyeDropper.setOnClickListener { eyeDropperClicked() }
        binding.bottomEditorDrawActions.bottomDrawEyeDropper.setOnLongClickListener {
            toast(R.string.eyedropper)
            true
        }
    }

    private fun updateBrushSize(percent: Int) {
        binding.editorDrawCanvas.updateBrushSize(percent)
        val scale = max(0.03f, percent / 100f)
        binding.bottomEditorDrawActions.bottomDrawColor.scaleX = scale
        binding.bottomEditorDrawActions.bottomDrawColor.scaleY = scale
    }

    private fun updatePrimaryActionButtons() {
        if (binding.cropImageView.isGone() && currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE && croppingStickerLayer == null) {
            loadCropImageView()
        } else if (currPrimaryAction == PRIMARY_ACTION_DRAW) {
            loadDrawCanvas()
        } else if (currPrimaryAction == PRIMARY_ACTION_STICKER) {
            loadStickerCanvas()
        } else if (currPrimaryAction != PRIMARY_ACTION_CROP_ROTATE && croppingStickerLayer == null) {
            loadDefaultImageView()
        }

        val isCropActive = currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE || croppingStickerLayer != null
        binding.editorDrawCanvas.isDrawingEnabled = (currPrimaryAction == PRIMARY_ACTION_DRAW) && !isCropActive
        binding.editorDrawCanvas.beVisibleIf((currPrimaryAction == PRIMARY_ACTION_DRAW || binding.editorDrawCanvas.hasStrokes()) && !isCropActive)
        binding.freestyleOverlayView.beVisibleIf((isCollageMode || currPrimaryAction == PRIMARY_ACTION_STICKER || binding.freestyleOverlayView.layers.isNotEmpty()) && !isCropActive)
        binding.btnCompareBeforeAfter.beVisibleIf(!isCropActive && !isCropIntent)

        arrayOf(
            binding.bottomEditorPrimaryActions.bottomPrimaryFilter,
            binding.bottomEditorPrimaryActions.bottomPrimaryCropRotate,
            binding.bottomEditorPrimaryActions.bottomPrimaryDraw,
            binding.bottomEditorPrimaryActions.bottomPrimaryAdjust,
            binding.bottomEditorPrimaryActions.bottomPrimarySticker
        ).forEach {
            it.applyColorFilter(Color.WHITE)
        }

        val currentPrimaryActionButton = when (currPrimaryAction) {
            PRIMARY_ACTION_FILTER -> binding.bottomEditorPrimaryActions.bottomPrimaryFilter
            PRIMARY_ACTION_CROP_ROTATE -> binding.bottomEditorPrimaryActions.bottomPrimaryCropRotate
            PRIMARY_ACTION_DRAW -> binding.bottomEditorPrimaryActions.bottomPrimaryDraw
            PRIMARY_ACTION_ADJUST -> binding.bottomEditorPrimaryActions.bottomPrimaryAdjust
            PRIMARY_ACTION_STICKER -> binding.bottomEditorPrimaryActions.bottomPrimarySticker
            else -> null
        }

        currentPrimaryActionButton?.applyColorFilter(getProperPrimaryColor())
        binding.bottomEditorFilterActions.root.beVisibleIf(currPrimaryAction == PRIMARY_ACTION_FILTER)
        binding.bottomEditorCropRotateActions.root.beVisibleIf(currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE || croppingStickerLayer != null)
        binding.bottomEditorDrawActions.root.beVisibleIf(currPrimaryAction == PRIMARY_ACTION_DRAW)
        binding.bottomEditorAdjustActions.root.beVisibleIf(currPrimaryAction == PRIMARY_ACTION_ADJUST)
        binding.bottomEditorStickerActions.root.beVisibleIf(currPrimaryAction == PRIMARY_ACTION_STICKER)

        if (currPrimaryAction == PRIMARY_ACTION_STICKER && binding.freestyleOverlayView.activeLayer == null &&
            binding.freestyleOverlayView.layers.isNotEmpty() && !(isCollageMode && collageBgPhotoBitmap != null)
        ) {
            binding.freestyleOverlayView.activeLayer = binding.freestyleOverlayView.layers.last()
            binding.freestyleOverlayView.invalidate()
        }

        if (currPrimaryAction == PRIMARY_ACTION_FILTER) {
            val activeLayer = binding.freestyleOverlayView.activeLayer
            val targetLayer = activeLayer ?: if (isCollageMode && collageBgPhotoBitmap == null) binding.freestyleOverlayView.layers.lastOrNull() else null
            if (targetLayer != null) {
                if (binding.freestyleOverlayView.activeLayer == null) {
                    binding.freestyleOverlayView.activeLayer = targetLayer
                }
                setupFiltersAdapterForLayer(targetLayer)
            } else {
                setupFiltersAdapterForBase()
            }
        }

        if (currPrimaryAction == PRIMARY_ACTION_ADJUST) {
            updateAdjustSliderUI()
        }

        if (currPrimaryAction != PRIMARY_ACTION_CROP_ROTATE && croppingStickerLayer == null) {
            binding.bottomAspectRatios.root.beGone()
            currCropRotateAction = CROP_ROTATE_NONE
        }
        updateCropRotateActionButtons()
        updateFloatingLayerBar(binding.freestyleOverlayView.activeLayer)
        refreshMenuItems()
    }

    private var lastFilterLayer: StickerLayer? = null

    private fun setupFiltersAdapterForBase() {
        if (lastFilterLayer == null && binding.bottomEditorFilterActions.bottomActionsFilterList.adapter != null) {
            return
        }
        lastFilterLayer = null

        val source = previewBaseBitmap ?: filterInitialBitmap ?: return
        if (source.isRecycled) return

        ensureBackgroundThread {
            try {
                val thumbnailSize = resources.getDimension(R.dimen.bottom_filters_thumbnail_size).toInt().coerceAtLeast(64)
                val scaled = Bitmap.createScaledBitmap(source, thumbnailSize, thumbnailSize, true)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        setupFiltersAdapter(scaled)
                    }
                }
            } catch (e: Exception) {
                if (isDestroyed || isFinishing) return@ensureBackgroundThread
                runOnUiThread {
                    showErrorToast("${getString(R.string.failed_to_load_filters)}: ${e.message}")
                    currPrimaryAction = PRIMARY_ACTION_NONE
                    updatePrimaryActionButtons()
                }
            }
        }
    }

    private fun setupFiltersAdapterForLayer(layer: StickerLayer, forceReload: Boolean = false) {
        if (lastFilterLayer == layer && !forceReload && binding.bottomEditorFilterActions.bottomActionsFilterList.adapter != null) {
            return
        }
        lastFilterLayer = layer

        ensureBackgroundThread {
            try {
                val thumbnailSize = resources.getDimension(R.dimen.bottom_filters_thumbnail_size).toInt().coerceAtLeast(64)
                val base = layer.baseUnadjustedBitmap
                if (base.isRecycled) return@ensureBackgroundThread
                val scaled = Bitmap.createScaledBitmap(base, thumbnailSize, thumbnailSize, true)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        setupFiltersAdapter(scaled)
                    }
                }
            } catch (e: Exception) {
                if (isDestroyed || isFinishing) return@ensureBackgroundThread
                runOnUiThread {
                    showErrorToast("${getString(R.string.failed_to_load_filters)}: ${e.message}")
                    currPrimaryAction = PRIMARY_ACTION_NONE
                    updatePrimaryActionButtons()
                }
            }
        }
    }

    private fun setupFiltersAdapter(bitmap: Bitmap) {
        val filterThumbnailsManager = FilterThumbnailsManager()
        filterThumbnailsManager.clearThumbs()

        val noFilter = Filter(getString(com.goodwy.commons.R.string.none))
        filterThumbnailsManager.addThumb(FilterItem(bitmap, noFilter))

        FilterPack.getFilterPack(this).forEach {
            val filterItem = FilterItem(bitmap, it)
            filterThumbnailsManager.addThumb(filterItem)
        }

        val filterItems = filterThumbnailsManager.processThumbs()
        val adapter = FiltersAdapter(applicationContext, filterItems) {
            val layoutManager = binding.bottomEditorFilterActions.bottomActionsFilterList.layoutManager as LinearLayoutManager
            applyFilter(filterItems[it])

            if (it == layoutManager.findLastCompletelyVisibleItemPosition() || it == layoutManager.findLastVisibleItemPosition()) {
                val thumbnailSize = resources.getDimension(R.dimen.bottom_filters_thumbnail_size).toInt()
                binding.bottomEditorFilterActions.bottomActionsFilterList.smoothScrollBy(thumbnailSize, 0)
            } else if (it == layoutManager.findFirstCompletelyVisibleItemPosition() || it == layoutManager.findFirstVisibleItemPosition()) {
                val thumbnailSize = resources.getDimension(R.dimen.bottom_filters_thumbnail_size).toInt()
                binding.bottomEditorFilterActions.bottomActionsFilterList.smoothScrollBy(-thumbnailSize, 0)
            }
        }

        binding.bottomEditorFilterActions.bottomActionsFilterList.adapter = adapter
        adapter.notifyDataSetChanged()
    }

    private fun applyFilter(filterItem: FilterItem) {
        val chosenFilter = if (filterItem.filter.name == getString(com.goodwy.commons.R.string.none)) null else filterItem.filter
        val targetLayer = binding.freestyleOverlayView.activeLayer
            ?: if (isCollageMode && collageBgPhotoBitmap == null) binding.freestyleOverlayView.layers.lastOrNull() else null

        if (targetLayer != null) {
            pushSessionSnapshot()
            targetLayer.currentFilter = chosenFilter
            val base = targetLayer.baseUnadjustedBitmap
            val filtered = if (chosenFilter == null) {
                base
            } else {
                val copy = Bitmap.createBitmap(base)
                chosenFilter.processFilter(copy) ?: base
            }
            targetLayer.bitmap = if (targetLayer.adjustParameters.isDefault()) {
                filtered
            } else {
                ImageAdjustHelper.applyAdjustments(filtered, targetLayer.adjustParameters)
            }
            binding.freestyleOverlayView.invalidate()
            refreshMenuItems()
            return
        }

        if (previewBaseBitmap != null || filterInitialBitmap != null) {
            pushSessionSnapshot()
            currentAppliedFilter = chosenFilter
            renderWorkingBaseToView()
            refreshMenuItems()
        }
    }

    private fun updateAspectRatio(aspectRatio: Int) {
        currAspectRatio = aspectRatio
        config.lastEditorCropAspectRatio = aspectRatio
        updateAspectRatioButtons()

        binding.cropImageView.apply {
            if (aspectRatio == ASPECT_RATIO_FREE) {
                setFixedAspectRatio(false)
            } else {
                val newAspectRatio = when (aspectRatio) {
                    ASPECT_RATIO_ONE_ONE -> Pair(1f, 1f)
                    ASPECT_RATIO_FOUR_THREE -> Pair(4f, 3f)
                    ASPECT_RATIO_SIXTEEN_NINE -> Pair(16f, 9f)
                    else -> Pair(lastOtherAspectRatio!!.first, lastOtherAspectRatio!!.second)
                }

                setAspectRatio(newAspectRatio.first.toInt(), newAspectRatio.second.toInt())
            }
        }
    }

    @SuppressLint("UseCompatTextViewDrawableApis")
    private fun updateAspectRatioButtons() {
        arrayOf(
            binding.bottomAspectRatios.bottomAspectRatioFree,
            binding.bottomAspectRatios.bottomAspectRatioOneOne,
            binding.bottomAspectRatios.bottomAspectRatioFourThree,
            binding.bottomAspectRatios.bottomAspectRatioSixteenNine,
            binding.bottomAspectRatios.bottomAspectRatioOther,
        ).forEach {
            it.setTextColor(Color.WHITE)
            it.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
        }

        val currentAspectRatioButton = when (currAspectRatio) {
            ASPECT_RATIO_FREE -> binding.bottomAspectRatios.bottomAspectRatioFree
            ASPECT_RATIO_ONE_ONE -> binding.bottomAspectRatios.bottomAspectRatioOneOne
            ASPECT_RATIO_FOUR_THREE -> binding.bottomAspectRatios.bottomAspectRatioFourThree
            ASPECT_RATIO_SIXTEEN_NINE -> binding.bottomAspectRatios.bottomAspectRatioSixteenNine
            else -> binding.bottomAspectRatios.bottomAspectRatioOther
        }

        currentAspectRatioButton.setTextColor(getProperPrimaryColor())
        currentAspectRatioButton.compoundDrawableTintList = ColorStateList.valueOf(getProperPrimaryColor())
    }

    private fun updateCropRotateActionButtons() {
        arrayOf(binding.bottomEditorCropRotateActions.bottomAspectRatio).forEach {
            it.applyColorFilter(Color.WHITE)
        }

        val primaryActionView = when (currCropRotateAction) {
            CROP_ROTATE_ASPECT_RATIO -> binding.bottomEditorCropRotateActions.bottomAspectRatio
            else -> null
        }

        primaryActionView?.applyColorFilter(getProperPrimaryColor())
    }

    private fun updateDrawColor(color: Int) {
        drawColor = color
//        binding.bottomEditorDrawActions.bottomDrawColor
//            .setFillWithStroke(color, getProperBackgroundColor())
        getBrushPreviewView().setColor(drawColor)
        getBrushIconPreviewView().setColor(drawColor)
        config.lastEditorDrawColor = color
        binding.editorDrawCanvas.updateColor(color)
    }

    private fun resizeImage() {
        val point = getAreaSize()
        if (point == null) {
            toast(com.goodwy.commons.R.string.unknown_error_occurred)
            return
        }

        ResizeDialog(
            activity = this,
            size = point,
            onPreview = { newSize, _ ->
                showResizePreview(newSize)
            }
        ) { newSize, _ ->
            resizeWidth = newSize.x
            resizeHeight = newSize.y
            cropImage()
        }
    }

    private fun showResizePreview(newSize: Point) {
        ensureBackgroundThread {
            try {
                val sourceBitmap = binding.cropImageView.getCroppedImage() ?: return@ensureBackgroundThread
                val targetW = newSize.x
                val targetH = newSize.y
                val maxDim = 2048
                val (previewW, previewH) = if (targetW > maxDim || targetH > maxDim) {
                    if (targetW >= targetH) {
                        val h = (maxDim * (targetH.toFloat() / targetW)).roundToInt().coerceAtLeast(1)
                        Pair(maxDim, h)
                    } else {
                        val w = (maxDim * (targetW.toFloat() / targetH)).roundToInt().coerceAtLeast(1)
                        Pair(w, maxDim)
                    }
                } else {
                    Pair(targetW, targetH)
                }

                val previewBitmap = Bitmap.createScaledBitmap(sourceBitmap, previewW, previewH, true)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val imageView = ImageView(this).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(previewBitmap)
                        val pad = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
                        setPadding(pad, pad, pad, pad)
                    }

                    getAlertDialogBuilder()
                        .setTitle("${getString(R.string.preview)} (${newSize.x} × ${newSize.y})")
                        .setView(imageView)
                        .setPositiveButton(com.goodwy.commons.R.string.ok, null)
                        .show()
                }
            } catch (_: OutOfMemoryError) {
                runOnUiThread { toast(com.goodwy.commons.R.string.out_of_memory_error) }
            } catch (e: Exception) {
                runOnUiThread { toast("${getString(com.goodwy.commons.R.string.unknown_error_occurred)}: ${e.message}") }
            }
        }
    }

    private fun shouldCropSquare(): Boolean {
        val extras = intent.extras
        return if (extras != null && extras.containsKey(ASPECT_X) && extras.containsKey(ASPECT_Y)) {
            extras.getInt(ASPECT_X) == extras.getInt(ASPECT_Y)
        } else {
            false
        }
    }

    private fun getAreaSize(): Point? {
        val rect = binding.cropImageView.cropRect ?: return null
        val rotation = binding.cropImageView.rotatedDegrees
        return if (rotation == 0 || rotation == 180) {
            Point(rect.width(), rect.height())
        } else {
            Point(rect.height(), rect.width())
        }
    }

    private fun resolveSaveAsPath(callback: (String) -> Unit) {
        runOnUiThread {
            resolveUriScheme(
                uri = saveUri,
                onPath = {
                    SaveAsDialog(this, it, true, callback = callback)
                },
                onContentUri = {
                    val (path, append) = proposeNewFilePath(it)
                    SaveAsDialog(this, path, append, callback = callback)
                }
            )
        }
    }

    private fun saveBitmap(overwrite: Boolean, bitmap: Bitmap, showSavingToast: Boolean = true) {
        if (overwrite) {
            resolveUriScheme(
                uri = saveUri,
                onPath = { path ->
                    ensureWritablePath(targetPath = path, confirmOverwrite = false) {
                        saveBitmapToPath(bitmap, it, showSavingToast)
                    }
                },
                onContentUri = { contentUri ->
                    saveBitmapToContentUri(bitmap, contentUri, showSavingToast, isCropCommit = false)
                }
            )
        } else {
            resolveSaveAsPath { path ->
                saveBitmapToPath(bitmap, path, showSavingToast)
            }
        }
    }

    private fun getPathFromUri(uri: Uri?): String? {
        if (uri == null) {
            return null
        }

        return when (uri.scheme) {
            "file" -> uri.path
            else -> getRealPathFromURI(uri)
        }?.takeIf { it.isNotEmpty() }
    }

    private fun getFoldersToResync(savedPaths: Collection<String>): ArrayList<String> {
        val folders = linkedSetOf<String>()

        savedPaths.map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { path ->
                folders.add(path.getParentPath())
            }

        arrayOf(uri, saveUri, originalUri).forEach { currentUri ->
            getPathFromUri(currentUri)?.getParentPath()?.let(folders::add)
        }

        return ArrayList(folders.filter { it.isNotEmpty() })
    }

    private fun finalizeEditedMedia(
        savedPaths: Collection<String>,
        resultIntent: Intent,
        completion: () -> Unit,
    ) {
        val pathsToScan = ArrayList(
            savedPaths.map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        )
        val foldersToResync = getFoldersToResync(pathsToScan)

        val completeOnUi = {
            runOnUiThread {
                try {
                    Glide.get(applicationContext).clearMemory()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                setResult(RESULT_OK, resultIntent)
                completion()
            }
        }

        val resyncFolders = {
            ensureBackgroundThread {
                for (path in pathsToScan) {
                    try {
                        val file = File(path)
                        if (file.exists()) {
                            mediaDB.updateMediumModifiedAndSize(path, file.lastModified(), file.length())
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                foldersToResync.forEach { folderPath ->
                    try {
                        applicationContext.rescanFolderMediaSync(folderPath)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                completeOnUi()
            }
        }

        if (pathsToScan.isNotEmpty()) {
            try {
                rescanPaths(pathsToScan) {
                    try {
                        fixDateTaken(pathsToScan, false) {
                            resyncFolders()
                        }
                    } catch (e: Exception) {
                        resyncFolders()
                    }
                }
            } catch (e: Exception) {
                resyncFolders()
            }
        } else {
            resyncFolders()
        }
    }

    private fun freeMemory() {
        // clean up everything to free as much memory as possible
        binding.defaultImageView.setImageResource(0)
        binding.cropImageView.setImageBitmap(null)
        binding.bottomEditorFilterActions.bottomActionsFilterList.adapter = null
        binding.bottomEditorFilterActions.bottomActionsFilterList.beGone()
    }

    private fun saveBitmapToPath(bitmap: Bitmap, path: String, showSavingToast: Boolean) {
        if (!packageName.contains("ywdoog".reversed(), true)) {
            if (baseConfig.appRunCount > 100) {
                val label =
                    "sknahT .moc.elgoog.yalp morf eno lanigiro eht daolnwod ytefas nwo ruoy roF .ppa eht fo noisrev ekaf a gnisu era uoY".reversed()
                runOnUiThread {
                    ConfirmationDialog(this, label, positive = com.goodwy.commons.R.string.ok, negative = 0) {
                        launchViewIntent("4103196680983618628=di?ved/sppa/erots/moc.elgoog.yalp//:sptth".reversed())
                    }
                }
                return
            }
        }

        try {
            ensureBackgroundThread {
                val file = File(path)
                val fileDirItem = FileDirItem(path, path.getFilenameFromPath())
                val isOverwriting = (path == originalFilePath) || overwriteRequested
                if (isOverwriting && file.exists()) {
                    backupToRecycleBin(path)
                }
                try {
                    val out = FileOutputStream(file)
                    saveBitmapToFile(file, bitmap, out, showSavingToast)
                } catch (e: Exception) {
                    getFileOutputStream(fileDirItem, true) {
                        if (it != null) {
                            saveBitmapToFile(file, bitmap, it, showSavingToast)
                        } else {
                            toast(R.string.image_editing_failed)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            showErrorToast(e)
        } catch (e: OutOfMemoryError) {
            toast(com.goodwy.commons.R.string.out_of_memory_error)
        }
    }

    private fun saveBitmapToFile(file: File, bitmap: Bitmap, out: OutputStream, showSavingToast: Boolean) {
        AppLogger.recordAction("saveBitmapToFile started: ${file.absolutePath} (showSavingToast: $showSavingToast)")
        if (showSavingToast) {
            runOnUiThread {
                try {
                    toast(com.goodwy.commons.R.string.saving)
                } catch (_: Exception) {}
            }
        }

        out.use {
            if (resizeWidth > 0 && resizeHeight > 0) {
                val resized = bitmap.scale(resizeWidth, resizeHeight, false)
                resized.compress(file.absolutePath.getCompressionFormat(), 90, out)
            } else {
                bitmap.compress(file.absolutePath.getCompressionFormat(), 90, out)
            }
        }

        writeExif(oldExif, file.toUri())
        AppLogger.recordAction("saveBitmapToFile: writeExif completed for ${file.absolutePath}")

        val isOverwritingOriginal = (file.absolutePath == originalFilePath) || overwriteRequested
        fun proceedFinalize() {
            Medium.markEdited(file.absolutePath)
            finalizeEditedMedia(arrayListOf(file.absolutePath), intent) {
                runOnUiThread {
                    try {
                        toast(com.goodwy.commons.R.string.file_saved)
                    } catch (_: Exception) {}
                    finish()
                }
            }
        }

        if (isOverwritingOriginal && (originalLastModified > 0L || originalDateTaken > 0L)) {
            val targetLastModified = if (originalLastModified > 0L) tweakTimestampByOneSecond(originalLastModified) else 0L
            val targetDateTaken = if (originalDateTaken > 0L) tweakTimestampByOneSecond(originalDateTaken) else targetLastModified

            if (targetLastModified > 0L) {
                try {
                    file.setLastModified(targetLastModified)
                } catch (_: Exception) {}
            }
            runOnUiThread {
                updateDateTimeForPaths(
                    paths = arrayListOf(file.absolutePath),
                    updateDateTaken = targetDateTaken > 0L,
                    newDateTaken = targetDateTaken,
                    updateLastModified = targetLastModified > 0L,
                    newLastModified = targetLastModified
                ) {
                    Medium.markEdited(file.absolutePath)
                    proceedFinalize()
                }
            }
        } else {
            proceedFinalize()
        }
    }

    private fun saveBitmapToContentUri(
        bitmap: Bitmap,
        uri: Uri,
        showSavingToast: Boolean,
        isCropCommit: Boolean
    ) {
        if (showSavingToast) {
            toast(com.goodwy.commons.R.string.saving)
        }

        ensureBackgroundThread {
            var out: OutputStream? = null
            try {
                out = contentResolver.openOutputStream(uri, "wt")
                    ?: contentResolver.openOutputStream(uri)
                if (out == null) {
                    val (path, append) = proposeNewFilePath(uri)
                    runOnUiThread {
                        SaveAsDialog(this, path, append) { path ->
                            saveBitmapToPath(bitmap, path, showSavingToast)
                        }
                    }
                    return@ensureBackgroundThread
                }

                val quality = if (isCropCommit) 100 else 90
                val bitmapToSave = if (resizeWidth > 0 && resizeHeight > 0 && (bitmap.width != resizeWidth || bitmap.height != resizeHeight)) {
                    try {
                        Bitmap.createScaledBitmap(bitmap, resizeWidth, resizeHeight, true)
                    } catch (_: Exception) {
                        bitmap
                    }
                } else {
                    bitmap
                }
                bitmapToSave.compress(getCompressionFormatFromUri(uri), quality, out)
                out.flush()
                writeExif(oldExif, uri)

                val realPath = getPathFromUri(uri)
                val savedPaths = listOfNotNull(realPath)
                val resultIntent = if (isCropCommit) {
                    Intent().apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    intent
                }

                finalizeEditedMedia(savedPaths, resultIntent) {
                    if (isCropCommit) {
                        finish()
                    } else {
                        toast(com.goodwy.commons.R.string.file_saved)
                        finish()
                    }
                }
            } catch (e: Exception) {
                showErrorToast(e)
            } finally {
                try { out?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun editWith() {
        openEditor(uri.toString(), true)
        isEditingWithThirdParty = true
    }

    override fun toggleUndoVisibility(visible: Boolean) {
        val showUndo = sessionUndoStack.isNotEmpty()
        binding.editorToolbar.menu.findItem(R.id.undo)?.apply {
            isEnabled = showUndo
            val drawableId = if (showUndo) R.drawable.ic_undo_vector else R.drawable.ic_undo_gray
            val iconColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else Color.WHITE
            val drawable = resources.getColoredDrawableWithColor(this@EditActivity, drawableId, iconColor)
            icon = drawable
        }
    }

    override fun toggleRedoVisibility(visible: Boolean) {
        val showRedo = sessionRedoStack.isNotEmpty()
        binding.editorToolbar.menu.findItem(R.id.redo)?.apply {
            isEnabled = showRedo
            val drawableId = if (showRedo) R.drawable.ic_redo_vector else R.drawable.ic_redo_gray
            val iconColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else Color.WHITE
            val drawable = resources.getColoredDrawableWithColor(this@EditActivity, drawableId, iconColor)
            icon = drawable
        }
    }

    private fun captureSessionSnapshot(overrideStickerSnap: CanvasStateSnapshot? = null): EditorSessionSnapshot {
        return EditorSessionSnapshot(
            baseBitmap = filterInitialBitmap,
            previewBitmap = previewBaseBitmap,
            appliedFilter = currentAppliedFilter,
            adjustParams = adjustParameters.copy(),
            canvasOpacity = canvasOpacity,
            drawOperations = binding.editorDrawCanvas.getOperationsSnapshot(),
            stickerSnapshot = overrideStickerSnap ?: binding.freestyleOverlayView.captureCanvasSnapshot(),
            collageBgColor = collageBgColor,
            collageBgPhoto = collageBgPhotoBitmap,
            hasBaseCropApplied = hasBaseCropApplied,
            resizeWidth = resizeWidth,
            resizeHeight = resizeHeight
        )
    }

    private fun pushSessionSnapshot(snapshot: EditorSessionSnapshot = captureSessionSnapshot()) {
        sessionUndoStack.addLast(snapshot)
        if (sessionUndoStack.size > MAX_SESSION_HISTORY) {
            sessionUndoStack.removeFirst()
        }
        sessionRedoStack.clear()
        refreshMenuItems()
    }

    private fun undoSession() {
        if (sessionUndoStack.isEmpty()) return
        sessionRedoStack.addLast(captureSessionSnapshot())
        val previous = sessionUndoStack.removeLast()
        restoreSessionSnapshot(previous)
    }

    private fun redoSession() {
        if (sessionRedoStack.isEmpty()) return
        sessionUndoStack.addLast(captureSessionSnapshot())
        val next = sessionRedoStack.removeLast()
        restoreSessionSnapshot(next)
    }

    private fun restoreSessionSnapshot(snap: EditorSessionSnapshot) {
        filterInitialBitmap = snap.baseBitmap
        previewBaseBitmap = snap.previewBitmap
        currentAppliedFilter = snap.appliedFilter
        adjustParameters = snap.adjustParams.copy()
        canvasOpacity = snap.canvasOpacity
        collageBgColor = snap.collageBgColor
        collageBgPhotoBitmap = snap.collageBgPhoto
        hasBaseCropApplied = snap.hasBaseCropApplied
        resizeWidth = snap.resizeWidth
        resizeHeight = snap.resizeHeight

        binding.editorDrawCanvas.restoreOperationsSnapshot(snap.drawOperations)
        binding.freestyleOverlayView.restoreCanvasState(snap.stickerSnapshot)

        val isCropActive = currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE || croppingStickerLayer != null
        binding.editorDrawCanvas.beVisibleIf((currPrimaryAction == PRIMARY_ACTION_DRAW || binding.editorDrawCanvas.hasStrokes()) && !isCropActive)
        binding.freestyleOverlayView.beVisibleIf((isCollageMode || currPrimaryAction == PRIMARY_ACTION_STICKER || binding.freestyleOverlayView.layers.isNotEmpty()) && !isCropActive)

        renderWorkingBaseToView()
        updateCanvasBackgroundToggleText()
        if (currPrimaryAction == PRIMARY_ACTION_ADJUST) {
            updateAdjustSliderUI()
        }
        updateFloatingLayerBar(binding.freestyleOverlayView.activeLayer)
        refreshMenuItems()
    }

    private fun setColor(pickedColor: Int) {
        drawColor = pickedColor
        binding.editorDrawCanvas.setColor(drawColor)
        isEraserOn = false
        updateEraserState()
        getBrushPreviewView().setColor(drawColor)
        getBrushIconPreviewView().setColor(drawColor)
        config.lastEditorDrawColor = drawColor
    }

    private fun getBrushPreviewView() = binding.bottomEditorDrawActions.bottomDrawColor.background as GradientDrawable

    private fun getBrushIconPreviewView() = binding.bottomEditorDrawActions.bottomDrawColorIcon.background as GradientDrawable

    private fun updateEraserState() {
        updateButtonStates()
        binding.editorDrawCanvas.toggleEraser(isEraserOn)
    }

    private fun updateButtonStates() {
        updateButtonColor(binding.bottomEditorDrawActions.bottomDrawEraser, isEraserOn)
        updateButtonColor(binding.bottomEditorDrawActions.bottomDrawEyeDropper, isEyeDropperOn)
    }

    private fun updateButtonColor(view: ImageView, enabled: Boolean) {
        if (enabled) {
            view.applyColorFilter(getProperPrimaryColor())
        } else {
            view.applyColorFilter(Color.WHITE)
        }
    }

    private fun eraserClicked() {
        if (isEyeDropperOn) {
            eyeDropperClicked()
        }
        isEraserOn = !isEraserOn
        updateEraserState()
    }

    private fun eyeDropperClicked() {
        if (isEraserOn) {
            eraserClicked()
        }

        isEyeDropperOn = !isEyeDropperOn
        if (isEyeDropperOn) {
            eyeDropper.start()
            binding.bottomEditorDrawActions.bottomDrawWidth.isEnabled = false
        } else {
            eyeDropper.stop()
            binding.bottomEditorDrawActions.bottomDrawWidth.isEnabled = true
        }

        updateButtonStates()
    }

    private fun loadStickerCanvas() {
        binding.cropImageView.beGone()
        binding.defaultImageView.beVisible()
        renderWorkingBaseToView()
        binding.freestyleOverlayView.beVisible()
        refreshMenuItems()
    }

    private fun updateAdjustSliderUI() {
        val slider = binding.bottomEditorAdjustActions.adjustSlider
        val propName = binding.bottomEditorAdjustActions.adjustPropertyName
        val propValue = binding.bottomEditorAdjustActions.adjustPropertyValue

        val tabs = listOf(
            binding.bottomEditorAdjustActions.adjustTabBrightness,
            binding.bottomEditorAdjustActions.adjustTabHighlights,
            binding.bottomEditorAdjustActions.adjustTabShadows,
            binding.bottomEditorAdjustActions.adjustTabContrast,
            binding.bottomEditorAdjustActions.adjustTabSaturation,
            binding.bottomEditorAdjustActions.adjustTabWarmth,
            binding.bottomEditorAdjustActions.adjustTabVignette
        )

        if (currentAdjustTab !in 0..6) {
            currentAdjustTab = 0
        }

        val targetParams = binding.freestyleOverlayView.activeLayer?.adjustParameters ?: adjustParameters

        val (name, value) = when (currentAdjustTab) {
            0 -> Pair(getString(R.string.brightness), targetParams.brightness.toInt())
            1 -> Pair(getString(R.string.highlights), targetParams.highlights.toInt())
            2 -> Pair(getString(R.string.shadows), targetParams.shadows.toInt())
            3 -> Pair(getString(R.string.contrast), targetParams.contrast.toInt())
            4 -> Pair(getString(R.string.saturation), targetParams.saturation.toInt())
            5 -> Pair(getString(R.string.warmth), targetParams.warmth.toInt())
            6 -> Pair(getString(R.string.vignette), targetParams.vignette.toInt())
            else -> Pair(getString(R.string.adjust), 0)
        }
        propName.text = name
        propValue.text = if (value > 0) "+$value" else "$value"

        if (currentAdjustTab == 6) { // Vignette is 0..100
            slider.max = 100
            slider.progress = value
        } else {
            slider.max = 200
            slider.progress = value + 100
        }

        tabs.forEachIndexed { index, textView ->
            textView.setBackgroundResource(
                if (currentAdjustTab == index) R.drawable.bg_pill_button_selected else R.drawable.bg_pill_button
            )
        }
    }

    private fun setupAdjustControls() {
        val slider = binding.bottomEditorAdjustActions.adjustSlider
        val resetBtn = binding.bottomEditorAdjustActions.adjustResetCurrent

        binding.bottomEditorAdjustActions.adjustTabBrightness.setOnClickListener { currentAdjustTab = 0; updateAdjustSliderUI() }
        binding.bottomEditorAdjustActions.adjustTabHighlights.setOnClickListener { currentAdjustTab = 1; updateAdjustSliderUI() }
        binding.bottomEditorAdjustActions.adjustTabShadows.setOnClickListener { currentAdjustTab = 2; updateAdjustSliderUI() }
        binding.bottomEditorAdjustActions.adjustTabContrast.setOnClickListener { currentAdjustTab = 3; updateAdjustSliderUI() }
        binding.bottomEditorAdjustActions.adjustTabSaturation.setOnClickListener { currentAdjustTab = 4; updateAdjustSliderUI() }
        binding.bottomEditorAdjustActions.adjustTabWarmth.setOnClickListener { currentAdjustTab = 5; updateAdjustSliderUI() }
        binding.bottomEditorAdjustActions.adjustTabVignette.setOnClickListener { currentAdjustTab = 6; updateAdjustSliderUI() }

        resetBtn.setOnClickListener {
            pushSessionSnapshot()
            val targetParams = binding.freestyleOverlayView.activeLayer?.adjustParameters ?: adjustParameters
            when (currentAdjustTab) {
                0 -> targetParams.brightness = 0f
                1 -> targetParams.highlights = 0f
                2 -> targetParams.shadows = 0f
                3 -> targetParams.contrast = 0f
                4 -> targetParams.saturation = 0f
                5 -> targetParams.warmth = 0f
                6 -> targetParams.vignette = 0f
            }
            updateAdjustSliderUI()
            applyCurrentAdjustments()
            refreshMenuItems()
        }

        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val targetParams = binding.freestyleOverlayView.activeLayer?.adjustParameters ?: adjustParameters
                    val value = if (currentAdjustTab == 6) progress.toFloat() else (progress - 100).toFloat()
                    when (currentAdjustTab) {
                        0 -> targetParams.brightness = value
                        1 -> targetParams.highlights = value
                        2 -> targetParams.shadows = value
                        3 -> targetParams.contrast = value
                        4 -> targetParams.saturation = value
                        5 -> targetParams.warmth = value
                        6 -> targetParams.vignette = value
                    }
                    binding.bottomEditorAdjustActions.adjustPropertyValue.text = if (value > 0) "+${value.toInt()}" else "${value.toInt()}"
                    applyCurrentAdjustments()
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                adjustTrackingSnapshot = captureSessionSnapshot()
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                adjustTrackingSnapshot?.let { beforeSnap ->
                    pushSessionSnapshot(beforeSnap)
                    adjustTrackingSnapshot = null
                }
                refreshMenuItems()
            }
        })

        updateAdjustSliderUI()
    }

    private var canvasOpacity = 1.0f

    private fun updateFloatingLayerBar(layer: StickerLayer?) {
        if (croppingStickerLayer != null || currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE || isCropIntent) {
            binding.floatingLayerOpacityBar.beGone()
            return
        }

        val layerButtons = arrayOf(
            binding.floatingBtnBringForward,
            binding.floatingBtnSendBackward,
            binding.floatingBtnDuplicate,
            binding.floatingBtnDelete
        )

        if (layer != null) {
            binding.floatingLayerOpacityBar.beVisible()
            binding.floatingOpacityLabel.text = getString(R.string.opacity)
            val op = (layer.opacity * 100).toInt()
            binding.floatingOpacitySlider.progress = op
            binding.floatingOpacityValue.text = "$op%"
            layerButtons.forEach { it.beVisible() }
        } else {
            val hasCanvasImage = (!isCollageMode && (filterInitialBitmap != null || previewBaseBitmap != null)) ||
                (isCollageMode && collageBgPhotoBitmap != null)
            if (hasCanvasImage) {
                binding.floatingLayerOpacityBar.beVisible()
                binding.floatingOpacityLabel.text = getString(R.string.opacity)
                val cop = (canvasOpacity * 100).toInt()
                binding.floatingOpacitySlider.progress = cop
                binding.floatingOpacityValue.text = "$cop%"
                layerButtons.forEach { it.beGone() }
            } else {
                binding.floatingLayerOpacityBar.beGone()
            }
        }
    }

    private fun setupFloatingLayerBar() {
        val slider = binding.floatingOpacitySlider
        val valueText = binding.floatingOpacityValue
        val overlay = binding.freestyleOverlayView

        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val active = overlay.activeLayer
                    if (active != null) {
                        active.opacity = progress / 100f
                        overlay.invalidate()
                    } else if (!isCollageMode || collageBgPhotoBitmap != null) {
                        canvasOpacity = progress / 100f
                        binding.defaultImageView.alpha = canvasOpacity
                    }
                    valueText.text = "$progress%"
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                opacityTrackingSnapshot = captureSessionSnapshot()
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                opacityTrackingSnapshot?.let { beforeSnap ->
                    pushSessionSnapshot(beforeSnap)
                    opacityTrackingSnapshot = null
                }
                refreshMenuItems()
            }
        })

        binding.floatingBtnBringForward.setOnClickListener {
            overlay.bringActiveLayerForward()
        }
        binding.floatingBtnBringForward.setOnLongClickListener {
            overlay.bringActiveLayerToFront()
            toast(R.string.bring_forward)
            true
        }

        binding.floatingBtnSendBackward.setOnClickListener {
            overlay.sendActiveLayerBackward()
        }
        binding.floatingBtnSendBackward.setOnLongClickListener {
            overlay.sendActiveLayerToBack()
            toast(R.string.send_backward)
            true
        }

        binding.floatingBtnDuplicate.setOnClickListener {
            overlay.duplicateActiveLayer()
        }
        binding.floatingBtnDuplicate.setOnLongClickListener {
            toast(R.string.duplicate)
            true
        }

        binding.floatingBtnDelete.setOnClickListener {
            overlay.removeActiveLayer()
        }
        binding.floatingBtnDelete.setOnLongClickListener {
            toast(com.goodwy.commons.R.string.delete)
            true
        }
    }

    private fun applyCurrentAdjustments() {
        pendingLayerToRender = binding.freestyleOverlayView.activeLayer
        adjustRenderPending = true
        if (adjustRenderRunning) return
        adjustRenderRunning = true

        adjustExecutor.execute {
            while (adjustRenderPending && !isFinishing && !isDestroyed) {
                adjustRenderPending = false
                val targetLayer = pendingLayerToRender
                if (targetLayer != null) {
                    val paramsSnapshot = targetLayer.adjustParameters.copy()
                    val filterSnapshot = targetLayer.currentFilter
                    val base = targetLayer.baseUnadjustedBitmap
                    if (base.isRecycled) continue

                    val filteredBase = if (filterSnapshot != null && filterSnapshot.name != getString(com.goodwy.commons.R.string.none)) {
                        val copy = Bitmap.createBitmap(base)
                        filterSnapshot.processFilter(copy) ?: base
                    } else {
                        base
                    }
                    val rendered = if (paramsSnapshot.isDefault()) {
                        filteredBase
                    } else {
                        ImageAdjustHelper.applyAdjustments(filteredBase, paramsSnapshot)
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            targetLayer.bitmap = rendered
                            binding.freestyleOverlayView.invalidate()
                        }
                    }
                } else {
                    val base = previewBaseBitmap ?: filterInitialBitmap ?: continue
                    if (base.isRecycled) continue
                    val paramsSnapshot = adjustParameters.copy()
                    val filterSnapshot = currentAppliedFilter

                    val filteredBase = if (filterSnapshot != null && filterSnapshot.name != getString(com.goodwy.commons.R.string.none)) {
                        val copy = Bitmap.createBitmap(base)
                        filterSnapshot.processFilter(copy) ?: base
                    } else {
                        base
                    }
                    val rendered = if (paramsSnapshot.isDefault()) {
                        filteredBase
                    } else {
                        ImageAdjustHelper.applyAdjustments(filteredBase, paramsSnapshot)
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            adjustedPreviewBitmap = rendered
                            binding.defaultImageView.setImageBitmap(rendered)
                            binding.defaultImageView.alpha = canvasOpacity
                        }
                    }
                }
            }
            adjustRenderRunning = false
        }
    }

    private fun startCroppingSticker(layer: StickerLayer) {
        if (layer.bitmap.isRecycled) return

        croppingStickerLayer = layer
        currPrimaryAction = PRIMARY_ACTION_CROP_ROTATE

        binding.defaultImageView.beGone()
        binding.editorDrawCanvas.beGone()
        binding.freestyleOverlayView.beGone()
        binding.floatingLayerOpacityBar.beGone()
        binding.btnCompareBeforeAfter.beGone()
        binding.bottomEditorStickerActions.root.beGone()
        binding.bottomEditorFilterActions.root.beGone()
        binding.bottomEditorDrawActions.root.beGone()
        binding.bottomEditorAdjustActions.root.beGone()

        refreshMenuItems()

        binding.cropImageView.apply {
            beVisible()
            setImageBitmap(layer.originalFullBitmap)
            guidelines = CropImageView.Guidelines.ON
            setFixedAspectRatio(false)
            clearAspectRatio()
            layer.lastCropRect?.let { prevRect ->
                post {
                    cropRect = prevRect
                }
            }
        }

        binding.bottomEditorCropRotateActions.root.beVisible()
        binding.bottomEditorCropRotateActions.bottomResize.beGone()
        binding.bottomAspectRatios.root.beGone()

        updatePrimaryActionButtons()
    }

    private fun cancelStickerCrop(keepActive: Boolean = true) {
        val layer = croppingStickerLayer
        croppingStickerLayer = null

        binding.cropImageView.apply {
            beGone()
            setImageBitmap(null)
        }
        binding.bottomEditorCropRotateActions.root.beGone()
        binding.bottomAspectRatios.root.beGone()

        binding.defaultImageView.beVisible()
        binding.freestyleOverlayView.beVisible()

        if (!keepActive) {
            binding.freestyleOverlayView.activeLayer = null
        } else if (layer != null) {
            binding.freestyleOverlayView.activeLayer = layer
        }

        binding.freestyleOverlayView.invalidate()
        currPrimaryAction = PRIMARY_ACTION_STICKER
        updatePrimaryActionButtons()
        refreshMenuItems()
    }

    private fun getCollageComposedBitmap(autoCropToBounds: Boolean = false): Bitmap {
        val overlay = binding.freestyleOverlayView
        val baseWidth = collageBgPhotoBitmap?.width ?: 1440
        val baseHeight = collageBgPhotoBitmap?.height ?: 1920
        val sourceRect = getBaseDisplayRectInOverlay()

        if (autoCropToBounds && overlay.layers.isNotEmpty()) {
            val bounds = overlay.getLayersBoundingBox()
            if (bounds != null && bounds.width() > 10f && bounds.height() > 10f) {
                val viewW = sourceRect?.width()?.takeIf { it > 1f } ?: (if (overlay.width > 0) overlay.width.toFloat() else baseWidth.toFloat())
                val viewH = sourceRect?.height()?.takeIf { it > 1f } ?: (if (overlay.height > 0) overlay.height.toFloat() else baseHeight.toFloat())
                val scale = maxOf(baseWidth / viewW, baseHeight / viewH, 1.5f)

                val cropW = (bounds.width() * scale).toInt().coerceAtLeast(100)
                val cropH = (bounds.height() * scale).toInt().coerceAtLeast(100)

                val cropped = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(cropped)
                if (collageBgPhotoBitmap != null && !collageBgPhotoBitmap!!.isRecycled && sourceRect != null) {
                    val save = canvas.save()
                    canvas.scale(scale, scale)
                    canvas.translate(-bounds.left, -bounds.top)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    p.alpha = (canvasOpacity * 255).toInt().coerceIn(0, 255)
                    var bgBmp = collageBgPhotoBitmap!!
                    val flt = currentAppliedFilter
                    if (flt != null && flt.name != getString(com.goodwy.commons.R.string.none)) {
                        val cp = Bitmap.createBitmap(bgBmp)
                        bgBmp = flt.processFilter(cp) ?: bgBmp
                    }
                    if (!adjustParameters.isDefault()) {
                        bgBmp = ImageAdjustHelper.applyAdjustments(bgBmp, adjustParameters)
                    }
                    canvas.drawBitmap(bgBmp, null, sourceRect, p)
                    canvas.restoreToCount(save)
                } else if (collageBgColor != Color.TRANSPARENT) {
                    canvas.drawColor(collageBgColor)
                }

                canvas.scale(scale, scale)
                canvas.translate(-bounds.left, -bounds.top)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                for (layer in overlay.layers) {
                    layer.draw(canvas, paint)
                }
                if (binding.editorDrawCanvas.hasStrokes()) {
                    val dispRect = binding.editorDrawCanvas.getImageDisplayRect()
                    canvas.save()
                    canvas.translate(dispRect.left, dispRect.top)
                    binding.editorDrawCanvas.renderStrokesToCanvas(
                        canvas,
                        dispRect.width().toInt().coerceAtLeast(1),
                        dispRect.height().toInt().coerceAtLeast(1)
                    )
                    canvas.restore()
                }
                return cropped
            }
        }

        return renderFinalBitmapSync(fullResolution = true, autoCropToBounds = false)
    }

    private fun setupStickerControls() {
        val overlay = binding.freestyleOverlayView
        val btnAddPhoto = binding.bottomEditorStickerActions.btnAddStickerPhoto

        overlay.onCommitUnifiedHistoryListener = { beforeSnap ->
            pushSessionSnapshot(captureSessionSnapshot(overrideStickerSnap = beforeSnap))
        }

        overlay.onLayerSelectedListener = { layer ->
            updateFloatingLayerBar(layer)
            if (layer != null && currPrimaryAction == PRIMARY_ACTION_FILTER) {
                setupFiltersAdapterForLayer(layer)
            } else if (layer == null && currPrimaryAction == PRIMARY_ACTION_FILTER) {
                setupFiltersAdapterForBase()
            }
            if (currPrimaryAction == PRIMARY_ACTION_ADJUST) {
                updateAdjustSliderUI()
            }
        }

        overlay.onCropRequestedListener = { layer ->
            startCroppingSticker(layer)
        }

        overlay.onHistoryChangedListener = {
            refreshMenuItems()
        }

        btnAddPhoto.setOnClickListener {
            val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            try {
                startActivityForResult(pickIntent, PICK_STICKER_PHOTO_INTENT)
            } catch (e: Exception) {
                toast(e.message ?: getString(com.goodwy.commons.R.string.unknown_error_occurred))
            }
        }

        val btnCanvasBgToggle = binding.bottomEditorStickerActions.btnCanvasBgToggle
        val btnCanvasPhoto = binding.bottomEditorStickerActions.btnCanvasPhoto
        if (isCollageMode) {
            btnCanvasBgToggle.beVisible()
            btnCanvasBgToggle.setOnClickListener {
                cycleCollageBackground()
            }
            btnCanvasPhoto.beVisible()
            btnCanvasPhoto.setOnClickListener {
                val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                try {
                    startActivityForResult(pickIntent, PICK_CANVAS_PHOTO_INTENT)
                } catch (e: Exception) {
                    toast(e.message ?: getString(com.goodwy.commons.R.string.unknown_error_occurred))
                }
            }
        } else {
            btnCanvasBgToggle.beGone()
            btnCanvasPhoto.beGone()
        }
    }

    private fun setupCompareButton() {
        val compareBtn = binding.btnCompareBeforeAfter
        compareBtn.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val compareOrig = initialUntouchedBitmap ?: filterInitialBitmap
                    compareOrig?.let { orig ->
                        binding.defaultImageView.setImageBitmap(orig)
                        binding.defaultImageView.alpha = 1.0f
                    }
                    binding.freestyleOverlayView.visibility = View.INVISIBLE
                    binding.editorDrawCanvas.visibility = View.INVISIBLE
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    renderWorkingBaseToView()
                    val isCropActive = currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE || croppingStickerLayer != null
                    binding.freestyleOverlayView.beVisibleIf((isCollageMode || currPrimaryAction == PRIMARY_ACTION_STICKER || binding.freestyleOverlayView.layers.isNotEmpty()) && !isCropActive)
                    binding.editorDrawCanvas.beVisibleIf((currPrimaryAction == PRIMARY_ACTION_DRAW || binding.editorDrawCanvas.hasStrokes()) && !isCropActive)
                    true
                }
                else -> false
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode != RESULT_OK || resultData == null) return

        if (requestCode == PICK_CANVAS_PHOTO_INTENT) {
            val pickedUri = resultData.data ?: return
            ensureBackgroundThread {
                try {
                    val bitmap = Glide.with(applicationContext)
                        .asBitmap()
                        .load(pickedUri)
                        .submit(1440, 1920)
                        .get()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            pushSessionSnapshot()
                            collageBgPhotoBitmap = bitmap
                            filterInitialBitmap = bitmap
                            previewBaseBitmap = makePreviewBitmap(bitmap)
                            initialUntouchedBitmap = bitmap
                            canvasOpacity = 1.0f
                            binding.editorDrawCanvas.setImageDimensions(bitmap.width, bitmap.height)
                            binding.freestyleOverlayView.activeLayer = null
                            renderWorkingBaseToView()
                            updateCanvasBackgroundToggleText()
                            updateFloatingLayerBar(null)
                            refreshMenuItems()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { showErrorToast(e) }
                }
            }
            return
        }

        if (requestCode == PICK_STICKER_PHOTO_INTENT) {
            val urisToLoad = ArrayList<Uri>()
            val clipData = resultData.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    val itemUri = clipData.getItemAt(i).uri
                    if (itemUri != null) {
                        urisToLoad.add(itemUri)
                    }
                }
            } else if (resultData.data != null) {
                urisToLoad.add(resultData.data!!)
            }

            if (urisToLoad.isNotEmpty()) {
                ensureBackgroundThread {
                    val loadedBitmaps = ArrayList<Bitmap>()
                    for (u in urisToLoad) {
                        try {
                            val bitmap = Glide.with(applicationContext)
                                .asBitmap()
                                .load(u)
                                .submit(1024, 1024)
                                .get()
                            loadedBitmaps.add(bitmap)
                        } catch (e: Exception) {
                            runOnUiThread { showErrorToast(e) }
                        }
                    }

                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && loadedBitmaps.isNotEmpty()) {
                            for (b in loadedBitmaps) {
                                binding.freestyleOverlayView.addSticker(b)
                            }
                            loadStickerCanvas()
                        }
                    }
                }
            }
        }
    }

    private fun initCollageStudio() {
        val paths = intent.getStringArrayListExtra(EXTRA_COLLAGE_PATHS) ?: arrayListOf()
        val baseWidth = 1440
        val baseHeight = 1920

        val blankBitmap = Bitmap.createBitmap(baseWidth, baseHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(blankBitmap)
        canvas.drawColor(Color.WHITE)

        initialUntouchedBitmap = blankBitmap
        filterInitialBitmap = blankBitmap
        previewBaseBitmap = makePreviewBitmap(blankBitmap)
        binding.editorDrawCanvas.setImageDimensions(baseWidth, baseHeight)
        binding.defaultImageView.setImageBitmap(blankBitmap)
        binding.defaultImageView.beVisible()
        binding.cropImageView.beGone()
        binding.editorDrawCanvas.beGone()
        binding.freestyleOverlayView.beVisible()

        val targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString()
        val destFile = File(targetDir, "Collage_${System.currentTimeMillis()}.jpg")
        saveUri = Uri.fromFile(destFile)
        uri = saveUri
        originalFilePath = destFile.absolutePath

        setupBottomActions()
        currPrimaryAction = PRIMARY_ACTION_NONE
        updatePrimaryActionButtons()

        if (paths.isNotEmpty()) {
            ensureBackgroundThread {
                val loaded = ArrayList<Bitmap>()
                for (p in paths) {
                    try {
                        val b = Glide.with(applicationContext)
                            .asBitmap()
                            .load(File(p))
                            .submit(1024, 1024)
                            .get()
                        loaded.add(b)
                    } catch (_: Exception) {
                        // ignore failed single load
                    }
                }

                runOnUiThread {
                    if (!isFinishing && !isDestroyed && loaded.isNotEmpty()) {
                        binding.freestyleOverlayView.beVisible()
                        binding.freestyleOverlayView.post {
                            for (b in loaded) {
                                binding.freestyleOverlayView.addSticker(b)
                            }
                            // Clear history accumulated by initial collage placement
                            sessionUndoStack.clear()
                            sessionRedoStack.clear()
                            loadStickerCanvas()
                            binding.freestyleOverlayView.invalidate()
                            updateFloatingLayerBar(binding.freestyleOverlayView.activeLayer)
                            refreshMenuItems()
                        }
                    }
                }
            }
        }
    }

    private fun updateCanvasBackgroundToggleText() {
        val btnCanvasBgToggle = binding.bottomEditorStickerActions.btnCanvasBgToggle
        btnCanvasBgToggle.text = when (collageBgColor) {
            Color.WHITE -> getString(R.string.canvas_bg_white)
            Color.TRANSPARENT -> getString(R.string.canvas_bg_transparent)
            else -> getString(R.string.canvas_bg_black)
        }
    }

    private fun cycleCollageBackground() {
        pushSessionSnapshot()
        if (collageBgPhotoBitmap != null) {
            collageBgPhotoBitmap = null
        } else {
            collageBgColor = when (collageBgColor) {
                Color.WHITE -> Color.TRANSPARENT
                Color.TRANSPARENT -> Color.BLACK
                else -> Color.WHITE
            }
        }

        updateCanvasBackgroundToggleText()

        val blankBitmap = Bitmap.createBitmap(1440, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(blankBitmap)
        if (collageBgColor != Color.TRANSPARENT) {
            canvas.drawColor(collageBgColor)
        }

        initialUntouchedBitmap = blankBitmap
        filterInitialBitmap = blankBitmap
        previewBaseBitmap = makePreviewBitmap(blankBitmap)
        canvasOpacity = 1.0f
        binding.editorDrawCanvas.setImageDimensions(1440, 1920)
        renderWorkingBaseToView()
        updateFloatingLayerBar(binding.freestyleOverlayView.activeLayer)

        val targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString()
        val ext = if (collageBgColor == Color.TRANSPARENT) "png" else "jpg"
        val destFile = File(targetDir, "Collage_${System.currentTimeMillis()}.$ext")
        saveUri = Uri.fromFile(destFile)
        uri = saveUri
        originalFilePath = destFile.absolutePath
        refreshMenuItems()
    }
}

