package com.goodwy.gallery

import android.content.ComponentCallbacks2
import com.bumptech.glide.Glide
import com.bumptech.glide.MemoryCategory
import com.github.ajalt.reprint.core.Reprint
import com.goodwy.commons.RightApp
import com.goodwy.commons.helpers.PurchaseHelper
import com.squareup.picasso.Downloader
import com.squareup.picasso.Picasso
import com.goodwy.gallery.helpers.AppLogger
import com.goodwy.gallery.helpers.CrashHandler
import okhttp3.Request
import okhttp3.Response

class App : RightApp() {

    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        CrashHandler.init(this)
        PurchaseHelper().initPurchaseIfNeed(this, "1504831423")
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Glide.get(this).setMemoryCategory(MemoryCategory.LOW)
        }
        Glide.get(this).trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Glide.get(this).clearMemory()
    }
}
