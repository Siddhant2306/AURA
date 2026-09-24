package com.example.phone_agent.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.phone_agent.agent.FridayAgent
import com.example.phone_agent.agent.FridayAgentConfig
import com.example.phone_agent.agent.FridayAgentFactory
import com.example.phone_agent.agent.core.AgentSession
import com.example.phone_agent.agent.voice.FridayVoiceController
import com.example.phone_agent.agent.voice.FridayVoiceMode
import com.example.phone_agent.agent.voice.FridayVoiceState
import com.example.phone_agent.mcp.McpHttpServer
import com.example.phone_agent.service.McpForegroundService
import com.example.phone_agent.service.PhoneControlService
import com.example.phone_agent.ui.theme.PhoneagentTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections
import android.util.Log
import android.content.Context
@Composable
fun FridayAssistantScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(loadOrCreateToken(prefs)) }
    var lanIp by remember { mutableStateOf(getLanIpAddress() ?: "DEVICE_IP") }
    var accessibilityReady by remember { mutableStateOf(PhoneControlService.instance != null) }
    var serverRunning by remember { mutableStateOf(McpForegroundService.isRunning) }
    var ollamaUrl by remember { mutableStateOf(prefs.getString(PREF_OLLAMA_URL, DEFAULT_OLLAMA_URL) ?: DEFAULT_OLLAMA_URL) }
    var ollamaModel by remember { mutableStateOf(prefs.getString(PREF_OLLAMA_MODEL, DEFAULT_OLLAMA_MODEL) ?: DEFAULT_OLLAMA_MODEL) }
    var mcpUrl by remember { mutableStateOf(prefs.getString(PREF_MCP_URL, DEFAULT_MCP_URL) ?: DEFAULT_MCP_URL) }
    var manualCommand by remember { mutableStateOf("") }
    var progressText by remember { mutableStateOf("Ready") }
    var lastCommand by remember { mutableStateOf("") }
    val port = McpHttpServer.DEFAULT_PORT

    lateinit var voiceController: FridayVoiceController
    voiceController = remember {
        FridayVoiceController(context) { command ->
            scope.launch {
                runFridayCommand(
                    context = context,
                    command = command,
                    config = FridayAgentConfig(
                        ollamaBaseUrl = ollamaUrl,
                        ollamaModel = ollamaModel,
                        mcpBaseUrl = mcpUrl,
                        mcpBearerToken = token,
                    ),
                    voiceController = voiceController,
                    accessibilityReady = accessibilityReady,
                    serverRunning = serverRunning,
                    onProgress = {
                        progressText = it
                        voiceController.setProgress(it)
                    },
                    onCommand = { lastCommand = it },
                )
            }
        }
    }
    val voiceState by voiceController.state.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        startMcpService(context, token, port)
        serverRunning = true
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceController.start()
        } else {
            voiceController.setError("Microphone permission is required for hi Friday.")
        }
    }

    DisposableEffect(Unit) {
        onDispose { voiceController.destroy() }
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
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF091014)) {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Friday Phone Agent",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )

                FridayHero(state = voiceState, progress = progressText)

                AssistantControls(
                    voiceState = voiceState,
                    onStartFriday = {
                        when {
                            !SpeechRecognizer.isRecognitionAvailable(context) -> {
                                voiceController.setError("Speech recognition is not available on this device.")
                            }

                            needsMicrophonePermission(context) -> {
                                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }

                            else -> voiceController.start()
                        }
                    },
                    onStopFriday = { voiceController.stop() },
                )

                StatusPanel(
                    accessibilityReady = accessibilityReady,
                    serverRunning = serverRunning,
                    lanIp = lanIp,
                    port = port,
                    voiceState = voiceState,
                    lastCommand = lastCommand,
                )

                ServerControls(
                    token = token,
                    onTokenChange = {
                        token = it
                        prefs.edit().putString(PREF_TOKEN, it).apply()
                    },
                    onNewToken = {
                        token = generateBearerToken()
                        prefs.edit().putString(PREF_TOKEN, token).apply()
                    },
                    onCopyToken = { copyToClipboard(context, "Bearer token", token) },
                    onOpenAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onStartServer = {
                        if (needsNotificationPermission(context)) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startMcpService(context, token, port)
                            serverRunning = true
                        }
                    },
                    onStopServer = {
                        stopMcpService(context)
                        serverRunning = false
                    },
                )

                AgentSettings(
                    ollamaUrl = ollamaUrl,
                    onOllamaUrlChange = {
                        ollamaUrl = it
                        prefs.edit().putString(PREF_OLLAMA_URL, it).apply()
                    },
                    ollamaModel = ollamaModel,
                    onOllamaModelChange = {
                        ollamaModel = it
                        prefs.edit().putString(PREF_OLLAMA_MODEL, it).apply()
                    },
                    mcpUrl = mcpUrl,
                    onMcpUrlChange = {
                        mcpUrl = it
                        prefs.edit().putString(PREF_MCP_URL, it).apply()
                    },
                )

                ManualCommandPanel(
                    command = manualCommand,
                    onCommandChange = { manualCommand = it },
                    enabled = voiceState.mode != FridayVoiceMode.Thinking,
                    onRun = {
                        val command = manualCommand.trim()
                        if (command.isNotEmpty()) {
                            scope.launch {
                                runFridayCommand(
                                    context = context,
                                    command = command,
                                    config = FridayAgentConfig(
                                        ollamaBaseUrl = ollamaUrl,
                                        ollamaModel = ollamaModel,
                                        mcpBaseUrl = mcpUrl,
                                        mcpBearerToken = token,
                                    ),
                                    voiceController = voiceController,
                                    accessibilityReady = accessibilityReady,
                                    serverRunning = serverRunning,
                                    onProgress = {
                                        progressText = it
                                        voiceController.setProgress(it)
                                    },
                                    onCommand = { lastCommand = it },
                                )
                            }
                        }
                    },
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun FridayHero(
    state: FridayVoiceState,
    progress: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101B20), RoundedCornerShape(8.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AuraGlobe(
                mode = state.mode,
                amplitude = state.amplitude,
                modifier = Modifier.size(190.dp),
            )
            Text(
                text = state.status,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = state.transcript.ifBlank { progress },
                color = Color(0xFFB7C9CE),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AuraGlobe(
    mode: FridayVoiceMode,
    amplitude: Float,
    modifier: Modifier = Modifier,
) {
    val activeBoost = when (mode) {
        FridayVoiceMode.ListeningForCommand -> 1.25f
        FridayVoiceMode.Thinking -> 0.85f
        FridayVoiceMode.Speaking -> 1.1f
        FridayVoiceMode.WaitingForWakeWord -> 0.55f
        FridayVoiceMode.Error -> 0.35f
        FridayVoiceMode.Stopped -> 0.2f
    }
    val transition = rememberInfiniteTransition(label = "auraPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension * 0.28f
        val radius = baseRadius * pulse * (1f + amplitude * 0.22f)
        val auraRadius = radius * (2.2f + activeBoost * 0.35f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF7DF9FF).copy(alpha = 0.72f * activeBoost),
                    Color(0xFFFF4FD8).copy(alpha = 0.28f * activeBoost),
                    Color.Transparent,
                ),
                center = center,
                radius = auraRadius,
            ),
            radius = auraRadius,
            center = center,
        )
        drawCircle(
            color = Color(0xFFB6FFF5).copy(alpha = 0.18f + activeBoost * 0.08f),
            radius = radius * 1.2f,
            center = center,
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFE8FFFB),
                    Color(0xFF45D6D0),
                    Color(0xFF1B5C7A),
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

@Composable
private fun AssistantControls(
    voiceState: FridayVoiceState,
    onStartFriday: () -> Unit,
    onStopFriday: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onStartFriday,
            enabled = !voiceState.isEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text("Enable hi Friday")
        }
        OutlinedButton(
            onClick = onStopFriday,
            enabled = voiceState.isEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text("Stop Friday")
        }
    }
}

@Composable
private fun StatusPanel(
    accessibilityReady: Boolean,
    serverRunning: Boolean,
    lanIp: String,
    port: Int,
    voiceState: FridayVoiceState,
    lastCommand: String,
) {
    Panel(title = "Status") {
        StatusLine("Accessibility", if (accessibilityReady) "connected" else "not enabled")
        StatusLine("MCP server", if (serverRunning) "running" else "stopped")
        StatusLine("LAN IP", lanIp)
        StatusLine("Port", port.toString())
        StatusLine("Friday", voiceState.mode.name)
        if (lastCommand.isNotBlank()) {
            StatusLine("Last command", lastCommand)
        }
    }
}

@Composable
private fun ServerControls(
    token: String,
    onTokenChange: (String) -> Unit,
    onNewToken: () -> Unit,
    onCopyToken: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
) {
    Panel(title = "Phone MCP Server") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpenAccessibility, modifier = Modifier.weight(1f)) {
                Text("Accessibility")
            }
            Button(onClick = onStartServer, modifier = Modifier.weight(1f)) {
                Text("Start Server")
            }
            OutlinedButton(onClick = onStopServer, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bearer token") },
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onNewToken, modifier = Modifier.weight(1f)) {
                Text("New token")
            }
            OutlinedButton(onClick = onCopyToken, modifier = Modifier.weight(1f)) {
                Text("Copy token")
            }
        }
    }
}

@Composable
private fun AgentSettings(
    ollamaUrl: String,
    onOllamaUrlChange: (String) -> Unit,
    ollamaModel: String,
    onOllamaModelChange: (String) -> Unit,
    mcpUrl: String,
    onMcpUrlChange: (String) -> Unit,
) {
    Panel(title = "Agent Connection") {
        OutlinedTextField(
            value = ollamaUrl,
            onValueChange = onOllamaUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("PC Ollama URL") },
            singleLine = true,
        )
        OutlinedTextField(
            value = ollamaModel,
            onValueChange = onOllamaModelChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model") },
            singleLine = true,
        )
        OutlinedTextField(
            value = mcpUrl,
            onValueChange = onMcpUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Phone MCP URL") },
            singleLine = true,
        )
    }
}

@Composable
private fun ManualCommandPanel(
    command: String,
    onCommandChange: (String) -> Unit,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    Panel(title = "Manual Command") {
        OutlinedTextField(
            value = command,
            onValueChange = onCommandChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Tell Friday what to do") },
            placeholder = { Text("Open WhatsApp and read the current screen") },
            minLines = 2,
        )
        Button(
            onClick = onRun,
            enabled = enabled && command.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (enabled) "Run command" else "Friday is busy")
        }
    }
}

@Composable
private fun Panel(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111C21), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        HorizontalDivider(color = Color(0xFF274148))
        content()
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
        Text(label, color = Color(0xFFB7C9CE), style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Text(
                value,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
            )
        }
    }
}

private suspend fun runFridayCommand(
    command: String,
    context: Context,
    config: FridayAgentConfig,
    voiceController: FridayVoiceController,
    accessibilityReady: Boolean,
    serverRunning: Boolean,
    onProgress: (String) -> Unit,
    onCommand: (String) -> Unit,
) {
    AgentSession.runExclusive {
        onCommand(command)
        voiceController.setThinking(command)

        val preflight = FridayAgent.preflight(
            accessibilityReady = accessibilityReady,
            mcpServerRunning = serverRunning,
            bearerToken = config.mcpBearerToken,
        )
        if (!preflight.ready) {
            voiceController.speak(preflight.message)
            return@runExclusive
        }

        val spokenAnswer = try {
            FridayAgentFactory.create(context, config).run(command) { progress ->
                onProgress(progress)
            }.answer
        } catch (e: Exception) {
            Log.e("FridayAgent", "Run failed", e)
            "I hit an error: ${e.message ?: e::class.java.simpleName}"
        }
        voiceController.speak(spokenAnswer)
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

private fun needsMicrophonePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
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
private const val PREF_OLLAMA_URL = "ollama_url"
private const val PREF_OLLAMA_MODEL = "ollama_model"
private const val PREF_MCP_URL = "mcp_url"
private const val DEFAULT_OLLAMA_URL = "http://10.20.16.233:11434"
private const val DEFAULT_OLLAMA_MODEL = "qwen3:4b"
private const val DEFAULT_MCP_URL = "http://127.0.0.1:8080"

@Preview(showBackground = true)
@Composable
fun FridayAssistantScreenPreview() {
    PhoneagentTheme {
        FridayAssistantScreen()
    }
}
