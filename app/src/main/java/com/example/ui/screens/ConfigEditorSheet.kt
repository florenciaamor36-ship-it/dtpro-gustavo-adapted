package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TunnelConfig
import com.example.data.model.TunnelMode
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberNavy
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorSheet(
    onOpenPayloadGenerator: (() -> Unit)? = null,
    config: TunnelConfig?,
    onDismiss: () -> Unit,
    onSave: (TunnelConfig) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(config?.name ?: "Perfil SSH") }
    var mode by remember { mutableStateOf(config?.mode ?: TunnelMode.SSH_DIRECT) }
    var host by remember { mutableStateOf(config?.serverHost ?: "") }
    var portText by remember { mutableStateOf((config?.serverPort ?: 22).toString()) }
    var username by remember { mutableStateOf(config?.username ?: "") }
    var password by remember { mutableStateOf(config?.password ?: "") }
    var privateKey by remember { mutableStateOf(config?.privateKey ?: "") }
    var passphrase by remember { mutableStateOf(config?.passphrase ?: "") }
    var sniHost by remember { mutableStateOf(config?.sniHost ?: "") }
    var payload by remember { mutableStateOf(config?.customPayload ?: "") }
    var proxyHost by remember { mutableStateOf(config?.proxyHost ?: "") }
    var proxyPortText by remember { mutableStateOf((config?.proxyPort ?: 8080).toString()) }
    var dnsPrimary by remember { mutableStateOf(config?.dnsPrimary ?: "8.8.8.8") }
    var dnsSecondary by remember { mutableStateOf(config?.dnsSecondary ?: "8.8.4.4") }
    var autoReconnect by remember { mutableStateOf(config?.autoReconnect ?: true) }
    var udpForwarding by remember { mutableStateOf(config?.isUdpForwarding ?: false) }

    var expandedModeDropdown by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CyberNavy,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = if (config == null) "NUEVO PERFIL SSH" else "EDITAR PERFIL SSH",
                color = NeonCyan,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Nombre del Perfil
            DarkTextField(
                value = name,
                onValueChange = { name = it },
                label = "Nombre del perfil SSH",
                testTag = "config_name_input"
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Selector de Modo de Transporte SSH
            Text(
                text = "Modo de Transporte SSH",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberCard, shape = RoundedCornerShape(10.dp))
                    .clickable { expandedModeDropdown = true }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = mode.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(text = mode.description, color = TextMuted, fontSize = 11.sp)
                    }
                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = NeonCyan)
                }

                DropdownMenu(
                    expanded = expandedModeDropdown,
                    onDismissRequest = { expandedModeDropdown = false },
                    modifier = Modifier.background(CyberCard)
                ) {
                    TunnelMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(m.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(m.description, color = TextMuted, fontSize = 10.sp)
                                }
                            },
                            onClick = {
                                mode = m
                                if (portText.toIntOrNull() == null || portText == "22" || portText == "80" || portText == "443") {
                                    portText = m.defaultPort.toString()
                                }
                                expandedModeDropdown = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Host / Puerto Servidor SSH
            Row(modifier = Modifier.fillMaxWidth()) {
                DarkTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = "Host o IP Servidor SSH",
                    modifier = Modifier.weight(2.5f),
                    testTag = "config_host_input"
                )
                Spacer(modifier = Modifier.width(8.dp))
                DarkTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = "Puerto SSH",
                    modifier = Modifier.weight(1f),
                    testTag = "config_port_input"
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Credenciales SSH
            Row(modifier = Modifier.fillMaxWidth()) {
                DarkTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Usuario SSH",
                    modifier = Modifier.weight(1f),
                    testTag = "config_user_input"
                )
                Spacer(modifier = Modifier.width(8.dp))
                DarkTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Contraseña SSH",
                    modifier = Modifier.weight(1f),
                    testTag = "config_pass_input"
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Llave Privada SSH & Passphrase
            DarkTextField(
                value = privateKey,
                onValueChange = { privateKey = it },
                label = "Llave privada SSH (PEM / OpenSSH - Opcional)",
                minLines = 2,
                testTag = "config_key_input"
            )

            Spacer(modifier = Modifier.height(12.dp))

            DarkTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = "Passphrase de la Llave Privada (Opcional)",
                testTag = "config_passphrase_input"
            )

            if (mode == TunnelMode.SSH_PAYLOAD || mode == TunnelMode.SSH_WEBSOCKET) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Host Frontal y Proxy HTTP Opcional", color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    DarkTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        label = "Host Frontal / Proxy HTTP",
                        modifier = Modifier.weight(2.5f),
                        testTag = "config_proxy_host_input"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    DarkTextField(
                        value = proxyPortText,
                        onValueChange = { proxyPortText = it },
                        label = "Puerto Proxy",
                        modifier = Modifier.weight(1f),
                        testTag = "config_proxy_port_input"
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                DarkTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = "HTTP Payload Template",
                    minLines = 3,
                    testTag = "config_payload_input"
                )
            }

            if (mode.requiresSni || mode == TunnelMode.SSH_SSL || mode == TunnelMode.SSH_WEBSOCKET_SSL) {
                Spacer(modifier = Modifier.height(12.dp))
                DarkTextField(
                    value = sniHost,
                    onValueChange = { sniHost = it },
                    label = "Host SNI / SSL (ej: front.example.test)",
                    testTag = "config_sni_input"
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // DNS Primario & Secundario
            Row(modifier = Modifier.fillMaxWidth()) {
                DarkTextField(
                    value = dnsPrimary,
                    onValueChange = { dnsPrimary = it },
                    label = "DNS Primario",
                    modifier = Modifier.weight(1f),
                    testTag = "config_dns1_input"
                )
                Spacer(modifier = Modifier.width(8.dp))
                DarkTextField(
                    value = dnsSecondary,
                    onValueChange = { dnsSecondary = it },
                    label = "DNS Secundario",
                    modifier = Modifier.weight(1f),
                    testTag = "config_dns2_input"
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Reconexión Automática
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Reconexión Automática", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(text = "Reintentar conexión si se pierde la red", color = TextMuted, fontSize = 11.sp)
                }
                Switch(
                    checked = autoReconnect,
                    onCheckedChange = { autoReconnect = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = CyberBorder)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: mode.defaultPort
                    val pPort = proxyPortText.toIntOrNull() ?: 8080
                    val updated = (config ?: TunnelConfig(name = name)).copy(
                        name = name.ifBlank { "Perfil SSH" },
                        mode = mode,
                        serverHost = host.trim(),
                        serverPort = port,
                        username = username.trim(),
                        password = password,
                        privateKey = privateKey.trim(),
                        passphrase = passphrase,
                        sniHost = sniHost.trim(),
                        customPayload = payload.trim(),
                        proxyHost = proxyHost.trim(),
                        proxyPort = pPort,
                        dnsPrimary = dnsPrimary.trim().ifBlank { "8.8.8.8" },
                        dnsSecondary = dnsSecondary.trim().ifBlank { "8.8.4.4" },
                        autoReconnect = autoReconnect,
                        isUdpForwarding = udpForwarding
                    )
                    onSave(updated)
                    onDismiss()
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = CyberNavy),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_config_button")
            ) {
                Icon(imageVector = Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("GUARDAR CONFIGURACIÓN SSH", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    testTag: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary, fontSize = 12.sp) },
        minLines = minLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = NeonCyan,
            unfocusedBorderColor = CyberBorder,
            focusedContainerColor = CyberCard,
            unfocusedContainerColor = CyberCard
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    )
}
