package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Places album-style floating back navigation beside a rounded playlist search field.
 *
 * @param query Current in-playlist filter text.
 * @param onQueryChange Invoked when the listener changes the filter.
 * @param onSearchActiveChange Invoked when the search field gains or loses focus.
 * @param onNavigateBack Invoked when the listener returns to the library.
 * @param modifier Optional modifier applied to the floating row.
 */
@Composable
internal fun PlaylistDetailTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onNavigateBack,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            modifier = Modifier.size(48.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_navigate_back),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(14.dp)
            )
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            modifier = Modifier
                .height(56.dp)
                .weight(1f),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onSearchActiveChange(it.isFocused) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.playlist_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.cd_clear_playlist_search)
                            )
                        }
                    }
                } else {
                    null
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
        }
    }
}
