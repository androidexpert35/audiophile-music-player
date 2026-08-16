package com.androidexpert35.audiophilemusicplayer.di

import com.androidexpert35.audiophilemusicplayer.BuildConfig
import com.androidexpert35.audiophilemusicplayer.data.remote.api.DeezerApiService
import com.androidexpert35.audiophilemusicplayer.data.remote.api.LrcLibApiService
import com.androidexpert35.audiophilemusicplayer.di.NetworkModule.LRCLIB_USER_AGENT
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing the Retrofit + OkHttp networking stack used exclusively
 * for Deezer remote metadata enrichment.
 *
 * A single [OkHttpClient] singleton is shared between Retrofit (for API calls)
 * and Coil (for loading the resolved image URLs), avoiding the overhead of
 * maintaining multiple connection pools.
 *
 * No API key is required; the Deezer search endpoints are publicly accessible
 * under Deezer's standard rate-limiting policy.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Base URL for all Deezer REST endpoints. */
    private const val DEEZER_BASE_URL = "https://api.deezer.com/"

    /** Base URL for the LRCLIB lyrics API. */
    private const val LRCLIB_BASE_URL = "https://lrclib.net/api/"

    /**
     * Identifying `User-Agent` sent with every LRCLIB request.
     *
     * LRCLIB sits behind Cloudflare, which rejects OkHttp's default
     * `okhttp/<version>` agent with a `520` before the request ever reaches the
     * API. Their documentation also asks clients to identify themselves, so the
     * app name, version, and project URL are all included.
     */
    private const val LRCLIB_USER_AGENT =
        "AudiophileMusicPlayer/${BuildConfig.VERSION_NAME} " +
            "(https://github.com/androidexpert35/audiophile-music-player)"

    /**
     * Provides the shared [OkHttpClient] used by both Retrofit and Coil.
     *
     * Timeouts are intentionally conservative — artwork enrichment is a
     * best-effort background operation and should never block the UI.
     *
     * @return Singleton [OkHttpClient] instance.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Provides the [Retrofit] instance configured for the Deezer API.
     *
     * Uses [GsonConverterFactory] to deserialise Deezer JSON responses into
     * the DTO data classes under `data.remote.dto`.
     *
     * @param okHttpClient Shared HTTP client.
     * @return Singleton [Retrofit] instance.
     */
    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(DEEZER_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /**
     * Provides the [DeezerApiService] Retrofit implementation.
     *
     * @param retrofit Configured Retrofit instance.
     * @return Singleton [DeezerApiService].
     */
    @Provides
    @Singleton
    fun provideDeezerApiService(retrofit: Retrofit): DeezerApiService =
        retrofit.create(DeezerApiService::class.java)

    /**
     * Provides the [Retrofit] instance configured for the LRCLIB lyrics API.
     *
     * Derives its client from the shared [OkHttpClient] via `newBuilder()` so the
     * connection pool and timeouts stay shared, while adding the
     * [LRCLIB_USER_AGENT] header required to get past Cloudflare. The header is
     * scoped to this client so Deezer and Coil traffic is untouched.
     *
     * @param okHttpClient Shared HTTP client.
     * @return Singleton [Retrofit] instance for LRCLIB.
     */
    @Provides
    @Singleton
    @LrcLibRetrofit
    fun provideLrcLibRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(LRCLIB_BASE_URL)
        .client(
            okHttpClient.newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", LRCLIB_USER_AGENT)
                            .build()
                    )
                }
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /**
     * Provides the [LrcLibApiService] Retrofit implementation.
     *
     * @param retrofit The LRCLIB-specific [Retrofit] instance.
     * @return Singleton [LrcLibApiService].
     */
    @Provides
    @Singleton
    fun provideLrcLibApiService(@LrcLibRetrofit retrofit: Retrofit): LrcLibApiService =
        retrofit.create(LrcLibApiService::class.java)
}

