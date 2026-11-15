package com.skullbeat.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class SkullBeatApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun newImageLoader(): ImageLoader {
        val builder = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnail_cache"))
                    .maxSizeBytes(250L * 1024 * 1024) // 250MB fixed thumbnail cache limit
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)

        if (::okHttpClient.isInitialized) {
            builder.okHttpClient(okHttpClient)
        }

        return builder.build()
    }

    override fun onCreate() {
        super.onCreate()
    }
}
