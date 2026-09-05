package com.androidexpert35.audiophilemusicplayer.presentation.error

import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import com.androidexpert35.audiophilemusicplayer.domain.model.common.PlaybackResourceError
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.ResourceError
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.state.UIError
import javax.inject.Inject

/**
 * Converts CoreUI and Audiophile failures into localized Audiophile error copy.
 */
class AudiophileUiErrorMapper @Inject constructor(
    private val stringResolver: StringResolver
) : UiErrorMapper {

    override fun map(errorObject: Any, retryAction: (() -> Unit)?): UIError = when (errorObject) {
        is ResourceError -> mapResourceError(errorObject, retryAction)
        is Throwable -> error(
            title = stringResolver.get(R.string.error_unexpected_title),
            message = errorObject.message
                ?: stringResolver.get(R.string.error_unknown_fallback_message),
            type = errorObject,
            retryAction = retryAction
        )
        else -> unknown(type = errorObject, retryAction = retryAction)
    }

    override fun mapResourceError(
        resource: ResourceError?,
        retryAction: (() -> Unit)?
    ): UIError = when (resource) {
        is LibraryResourceError -> error(
            title = stringResolver.get(R.string.library_error_title, resource.code),
            message = stringResolver.get(when (resource) {
                LibraryResourceError.UNSUPPORTED_FOLDER -> R.string.library_error_unsupported_folder
                LibraryResourceError.FOLDER_PERMISSION_DENIED -> R.string.library_error_folder_permission
                LibraryResourceError.FOLDER_SAVE_FAILED -> R.string.library_error_folder_save
                LibraryResourceError.STORAGE_UNAVAILABLE -> R.string.library_error_storage_unavailable
                LibraryResourceError.SCAN_READ_FAILED -> R.string.library_error_scan_read
                LibraryResourceError.SCAN_PERMISSION_DENIED -> R.string.library_error_scan_permission
                LibraryResourceError.SCAN_FAILED -> R.string.library_error_scan_failed
                LibraryResourceError.FOLDER_FAILED -> R.string.library_error_folder_failed
            }),
            type = resource,
            retryAction = retryAction.takeIf { resource.isRecoverable }
        )
        is PlaybackResourceError -> error(
            title = stringResolver.get(R.string.error_playback_title),
            message = resource.message.ifBlank {
                stringResolver.get(R.string.error_playback_message)
            },
            type = resource,
            retryAction = retryAction
        )
        is ResourceError.LogicError -> error(
            title = stringResolver.get(R.string.error_generic_title),
            message = resource.errorMessage
                ?: stringResolver.get(R.string.error_generic_fallback_message),
            type = resource,
            retryAction = retryAction
        )
        is ResourceError.ValidationError -> error(
            title = stringResolver.get(R.string.error_validation_title),
            message = resource.message,
            type = resource,
            retryAction = retryAction
        )
        is ResourceError.DatabaseError -> error(
            title = stringResolver.get(R.string.error_database_title),
            message = resource.message,
            type = resource,
            retryAction = retryAction
        )
        is ResourceError.StorageError -> error(
            title = stringResolver.get(R.string.error_storage_title),
            message = resource.message.ifBlank {
                stringResolver.get(R.string.error_storage_message)
            },
            type = resource,
            retryAction = retryAction
        )
        is ResourceError.ServiceError -> error(
            title = stringResolver.get(R.string.error_service_title),
            message = resource.message,
            type = resource,
            retryAction = retryAction
        )
        is ResourceError.NetworkError -> error(
            title = stringResolver.get(R.string.error_network_title),
            message = resource.message.ifBlank {
                stringResolver.get(R.string.error_network_message)
            },
            type = resource,
            retryAction = retryAction
        )
        ResourceError.UnknownError, null -> unknown(
            type = resource,
            retryAction = retryAction
        )
        else -> unknown(
            type = resource,
            retryAction = retryAction,
            metadata = mapOf("resourceErrorType" to resource::class.qualifiedName)
        )
    }

    private fun unknown(
        type: Any?,
        retryAction: (() -> Unit)?,
        metadata: Map<String, Any?> = emptyMap()
    ): UIError = error(
        title = stringResolver.get(R.string.error_unknown_title),
        message = stringResolver.get(R.string.error_unknown_message),
        type = type,
        retryAction = retryAction,
        metadata = metadata
    )

    private fun error(
        title: String,
        message: String,
        type: Any?,
        retryAction: (() -> Unit)?,
        metadata: Map<String, Any?> = emptyMap()
    ): UIError = UIError(
        title = title,
        message = message,
        type = type,
        retryAction = retryAction,
        metadata = metadata
    )
}
