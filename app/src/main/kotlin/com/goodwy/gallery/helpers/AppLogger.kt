package com.goodwy.gallery.helpers

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

object AppLogger {
    private const val TAG = "GalleryApp"
    private const val MAX_BREADCRUMBS = 250
    private val breadcrumbs = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val logFiles = ArrayList<File>()

    fun init(context: Context) {
        try {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val logsDir = File(File(dcim, "Goodwy Gallery"), "Logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            val galleryLog = File(logsDir, "gallery_debug.log")
            val internalLog = File(context.filesDir, "gallery_debug.log")

            logFiles.clear()
            logFiles.add(galleryLog)
            logFiles.add(internalLog)
        } catch (_: Exception) {}

        i("AppLogger", "=== App started: OS ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), Model: ${Build.MANUFACTURER} ${Build.MODEL} ===")

        if (context is Application) {
            context.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    d("Lifecycle", "${activity.javaClass.simpleName} -> onCreated (restored: ${savedInstanceState != null})")
                }

                override fun onActivityStarted(activity: Activity) {
                    d("Lifecycle", "${activity.javaClass.simpleName} -> onStarted")
                }

                override fun onActivityResumed(activity: Activity) {
                    d("Lifecycle", "${activity.javaClass.simpleName} -> onResumed")
                }

                override fun onActivityPaused(activity: Activity) {
                    d("Lifecycle", "${activity.javaClass.simpleName} -> onPaused")
                }

                override fun onActivityStopped(activity: Activity) {
                    d("Lifecycle", "${activity.javaClass.simpleName} -> onStopped")
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                    d("Lifecycle", "${activity.javaClass.simpleName} -> onSaveInstanceState")
                }

                override fun onActivityDestroyed(activity: Activity) {
                    d("Lifecycle", "${activity.javaClass.simpleName} -> onDestroyed")
                }
            })
        }
    }

    fun d(tag: String, message: String) {
        log("DEBUG", tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        log("INFO", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message
        log("WARN", tag, msg)
        Log.w(tag, msg)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message
        log("ERROR", tag, msg)
        Log.e(tag, msg)
    }

    fun recordAction(action: String) {
        i("Action", action)
    }

    private fun log(level: String, tag: String, message: String) {
        val time = dateFormat.format(Date())
        val threadName = Thread.currentThread().name
        val line = "[$time] [$level] [$threadName] [$tag]: $message"

        breadcrumbs.add(line)
        while (breadcrumbs.size > MAX_BREADCRUMBS) {
            breadcrumbs.poll()
        }

        fileExecutor.execute {
            for (file in logFiles) {
                try {
                    file.parentFile?.mkdirs()
                    FileWriter(file, true).use { writer ->
                        writer.appendLine(line)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun getBreadcrumbs(): List<String> {
        return breadcrumbs.toList()
    }

    fun getBreadcrumbsFormatted(): String {
        return breadcrumbs.joinToString("\n")
    }
}
