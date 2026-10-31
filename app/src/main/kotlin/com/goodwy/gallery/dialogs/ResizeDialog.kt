package com.goodwy.gallery.dialogs

import android.graphics.Point
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.*
import com.goodwy.gallery.R
import com.goodwy.gallery.databinding.DialogResizeImageBinding
import kotlin.math.roundToInt

class ResizeDialog(
    val activity: BaseSimpleActivity,
    val size: Point,
    val onPreview: ((newSize: Point, isStretch: Boolean) -> Unit)? = null,
    val callback: (newSize: Point, isStretch: Boolean) -> Unit
) {
    init {
        val binding = DialogResizeImageBinding.inflate(activity.layoutInflater)
        val widthView = binding.resizeImageWidth
        val heightView = binding.resizeImageHeight
        val keepAspect = binding.keepAspectRatio
        val stretchImage = binding.stretchImage

        widthView.setText(size.x.toString())
        heightView.setText(size.y.toString())

        val ratio = if (size.y > 0) size.x / size.y.toFloat() else 1f

        stretchImage.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                keepAspect.isChecked = false
            }
        }

        keepAspect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                stretchImage.isChecked = false
                val width = getViewValue(widthView)
                if (width > 0 && ratio > 0) {
                    heightView.setText((width / ratio).roundToInt().toString())
                }
            }
        }

        widthView.onTextChangeListener {
            if (widthView.hasFocus()) {
                val width = getViewValue(widthView)
                if (keepAspect.isChecked && width > 0 && ratio > 0) {
                    heightView.setText((width / ratio).roundToInt().toString())
                }
            }
        }

        heightView.onTextChangeListener {
            if (heightView.hasFocus()) {
                val height = getViewValue(heightView)
                if (keepAspect.isChecked && height > 0 && ratio > 0) {
                    widthView.setText((height * ratio).roundToInt().toString())
                }
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok, null)
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .setNeutralButton(R.string.preview, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.resize_and_save) { alertDialog ->
                    alertDialog.showKeyboard(binding.resizeImageWidth)

                    alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val width = getViewValue(widthView)
                        val height = getViewValue(heightView)
                        if (width <= 0 || height <= 0) {
                            activity.toast(R.string.invalid_values)
                            return@setOnClickListener
                        }

                        val newSize = Point(width, height)
                        val isStretch = stretchImage.isChecked || !keepAspect.isChecked
                        onPreview?.invoke(newSize, isStretch)
                    }

                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val width = getViewValue(widthView)
                        val height = getViewValue(heightView)
                        if (width <= 0 || height <= 0) {
                            activity.toast(R.string.invalid_values)
                            return@setOnClickListener
                        }

                        val newSize = Point(width, height)
                        val isStretch = stretchImage.isChecked || !keepAspect.isChecked
                        callback(newSize, isStretch)
                        alertDialog.dismiss()
                    }
                }
            }
    }

    private fun getViewValue(view: EditText): Int {
        val textValue = view.value
        return if (textValue.isEmpty()) 0 else (textValue.toIntOrNull() ?: 0)
    }
}
