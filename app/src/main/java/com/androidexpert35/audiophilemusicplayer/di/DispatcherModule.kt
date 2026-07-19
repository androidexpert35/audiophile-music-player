package com.androidexpert35.audiophilemusicplayer.di

import javax.inject.Qualifier

/**
 * Qualifier for the IO [kotlinx.coroutines.CoroutineDispatcher] used for blocking I/O operations
 * (MediaStore queries, file access, database reads).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifier for the Main [kotlinx.coroutines.CoroutineDispatcher] used for UI-thread operations.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * Qualifier for the Default [kotlinx.coroutines.CoroutineDispatcher] used for CPU-intensive work.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * Qualifier for the application-scoped [kotlinx.coroutines.CoroutineScope] that outlives any
 * single ViewModel or screen and is tied to the process lifetime.
 *
 * Kept separate from [MainDispatcher] so the DI graph can unambiguously distinguish between
 * "a dispatcher that runs on the main thread" and "a long-lived scope that happens to run
 * its work on the main thread".
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Qualifier for the [retrofit2.Retrofit] instance configured for the LRCLIB lyrics API.
 *
 * Kept separate from the default Retrofit binding (Deezer) so both API clients can
 * share the same [okhttp3.OkHttpClient] while pointing at different base URLs.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LrcLibRetrofit

