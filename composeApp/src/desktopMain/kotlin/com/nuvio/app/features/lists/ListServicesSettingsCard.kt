package com.nuvio.app.features.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvioSwitchColors
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.lists_settings_hint
import nuvio.composeapp.generated.resources.lists_settings_in_tab
import nuvio.composeapp.generated.resources.lists_settings_in_tab_for
import nuvio.composeapp.generated.resources.lists_settings_move_down
import nuvio.composeapp.generated.resources.lists_settings_move_up
import nuvio.composeapp.generated.resources.lists_settings_on_titles
import nuvio.composeapp.generated.resources.lists_settings_on_titles_for
import nuvio.composeapp.generated.resources.lists_settings_subtitle
import nuvio.composeapp.generated.resources.lists_settings_title
import org.jetbrains.compose.resources.stringResource

/** Settings card: order the connected list services and choose where each one shows (Lists tab, title pages). */
@Composable internal fun ListServicesSettingsCard() {
    val profile by ProfileRepository.state.collectAsState()
    LaunchedEffect(profile.activeProfile) { ListPreferencesRepository.ensureLoaded() }
    val prefs by ListPreferencesRepository.state.collectAsState()
    // Collected in the registry's fixed order so the same flows are read on every composition.
    val connectedIds = ListServices.all().filter { it.connected.collectAsState().value }.map { it.id }.toSet()
    val rows = prefs.order.filter { it in connectedIds }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.lists_settings_title), style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty()) {
                Text(stringResource(Res.string.lists_settings_hint), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(Res.string.lists_settings_subtitle), style = MaterialTheme.typography.bodySmall)
                rows.forEachIndexed { index, id ->
                    ListServiceSettingsRow(
                        id = id,
                        canMoveUp = index > 0,
                        canMoveDown = index < rows.lastIndex,
                        inTab = id in prefs.inTab,
                        onTitlePages = id in prefs.onTitlePages,
                        onMoveUp = { moveAmongConnected(id, rows, up = true) },
                        onMoveDown = { moveAmongConnected(id, rows, up = false) },
                    )
                }
            }
        }
    }
}

/** Swaps [id] with its visible neighbour; disconnected services in between are stepped over. */
private fun moveAmongConnected(id: ListServiceId, visible: List<ListServiceId>, up: Boolean) {
    val neighbour = visible.getOrNull(visible.indexOf(id) + if (up) -1 else 1) ?: return
    val order = ListPreferencesRepository.state.value.order
    repeat(kotlin.math.abs(order.indexOf(neighbour) - order.indexOf(id))) { ListPreferencesRepository.move(id, up) }
}

@Composable private fun ListServiceSettingsRow(
    id: ListServiceId,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    inTab: Boolean,
    onTitlePages: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val name = id.displayName
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ListLogo(id, contentDescription = null, size = 22.dp)
            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val up = stringResource(Res.string.lists_settings_move_up, name)
            val down = stringResource(Res.string.lists_settings_move_down, name)
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp).semantics { contentDescription = up }) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null)
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp).semantics { contentDescription = down }) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
            }
        }
        SettingsSwitchRow(
            label = stringResource(Res.string.lists_settings_in_tab),
            description = stringResource(Res.string.lists_settings_in_tab_for, name),
            checked = inTab,
            onChange = { ListPreferencesRepository.setInTab(id, it) },
        )
        SettingsSwitchRow(
            label = stringResource(Res.string.lists_settings_on_titles),
            description = stringResource(Res.string.lists_settings_on_titles_for, name),
            checked = onTitlePages,
            onChange = { ListPreferencesRepository.setOnTitlePages(id, it) },
        )
    }
}

@Composable private fun SettingsSwitchRow(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = nuvioSwitchColors(), modifier = Modifier.semantics { contentDescription = description })
    }
}
