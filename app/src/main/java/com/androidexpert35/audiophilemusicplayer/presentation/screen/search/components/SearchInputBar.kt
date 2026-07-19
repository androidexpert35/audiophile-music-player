package com.androidexpert35.audiophilemusicplayer.presentation.screen.search.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Prominent search input bar used as the primary interaction surface on the search screen.
 *
 * Requests focus automatically on first composition to open the software keyboard
 * immediately when the user navigates to the search destination.
 *
 * @param query Current search text managed by the caller.
 * @param onQueryChanged Callback invoked on every character change.
 * @param onClearQuery Callback invoked when the user taps the clear icon.
 * @param modifier Optional [Modifier] for the root text field.
 */
@Composable
internal fun SearchInputBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    // Auto-focus on first entry to immediately open the keyboard.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        placeholder = {
            Text(
                text = stringResource(R.string.search_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = onClearQuery) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.search_clear_content_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        textStyle = MaterialTheme.typography.bodyLarge
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF101114, name = "SearchInputBar — empty")
@Composable
private fun SearchInputBarEmptyPreview() {
    AudiophileMusicPlayerTheme {
        SearchInputBar(
            query = "",
            onQueryChanged = {},
            onClearQuery = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101114, name = "SearchInputBar — with text")
@Composable
private fun SearchInputBarFilledPreview() {
    AudiophileMusicPlayerTheme {
        SearchInputBar(
            query = "Miles Davis",
            onQueryChanged = {},
            onClearQuery = {}
        )
    }
}

