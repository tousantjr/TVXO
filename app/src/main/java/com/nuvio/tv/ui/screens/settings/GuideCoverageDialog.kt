package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.coverage.GuideCoverage
import com.nuvio.tv.livetv.coverage.GuideCoverageState
import com.nuvio.tv.livetv.coverage.GuideCoverageViewModel
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.NumberFormat

private const val GROUPS_SHOWN = 25
private const val MISSING_GROUPS_SHOWN = 20
private const val CHANNEL_PAGE = 50

/** Settings > Live TV > Guide Coverage: which channels have TV guide listings, and which don't. */
@Composable
internal fun GuideCoverageDialog(onDismiss: () -> Unit) {
    val viewModel: GuideCoverageViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(state is GuideCoverageState.Loading) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    val subtitle = when (val s = state) {
        GuideCoverageState.Loading -> stringResource(R.string.guide_coverage_loading)
        GuideCoverageState.NoPlaylists -> stringResource(R.string.guide_coverage_no_playlists)
        is GuideCoverageState.NoGuide ->
            if (s.channels == 0) stringResource(R.string.guide_coverage_no_channels)
            else stringResource(R.string.guide_coverage_no_guide, s.channels)
        is GuideCoverageState.Failed -> stringResource(R.string.guide_coverage_failed, s.message)
        is GuideCoverageState.Ready -> if (s.report.isEmpty) stringResource(R.string.guide_coverage_no_channels)
            else stringResource(R.string.guide_coverage_headline, s.report.percent)
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.guide_coverage_title),
        subtitle = subtitle,
        width = 820.dp,
        usePlatformDefaultWidth = false
    ) {
        val ready = state as? GuideCoverageState.Ready
        if (ready == null || ready.report.isEmpty) {
            SettingsDialogActionRow {
                if (state !is GuideCoverageState.Loading) {
                    SettingsActionRow(
                        title = stringResource(R.string.guide_coverage_refresh),
                        subtitle = null,
                        onClick = viewModel::refresh,
                        modifier = Modifier.weight(1f).focusRequester(first)
                    )
                }
                SettingsDialogActionButton(text = stringResource(R.string.guide_coverage_close), onClick = onDismiss)
            }
        } else {
            CoverageReport(ready.report, ready.showPlaylists, first, viewModel::refresh)
        }
    }
}

@Composable
private fun CoverageReport(
    report: GuideCoverage.Report,
    showPlaylists: Boolean,
    first: FocusRequester,
    onRefresh: () -> Unit
) {
    val nf = remember { NumberFormat.getIntegerInstance() }
    var allGroups by remember(report) { mutableStateOf(false) }
    var allMissingGroups by remember(report) { mutableStateOf(false) }
    // How many channel names each missing group shows (0 = collapsed). Grows a page at a time.
    val shown = remember(report) { mutableStateMapOf<String, Int>() }
    val strValue: @Composable (GuideCoverage.Tally) -> String = {
        stringResource(R.string.guide_coverage_value, nf.format(it.covered), nf.format(it.total), it.percent)
    }
    val headings = CoverageHeadings(
        byGroup = stringResource(R.string.guide_coverage_by_group),
        byPlaylist = stringResource(R.string.guide_coverage_by_playlist),
        byGuide = stringResource(R.string.guide_coverage_by_guide),
        missing = stringResource(R.string.guide_coverage_missing)
    )
    val summary = stringResource(
        R.string.guide_coverage_summary,
        nf.format(report.covered), nf.format(report.total), nf.format(report.missingCount), nf.format(report.programs)
    )
    val groupValues = report.groups.associate { it.id to strValue(it) }
    val playlistValues = report.playlists.associate { it.id to strValue(it) }
    val guideValues = report.guides.associate { it.id to stringResource(R.string.guide_coverage_guide_value, nf.format(it.channels)) }
    val guideSubs = report.guides.associate { it.id to stringResource(R.string.guide_coverage_guide_programmes, nf.format(it.programs)) }
    val missingValues = report.missing.associate { it.groupId to stringResource(R.string.guide_coverage_missing_count, nf.format(it.count)) }
    val groupHint = stringResource(R.string.guide_coverage_group_hint)
    val noneMissing = stringResource(R.string.guide_coverage_none_missing)
    val moreGroups = stringResource(R.string.guide_coverage_more_groups, nf.format(report.groups.size))
    val moreMissingGroups = stringResource(R.string.guide_coverage_more_groups, nf.format(report.missing.size))
    val lockedText = report.missing.associate { it.groupId to stringResource(R.string.guide_coverage_locked, nf.format(it.lockedCount)) }
    val moreText: @Composable (Int) -> String = { stringResource(R.string.guide_coverage_more_channels, nf.format(it)) }
    val moreByGroup = report.missing.associate { g -> g.groupId to moreText((g.channels.size - (shown[g.groupId] ?: 0)).coerceAtLeast(0)) }

    LazyColumn(
        modifier = Modifier.heightIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
    ) {
        item(key = "refresh") {
            SettingsActionRow(
                title = stringResource(R.string.guide_coverage_refresh),
                subtitle = summary,
                onClick = onRefresh,
                modifier = Modifier.focusRequester(first)
            )
        }

        heading("h_groups", headings.byGroup)
        val groups = if (allGroups) report.groups else report.groups.take(GROUPS_SHOWN)
        items(groups, key = { "g_" + it.id }) { g ->
            SettingsActionRow(title = g.name, subtitle = null, value = groupValues[g.id], onClick = {})
        }
        if (!allGroups && report.groups.size > GROUPS_SHOWN) {
            item(key = "g_more") { SettingsActionRow(title = moreGroups, subtitle = null, onClick = { allGroups = true }) }
        }

        if (showPlaylists) {
            heading("h_playlists", headings.byPlaylist)
            items(report.playlists, key = { "p_" + it.id }) { p ->
                SettingsActionRow(title = p.name, subtitle = null, value = playlistValues[p.id], onClick = {})
            }
        }

        if (report.guides.isNotEmpty()) {
            heading("h_guides", headings.byGuide)
            items(report.guides, key = { "e_" + it.id }) { e ->
                SettingsActionRow(title = e.name, subtitle = guideSubs[e.id], value = guideValues[e.id], onClick = {})
            }
        }

        heading("h_missing", headings.missing)
        if (report.missing.isEmpty()) {
            item(key = "m_none") { SettingsActionRow(title = noneMissing, subtitle = null, onClick = {}) }
        }
        val missing = if (allMissingGroups) report.missing else report.missing.take(MISSING_GROUPS_SHOWN)
        missing.forEach { g ->
            val count = shown[g.groupId] ?: 0
            item(key = "m_" + g.groupId) {
                SettingsActionRow(
                    title = g.group,
                    subtitle = if (count == 0 && g.channels.isNotEmpty()) groupHint else null,
                    value = missingValues[g.groupId],
                    onClick = {
                        if (g.channels.isNotEmpty()) shown[g.groupId] = if (count == 0) CHANNEL_PAGE else 0
                    }
                )
            }
            if (count > 0) {
                items(g.channels.take(count), key = { "mc_" + g.groupId + "_" + it.key }) { ch ->
                    SettingsActionRow(
                        title = ch.name,
                        subtitle = ch.playlistName,
                        onClick = {},
                        modifier = Modifier.padding(start = NuvioTheme.spacing.xl)
                    )
                }
                if (g.channels.size > count) {
                    item(key = "mm_" + g.groupId) {
                        SettingsActionRow(
                            title = moreByGroup[g.groupId].orEmpty(),
                            subtitle = null,
                            onClick = { shown[g.groupId] = count + CHANNEL_PAGE * 2 },
                            modifier = Modifier.padding(start = NuvioTheme.spacing.xl)
                        )
                    }
                }
            }
            if (g.lockedCount > 0 && (count > 0 || g.channels.isEmpty())) {
                item(key = "ml_" + g.groupId) {
                    SettingsActionRow(
                        title = lockedText[g.groupId].orEmpty(),
                        subtitle = null,
                        onClick = {},
                        modifier = Modifier.padding(start = NuvioTheme.spacing.xl)
                    )
                }
            }
        }
        if (!allMissingGroups && report.missing.size > MISSING_GROUPS_SHOWN) {
            item(key = "m_more") { SettingsActionRow(title = moreMissingGroups, subtitle = null, onClick = { allMissingGroups = true }) }
        }
    }
}

private class CoverageHeadings(val byGroup: String, val byPlaylist: String, val byGuide: String, val missing: String)

private fun LazyListScope.heading(key: String, text: String) {
    item(key = key) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(top = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.xxs)
        )
    }
}
