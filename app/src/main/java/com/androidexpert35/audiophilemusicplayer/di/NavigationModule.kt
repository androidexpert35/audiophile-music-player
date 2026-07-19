package com.androidexpert35.audiophilemusicplayer.di

import android.content.Context
import com.androidexpert35.audiophilemusicplayer.presentation.error.AudiophileUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.overlay.PlayerOverlayManager
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.overlay.PlayerOverlayManagerImpl
import com.tony.coreui.data.navigation.NavigationManagerImpl
import com.tony.coreui.data.strings.AndroidStringResolver
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding the [NavigationManager] interface to its implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
object NavigationModule {

    /**
     * Provides CoreUI's navigation runtime as the app-wide navigation source of truth.
     */
    @Provides
    @Singleton
    fun provideNavigationManager(): NavigationManager = NavigationManagerImpl()

    /**
     * Resolves localized strings without retaining an activity context.
     */
    @Provides
    @Singleton
    fun provideStringResolver(
        @ApplicationContext context: Context
    ): StringResolver = AndroidStringResolver(context)

    /**
     * Maps shared and playback-specific failures to Audiophile presentation copy.
     */
    @Provides
    @Singleton
    fun provideUiErrorMapper(mapper: AudiophileUiErrorMapper): UiErrorMapper = mapper

    /**
     * Provides the app-specific coordinator for the persistent player overlay.
     */
    @Provides
    @Singleton
    fun providePlayerOverlayManager(
        implementation: PlayerOverlayManagerImpl
    ): PlayerOverlayManager = implementation
}
