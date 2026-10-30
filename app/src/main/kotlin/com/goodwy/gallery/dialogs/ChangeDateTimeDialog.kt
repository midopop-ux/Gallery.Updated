package com.goodwy.gallery.dialogs

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.value
import com.goodwy.gallery.R
import com.goodwy.gallery.databinding.DialogChangeDateTimeBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChangeDateTimeDialog(
    val activity: BaseSimpleActivity,
    val initialDateTaken: Long,
    val initialLastModified: Long,
    val callback: (updateDateTaken: Boolean, newDateTaken: Long, updateLastModified: Boolean, newLastModified: Long) -> Unit
) {
    private val binding = DialogChangeDateTimeBinding.inflate(activity.layoutInflater)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

    // Fallback logic: if initialDateTaken <= 0, use initialLastModified, or current time
    private var dateTakenCalendar = Calendar.getInstance().apply {
        timeInMillis = when {
            initialDateTaken > 0L -> initialDateTaken
            initialLastModified > 0L -> initialLastModified
            else -> System.currentTimeMillis()
        }
    }

    private var lastModifiedCalendar = Calendar.getInstance().apply {
        timeInMillis = when {
            initialLastModified > 0L -> initialLastModified
            initialDateTaken > 0L -> initialDateTaken
            else -> System.currentTimeMillis()
        }
    }

    init {
        updateDateTakenText()
        updateLastModifiedText()

        binding.changeDateTakenCheckbox.setOnCheckedChangeListener { _, isChecked ->
            binding.dateTakenHolder.beVisibleIf(isChecked)
        }

        binding.changeLastModifiedCheckbox.setOnCheckedChangeListener { _, isChecked ->
            binding.lastModifiedHolder.beVisibleIf(isChecked)
        }

        // Date & Time Picker Dialogs via side icon
        binding.dateTakenPickerIcon.setOnClickListener {
            syncCalendarFromEditText(binding.dateTakenEditText.value, dateTakenCalendar)
            showDatePicker(dateTakenCalendar) {
                showTimePicker(dateTakenCalendar) {
                    updateDateTakenText()
                }
            }
        }

        binding.lastModifiedPickerIcon.setOnClickListener {
            syncCalendarFromEditText(binding.lastModifiedEditText.value, lastModifiedCalendar)
            showDatePicker(lastModifiedCalendar) {
                showTimePicker(lastModifiedCalendar) {
                    updateLastModifiedText()
                }
            }
        }

        // Current time button (like MT Manager)
        binding.setCurrentTimeButton.setOnClickListener {
            val now = System.currentTimeMillis()
            dateTakenCalendar.timeInMillis = now
            lastModifiedCalendar.timeInMillis = now
            updateDateTakenText()
            updateLastModifiedText()
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok, null)
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.change_date_time) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val updateDateTaken = binding.changeDateTakenCheckbox.isChecked
                        val updateLastModified = binding.changeLastModifiedCheckbox.isChecked

                        var finalDateTaken = dateTakenCalendar.timeInMillis
                        var finalLastModified = lastModifiedCalendar.timeInMillis

                        if (updateDateTaken) {
                            val parsed = parseDateTime(binding.dateTakenEditText.value)
                            if (parsed == null) {
                                activity.toast(R.string.invalid_date_format)
                                return@setOnClickListener
                            }
                            finalDateTaken = parsed
                        }

                        if (updateLastModified) {
                            val parsed = parseDateTime(binding.lastModifiedEditText.value)
                            if (parsed == null) {
                                activity.toast(R.string.invalid_date_format)
                                return@setOnClickListener
                            }
                            finalLastModified = parsed
                        }

                        if (updateDateTaken || updateLastModified) {
                            callback(
                                updateDateTaken,
                                finalDateTaken,
                                updateLastModified,
                                finalLastModified
                            )
                        }
                        alertDialog.dismiss()
                    }
                }
            }
    }

    private fun updateDateTakenText() {
        binding.dateTakenEditText.setText(dateTimeFormat.format(Date(dateTakenCalendar.timeInMillis)))
    }

    private fun updateLastModifiedText() {
        binding.lastModifiedEditText.setText(dateTimeFormat.format(Date(lastModifiedCalendar.timeInMillis)))
    }

    private fun parseDateTime(text: String): Long? {
        return try {
            dateTimeFormat.parse(text.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun syncCalendarFromEditText(text: String, calendar: Calendar) {
        val parsed = parseDateTime(text)
        if (parsed != null) {
            calendar.timeInMillis = parsed
        }
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
