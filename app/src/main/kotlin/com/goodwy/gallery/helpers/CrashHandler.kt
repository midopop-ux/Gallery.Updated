package com.goodwy.gallery.helpers

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import com.goodwy.gallery.activities.CrashReportActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class CrashHandler private constructor(private val application: Application) : Thread.UncaughtExceptionHandler {
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private var isInitialized = false

        fun init(application: Application) {
            if (!isInitialized) {
                isInitialized = true
                val handler = CrashHandler(application)
                Thread.setDefaultUncaughtExceptionHandler(handler)
                AppLogger.d("CrashHandler", "Global uncaught exception handler registered")
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            AppLogger.e("CrashHandler", "FATAL CRASH on thread '${thread.name}': ${throwable.message}", throwable)

            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            throwable.printStackTrace(printWriter)
            val fullStackTrace = stringWriter.toString()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val crashTime = dateFormat.format(Date())

            val rootCause = getRootCause(throwable)
            val rootCauseLine = rootCause.stackTrace.firstOrNull()?.toString() ?: "Unknown line"

            val report = StringBuilder().apply {
                appendLine("==================================================")
                appendLine("             GALLERY CRASH REPORT                 ")
                appendLine("==================================================")
                appendLine("Time: $crashTime")
                appendLine("App Version: 8.4.0 (840) [TEST-DEBUG]")
                appendLine("Android OS: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendLine("Failing Thread: ${thread.name} (id: ${thread.id})")
                appendLine("--------------------------------------------------")
                appendLine("EXACT ERROR LOCATION:")
                appendLine("-> Exception: ${rootCause.javaClass.name}")
                appendLine("-> Message: ${rootCause.message}")
                appendLine("-> At: $rootCauseLine")
                appendLine("--------------------------------------------------")
                appendLine("FULL STACK TRACE:")
                appendLine(fullStackTrace)
                appendLine("--------------------------------------------------")
                appendLine("RECENT MOVEMENTS & BREADCRUMBS (Last ${AppLogger.getBreadcrumbs().size} events):")
                appendLine(AppLogger.getBreadcrumbsFormatted())
                appendLine("==================================================")
            }.toString()

            // Save crash report to persistent files
            saveCrashReportToDisk(report)

            // Launch CrashReportActivity in a separate process (:crash)
            val intent = Intent(application, CrashReportActivity::class.java).apply {
                putExtra(CrashReportActivity.EXTRA_CRASH_REPORT, report)
                putExtra(CrashReportActivity.EXTRA_ERROR_MESSAGE, "${rootCause.javaClass.simpleName}: ${rootCause.message}")
                putExtra(CrashReportActivity.EXTRA_ERROR_LOCATION, rootCauseLine)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            application.startActivity(intent)

            // Allow the intent to dispatch before killing
            Thread.sleep(300)
            Process.killProcess(Process.myPid())
            exitProcess(10)
        } catch (e: Exception) {
            Log.e("CrashHandler", "Error handling uncaught exception", e)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun getRootCause(throwable: Throwable): Throwable {
        var cause: Throwable = throwable
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }

    private fun saveCrashReportToDisk(report: String) {
        val targets = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "gallery_crash.log"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "gallery_crash.log"),
            File(application.filesDir, "gallery_crash.log")
        )

        for (file in targets) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(report)
            } catch (_: Exception) {}
        }
    }
}
