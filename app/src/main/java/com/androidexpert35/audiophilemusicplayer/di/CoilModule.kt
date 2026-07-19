package com.androidexpert35.audiophilemusicplayer.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import javax.inject.Singleton

/**
 * Hilt module configuring the singleton [ImageLoader] used by all Coil composables
 * across the application.
 *
 * The loader is wired to the shared [OkHttpClient] from [NetworkModule] so that
 * HTTPS image URLs resolved from Deezer are fetched through the same connection
 * pool as the API calls — maximising connection reuse and minimising latency.
 *
 * Remote image bytes are retained in a dedicated on-disk cache so reopening an
 * artist does not download the same Deezer asset again. A global crossfade is
 * enabled so local and remote artwork transitions in smoothly.
 *
 * The singleton [ImageLoader] is injected into [com.androidexpert35.audiophilemusicplayer.AudiophileMusicPlayerApp]
 * and registered as the process-wide Coil singleton so that composables using
 * `SubcomposeAsyncImage` / `AsyncImage` without an explicit loader receive it
 * automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoilModule {

    /**
     * Provides a fully configured [ImageLoader] with OkHttp network support.
     *
     * @param context Application context required by Coil's disk cache and
     *   platform-specific decoders.
     * @param okHttpClient Shared HTTP client that handles HTTPS image fetches.
     * @return Singleton [ImageLoader] instance used by all Coil composables.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): ImageLoader = ImageLoader.Builder(context)
        .diskCache {
            DiskCache.Builder()
                .directory(
                    context.cacheDir
                        .resolve(REMOTE_IMAGE_CACHE_DIRECTORY)
                        .toOkioPath()
                )
                .maxSizeBytes(REMOTE_IMAGE_CACHE_MAX_BYTES)
                .build()
        }
        .components {
            // Wire the shared OkHttpClient so HTTPS URLs resolve without a
            // separate connection pool — avoids redundant TLS handshakes.
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
        }
        .crossfade(true)
        .build()

    private const val REMOTE_IMAGE_CACHE_DIRECTORY = "remote_image_cache"
    private const val REMOTE_IMAGE_CACHE_MAX_BYTES = 128L * 1024L * 1024L
}
