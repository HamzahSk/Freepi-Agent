package rofil.movie.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rofil.movie.app.ui.theme.Outline
import rofil.movie.app.ui.theme.Primary
import rofil.movie.app.ui.theme.Surface
import rofil.movie.app.ui.theme.SurfaceElevated
import rofil.movie.app.ui.theme.TextSecondary

data class RemoteSourceOption(val id: String, val name: String, val version: String = "", val language: String = "", val website: String = "")

@Composable
fun SettingsScreen(
    selectedSource: String,
    sources: List<RemoteSourceOption>,
    manifestStatus: String,
    syncing: Boolean,
    autoPlayInline: Boolean,
    onSourceSelect: (String) -> Unit,
    onSync: () -> Unit,
    onClearCache: () -> Unit,
    onAutoPlayChange: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Settings", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "Keep your catalog, runtime, and playback preferences in one place.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                Icon(Icons.Filled.Settings, contentDescription = null, tint = Primary)
            }
        }

        item {
            SettingsSectionCard(
                title = "Remote source",
                subtitle = "The active source supplies catalog and stream data through the synced runtime.",
                icon = { Icon(Icons.Filled.Storage, contentDescription = null, tint = Primary) },
            ) {
                sources.forEach { source ->
                    val selected = source.id == selectedSource
                    OutlinedButton(
                        onClick = { onSourceSelect(source.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) Primary.copy(alpha = 0.14f) else Color.Transparent,
                            contentColor = if (selected) Primary else MaterialTheme.colorScheme.onSurface,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Primary else Outline),
                    ) {
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Filled.Storage,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(source.name)
                            Text(listOf(source.version, source.language, source.website).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        }
                        if (selected) Text("Active", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider(color = Outline.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudSync, contentDescription = null, tint = TextSecondary)
                    Text(
                        manifestStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    IconButton(onClick = onSync, enabled = !syncing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Sync source registry", tint = Primary)
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Playback",
                subtitle = "Tune the first frame you see when opening a title.",
                icon = { Icon(Icons.Filled.PlayCircle, contentDescription = null, tint = Primary) },
            ) {
                PreferenceRow(
                    title = "Auto-play inline player",
                    subtitle = "Resolve and start the selected provider as the detail screen opens.",
                    checked = autoPlayInline,
                    onCheckedChange = onAutoPlayChange,
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "Storage & diagnostics",
                subtitle = "Room keeps favorites and history local. Remote modules are hash-verified before use.",
                icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = Primary) },
            ) {
                Text(
                    "Catalog cache, HTTP responses, manifest snapshots, and runtime modules can be cleared together.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onClearCache,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceElevated,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                    Text("Clear synced cache", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        item {
            Text(
                "For stage-level scraping diagnostics, use adb logcat -s FreepiScrape. URLs and page contents are redacted from those logs.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Column(Modifier.padding(start = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun PreferenceRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
