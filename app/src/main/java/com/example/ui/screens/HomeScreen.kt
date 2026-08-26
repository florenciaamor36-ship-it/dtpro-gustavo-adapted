package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TunnelConfig
import com.example.ui.TunnelViewModel
import com.example.ui.components.ConnectButton
import com.example.ui.components.ConsoleLogView
import com.example.ui.components.StatsCard
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberCardLight
import com.example.ui.theme.CyberNavy
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonOrange
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: TunnelViewModel,
    onPrepareVpn: () -> Unit
) {
    val context = LocalContext.current
    val tunnelState by viewModel.tunnelState.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val allConfigs by viewModel.allConfigs.collectAsState()
    val selectedConfig by viewModel.selectedConfig.collectAsState()

    val showConfigEditor by viewModel.showConfigEditor.collectAsState()
    val editingConfig by viewModel.editingConfig.collectAsState()
    val showServersSheet by viewModel.showServersSheet.collectAsState()
    val showPayloadGenerator by viewModel.showPayloadGenerator.collectAsState()
    val showToolsDialog by viewModel.showToolsDialog.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val showImportExportDialog by viewModel.showImportExportDialog.collectAsState()

    val pingResult by viewModel.testPingResult.collectAsState()
    val isTestingPing by viewModel.isTestingPing.collectAsState()

    val activeConfig = selectedConfig ?: allConfigs.firstOrNull()
    val deviceHwid = remember { viewModel.getDeviceHwid() }

    Scaffold(
        containerColor = CyberNavy,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(NeonCyan.copy(alpha = 0.15f))
                                .border(1.dp, NeonCyan, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "LA CLAVE ARGENTINA",
                                color = TextPrimary,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "CLIENTE SSH & VPN TUN",
                                color = NeonCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.showImportExportDialog.value = true },
                        modifier = Modifier.testTag("import_export_button")
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Importar/Exportar", tint = NeonCyan)
                    }
                    IconButton(
                        onClick = { viewModel.showToolsDialog.value = true },
                        modifier = Modifier.testTag("tools_button")
                    ) {
                        Icon(imageVector = Icons.Default.Build, contentDescription = "Herramientas", tint = TextSecondary)
                    }
                    IconButton(
                        onClick = { viewModel.showSettingsDialog.value = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Ajustes", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberNavy)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            HwidDeviceCard(
                hwid = deviceHwid,
                onCopy = { viewModel.copyDeviceHwid(context) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            ActiveServerCard(
                config = activeConfig,
                onSelectServer = { viewModel.showServersSheet.value = true },
                onEditServer = {
                    if (activeConfig?.isLocked != true) {
                        viewModel.editingConfig.value = activeConfig
                        viewModel.showConfigEditor.value = true
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickToolButton(
                    icon = Icons.Default.Code,
                    label = "Generar Payload",
                    onClick = { viewModel.showPayloadGenerator.value = true },
                    modifier = Modifier.weight(1f)
                )
                QuickToolButton(
                    icon = Icons.Default.Build,
                    label = "Test Latencia",
                    onClick = { viewModel.showToolsDialog.value = true },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            ConnectButton(
                status = tunnelState.status,
                onClick = {
                    onPrepareVpn()
                    viewModel.toggleConnection(context)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            StatsCard(state = tunnelState)

            Spacer(modifier = Modifier.height(16.dp))

            ConsoleLogView(
                logs = logs,
                onClearLogs = { viewModel.clearLogs() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showConfigEditor) {
        ConfigEditorSheet(
            config = editingConfig,
            onSave = { updated ->
                viewModel.saveConfig(updated)
                viewModel.selectConfig(updated)
            },
            onDismiss = { viewModel.showConfigEditor.value = false },
            onOpenPayloadGenerator = { viewModel.showPayloadGenerator.value = true }
        )
    }

    if (showServersSheet) {
        ServersListSheet(
            configs = allConfigs,
            selectedConfig = activeConfig,
            onSelect = { conf -> viewModel.selectConfig(conf) },
            onAddNew = {
                viewModel.editingConfig.value = null
                viewModel.showConfigEditor.value = true
            },
            onEdit = { conf ->
                if (!conf.isLocked) {
                    viewModel.editingConfig.value = conf
                    viewModel.showConfigEditor.value = true
                }
            },
            onDelete = { conf -> viewModel.deleteConfig(conf) },
            onOpenImportExport = { viewModel.showImportExportDialog.value = true },
            onDismiss = { viewModel.showServersSheet.value = false }
        )
    }

    if (showPayloadGenerator) {
        PayloadGeneratorDialog(
            onPayloadGenerated = { generated ->
                val current = editingConfig ?: activeConfig ?: TunnelConfig(name = "Perfil SSH")
                val updated = current.copy(customPayload = generated)
                viewModel.editingConfig.value = updated
                viewModel.showConfigEditor.value = true
            },
            onDismiss = { viewModel.showPayloadGenerator.value = false }
        )
    }

    if (showToolsDialog) {
        ToolsDialog(
            currentIp = tunnelState.publicIp,
            ipLocation = tunnelState.ipLocation,
            pingResult = pingResult,
            isTesting = isTestingPing,
            onRunPing = { host, port -> viewModel.runHostPing(host, port) },
            onRefreshIp = { viewModel.refreshPublicIp() },
            onDismiss = { viewModel.showToolsDialog.value = false }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentConfig = activeConfig,
            onSaveDns = { dns1, dns2 ->
                activeConfig?.let { current ->
                    val updated = current.copy(dnsPrimary = dns1, dnsSecondary = dns2)
                    viewModel.saveConfig(updated)
                }
            },
            onDismiss = { viewModel.showSettingsDialog.value = false }
        )
    }

    if (showImportExportDialog) {
        ImportExportDialog(
            currentConfig = activeConfig,
            onImportSuccess = { imported ->
                viewModel.saveConfig(imported)
                viewModel.selectConfig(imported)
            },
            onDismiss = { viewModel.showImportExportDialog.value = false }
        )
    }
}

@Composable
private fun HwidDeviceCard(
    hwid: String,
    onCopy: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CyberCard),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyberBorder, RoundedCornerShape(12.dp))
            .clickable { onCopy() }
            .testTag("copy_hwid_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(CyberCardLight, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "MI HWID DE DISPOSITIVO",
                            color = NeonCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(NeonGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("ÚNICO", color = NeonGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = hwid,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .background(CyberCardLight, RoundedCornerShape(8.dp))
                    .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copiar HWID",
                        tint = NeonCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "COPIAR",
                        color = NeonCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveServerCard(
    config: TunnelConfig?,
    onSelectServer: () -> Unit,
    onEditServer: () -> Unit
) {
    val isLocked = config?.isLocked == true
    val configTypeBadge = when {
        config == null -> "SIN PERFIL"
        !isLocked -> "🔓 MANUAL / ABIERTO"
        config.allowedHwids.isNotBlank() -> "📱 BLOQUEO HWID"
        config.expiryTimestamp > 0 -> "⏳ POR FECHA"
        else -> "🔒 CERRADO"
    }

    val badgeColor = when {
        config == null -> TextMuted
        !isLocked -> NeonGreen
        else -> NeonOrange
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberCard),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isLocked) NeonOrange.copy(alpha = 0.5f) else NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectServer() }
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(CyberCardLight, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.Router,
                        contentDescription = null,
                        tint = if (isLocked) NeonOrange else NeonCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = config?.name ?: "Ingresar Servidor SSH Manual",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(configTypeBadge, color = badgeColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    val hostDisplay = config?.serverHost?.ifBlank { "Host Manual (Sin configurar)" } ?: "Toca para configurar"
                    Text(
                        text = "${config?.mode?.title ?: "SSH"} • $hostDisplay:${config?.serverPort ?: 22}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                if (!isLocked) {
                    IconButton(
                        onClick = onEditServer,
                        modifier = Modifier.testTag("edit_server_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Editar Parámetros",
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = "Cambiar",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (config != null && (config.expiryTimestamp > 0 || config.creatorNote.isNotBlank())) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberCardLight, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (config.expiryTimestamp > 0) {
                        val sdf = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
                        val isExpired = System.currentTimeMillis() > config.expiryTimestamp
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = null,
                                tint = if (isExpired) NeonRed else NeonGreen,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isExpired) "Expiró: ${sdf.format(Date(config.expiryTimestamp))}" else "Vence: ${sdf.format(Date(config.expiryTimestamp))}",
                                color = if (isExpired) NeonRed else NeonGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (config.creatorNote.isNotBlank()) {
                        Text(
                            text = config.creatorNote,
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(CyberCard, RoundedCornerShape(12.dp))
            .border(1.dp, CyberBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = NeonCyan, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
