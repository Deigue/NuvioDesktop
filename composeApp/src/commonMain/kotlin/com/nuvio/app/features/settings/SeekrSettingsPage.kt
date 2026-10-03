package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SeekrPreviews
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_seekr_api_key
import nuvio.composeapp.generated.resources.settings_seekr_api_key_description
import nuvio.composeapp.generated.resources.settings_seekr_api_key_placeholder
import nuvio.composeapp.generated.resources.settings_seekr_intro
import nuvio.composeapp.generated.resources.settings_seekr_key_checking
import nuvio.composeapp.generated.resources.settings_seekr_key_invalid
import nuvio.composeapp.generated.resources.settings_seekr_key_unreachable
import nuvio.composeapp.generated.resources.settings_seekr_key_valid
import nuvio.composeapp.generated.resources.settings_seekr_section_title
import org.jetbrains.compose.resources.stringResource

/**
 * Seekr is an outside service the user signs up to, so its key lives here with the other
 * integrations; which sources use it is the Seek bar thumbnails mode under Playback.
 */
internal fun LazyListScope.seekrSettingsContent(isTablet: Boolean) {
    item {
        val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
        val apiKey = playerSettings.seekrApiKey
        SettingsSection(
            title = stringResource(Res.string.settings_seekr_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors("seekr-key"),
            ) {
                SeekrInfoText(isTablet = isTablet, text = stringResource(Res.string.settings_seekr_intro))
                SettingsGroupDivider(isTablet = isTablet)
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_seekr_api_key),
                    description = stringResource(Res.string.settings_seekr_api_key_description),
                    value = apiKey,
                    placeholder = stringResource(Res.string.settings_seekr_api_key_placeholder),
                    isTablet = isTablet,
                    secret = true,
                    onSave = PlayerSettingsRepository::setSeekrApiKey,
                )
                if (apiKey.isNotBlank()) {
                    // The check is Seekr's own validate call, which spends no lookup or title.
                    var validity by remember(apiKey) { mutableStateOf<Boolean?>(null) }
                    var checked by remember(apiKey) { mutableStateOf(false) }
                    LaunchedEffect(apiKey) {
                        validity = SeekrPreviews.validateKey(apiKey)
                        checked = true
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SeekrInfoText(
                        isTablet = isTablet,
                        text = stringResource(
                            when {
                                !checked -> Res.string.settings_seekr_key_checking
                                validity == true -> Res.string.settings_seekr_key_valid
                                validity == false -> Res.string.settings_seekr_key_invalid
                                else -> Res.string.settings_seekr_key_unreachable
                            },
                        ),
                        isError = checked && validity == false,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeekrInfoText(isTablet: Boolean, text: String, isError: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = if (isTablet) 14.dp else 12.dp,
            ),
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
