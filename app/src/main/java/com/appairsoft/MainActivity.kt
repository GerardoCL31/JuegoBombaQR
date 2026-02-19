package com.appairsoft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val ADMIN_PIN = "0160"

class MainActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AirsoftApp(viewModel)
                }
            }
        }
    }
}

enum class GameMode {
    BOMB,
    HUNT_3_CODES
}

enum class MatchPhase {
    ADMIN_SETUP,
    MATCH_RUNNING,
    BOMB_ARMED,
    DETONATED,
    CODES_COMPLETED,
    ABORTED,
    TIME_OVER
}

enum class AdminScanTarget {
    BOMB_OBJECTIVE,
    HUNT_CODE_1,
    HUNT_CODE_2,
    HUNT_CODE_3
}

data class GameUiState(
    val gameMode: GameMode = GameMode.BOMB,
    val expectedQr: String = "AIRSOFT-ALPHA-01",
    val huntQr1: String = "CODE-ALPHA",
    val huntQr2: String = "CODE-BRAVO",
    val huntQr3: String = "CODE-CHARLIE",
    val foundCode1: Boolean = false,
    val foundCode2: Boolean = false,
    val foundCode3: Boolean = false,
    val setupMinutes: Int = 30,
    val phase: MatchPhase = MatchPhase.ADMIN_SETUP,
    val matchRemainingSeconds: Int = 1800,
    val bombRemainingSeconds: Int = 10,
    val lastScan: String = "",
    val message: String = "Configura la partida y pulsa Iniciar"
)

class GameViewModel : ViewModel() {
    private val _state = MutableStateFlow(GameUiState())
    val state: StateFlow<GameUiState> = _state

    private var matchJob: Job? = null
    private var bombJob: Job? = null
    private var lastValidScanAt = 0L

    fun setGameMode(mode: GameMode) {
        _state.update {
            it.copy(
                gameMode = mode,
                message = if (mode == GameMode.BOMB) "Modo bomba seleccionado." else "Modo 3 codigos seleccionado."
            )
        }
    }

    fun setExpectedQr(value: String) {
        _state.update { it.copy(expectedQr = value.trim().uppercase()) }
    }

    fun setHuntQr1(value: String) {
        _state.update { it.copy(huntQr1 = value.trim().uppercase()) }
    }

    fun setHuntQr2(value: String) {
        _state.update { it.copy(huntQr2 = value.trim().uppercase()) }
    }

    fun setHuntQr3(value: String) {
        _state.update { it.copy(huntQr3 = value.trim().uppercase()) }
    }

    fun setQrFromAdminScan(target: AdminScanTarget, raw: String) {
        val normalized = raw.trim().uppercase()
        if (normalized.isBlank()) return
        _state.update {
            when (target) {
                AdminScanTarget.BOMB_OBJECTIVE -> it.copy(expectedQr = normalized, message = "QR objetivo cargado: $normalized")
                AdminScanTarget.HUNT_CODE_1 -> it.copy(huntQr1 = normalized, message = "Codigo 1 cargado: $normalized")
                AdminScanTarget.HUNT_CODE_2 -> it.copy(huntQr2 = normalized, message = "Codigo 2 cargado: $normalized")
                AdminScanTarget.HUNT_CODE_3 -> it.copy(huntQr3 = normalized, message = "Codigo 3 cargado: $normalized")
            }
        }
    }

    fun setSetupMinutes(value: Int) {
        val safe = value.coerceIn(1, 240)
        _state.update {
            if (it.phase == MatchPhase.ADMIN_SETUP) it.copy(setupMinutes = safe, matchRemainingSeconds = safe * 60)
            else it.copy(setupMinutes = safe)
        }
    }

    fun startMatch() {
        val s = _state.value
        stopAllTimers()
        _state.update {
            it.copy(
                phase = MatchPhase.MATCH_RUNNING,
                matchRemainingSeconds = s.setupMinutes * 60,
                bombRemainingSeconds = 10,
                foundCode1 = false,
                foundCode2 = false,
                foundCode3 = false,
                message = if (s.gameMode == GameMode.BOMB) "Partida iniciada. Escanea el QR del objetivo." else "Partida iniciada. Encuentra y escanea los 3 codigos.",
                lastScan = ""
            )
        }
        startMatchTimer()
    }

    fun resetToAdmin() {
        stopAllTimers()
        _state.update {
            it.copy(
                phase = MatchPhase.ADMIN_SETUP,
                matchRemainingSeconds = it.setupMinutes * 60,
                bombRemainingSeconds = 10,
                foundCode1 = false,
                foundCode2 = false,
                foundCode3 = false,
                message = "Configura la partida y pulsa Iniciar"
            )
        }
    }

    fun abortMission() {
        val current = _state.value.phase
        if (current != MatchPhase.MATCH_RUNNING && current != MatchPhase.BOMB_ARMED) return
        stopAllTimers()
        _state.update { it.copy(phase = MatchPhase.ABORTED, message = "Mision abortada manualmente por admin.") }
    }

    fun onQrDetected(raw: String) {
        val now = System.currentTimeMillis()
        val current = _state.value
        val normalized = raw.trim().uppercase()

        _state.update { it.copy(lastScan = normalized) }

        if (current.phase != MatchPhase.MATCH_RUNNING) return
        if (now - lastValidScanAt < 1200) return
        lastValidScanAt = now

        if (current.gameMode == GameMode.BOMB) handleBombQr(normalized, current) else handleHuntQr(normalized, current)
    }

    private fun handleBombQr(scan: String, current: GameUiState) {
        if (scan != current.expectedQr) {
            _state.update { it.copy(message = "QR invalido: $scan") }
            return
        }
        armBomb()
    }

    private fun handleHuntQr(scan: String, current: GameUiState) {
        var found1 = current.foundCode1
        var found2 = current.foundCode2
        var found3 = current.foundCode3
        var matched = false

        if (!found1 && scan == current.huntQr1) { found1 = true; matched = true }
        if (!found2 && scan == current.huntQr2) { found2 = true; matched = true }
        if (!found3 && scan == current.huntQr3) { found3 = true; matched = true }

        if (!matched) {
            _state.update { it.copy(message = "Codigo no valido o ya encontrado.") }
            return
        }

        val count = (if (found1) 1 else 0) + (if (found2) 1 else 0) + (if (found3) 1 else 0)
        _state.update { it.copy(foundCode1 = found1, foundCode2 = found2, foundCode3 = found3, message = "Codigo encontrado ($count/3).") }

        if (found1 && found2 && found3) {
            stopAllTimers()
            _state.update { it.copy(phase = MatchPhase.CODES_COMPLETED, message = "Objetivo completado: 3/3 codigos encontrados.") }
        }
    }

    private fun startMatchTimer() {
        matchJob?.cancel()
        matchJob = viewModelScope.launch {
            var remaining = _state.value.matchRemainingSeconds
            while (remaining > 0 && _state.value.phase == MatchPhase.MATCH_RUNNING) {
                _state.update { it.copy(matchRemainingSeconds = remaining) }
                delay(1000)
                remaining -= 1
            }
            if (_state.value.phase == MatchPhase.MATCH_RUNNING) {
                _state.update { it.copy(phase = MatchPhase.TIME_OVER, matchRemainingSeconds = 0, message = "Tiempo de partida agotado.") }
            }
        }
    }

    private fun armBomb() {
        if (_state.value.phase != MatchPhase.MATCH_RUNNING) return
        bombJob?.cancel()
        _state.update { it.copy(phase = MatchPhase.BOMB_ARMED, bombRemainingSeconds = 10, message = "Bomba activada. Detonacion en 10 segundos.") }

        bombJob = viewModelScope.launch {
            var remaining = 10
            while (remaining > 0) {
                _state.update { it.copy(bombRemainingSeconds = remaining) }
                delay(1000)
                remaining -= 1
            }
            _state.update { it.copy(phase = MatchPhase.DETONATED, bombRemainingSeconds = 0, message = "BOOM. Objetivo detonado.") }
        }
    }

    private fun stopAllTimers() {
        matchJob?.cancel()
        bombJob?.cancel()
    }
}

@Composable
fun AirsoftApp(viewModel: GameViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val logoResId = remember(context) { resolveImageResId(context, "area96_logo", "logo", "area96") }
    val bgResId = remember(context) { resolveImageResId(context, "area96_bg", "fondo", "background", "area96_fondo") }
    var adminScanTarget by remember { mutableStateOf<AdminScanTarget?>(null) }
    var adminUnlocked by remember { mutableStateOf(false) }
    var pinDraft by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var bombPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(Unit) {
        onDispose {
            bombPlayer?.stop()
            bombPlayer?.release()
            bombPlayer = null
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == MatchPhase.BOMB_ARMED) {
            if (bombPlayer == null) {
                bombPlayer = MediaPlayer.create(context, R.raw.bomba_nuclear)?.apply {
                    isLooping = false
                    start()
                }
            } else if (bombPlayer?.isPlaying == false) {
                bombPlayer?.seekTo(0)
                bombPlayer?.start()
            }
        } else {
            bombPlayer?.stop()
            bombPlayer?.release()
            bombPlayer = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E11))
    ) {
        if (bgResId != 0) {
            Image(
                painter = painterResource(id = bgResId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(0.35f),
                contentScale = ContentScale.Crop
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x88000000))
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Header(logoResId, state.gameMode)
                GameStatusCard(state)

                if (state.phase == MatchPhase.ADMIN_SETUP) {
                    AdminSetupCard(
                        state = state,
                        adminUnlocked = adminUnlocked,
                        pinDraft = pinDraft,
                        pinError = pinError,
                        onPinChanged = {
                            pinDraft = it.filter(Char::isDigit).take(4)
                            pinError = false
                        },
                        onUnlock = {
                            if (pinDraft == ADMIN_PIN) {
                                adminUnlocked = true
                                pinError = false
                            } else {
                                pinError = true
                            }
                        },
                        onLock = {
                            adminUnlocked = false
                            pinDraft = ""
                            pinError = false
                            adminScanTarget = null
                        },
                        onGameModeChanged = viewModel::setGameMode,
                        onExpectedQrChanged = viewModel::setExpectedQr,
                        onHuntQr1Changed = viewModel::setHuntQr1,
                        onHuntQr2Changed = viewModel::setHuntQr2,
                        onHuntQr3Changed = viewModel::setHuntQr3,
                        onMinutesChanged = viewModel::setSetupMinutes,
                        onStartMatch = {
                            adminScanTarget = null
                            viewModel.startMatch()
                        },
                        onScanBombObjective = { adminScanTarget = AdminScanTarget.BOMB_OBJECTIVE },
                        onScanCode1 = { adminScanTarget = AdminScanTarget.HUNT_CODE_1 },
                        onScanCode2 = { adminScanTarget = AdminScanTarget.HUNT_CODE_2 },
                        onScanCode3 = { adminScanTarget = AdminScanTarget.HUNT_CODE_3 },
                        adminScanTarget = adminScanTarget
                    )
                } else {
                    ActionCard(
                        state = state,
                        onBackToAdmin = viewModel::resetToAdmin,
                        onAbortMission = viewModel::abortMission
                    )
                }
            }

            if (state.phase == MatchPhase.ADMIN_SETUP && adminUnlocked && adminScanTarget != null) {
                CameraPanel(
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onQrDetected = {
                        viewModel.setQrFromAdminScan(adminScanTarget ?: return@CameraPanel, it)
                        adminScanTarget = null
                    }
                )
            } else if (state.phase == MatchPhase.MATCH_RUNNING) {
                CameraPanel(
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onQrDetected = viewModel::onQrDetected
                )
            }
        }
    }
}

@Composable
private fun Header(logoResId: Int, mode: GameMode) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (logoResId != 0) {
            Image(
                painter = painterResource(id = logoResId),
                contentDescription = "Logo Area 96",
                modifier = Modifier.height(44.dp),
                contentScale = ContentScale.Fit
            )
        }
        Text(
            text = if (mode == GameMode.BOMB) "Airsoft - Modo Bomba" else "Airsoft - Buscar 3 Codigos",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun GameStatusCard(state: GameUiState) {
    val phaseColor = when (state.phase) {
        MatchPhase.ADMIN_SETUP -> Color(0xFF8A8F98)
        MatchPhase.MATCH_RUNNING -> Color(0xFF66BB6A)
        MatchPhase.BOMB_ARMED -> Color(0xFFFFA726)
        MatchPhase.DETONATED -> Color(0xFFEF5350)
        MatchPhase.CODES_COMPLETED -> Color(0xFF4DB6AC)
        MatchPhase.ABORTED -> Color(0xFF90A4AE)
        MatchPhase.TIME_OVER -> Color(0xFFE57373)
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Estado: ${state.phase}", color = phaseColor, fontWeight = FontWeight.Bold)
            Text("Tiempo partida: ${formatClock(state.matchRemainingSeconds)}")
            if (state.gameMode == GameMode.BOMB && (state.phase == MatchPhase.BOMB_ARMED || state.phase == MatchPhase.DETONATED)) {
                Text("Bomba: ${state.bombRemainingSeconds}s")
            }
            if (state.gameMode == GameMode.HUNT_3_CODES) {
                val found = (if (state.foundCode1) 1 else 0) + (if (state.foundCode2) 1 else 0) + (if (state.foundCode3) 1 else 0)
                Text("Codigos encontrados: $found/3")
            }
            Text(state.message)
            if (state.lastScan.isNotEmpty()) Text("Ultimo scan: ${state.lastScan}")
        }
    }
}

@Composable
private fun AdminSetupCard(
    state: GameUiState,
    adminUnlocked: Boolean,
    pinDraft: String,
    pinError: Boolean,
    onPinChanged: (String) -> Unit,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onGameModeChanged: (GameMode) -> Unit,
    onExpectedQrChanged: (String) -> Unit,
    onHuntQr1Changed: (String) -> Unit,
    onHuntQr2Changed: (String) -> Unit,
    onHuntQr3Changed: (String) -> Unit,
    onMinutesChanged: (Int) -> Unit,
    onStartMatch: () -> Unit,
    onScanBombObjective: () -> Unit,
    onScanCode1: () -> Unit,
    onScanCode2: () -> Unit,
    onScanCode3: () -> Unit,
    adminScanTarget: AdminScanTarget?
) {
    var minutesDraft by remember(state.setupMinutes) { mutableStateOf(state.setupMinutes.toString()) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Panel admin", fontWeight = FontWeight.Bold)
            if (!adminUnlocked) {
                OutlinedTextField(
                    value = pinDraft,
                    onValueChange = onPinChanged,
                    label = { Text("PIN admin") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (pinError) Text("PIN incorrecto", color = Color(0xFFE57373))
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text("Desbloquear admin") }
                return@Column
            }

            Button(onClick = onLock, modifier = Modifier.fillMaxWidth()) { Text("Bloquear panel admin") }
            Text("Seleccion de modo")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onGameModeChanged(GameMode.BOMB) }, modifier = Modifier.weight(1f)) { Text("Bomba") }
                Button(onClick = { onGameModeChanged(GameMode.HUNT_3_CODES) }, modifier = Modifier.weight(1f)) { Text("3 Codigos") }
            }

            if (state.gameMode == GameMode.BOMB) {
                OutlinedTextField(
                    value = state.expectedQr,
                    onValueChange = onExpectedQrChanged,
                    label = { Text("QR objetivo bomba") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = onScanBombObjective, modifier = Modifier.fillMaxWidth()) {
                    Text(if (adminScanTarget == AdminScanTarget.BOMB_OBJECTIVE) "Escaneando..." else "Escanear QR de bomba")
                }
            } else {
                OutlinedTextField(
                    value = state.huntQr1,
                    onValueChange = onHuntQr1Changed,
                    label = { Text("Codigo 1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = onScanCode1, modifier = Modifier.fillMaxWidth()) {
                    Text(if (adminScanTarget == AdminScanTarget.HUNT_CODE_1) "Escaneando codigo 1..." else "Escanear codigo 1")
                }
                OutlinedTextField(
                    value = state.huntQr2,
                    onValueChange = onHuntQr2Changed,
                    label = { Text("Codigo 2") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = onScanCode2, modifier = Modifier.fillMaxWidth()) {
                    Text(if (adminScanTarget == AdminScanTarget.HUNT_CODE_2) "Escaneando codigo 2..." else "Escanear codigo 2")
                }
                OutlinedTextField(
                    value = state.huntQr3,
                    onValueChange = onHuntQr3Changed,
                    label = { Text("Codigo 3") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = onScanCode3, modifier = Modifier.fillMaxWidth()) {
                    Text(if (adminScanTarget == AdminScanTarget.HUNT_CODE_3) "Escaneando codigo 3..." else "Escanear codigo 3")
                }
            }

            OutlinedTextField(
                value = minutesDraft,
                onValueChange = {
                    minutesDraft = it.filter(Char::isDigit)
                    minutesDraft.toIntOrNull()?.let(onMinutesChanged)
                },
                label = { Text("Duracion partida (min)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(onClick = onStartMatch, modifier = Modifier.fillMaxWidth()) { Text("Iniciar partida") }
        }
    }
}

@Composable
private fun ActionCard(
    state: GameUiState,
    onBackToAdmin: () -> Unit,
    onAbortMission: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.phase) {
                MatchPhase.MATCH_RUNNING -> {
                    if (state.gameMode == GameMode.BOMB) Text("Escanea el QR correcto para activar la bomba.")
                    else Text("Busca por el campo los 3 codigos y escanealos.")
                }
                MatchPhase.BOMB_ARMED -> Text("Bomba armada. Espera la detonacion.")
                MatchPhase.DETONATED -> Text("Partida terminada por detonacion.")
                MatchPhase.CODES_COMPLETED -> Text("Partida completada: 3 codigos encontrados.")
                MatchPhase.ABORTED -> Text("Partida abortada por admin.")
                MatchPhase.TIME_OVER -> Text("Partida terminada por tiempo.")
                MatchPhase.ADMIN_SETUP -> Unit
            }
            if (state.phase == MatchPhase.MATCH_RUNNING || state.phase == MatchPhase.BOMB_ARMED) {
                HoldToAbortButton(onAbortMission)
            }
            if (
                state.phase == MatchPhase.DETONATED ||
                state.phase == MatchPhase.CODES_COMPLETED ||
                state.phase == MatchPhase.TIME_OVER ||
                state.phase == MatchPhase.ABORTED
            ) {
                Button(onClick = onBackToAdmin, modifier = Modifier.fillMaxWidth()) {
                    Text("Nueva partida (Admin)")
                }
            }
        }
    }
}

@Composable
private fun HoldToAbortButton(onAbort: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }
    var label by remember { mutableStateOf("Mantener 10s para abortar mision") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF5D1212), RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val holdMillis = 10_000L
                        val start = System.currentTimeMillis()
                        coroutineScope {
                            val ticker = launch {
                                while (true) {
                                    val elapsed = System.currentTimeMillis() - start
                                    progress = (elapsed.toFloat() / holdMillis.toFloat()).coerceIn(0f, 1f)
                                    val left = ((holdMillis - elapsed).coerceAtLeast(0L) / 1000L) + 1L
                                    label = "Mantener $left s para abortar"
                                    delay(80)
                                }
                            }

                            val released = withTimeoutOrNull(holdMillis) { tryAwaitRelease() } != null
                            ticker.cancel()
                            if (!released) {
                                progress = 1f
                                label = "Abortando..."
                                onAbort()
                                delay(250)
                            } else {
                                label = "Mantener 10s para abortar mision"
                            }
                            progress = 0f
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxSize()
                .background(Color(0x66FF5252), RoundedCornerShape(10.dp))
        )
        Text(text = label, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CameraPanel(
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onQrDetected: (String) -> Unit
) {
    if (hasCameraPermission) {
        QrScannerView(onQrDetected = onQrDetected)
    } else {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Sin permiso de camara")
                Button(onClick = onRequestPermission, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Solicitar permiso")
                }
            }
        }
    }
}

@Composable
fun QrScannerView(onQrDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            bindCamera(
                context = context,
                previewView = previewView,
                lifecycleOwner = lifecycleOwner,
                cameraExecutor = cameraExecutor,
                scanner = scanner,
                onQrDetected = onQrDetected
            )
            previewView
        }
    )
}

private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    cameraExecutor: ExecutorService,
    scanner: BarcodeScanner,
    onQrDetected: (String) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
        {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.getSurfaceProvider()) }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                barcodes.firstOrNull { it.rawValue != null }?.rawValue?.let(onQrDetected)
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalyzer
            )
        },
        ContextCompat.getMainExecutor(context)
    )
}

private fun formatClock(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

private fun resolveImageResId(context: Context, vararg names: String): Int {
    val pkg = context.packageName
    for (name in names) {
        val drawableId = context.resources.getIdentifier(name, "drawable", pkg)
        if (drawableId != 0) return drawableId
        val mipmapId = context.resources.getIdentifier(name, "mipmap", pkg)
        if (mipmapId != 0) return mipmapId
    }
    return 0
}
