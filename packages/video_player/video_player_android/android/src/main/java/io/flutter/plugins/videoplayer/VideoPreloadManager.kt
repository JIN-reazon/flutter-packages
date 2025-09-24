package io.flutter.plugins.videoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.collections.set

@SuppressLint("UnsafeOptInUsageError")
class VideoPreloadManager private constructor(private val applicationContext: Context) {
    companion object {
        private const val CACHE_DIR = "video_player_android_cache"
        private const val MAX_CACHE_SIZE = (200 * 1024 * 1024).toLong()

        private var instance: VideoPreloadManager? = null

        @Synchronized
        fun getInstance(context: Context): VideoPreloadManager {
            if (instance == null) {
                instance = VideoPreloadManager(context.applicationContext)
            }
            return instance!!
        }

    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val preloadingJobs = mutableMapOf<String, Job>()

    val cache: SimpleCache by lazy {
        val cacheDir = File(applicationContext.cacheDir, CACHE_DIR)
        val databaseProvider = StandaloneDatabaseProvider(applicationContext)
        SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE), databaseProvider)
    }


    private val cacheDataSourceFactory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun preloadVideos(urls: List<String>) {
        for (url in urls) {
            if (preloadingJobs.containsKey(url)) {
                continue
            }
            val job = scope.launch {
                try {
                    precacheVideo(url)
                } catch (ignore: Exception) {

                } finally {
                    preloadingJobs.remove(url)
                }
            }
            preloadingJobs[url] = job
        }
    }

    @OptIn(UnstableApi::class)
    private fun precacheVideo(url: String) {
        val dataSpec = DataSpec.Builder()
            .setUri(url.toUri())
            .setPosition(0)
            .setLength(1 * 1024 * 1024)
            .setKey(url)
            .build()

        val cacheDataSource = cacheDataSourceFactory.createDataSource()
        val cacheWriter = CacheWriter(
            cacheDataSource,
            dataSpec,
            null,
            null
        )

        try {
            cacheWriter.cache()
        } catch (e: Exception) {
            throw e
        }
    }

    fun cancelPreload(urls: List<String>) {
        for (url in urls) {
            preloadingJobs[url]?.cancel()
            preloadingJobs.remove(url)
        }
    }

    fun cancelAllPreloads() {
        for (job in preloadingJobs.values) {
            job.cancel()
        }
        preloadingJobs.clear()
    }
}
