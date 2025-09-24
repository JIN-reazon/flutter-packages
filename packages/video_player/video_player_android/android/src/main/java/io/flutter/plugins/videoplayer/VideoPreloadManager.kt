package io.flutter.plugins.videoplayer

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
class VideoPreloadManager private constructor(private val applicationContext: Context) {
    companion object {
        private const val CACHE_DIR = "video_player_android_cache"
        private const val MAX_CACHE_SIZE = (200 * 1024 * 1024).toLong()
        private const val TAG = "VideoPreloadManager"

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
    private val preloadingJobs = mutableMapOf<String, Pair<Job, CacheWriter>>()

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

            val dataSpec = DataSpec.Builder()
                .setUri(url.toUri())
                .setPosition(0)
                .setLength((0.5 * 1024 * 1024).toLong()) // Preload 0.5MB
                .setKey(url)
                .build()

            val cacheDataSource = cacheDataSourceFactory.createDataSource()
            val cacheWriter = CacheWriter(
                cacheDataSource,
                dataSpec,
                null,
                null
            )

            val job = scope.launch {
                try {
                    Log.d(TAG, "Starting preload for $url")
                    cacheWriter.cache()
                    Log.d(TAG, "Preload completed for $url")
                } catch (e: Exception) {
                    Log.w(TAG, "Preloading failed or was cancelled for $url.", e)
                } finally {
                    preloadingJobs.remove(url)
                }
            }
            preloadingJobs[url] = Pair(job, cacheWriter)
        }
    }

    fun cancelPreload(urls: List<String>) {
        for (url in urls) {
            preloadingJobs[url]?.let { (job, writer) ->
                Log.d(TAG, "Cancelling preload for $url")
                writer.cancel()
                job.cancel()
            }
            preloadingJobs.remove(url)
        }
    }

    fun cancelAllPreloads() {
        Log.d(TAG, "Cancelling all preloads")
        val urlsToCancel = preloadingJobs.keys.toList()
        for (url in urlsToCancel) {
            preloadingJobs[url]?.let { (job, writer) ->
                writer.cancel()
                job.cancel()
            }
        }
        preloadingJobs.clear()
    }
}
