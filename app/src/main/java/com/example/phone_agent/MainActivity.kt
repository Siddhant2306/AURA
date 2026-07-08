package com.example.phone_agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.phone_agent.mcp.McpHttpServer
import com.example.phone_agent.service.McpForegroundService
import com.example.phone_agent.service.PhoneControlService
import com.example.phone_agent.ui.theme.PhoneagentTheme
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneagentTheme {
                PhoneControlScreen()
            }
        }
    }
}

@Composable
fun PhoneControlScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(loadOrCreateToken(prefs)) }
    var lanIp by remember { mutableStateOf(getLanIpAddress() ?: "DEVICE_IP") }
    var accessibilityReady by remember { mutableStateOf(PhoneControlService.instance != null) }
    var serverRunning by remember { mutableStateOf(McpForegroundService.isRunning) }
    val port = McpHttpServer.DEFAULT_PORT
    val curl = remember(lanIp, port, token) { firstTestCurl(lanIp, port, token) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        startMcpService(context, token, port)
        serverRunning = true
    }

    LaunchedEffect(Unit) {
        while (true) {
            lanIp = getLanIpAddress() ?: "DEVICE_IP"
            accessibilityReady = PhoneControlService.instance != null
            serverRunning = McpForegroundService.isRunning
            delay(1000)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Phone-agent MCP",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                StatusLine("Accessibility", if (accessibilityReady) "connected" else "not enabled")
                StatusLine("Server", if (serverRunning) "running" else "stopped")
                StatusLine("LAN IP", lanIp)
                StatusLine("Port", port.toString())

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) {
                        Text("Accessibility Settings")
                    }
                    OutlinedButton(onClick = {
                        token = generateBearerToken()
                        prefs.edit().putString(PREF_TOKEN, token).apply()
                    }) {
                        Text("New Token")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (needsNotificationPermission(context)) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startMcpService(context, token, port)
                            serverRunning = true
                        }
                    }) {
                        Text("Start Server")
                    }
                    OutlinedButton(onClick = {
                        stopMcpService(context)
                        serverRunning = false
                    }) {
                        Text("Stop Server")
                    }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = token,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    label = { Text("Bearer token") },
                    minLines = 2,
                )

                SelectionContainer {
                    OutlinedTextField(
                        value = curl,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        label = { Text("First curl test") },
                        minLines = 5,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { copyToClipboard(context, "Bearer token", token) }) {
                        Text("Copy Token")
                    }
                    OutlinedButton(onClick = { copyToClipboard(context, "MCP curl", curl) }) {
                        Text("Copy Curl")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

private fun startMcpService(
    context: Context,
    token: String,
    port: Int,
) {
    val intent = Intent(context, McpForegroundService::class.java).apply {
        action = McpForegroundService.ACTION_START
        putExtra(McpForegroundService.EXTRA_TOKEN, token)
        putExtra(McpForegroundService.EXTRA_PORT, port)
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopMcpService(context: Context) {
    val intent = Intent(context, McpForegroundService::class.java).apply {
        action = McpForegroundService.ACTION_STOP
    }
    context.startService(intent)
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED

private fun loadOrCreateToken(prefs: SharedPreferences): String {
    val existing = prefs.getString(PREF_TOKEN, null)
    if (!existing.isNullOrBlank()) return existing
    return generateBearerToken().also { prefs.edit().putString(PREF_TOKEN, it).apply() }
}

private fun generateBearerToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

private fun firstTestCurl(
    ip: String,
    port: Int,
    token: String,
): String =
    "curl -s -X POST http://$ip:$port/mcp \\\n" +
        "  -H \"Authorization: Bearer $token\" \\\n" +
        "  -H \"Content-Type: application/json\" \\\n" +
        "  -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"${McpHttpServer.MCP_PROTOCOL_VERSION}\",\"capabilities\":{},\"clientInfo\":{\"name\":\"curl\",\"version\":\"0.1\"}}}'"

private fun copyToClipboard(
    context: Context,
    label: String,
    value: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun getLanIpAddress(): String? =
    runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

private const val PREFS_NAME = "mcp_settings"
private const val PREF_TOKEN = "bearer_token"

@Preview(showBackground = true)
@Composable
fun PhoneControlScreenPreview() {
    PhoneagentTheme {
        PhoneControlScreen()
    }
}
