package com.nuvio.tv.ui.screens.party

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.party.ActiveParty
import com.nuvio.tv.core.party.PARTY_CODE_LENGTH
import com.nuvio.tv.core.party.PartyError
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.PartyRepository
import com.nuvio.tv.core.party.isValidPartyCode
import com.nuvio.tv.core.party.normalizePartyCode
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import com.nuvio.tv.ui.screens.settings.settingsHeaderInset
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PartyScreenMode {
    Join,
    Joined,
    Party,
}

@Composable
fun PartyScreen(
    initialCode: String,
    onBackPress: () -> Unit,
    onWatch: (PartyMedia) -> Unit,
    viewModel: PartyViewModel = hiltViewModel(),
) {
    val repository = viewModel.party
    val state by repository.state.collectAsStateWithLifecycle()
    val party = state.party
    var joined by remember { mutableStateOf<ActiveParty?>(null) }
    var pendingCode by remember {
        mutableStateOf(initialCode.takeIf { isValidPartyCode(it) && normalizePartyCode(it) != party?.code }.orEmpty())
    }
    var joining by remember { mutableStateOf(party == null || pendingCode.isNotEmpty()) }
    val mode = when {
        joined != null -> PartyScreenMode.Joined
        party != null && !joining -> PartyScreenMode.Party
        else -> PartyScreenMode.Join
    }
    val shown = joined ?: party

    BackHandler { onBackPress() }

    LaunchedEffect(joined) {
        val current = joined ?: return@LaunchedEffect
        delay(1600)
        onWatch(current.media)
        joined = null
    }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.party_title),
        subtitle = stringResource(R.string.party_settings_description),
        classicContainer = false,
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.party_title),
            subtitle = shown
                ?.takeIf { mode != PartyScreenMode.Join }
                ?.member(shown.hostMemberId)
                ?.displayName
                ?.let { stringResource(R.string.party_hosted_by, it) }
                ?: stringResource(R.string.party_settings_description),
        )
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(320), initialScale = 0.96f)) togetherWith fadeOut(tween(140))
            },
            label = "partyScreenMode",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = settingsHeaderInset()),
        ) { target ->
            when (target) {
                PartyScreenMode.Join -> JoinContent(
                    repository = repository,
                    initialCode = pendingCode,
                    onJoined = { newParty ->
                        pendingCode = ""
                        joining = false
                        joined = newParty
                    },
                )
                PartyScreenMode.Joined -> shown?.let { JoinedContent(it) }
                PartyScreenMode.Party -> party?.let { PartyContent(repository, it, onWatch) }
            }
        }
    }
}

@Composable
private fun JoinContent(
    repository: PartyRepository,
    initialCode: String,
    onJoined: (ActiveParty) -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val available by repository.available.collectAsStateWithLifecycle()
    val canUse by repository.canUse.collectAsStateWithLifecycle()
    val profile by repository.profile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val firstKeyFocusRequester = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    val autoJoin = remember { isValidPartyCode(initialCode) }
    var code by remember {
        mutableStateOf(if (autoJoin) "" else normalizePartyCode(initialCode).take(PARTY_CODE_LENGTH))
    }
    var error by remember { mutableStateOf<PartyError?>(null) }
    val blockedError = when {
        !available -> PartyError.Unavailable
        !canUse -> PartyError.SignInRequired
        else -> null
    }
    val shownError = blockedError ?: error

    fun submit() {
        if (!isValidPartyCode(code) || state.busy || blockedError != null) return
        scope.launch {
            error = null
            val result = repository.join(code)
            val joinedParty = repository.state.value.party
            if (result == null && joinedParty != null) {
                onJoined(joinedParty)
            } else {
                error = result ?: PartyError.Network
                shake.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 420
                        -14f at 60
                        12f at 120
                        -8f at 180
                        6f at 240
                        -3f at 300
                    },
                )
            }
        }
    }

    fun changeCode(value: String) {
        if (state.busy || value == code) return
        code = value
        error = null
        if (isValidPartyCode(value)) submit()
    }

    LaunchedEffect(Unit) {
        if (blockedError != null) return@LaunchedEffect
        if (autoJoin) {
            val target = normalizePartyCode(initialCode).take(PARTY_CODE_LENGTH)
            delay(250)
            target.indices.forEach { index ->
                delay(60)
                code = target.take(index + 1)
            }
            submit()
        }
        firstKeyFocusRequester.requestFocusAfterFrames()
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SeatsHero(
                profile = profile,
                size = 64.dp,
                ringColor = NuvioTheme.colors.Background,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Text(
                text = stringResource(R.string.party_join_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.party_join_description),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary,
            )
            CodeSlots(
                code = code,
                activeIndex = if (blockedError == null && !state.busy) code.length.coerceAtMost(PARTY_CODE_LENGTH - 1) else null,
                textStyle = MaterialTheme.typography.headlineLarge,
                slotHeight = 64.dp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .graphicsLayer { translationX = shake.value.dp.toPx() },
            )
            AnimatedContent(
                targetState = shownError,
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 }) togetherWith fadeOut(tween(120))
                },
                label = "partyJoinError",
            ) { current ->
                Text(
                    text = current?.let { stringResource(it.messageRes) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.Error,
                    minLines = 1,
                )
            }
            Text(
                text = stringResource(R.string.party_host_hint),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
            )
        }
        CodeKeypad(
            enabled = blockedError == null,
            canSubmit = isValidPartyCode(code),
            busy = state.busy,
            onKey = { character -> if (code.length < PARTY_CODE_LENGTH) changeCode(code + character) },
            onDelete = { changeCode(code.dropLast(1)) },
            onSubmit = ::submit,
            firstKeyFocusRequester = firstKeyFocusRequester,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun JoinedContent(party: ActiveParty) {
    val host = party.member(party.hostMemberId)?.displayName
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        AvatarStack(
            members = party.members,
            size = 88.dp,
            animateIn = true,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(R.string.party_joined_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
        )
        if (host != null) {
            Text(
                text = stringResource(R.string.party_hosted_by, host),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
        }
        Text(
            text = party.media.displayTitle(),
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PartyContent(
    repository: PartyRepository,
    party: ActiveParty,
    onWatch: (PartyMedia) -> Unit,
) {
    val listState = rememberLazyListState()
    val watchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { watchFocusRequester.requestFocusAfterFrames() }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val image = party.media.background ?: party.media.poster
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 190.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(NuvioTheme.radii.xl)),
                )
            }
            Text(
                text = party.media.displayTitle(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            CodeSection(
                code = party.code,
                connected = party.connected,
                slotHeight = 52.dp,
            )
        }
        Box(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "party_actions") {
                    SettingsGroupCard {
                        SettingsActionRow(
                            title = stringResource(R.string.party_watch_with_party),
                            subtitle = party.media.displayTitle(),
                            onClick = { onWatch(party.media) },
                            leadingIcon = Icons.Rounded.PlayArrow,
                            modifier = Modifier.focusRequester(watchFocusRequester),
                        )
                        PartyActions(
                            party = party,
                            onLeave = repository::leave,
                            onEnd = repository::end,
                        )
                    }
                }
                item(key = "party_members") {
                    SettingsGroupCard {
                        MembersSection(
                            party = party,
                            ringColor = NuvioTheme.colors.BackgroundCard,
                            onRemove = { member -> repository.removeMember(member.id) },
                        )
                        if (!party.isHost && party.hostOnly) HostOnlyNote()
                    }
                }
                if (party.isHost) {
                    item(key = "party_host_settings") {
                        SettingsGroupCard {
                            HostSettings(
                                party = party,
                                onHostOnlyChange = repository::setHostOnly,
                                onSharingChange = repository::setSharing,
                            )
                        }
                    }
                }
            }
            SettingsVerticalScrollIndicators(state = listState)
        }
    }
}
