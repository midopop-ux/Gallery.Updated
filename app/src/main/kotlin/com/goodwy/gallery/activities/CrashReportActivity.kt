package com.goodwy.gallery.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.goodwy.gallery.R
import kotlin.system.exitProcess

class CrashReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CRASH_REPORT = "extra_crash_report"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_ERROR_LOCATION = "extra_error_location"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_report)

        val report = intent.getStringExtra(EXTRA_CRASH_REPORT) ?: "No crash report available"
        val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE) ?: "Unknown Error"
        val errorLocation = intent.getStringExtra(EXTRA_ERROR_LOCATION) ?: "Unknown Location"

        findViewById<TextView>(R.id.crash_error_title).text = errorMessage
        findViewById<TextView>(R.id.crash_error_location).text = errorLocation
        findViewById<TextView>(R.id.crash_full_details).text = report

        findViewById<Button>(R.id.btn_copy_report).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Gallery Crash Log", report)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "تم نسخ تقرير الخطأ بالكامل! يمكنك لصقه الآن.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btn_share_report).setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Gallery Crash Report")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Crash Report"))
        }

        findViewById<Button>(R.id.btn_restart_app).setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(launchIntent)
            }
            finish()
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }

        findViewById<Button>(R.id.btn_close).setOnClickListener {
            finish()
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
