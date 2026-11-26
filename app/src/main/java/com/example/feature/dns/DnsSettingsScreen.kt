package com.example.feature.dns

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.DnsCategory
import com.example.domain.model.DnsMode
import com.example.domain.model.ManualOverrideKey
import com.example.domain.model.SavedDnsEntry
import com.example.domain.model.VpnSettings
import com.example.core.haptics.HapticEngine
import com.example.core.ui.components.SettingsCardGroup
import com.example.core.ui.components.SettingsDivider
import com.example.core.ui.localization.localizedDescription
import com.example.core.ui.localization.localizedSubtitle
import com.example.core.ui.localization.localizedTitle
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily
import com.example.feature.home.PingBadge
import com.example.feature.shared.FptnUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsSettingsScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onSelectDnsMode: (DnsMode) -> Unit,
    onSelectDnsEntry: (SavedDnsEntry) -> Unit,
    onAddCustomDnsEntry: (title: String, urlOrHost: String) -> Unit,
    onDeleteDnsEntry: (String) -> Unit,
    onRestoreDefaultDns: () -> Unit,
    onPingSmartDns: (autoPickFastest: Boolean) -> Unit,
    onSaveIpv4Dns: (primary: String, secondary: String) -> Unit,
    onResetDnsOverride: () -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val view = LocalView.current
    val settings = uiState.settings

    var selectedCategory by remember { mutableStateOf<DnsCategory?>(null) }
    var showAddForm by remember { mutableStateOf(false) }
    var newDnsTitle by remember { mutableStateOf("") }
    var newDnsUrl by remember { mutableStateOf("") }

    var dnsPrimaryInput by remember(settings.dnsPrimary) { mutableStateOf(settings.dnsPrimary) }
    var dnsSecondaryInput by remember(settings.dnsSecondary) { mutableStateOf(settings.dnsSecondary) }

    val visibleEntries = remember(settings.savedDnsEntries, selectedCategory) {
        if (selectedCategory == null) {
            settings.savedDnsEntries
        } else {
            settings.savedDnsEntries.filter { it.category == selectedCategory }
        }
    }

    val groupedEntries = remember(visibleEntries) {
        DnsCategory.entries.mapNotNull { cat ->
            val list = visibleEntries.filter { it.category == cat }
            if (list.isEmpty()) null else cat to list
        }
    }

    val missingDefaultsCount = remember(settings.savedDnsEntries) {
        val urls = settings.savedDnsEntries.map { it.urlOrHost.lowercase() }.toSet()
        VpnSettings.DEFAULT_DNS_ENTRIES.count { it.urlOrHost.lowercase() !in urls }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_dns_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("dns_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .testTag("dns_lazy_list"),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Section 1: Mutually-exclusive DNS mode selector (eliminates Smart DNS + AdBlock conflict!)
            item {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = tr("Режим DNS внутри VPN", "In-tunnel DNS mode"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = tr(
                            "Одновременно активен только один режим — никаких конфликтов между Smart DNS, блокировкой рекламы и ручным IPv4.",
                            "Only one mode is active at a time — avoiding conflicts between Smart DNS, ad blocking, and custom IPv4.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsCardGroup {
                        DnsMode.entries.forEachIndexed { idx, mode ->
                            val selected = settings.dnsMode == mode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dns_mode_${mode.name}")
                                    .clickable {
                                        hapticEngine.performToggle(view, enabled = true)
                                        onSelectDnsMode(mode)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = {
                                        hapticEngine.performToggle(view, enabled = true)
                                        onSelectDnsMode(mode)
                                    },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mode.localizedTitle(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    Text(
                                        text = mode.localizedSubtitle(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (idx < DnsMode.entries.lastIndex) SettingsDivider()
                        }
                    }
                }
            }

            if (ManualOverrideKey.DNS in settings.manualOverrides) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp)
                            .testTag("dns_forced_banner"),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("DNS зафиксирован вручную", "DNS manually locked"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    text = tr(
                                        "Пресеты и переподключения его не сбросят",
                                        "Network presets and reconnects will not override it",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    hapticEngine.performToggle(view, enabled = false)
                                    onResetDnsOverride()
                                },
                                modifier = Modifier.testTag("dns_reset_override_button"),
                            ) {
                                Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(tr("Авто", "Auto"))
                            }
                        }
                    }
                }
            }

            // Section 2A: Smart DNS Catalog (only when SMART_DNS_DOH is selected)
            if (settings.dnsMode == DnsMode.SMART_DNS_DOH) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = tr("Что такое Smart DNS?", "What is Smart DNS?"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Text(
                                text = tr(
                                    "Специальные DoH-резолверы, которые открывают доступ к ChatGPT, Gemini, Claude, Grok и Copilot без потери скорости на обычных сайтах. Международные адреса (без .ru) реже попадают под локальные сбои маршрутизации.",
                                    "Specialized DoH resolvers inside the tunnel that unlock ChatGPT, Gemini, Claude, Grok, and Copilot without slowing down regular traffic. Non-.ru endpoints are less affected by local routing issues.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        hapticEngine.performSuccess(view)
                                        onPingSmartDns(true)
                                    },
                                    enabled = !uiState.isPingingDns,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("dns_find_fastest_button"),
                                ) {
                                    if (uiState.isPingingDns) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(tr("Проверка…", "Testing…"))
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Bolt,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(tr("Найти быстрейший", "Find fastest"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }

                                FilledTonalButton(
                                    onClick = {
                                        hapticEngine.performNavigationClick(view)
                                        showAddForm = !showAddForm
                                    },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("dns_toggle_add_form_button"),
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(tr("Свой", "Add"))
                                }
                            }
                        }
                    }
                }

                // Inline Add Custom DNS Form
                item {
                    AnimatedVisibility(visible = showAddForm) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 600.dp),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = tr(
                                        "Добавить свой Smart DNS (DoH / DoT / IPv4)",
                                        "Add custom Smart DNS (DoH / DoT / IPv4)",
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                OutlinedTextField(
                                    value = newDnsTitle,
                                    onValueChange = { newDnsTitle = it },
                                    label = { Text(tr("Название (необязательно)", "Name (optional)")) },
                                    placeholder = { Text(tr("Мой DNS", "My DNS")) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("dns_custom_title_input"),
                                )
                                OutlinedTextField(
                                    value = newDnsUrl,
                                    onValueChange = { newDnsUrl = it },
                                    label = { Text(tr("DoH URL, DoT домен или IPv4", "DoH URL, DoT host, or IPv4")) },
                                    placeholder = { Text("https://magicinfo.art/dns-query") },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("dns_custom_url_input"),
                                )
                                Button(
                                    onClick = {
                                        hapticEngine.performSuccess(view)
                                        onAddCustomDnsEntry(newDnsTitle, newDnsUrl)
                                        newDnsTitle = ""
                                        newDnsUrl = ""
                                        showAddForm = false
                                    },
                                    enabled = newDnsUrl.isNotBlank(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("dns_custom_add_submit"),
                                ) {
                                    Text(tr("Сохранить и выбрать", "Save & select"))
                                }
                            }
                        }
                    }
                }

                // Category filter chips
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = {
                                hapticEngine.performNavigationClick(view)
                                selectedCategory = null
                            },
                            label = { Text(tr("Все (${settings.savedDnsEntries.size})", "All (${settings.savedDnsEntries.size})")) },
                            modifier = Modifier.testTag("dns_cat_all"),
                        )
                        DnsCategory.entries.forEach { cat ->
                            val count = settings.savedDnsEntries.count { it.category == cat }
                            if (count > 0) {
                                val shortLabel = when (cat) {
                                    DnsCategory.INTERNATIONAL_AI -> tr("Международные ($count)", "Global ($count)")
                                    DnsCategory.RU_ZONE_AI -> tr("Зона .ru ($count)", ".ru zone ($count)")
                                    DnsCategory.PUBLIC_RESERVE -> tr("Базовые ($count)", "Public ($count)")
                                    DnsCategory.CUSTOM -> tr("Свои ($count)", "Custom ($count)")
                                }
                                FilterChip(
                                    selected = selectedCategory == cat,
                                    onClick = {
                                        hapticEngine.performNavigationClick(view)
                                        selectedCategory = cat
                                    },
                                    label = { Text(shortLabel) },
                                    modifier = Modifier.testTag("dns_cat_${cat.name}"),
                                )
                            }
                        }
                    }
                }

                // Grouped Smart DNS cards
                groupedEntries.forEach { (category, entries) ->
                    item(key = "header_${category.name}") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 600.dp)
                                .padding(top = 4.dp),
                        ) {
                            Text(
                                text = category.localizedTitle(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = category.localizedDescription(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    items(entries, key = { it.id }) { entry ->
                        val isSelected = settings.customDnsUrl.equals(entry.urlOrHost, ignoreCase = true)
                        val rtt = uiState.dnsPingResults[entry.id]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 600.dp)
                                .testTag("dns_entry_${entry.id}")
                                .clickable {
                                    hapticEngine.performToggle(view, enabled = true)
                                    onSelectDnsEntry(entry)
                                },
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.Dns,
                                        contentDescription = null,
                                        tint = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.localizedTitle(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = entry.urlOrHost,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val localizedSub = entry.localizedSubtitle()
                                    if (localizedSub.isNotBlank()) {
                                        Text(
                                            text = localizedSub,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                if (rtt != null) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    PingBadge(pingMs = rtt)
                                }

                                IconButton(
                                    onClick = {
                                        hapticEngine.performDestructiveAction(view)
                                        onDeleteDnsEntry(entry.id)
                                    },
                                    modifier = Modifier.testTag("dns_delete_${entry.id}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = tr("Удалить DNS", "Delete DNS"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (missingDefaultsCount > 0) {
                    item {
                        OutlinedButton(
                            onClick = {
                                hapticEngine.performSuccess(view)
                                onRestoreDefaultDns()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 600.dp)
                                .height(48.dp)
                                .testTag("dns_restore_defaults_button"),
                        ) {
                            Icon(Icons.Rounded.Restore, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                tr(
                                    "Восстановить базу Smart DNS (+$missingDefaultsCount)",
                                    "Restore built-in Smart DNS (+$missingDefaultsCount)",
                                ),
                            )
                        }
                    }
                }
            }

            // Section 2B: Manual IPv4 DNS (only when CUSTOM_IPV4 is selected — never conflicting with DoH)
            if (settings.dnsMode == DnsMode.CUSTOM_IPV4) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = tr("Классический IPv4 DNS", "Classic IPv4 DNS"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = tr(
                                    "Передаётся напрямую в системный TUN-интерфейс (без локального DoH-прокси).",
                                    "Passed directly to the TUN interface (without local DoH proxy).",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = dnsPrimaryInput,
                                onValueChange = { dnsPrimaryInput = it },
                                label = { Text(tr("Основной IPv4 DNS", "Primary IPv4 DNS")) },
                                placeholder = { Text("1.1.1.1") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dns_ipv4_primary_input"),
                            )
                            OutlinedTextField(
                                value = dnsSecondaryInput,
                                onValueChange = { dnsSecondaryInput = it },
                                label = { Text(tr("Резервный IPv4 DNS", "Secondary IPv4 DNS")) },
                                placeholder = { Text("8.8.8.8") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dns_ipv4_secondary_input"),
                            )
                            Button(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onSaveIpv4Dns(dnsPrimaryInput, dnsSecondaryInput)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("dns_ipv4_save_button"),
                            ) {
                                Text(tr("Сохранить IPv4 DNS", "Save IPv4 DNS"))
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(64.dp)) }
        }
    }
}
