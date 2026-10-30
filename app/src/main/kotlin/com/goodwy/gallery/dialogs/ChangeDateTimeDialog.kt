package com.goodwy.gallery.dialogs

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.formatDate
import com.goodwy.commons.extensions.getTimeFormat
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.gallery.R
import com.goodwy.gallery.databinding.DialogChangeDateTimeBinding
import com.goodwy.gallery.extensions.config
import java.io.File
import java.util.Calendar

class ChangeDateTimeDialog(
    val activity: BaseSimpleActivity,
    val initialDateTaken: Long,
    val initialLastModified: Long,
    val callback: (updateDateTaken: Boolean, newDateTaken: Long, updateLastModified: Boolean, newLastModified: Long) -> Unit
) {
    private val binding = DialogChangeDateTimeBinding.inflate(activity.layoutInflater)
    private var dateTakenCalendar = Calendar.getInstance().apply {
        timeInMillis = if (initialDateTaken > 0L) initialDateTaken else System.currentTimeMillis()
    }
    private var lastModifiedCalendar = Calendar.getInstance().apply {
        timeInMillis = if (initialLastModified > 0L) initialLastModified else System.currentTimeMillis()
    }

    private val dateFormat = activity.config.dateFormat
    private val timeFormat = activity.getTimeFormat()

    init {
        updateDateTakenViews()
        updateLastModifiedViews()

        binding.changeDateTakenCheckbox.setOnCheckedChangeListener { _, isChecked ->
            binding.dateTakenHolder.beVisibleIf(isChecked)
        }

        binding.changeLastModifiedCheckbox.setOnCheckedChangeListener { _, isChecked ->
            binding.lastModifiedHolder.beVisibleIf(isChecked)
        }

        binding.dateTakenDateValue.setOnClickListener {
            showDatePicker(dateTakenCalendar) {
                updateDateTakenViews()
            }
        }

        binding.dateTakenTimeValue.setOnClickListener {
            showTimePicker(dateTakenCalendar) {
                updateDateTakenViews()
            }
        }

        binding.lastModifiedDateValue.setOnClickListener {
            showDatePicker(lastModifiedCalendar) {
                updateLastModifiedViews()
            }
        }

        binding.lastModifiedTimeValue.setOnClickListener {
            showTimePicker(lastModifiedCalendar) {
                updateLastModifiedViews()
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ ->
                val updateDateTaken = binding.changeDateTakenCheckbox.isChecked
                val updateLastModified = binding.changeLastModifiedCheckbox.isChecked
                if (updateDateTaken || updateLastModified) {
                    callback(
                        updateDateTaken,
                        dateTakenCalendar.timeInMillis,
                        updateLastModified,
                        lastModifiedCalendar.timeInMillis
                    )
                }
            }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.change_date_time)
            }
    }

    private fun updateDateTakenViews() {
        val ts = dateTakenCalendar.timeInMillis
        binding.dateTakenDateValue.text = ts.formatDate(activity, dateFormat)
        binding.dateTakenTimeValue.text = ts.formatDate(activity, timeFormat = timeFormat)
    }

    private fun updateLastModifiedViews() {
        val ts = lastModifiedCalendar.timeInMillis
        binding.lastModifiedDateValue.text = ts.formatDate(activity, dateFormat)
        binding.lastModifiedTimeValue.text = ts.formatDate(activity, timeFormat = timeFormat)
    }

    private fun showDatePicker(calendar: Calendar, onDateSet: () -> Unit) {
        DatePickerDialog(
            activity,
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                onDateSet()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePicker(calendar: Calendar, onTimeSet: () -> Unit) {
        val is24Hour = android.text.format.DateFormat.is24HourFormat(activity)
        TimePickerDialog(
            activity,
            { _, hourOfDay, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                onTimeSet()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            is24Hour
        ).show()
    }
}
