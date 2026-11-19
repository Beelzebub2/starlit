package com.nuvio.app.features.myanimelist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.lists.ListPreferencesRepository
import com.nuvio.app.features.lists.ListServiceId
import com.nuvio.app.features.lists.ListTrackerControls
import com.nuvio.app.features.lists.listsTabTitle
import com.nuvio.app.features.lists.rememberListsTabServices
import com.nuvio.app.features.lists.MalTitleHooks
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable private fun rememberMalConnection(): MalConnectionState {
    val profile by ProfileRepository.state.collectAsState()
    LaunchedEffect(profile.activeProfile) { MyAnimeListRepository.ensureLoaded() }
    return MyAnimeListRepository.state.collectAsState().value
}
/** The lists tab shows when at least one connected service is chosen for it. */
@Composable internal actual fun MyAnimeListTabVisible(): Boolean = rememberListsTabServices().isNotEmpty()

/** The only tab service's name, or "Lists" when several share the tab. */
@Composable internal actual fun listsTabLabel(): String = listsTabTitle(rememberListsTabServices())

internal actual fun myAnimeListPlaybackUpdateThresholdPercent(): Int =
    MyAnimeListRepository.playbackUpdateThresholdPercent()

internal actual suspend fun markMyAnimeListRewatchOnPlayback(
    meta: MetaDetails,
    playbackProfileId: Int,
    positionMs: Long,
    durationMs: Long,
    seasonNumber: Int?,
    episodeNumber: Int?,
    completionReached: Boolean,
): Boolean {
    return MyAnimeListRepository.markRewatchingIfCompleted(
        meta, playbackProfileId, positionMs, durationMs, seasonNumber, episodeNumber, completionReached,
    )
}

@Composable internal actual fun MyAnimeListSettingsCard() {
    val connection = rememberMalConnection()
    var setup by remember { mutableStateOf(false) }
    val syncScope = rememberCoroutineScope()
    val tvSyncSuccessText = stringResource(Res.string.mal_tv_sync_success)
    val tvSyncFailureText = stringResource(Res.string.mal_tv_sync_failure)
    var tvSyncMessage by remember(connection.profileId) { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MyAnimeList", style = MaterialTheme.typography.titleMedium)
            Text(if (!connection.connected) "Connect your anime list to set statuses and ratings."
                else if (connection.username.isBlank() || connection.username == "Connected") "Connected to MyAnimeList."
                else "Connected as ${connection.username}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !connection.busy, onClick = { setup = true }) { Text(if (connection.connected) "Reconnect" else "Set up / Connect") }
                if (connection.connected) TextButton(onClick = MyAnimeListRepository::disconnect) { Text(stringResource(Res.string.remote_disconnect)) }
                if (connection.connecting) TextButton(onClick = MyAnimeListRepository::cancelConnect) { Text(stringResource(Res.string.action_cancel)) }
            }
            if (connection.connected) {
                TextButton(enabled = !connection.busy, onClick = {
                    syncScope.launch {
                        tvSyncMessage = try { MyAnimeListRepository.syncConnectionToAccount(); tvSyncSuccessText }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { tvSyncFailureText }
                    }
                }) { Text(stringResource(Res.string.mal_tv_sync_button)) }
                Text(stringResource(Res.string.mal_tv_sync_description), style = MaterialTheme.typography.bodySmall)
                tvSyncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Show MyAnimeList tab", style = MaterialTheme.typography.bodyMedium)
                    Text("Browse your MAL anime list from the Starlit navigation bar.", style = MaterialTheme.typography.bodySmall)
                }
                // Until the lists settings card replaces this switch, it also chooses MyAnimeList for the lists tab.
                val listPrefs by ListPreferencesRepository.state.collectAsState()
                Switch(
                    checked = ListServiceId.MYANIMELIST in listPrefs.inTab && connection.connected,
                    onCheckedChange = { visible ->
                        MyAnimeListRepository.setTabVisible(visible)
                        ListPreferencesRepository.setInTab(ListServiceId.MYANIMELIST, visible)
                    },
                    enabled = connection.connected && !connection.busy,
                )
            }
            Text("Automatic MAL update: ${connection.playbackUpdateThresholdPercent}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = connection.playbackUpdateThresholdPercent.toFloat(),
                onValueChange = { MyAnimeListRepository.setPlaybackUpdateThresholdPercent(it.roundToInt()) },
                valueRange = MinimumMalPlaybackUpdateThresholdPercent.toFloat()..MaximumMalPlaybackUpdateThresholdPercent.toFloat(),
                steps = 7,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "A completed title is marked Rewatching only when playback reaches this percentage of an episode. " +
                    "Short playback sessions do not update MAL. Episode counts are still edited manually.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (connection.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            connection.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (setup) MalSetupDialog(connection, onDismiss = { setup = false })
}

@Composable internal actual fun MyAnimeListControls(meta: MetaDetails) = ListTrackerControls(meta)

/**
 * Supplies the MyAnimeList-only hooks of the generic title tracker (id resolution, connect and link entry) and
 * hosts the dialogs behind them, keyed so a MAL reconnect or profile change starts the tracker afresh.
 */
@Composable internal fun MalTitleLinkHost(meta: MetaDetails, content: @Composable (MalTitleHooks) -> Unit) {
    val connection = rememberMalConnection()
    var setup by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var linkRevision by remember(meta.type, meta.id) { mutableIntStateOf(0) }
    val hooks = MalTitleHooks(
        connected = connection.connected,
        busy = connection.busy,
        linkRevision = linkRevision,
        resolveId = { withTimeoutOrNull(20_000L) { MyAnimeListRepository.resolveId(it) } },
        onConnect = { setup = true },
        onChooseEntry = { search = true },
        revision = connection.revision,
    )
    key(meta.type, meta.id, connection.profileId) { content(hooks) }
    if (setup) MalSetupDialog(connection, onDismiss = { setup = false })
    if (search) MalSearchDialog(meta, onDismiss = { search = false }, onSelect = { selected ->
        MyAnimeListRepository.link(meta, selected.id)
        linkRevision++
        search = false
    })
}

@Composable internal fun MalSetupDialog(connection: MalConnectionState, onDismiss: () -> Unit) {
    var clientId by remember { mutableStateOf(connection.clientId) }
    var clientSecret by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connect MyAnimeList") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("In MyAnimeList API setup, choose App Type Other (recommended, no secret). Existing Web apps require their client secret. Your MAL password stays on MyAnimeList.")
            TextButton(onClick = { uri.openUri("https://myanimelist.net/apiconfig") }) { Text("Open MyAnimeList API setup") }
            Text("Set the App Redirect URL to exactly:")
            SelectionContainer { Text(MAL_REDIRECT, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(value = clientId, onValueChange = { clientId = it.trim() }, label = { Text("Public client ID") }, singleLine = true)
            OutlinedTextField(value = clientSecret, onValueChange = { clientSecret = it.trim() },
                label = { Text("Client secret (Web apps only)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Text("The site shown on MAL's approval screen (such as example.com) is your registered app's homepage. You can correct it in MAL API setup; it does not determine the sign-in callback.")
            Text("Connect opens your browser. Approve access there, then return here. Tokens are protected by your Windows account.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = clientId.isNotBlank() && !connection.busy, onClick = {
        runCatching { MyAnimeListRepository.connect(clientId, clientSecret); onDismiss() }.onFailure { error = it.message }
    }) { Text(stringResource(Res.string.remote_pc_connect)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } })
}

@Composable private fun MalSearchDialog(meta: MetaDetails, onDismiss: () -> Unit, onSelect: (MalAnime) -> Unit) {
    var query by remember { mutableStateOf(meta.name) }
    var results by remember { mutableStateOf(emptyList<MalAnime>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() { if (busy) return; scope.launch { busy = true; error = null
        try { results = MyAnimeListRepository.search(query) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message } finally { busy = false } } }
    LaunchedEffect(meta.id) { search() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose this title on MyAnimeList") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true)
            TextButton(enabled = !busy && query.length >= 2, onClick = ::search) { Text(stringResource(Res.string.compose_nav_search)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            results.forEach { anime -> TextButton(onClick = { onSelect(anime) }) { Text(anime.title, maxLines = 2) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } })
}
