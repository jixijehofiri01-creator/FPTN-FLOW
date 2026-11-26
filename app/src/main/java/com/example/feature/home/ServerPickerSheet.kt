package com.example.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.R
import com.example.domain.model.FptnServer
import com.example.domain.model.ServerSortOrder
import com.example.core.haptics.HapticEngine
import com.example.core.ui.flags.ServerFlagIcon
import com.example.core.ui.localization.localizedTitle
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily

/**
 * Rock-solid non-collapsing server selector modal:
 * - Hosted in a bottom-aligned Dialog sheet so scrolling the LazyColumn NEVER accidentally collapses/dismisses it!
 * - Includes compact Sort Toggle ("По порядку" vs "По пингу") persisted in settings so servers stay rock-steady during auto-ping.
 */
@Composable
fun ServerPickerSheet(
    servers: List<FptnServer>,
    selectedServerId: Long?,
    autoSelectBest: Boolean,
    sortOrder: ServerSortOrder,
    isPinging: Boolean,
    hapticEngine: HapticEngine,
    showCoatOfArms: Boolean = true,
    onSelectSortOrder: (ServerSortOrder) -> Unit,
    onSelectAuto: () -> Unit,
    onSelectServer: (FptnServer) -> Unit,
    onRefreshPing: () -> Unit,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current

    val profileNames = remember(servers) {
        servers.map { it.profileName }.filter { it.isNotBlank() }.distinct()
    }
    var selectedProfileFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredServers = remember(servers, selectedProfileFilter, searchQuery, sortOrder) {
        val q = searchQuery.trim().lowercase()
        val base = servers.filter { srv ->
            (selectedProfileFilter == null || srv.profileName == selectedProfileFilter) &&
                (q.isEmpty() ||
                    srv.displayTitle.lowercase().contains(q) ||
                    srv.host.lowercase().contains(q))
        }
        when (sortOrder) {
            ServerSortOrder.ORIGINAL -> base
            ServerSortOrder.BY_PING -> base.sortedWith(
                compareBy<FptnServer> { it.pingMs ?: Int.MAX_VALUE }.thenBy { it.name },
            )
        }
    }

    // Capture host Activity's real navigation bar height BEFORE entering Dialog's separate window
    // (Dialog windows with decorFitsSystemWindows=false frequently report 0.dp for navigationBars on OEM ROMs).
    val density = LocalDensity.current
    val hostNavBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hostRootNavBottomDp = with(density) {
        (ViewCompat.getRootWindowInsets(view)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            ?.bottom ?: 0).toDp()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val dialogView = LocalView.current
        val dialogNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val dialogRootNavBottomDp = with(density) {
            (ViewCompat.getRootWindowInsets(dialogView)
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
                ?.bottom ?: 0).toDp()
        }
        val safeNavBottom = maxOf(dialogNavBottom, hostNavBarBottom, hostRootNavBottomDp, dialogRootNavBottomDp)

        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        ) {
            val isTablet = maxWidth >= 600.dp
            val containerAlignment = if (isTablet) Alignment.Center else Alignment.BottomCenter
            val modalShape = if (isTablet) {
                RoundedCornerShape(28.dp)
            } else {
                RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            }
            val modalModifier = if (isTablet) {
                Modifier
                    .align(containerAlignment)
                    .padding(24.dp)
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .fillMaxHeight(0.82f)
            } else {
                Modifier
                    .align(containerAlignment)
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .fillMaxHeight(0.86f)
            }
            Surface(
                modifier = modalModifier
                    .clickable(enabled = false) {}
                    .testTag("server_picker_modal"),
                shape = modalShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 18.dp,
                            bottom = if (isTablet) 18.dp else safeNavBottom + 16.dp,
                        ),
                ) {
                    // Header: Title + Ping refresh + Explicit Close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.home_select_server_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = tr(
                                    "Доступно: ${servers.size} • ${sortOrder.localizedTitle().lowercase()}",
                                    "Available: ${servers.size} • ${sortOrder.localizedTitle().lowercase()}",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalIconButton(
                            onClick = {
                                hapticEngine.performSegmentTick(view)
                                onRefreshPing()
                            },
                            enabled = !isPinging,
                            modifier = Modifier.testTag("sheet_refresh_ping_button"),
                        ) {
                            if (isPinging) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.home_ping_refresh),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                hapticEngine.performSegmentTick(view)
                                onDismiss()
                            },
                            modifier = Modifier.testTag("server_picker_close_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close_cd),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Compact Sort Toggle ("По порядку" vs "По пингу") + optional profile chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = sortOrder == ServerSortOrder.ORIGINAL,
                            onClick = {
                                hapticEngine.performSegmentTick(view)
                                onSelectSortOrder(ServerSortOrder.ORIGINAL)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.FormatListNumbered,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            label = { Text(ServerSortOrder.ORIGINAL.localizedTitle()) },
                            modifier = Modifier.testTag("server_sort_original_chip"),
                        )
                        FilterChip(
                            selected = sortOrder == ServerSortOrder.BY_PING,
                            onClick = {
                                hapticEngine.performSegmentTick(view)
                                onSelectSortOrder(ServerSortOrder.BY_PING)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            label = { Text(ServerSortOrder.BY_PING.localizedTitle()) },
                            modifier = Modifier.testTag("server_sort_ping_chip"),
                        )

                        if (profileNames.size > 1) {
                            Spacer(modifier = Modifier.width(4.dp))
                            FilterChip(
                                selected = selectedProfileFilter == null,
                                onClick = {
                                    hapticEngine.performSegmentTick(view)
                                    selectedProfileFilter = null
                                },
                                label = { Text(tr("Все (${servers.size})", "All (${servers.size})")) },
                            )
                            profileNames.forEach { profileName ->
                                FilterChip(
                                    selected = selectedProfileFilter == profileName,
                                    onClick = {
                                        hapticEngine.performSegmentTick(view)
                                        selectedProfileFilter = profileName
                                    },
                                    label = { Text(profileName) },
                                )
                            }
                        }
                    }

                    if (servers.size > 3) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            placeholder = { Text(tr("Поиск страны, узла или профиля…", "Search country, server, or profile…")) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("server_picker_search"),
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Auto-select best ping row
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (autoSelectBest) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("server_auto_option")
                            .clickable {
                                hapticEngine.performConfirm(view)
                                onSelectAuto()
                                onDismiss()
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = if (autoSelectBest) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.home_server_auto),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (autoSelectBest) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    text = tr("Автоматически выбирает самый быстрый узел", "Automatically selects the fastest server"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (autoSelectBest) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (autoSelectBest) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(8.dp))

                    // Full-weight scrollable list prevents accidental sheet dismissal and preserves bottom inset.
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("server_picker_list"),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 84.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = filteredServers,
                            key = { it.id },
                            contentType = { "server_row" },
                        ) { server ->
                            val isSelected = !autoSelectBest && server.id == selectedServerId
                            ServerPickerRow(
                                server = server,
                                isSelected = isSelected,
                                showCoatOfArms = showCoatOfArms,
                                onClick = {
                                    hapticEngine.performConfirm(view)
                                    onSelectServer(server)
                                    onDismiss()
                                },
                            )
                        }
                        item(key = "bottom_safe_spacer", contentType = "spacer") {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerPickerRow(
    server: FptnServer,
    isSelected: Boolean,
    showCoatOfArms: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_item_${server.id}")
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServerFlagIcon(
                countryCode = server.countryCode,
                isCensoredZone = server.isCensoredZone,
                size = 34.dp,
                showCoatOfArms = showCoatOfArms,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.cleanDisplayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = server.endpoint,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (server.isCensoredZone) {
                        Text(
                            text = stringResource(R.string.home_censored_server_tag),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            PingBadge(pingMs = server.pingMs)
        }
    }
}

@Composable
fun PingBadge(pingMs: Int?) {
    val msUnit = tr("мс", "ms")
    val (label, containerColor, contentColor) = when {
        pingMs == null -> Triple(
            "— $msUnit",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pingMs < 90 -> Triple(
            "$pingMs $msUnit",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        pingMs < 200 -> Triple(
            "$pingMs $msUnit",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        else -> Triple(
            "$pingMs $msUnit",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
