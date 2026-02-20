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
import androidx.compose.foundation.layout.heightIn
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
    HUNT_N_CODES,
    SEQUENCE_CODES,
    KING_OF_THE_QR
}

enum class MatchPhase {
    ADMIN_SETUP,
    MATCH_RUNNING,
    BOMB_ARMED,
    DETONATED,
    CODES_COMPLETED,
    KING_COMPLETED,
    ABORTED,
    TIME_OVER
}

enum class AdminScanTarget {
    BOMB_OBJECTIVE,
    HUNT_CODE_1,
    HUNT_CODE_2,
    HUNT_CODE_3,
    HUNT_CODE_4,
    HUNT_CODE_5,
    KING_POINT
}

enum class TeamSide {
    NONE,
    RED,
    GREEN
}

data class GameUiState(
    val gameMode: GameMode = GameMode.BOMB,
    val expectedQr: String = "AIRSOFT-ALPHA-01",
    val huntQr1: String = "CODE-ALPHA",
    val huntQr2: String = "CODE-BRAVO",
    val huntQr3: String = "CODE-CHARLIE",
    val huntQr4: String = "CODE-DELTA",
    val huntQr5: String = "CODE-ECHO",
    val huntTargetCount: Int = 3,
    val foundCode1: Boolean = false,
    val foundCode2: Boolean = false,
    val foundCode3: Boolean = false,
    val foundCode4: Boolean = false,
    val foundCode5: Boolean = false,
    val sequenceProgress: Int = 0,
    val kingQr: String = "KING-POINT-01",
    val kingOwner: TeamSide = TeamSide.NONE,
    val selectedTeam: TeamSide = TeamSide.NONE,
    val kingRedScore: Int = 0,
    val kingGreenScore: Int = 0,
    val kingRedControlSeconds: Int = 0,
    val kingGreenControlSeconds: Int = 0,
    val kingScoreToWin: Int = 20,
    val setupMinutes: Int = 30,
    val phase: MatchPhase = MatchPhase.ADMIN_SETUP,
    val matchRemainingSeconds: Int = 1800,
    val bombRemainingSeconds: Int = 10,
    val scanSuccessCount: Int = 0,
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
                message = when (mode) {
                    GameMode.BOMB -> "Modo bomba seleccionado."
                    GameMode.HUNT_N_CODES -> "Modo buscar N codigos seleccionado."
                    GameMode.SEQUENCE_CODES -> "Modo secuencia seleccionado."
                    GameMode.KING_OF_THE_QR -> "Modo King of the QR seleccionado."
                }
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

    fun setHuntQr4(value: String) {
        _state.update { it.copy(huntQr4 = value.trim().uppercase()) }
    }

    fun setHuntQr5(value: String) {
        _state.update { it.copy(huntQr5 = value.trim().uppercase()) }
    }

    fun setHuntTargetCount(value: Int) {
        _state.update { it.copy(huntTargetCount = value.coerceIn(1, 5)) }
    }

    fun setKingQr(value: String) {
        _state.update { it.copy(kingQr = value.trim().uppercase()) }
    }

    fun setKingScoreToWin(value: Int) {
        _state.update { it.copy(kingScoreToWin = value.coerceIn(5, 200)) }
    }

    fun setSelectedTeam(team: TeamSide) {
        _state.update { it.copy(selectedTeam = team, message = "Equipo activo: ${team.name}") }
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
                AdminScanTarget.HUNT_CODE_4 -> it.copy(huntQr4 = normalized, message = "Codigo 4 cargado: $normalized")
                AdminScanTarget.HUNT_CODE_5 -> it.copy(huntQr5 = normalized, message = "Codigo 5 cargado: $normalized")
                AdminScanTarget.KING_POINT -> it.copy(kingQr = normalized, message = "QR king cargado: $normalized")
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
                foundCode4 = false,
                foundCode5 = false,
                sequenceProgress = 0,
                kingOwner = TeamSide.NONE,
                selectedTeam = TeamSide.NONE,
                kingRedScore = 0,
                kingGreenScore = 0,
                kingRedControlSeconds = 0,
                kingGreenControlSeconds = 0,
                scanSuccessCount = 0,
                message = when (s.gameMode) {
                    GameMode.BOMB -> "Partida iniciada. Escanea el QR del objetivo."
                    GameMode.HUNT_N_CODES -> "Partida iniciada. Encuentra ${s.huntTargetCount} codigos."
                    GameMode.SEQUENCE_CODES -> "Partida iniciada. Escanea la secuencia en orden."
                    GameMode.KING_OF_THE_QR -> "Partida iniciada. Escanea una vez para capturar control y sumar tiempo."
                },
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
                foundCode4 = false,
                foundCode5 = false,
                sequenceProgress = 0,
                kingOwner = TeamSide.NONE,
                selectedTeam = TeamSide.NONE,
                kingRedScore = 0,
                kingGreenScore = 0,
                kingRedControlSeconds = 0,
                kingGreenControlSeconds = 0,
                scanSuccessCount = 0,
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

        when (current.gameMode) {
            GameMode.BOMB -> handleBombQr(normalized, current)
            GameMode.HUNT_N_CODES -> handleHuntQr(normalized, current)
            GameMode.SEQUENCE_CODES -> handleSequenceQr(normalized, current)
            GameMode.KING_OF_THE_QR -> handleKingQr(normalized, current)
        }
    }

    private fun handleBombQr(scan: String, current: GameUiState) {
        if (scan != current.expectedQr) {
            _state.update { it.copy(message = "QR invalido: $scan") }
            return
        }
        armBomb()
    }

    private fun handleHuntQr(scan: String, current: GameUiState) {
        val codes = configuredCodes(current)
        val found = mutableListOf(
            current.foundCode1,
            current.foundCode2,
            current.foundCode3,
            current.foundCode4,
            current.foundCode5
        )
        val idx = codes.indexOf(scan)
        if (idx == -1 || found[idx]) {
            _state.update { it.copy(message = "Codigo no valido o ya encontrado.") }
            return
        }
        found[idx] = true
        val count = found.take(current.huntTargetCount).count { it }
        _state.update {
            it.withFoundFlags(found).copy(
                scanSuccessCount = it.scanSuccessCount + 1,
                message = "Codigo encontrado ($count/${current.huntTargetCount})."
            )
        }
        if (count >= current.huntTargetCount) {
            stopAllTimers()
            _state.update {
                it.copy(
                    phase = MatchPhase.CODES_COMPLETED,
                    message = "Objetivo completado: $count/${current.huntTargetCount} codigos encontrados."
                )
            }
        }
    }

    private fun handleSequenceQr(scan: String, current: GameUiState) {
        val codes = configuredCodes(current)
        val expectedIndex = current.sequenceProgress
        if (expectedIndex >= current.huntTargetCount) return
        if (scan in codes.take(expectedIndex)) {
            _state.update { it.copy(message = "Codigo ya validado en la secuencia.") }
            return
        }
        if (scan != codes[expectedIndex]) {
            _state.update {
                it.copy(
                    sequenceProgress = 0,
                    foundCode1 = false,
                    foundCode2 = false,
                    foundCode3 = false,
                    foundCode4 = false,
                    foundCode5 = false,
                    message = "Orden incorrecto. Secuencia reiniciada."
                )
            }
            return
        }
        val next = expectedIndex + 1
        val found = mutableListOf(false, false, false, false, false)
        for (i in 0 until next) found[i] = true
        if (next >= current.huntTargetCount) {
            stopAllTimers()
            _state.update {
                it.withFoundFlags(found).copy(
                    sequenceProgress = next,
                    phase = MatchPhase.CODES_COMPLETED,
                    message = "Secuencia completada (${current.huntTargetCount}/${current.huntTargetCount})."
                )
            }
            return
        }
        _state.update {
            it.withFoundFlags(found).copy(
                sequenceProgress = next,
                scanSuccessCount = it.scanSuccessCount + 1,
                message = "Secuencia correcta ($next/${current.huntTargetCount})."
            )
        }
    }

    private fun handleKingQr(scan: String, current: GameUiState) {
        if (current.selectedTeam == TeamSide.NONE) {
            _state.update { it.copy(message = "Selecciona equipo (ROJO o VERDE) antes de escanear.") }
            return
        }
        if (scan != current.kingQr) {
            _state.update { it.copy(message = "QR invalido para KING: $scan") }
            return
        }
        if (current.kingOwner == current.selectedTeam) {
            _state.update { it.copy(message = "Control ya capturado por ${current.selectedTeam.name}.") }
            return
        }
        _state.update {
            it.copy(
                kingOwner = current.selectedTeam,
                scanSuccessCount = it.scanSuccessCount + 1,
                message = "Control capturado por ${current.selectedTeam.name}."
            )
        }
    }

    private fun startMatchTimer() {
        matchJob?.cancel()
        matchJob = viewModelScope.launch {
            var remaining = _state.value.matchRemainingSeconds
            while (remaining > 0 && _state.value.phase == MatchPhase.MATCH_RUNNING) {
                delay(1000)
                remaining -= 1
                _state.update { s ->
                    if (s.phase != MatchPhase.MATCH_RUNNING) s
                    else {
                        val redControl = if (s.gameMode == GameMode.KING_OF_THE_QR && s.kingOwner == TeamSide.RED) s.kingRedControlSeconds + 1 else s.kingRedControlSeconds
                        val greenControl = if (s.gameMode == GameMode.KING_OF_THE_QR && s.kingOwner == TeamSide.GREEN) s.kingGreenControlSeconds + 1 else s.kingGreenControlSeconds
                        s.copy(
                            matchRemainingSeconds = remaining,
                            kingRedControlSeconds = redControl,
                            kingGreenControlSeconds = greenControl
                        )
                    }
                }
            }
            if (_state.value.phase == MatchPhase.MATCH_RUNNING) {
                val s = _state.value
                val message = if (s.gameMode == GameMode.KING_OF_THE_QR) {
                    when {
                        s.kingRedControlSeconds > s.kingGreenControlSeconds -> "Tiempo agotado. Gana ROJO por control (${formatClock(s.kingRedControlSeconds)} vs ${formatClock(s.kingGreenControlSeconds)})."
                        s.kingGreenControlSeconds > s.kingRedControlSeconds -> "Tiempo agotado. Gana VERDE por control (${formatClock(s.kingGreenControlSeconds)} vs ${formatClock(s.kingRedControlSeconds)})."
                        else -> "Tiempo agotado. Empate por control (${formatClock(s.kingRedControlSeconds)})."
                    }
                } else {
                    "Tiempo de partida agotado."
                }
                _state.update { it.copy(phase = MatchPhase.TIME_OVER, matchRemainingSeconds = 0, message = message) }
            }
        }
    }

    private fun armBomb() {
        if (_state.value.phase != MatchPhase.MATCH_RUNNING) return
        bombJob?.cancel()
        _state.update {
            it.copy(
                phase = MatchPhase.BOMB_ARMED,
                bombRemainingSeconds = 10,
                scanSuccessCount = it.scanSuccessCount + 1,
                message = "Bomba activada. Detonacion en 10 segundos."
            )
        }

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

    private fun configuredCodes(state: GameUiState): List<String> {
        return listOf(state.huntQr1, state.huntQr2, state.huntQr3, state.huntQr4, state.huntQr5)
            .take(state.huntTargetCount)
    }

    private fun GameUiState.withFoundFlags(found: List<Boolean>): GameUiState {
        return copy(
            foundCode1 = found.getOrElse(0) { false },
            foundCode2 = found.getOrElse(1) { false },
            foundCode3 = found.getOrElse(2) { false },
            foundCode4 = found.getOrElse(3) { false },
            foundCode5 = found.getOrElse(4) { false }
        )
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
    var successPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var cameraVisible by remember { mutableStateOf(true) }

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
            successPlayer?.stop()
            successPlayer?.release()
            successPlayer = null
        }
    }

    LaunchedEffect(state.scanSuccessCount) {
        if (state.scanSuccessCount <= 0) return@LaunchedEffect
        successPlayer?.stop()
        successPlayer?.release()
        successPlayer = MediaPlayer.create(context, R.raw.correcto_efecto)?.apply {
            isLooping = false
            start()
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

        if (
            state.phase == MatchPhase.DETONATED ||
            state.phase == MatchPhase.CODES_COMPLETED ||
            state.phase == MatchPhase.KING_COMPLETED ||
            state.phase == MatchPhase.ABORTED ||
            state.phase == MatchPhase.TIME_OVER
        ) {
            adminUnlocked = false
            pinDraft = ""
            pinError = false
            adminScanTarget = null
        }

        if (state.phase == MatchPhase.ADMIN_SETUP) {
            cameraVisible = true
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
                        onHuntQr4Changed = viewModel::setHuntQr4,
                        onHuntQr5Changed = viewModel::setHuntQr5,
                        onHuntTargetCountChanged = viewModel::setHuntTargetCount,
                        onKingQrChanged = viewModel::setKingQr,
                        onKingScoreToWinChanged = viewModel::setKingScoreToWin,
                        onMinutesChanged = viewModel::setSetupMinutes,
                        onStartMatch = {
                            adminScanTarget = null
                            viewModel.startMatch()
                        },
                        onScanBombObjective = { adminScanTarget = AdminScanTarget.BOMB_OBJECTIVE },
                        onScanCode1 = { adminScanTarget = AdminScanTarget.HUNT_CODE_1 },
                        onScanCode2 = { adminScanTarget = AdminScanTarget.HUNT_CODE_2 },
                        onScanCode3 = { adminScanTarget = AdminScanTarget.HUNT_CODE_3 },
                        onScanCode4 = { adminScanTarget = AdminScanTarget.HUNT_CODE_4 },
                        onScanCode5 = { adminScanTarget = AdminScanTarget.HUNT_CODE_5 },
                        onScanKingPoint = { adminScanTarget = AdminScanTarget.KING_POINT },
                        adminScanTarget = adminScanTarget
                    )
                } else {
                    ActionCard(
                        state = state,
                        onBackToAdmin = viewModel::resetToAdmin,
                        onAbortMission = viewModel::abortMission,
                        onTeamSelected = viewModel::setSelectedTeam
                    )
                }
            }

            val cameraNeeded =
                (state.phase == MatchPhase.ADMIN_SETUP && adminUnlocked && adminScanTarget != null) ||
                state.phase == MatchPhase.MATCH_RUNNING

            if (cameraNeeded) {
                Button(onClick = { cameraVisible = !cameraVisible }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (cameraVisible) "Ocultar camara" else "Mostrar camara")
                }
            }

            if (cameraVisible && state.phase == MatchPhase.ADMIN_SETUP && adminUnlocked && adminScanTarget != null) {
                CameraPanel(
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onQrDetected = {
                        viewModel.setQrFromAdminScan(adminScanTarget ?: return@CameraPanel, it)
                        adminScanTarget = null
                    }
                )
            } else if (cameraVisible && state.phase == MatchPhase.MATCH_RUNNING) {
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
            text = when (mode) {
                GameMode.BOMB -> "Airsoft - Modo Bomba"
                GameMode.HUNT_N_CODES -> "Airsoft - Buscar N Codigos"
                GameMode.SEQUENCE_CODES -> "Airsoft - Secuencia de Codigos"
                GameMode.KING_OF_THE_QR -> "Airsoft - King of the QR"
            },
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
        MatchPhase.KING_COMPLETED -> Color(0xFF81C784)
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
            if (state.gameMode == GameMode.HUNT_N_CODES || state.gameMode == GameMode.SEQUENCE_CODES) {
                val found = listOf(state.foundCode1, state.foundCode2, state.foundCode3, state.foundCode4, state.foundCode5)
                    .take(state.huntTargetCount)
                    .count { it }
                Text("Codigos encontrados: $found/${state.huntTargetCount}")
            }
            if (state.gameMode == GameMode.SEQUENCE_CODES) {
                Text("Progreso secuencia: ${state.sequenceProgress}/${state.huntTargetCount}")
            }
            if (state.gameMode == GameMode.KING_OF_THE_QR) {
                Text("KING QR: ${state.kingQr}")
                Text("Control actual: ${state.kingOwner.name}")
                Text("Equipo activo en app: ${state.selectedTeam.name}")
                Text("Tiempo control ROJO: ${formatClock(state.kingRedControlSeconds)}")
                Text("Tiempo control VERDE: ${formatClock(state.kingGreenControlSeconds)}")
                Text("Gana quien tenga mas tiempo de control al acabar la partida.")
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
    onHuntQr4Changed: (String) -> Unit,
    onHuntQr5Changed: (String) -> Unit,
    onHuntTargetCountChanged: (Int) -> Unit,
    onKingQrChanged: (String) -> Unit,
    onKingScoreToWinChanged: (Int) -> Unit,
    onMinutesChanged: (Int) -> Unit,
    onStartMatch: () -> Unit,
    onScanBombObjective: () -> Unit,
    onScanCode1: () -> Unit,
    onScanCode2: () -> Unit,
    onScanCode3: () -> Unit,
    onScanCode4: () -> Unit,
    onScanCode5: () -> Unit,
    onScanKingPoint: () -> Unit,
    adminScanTarget: AdminScanTarget?
) {
    var minutesDraft by remember(state.setupMinutes) { mutableStateOf(state.setupMinutes.toString()) }
    var huntCountDraft by remember(state.huntTargetCount) { mutableStateOf(state.huntTargetCount.toString()) }
    var kingScoreDraft by remember(state.kingScoreToWin) { mutableStateOf(state.kingScoreToWin.toString()) }
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
                Button(onClick = { onGameModeChanged(GameMode.HUNT_N_CODES) }, modifier = Modifier.weight(1f)) { Text("N Codigos") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onGameModeChanged(GameMode.SEQUENCE_CODES) }, modifier = Modifier.weight(1f)) { Text("Secuencia") }
                Button(onClick = { onGameModeChanged(GameMode.KING_OF_THE_QR) }, modifier = Modifier.weight(1f)) { Text("King QR") }
            }

            when (state.gameMode) {
                GameMode.BOMB -> {
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
                }
                GameMode.HUNT_N_CODES, GameMode.SEQUENCE_CODES -> {
                    OutlinedTextField(
                        value = huntCountDraft,
                        onValueChange = {
                            huntCountDraft = it.filter(Char::isDigit)
                            huntCountDraft.toIntOrNull()?.let(onHuntTargetCountChanged)
                        },
                        label = { Text("Cantidad de codigos (1-5)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
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
                    OutlinedTextField(
                        value = state.huntQr4,
                        onValueChange = onHuntQr4Changed,
                        label = { Text("Codigo 4") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = onScanCode4, modifier = Modifier.fillMaxWidth()) {
                        Text(if (adminScanTarget == AdminScanTarget.HUNT_CODE_4) "Escaneando codigo 4..." else "Escanear codigo 4")
                    }
                    OutlinedTextField(
                        value = state.huntQr5,
                        onValueChange = onHuntQr5Changed,
                        label = { Text("Codigo 5") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = onScanCode5, modifier = Modifier.fillMaxWidth()) {
                        Text(if (adminScanTarget == AdminScanTarget.HUNT_CODE_5) "Escaneando codigo 5..." else "Escanear codigo 5")
                    }
                }
                GameMode.KING_OF_THE_QR -> {
                    OutlinedTextField(
                        value = state.kingQr,
                        onValueChange = onKingQrChanged,
                        label = { Text("QR punto KING") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = onScanKingPoint, modifier = Modifier.fillMaxWidth()) {
                        Text(if (adminScanTarget == AdminScanTarget.KING_POINT) "Escaneando punto KING..." else "Escanear punto KING")
                    }
                    OutlinedTextField(
                        value = kingScoreDraft,
                        onValueChange = {
                            kingScoreDraft = it.filter(Char::isDigit)
                            kingScoreDraft.toIntOrNull()?.let(onKingScoreToWinChanged)
                        },
                        label = { Text("Puntos para ganar KING") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
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
    onAbortMission: () -> Unit,
    onTeamSelected: (TeamSide) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (state.phase) {
                MatchPhase.MATCH_RUNNING -> {
                    when (state.gameMode) {
                        GameMode.BOMB -> Text("Escanea el QR correcto para activar la bomba.")
                        GameMode.HUNT_N_CODES -> Text("Busca por el campo ${state.huntTargetCount} codigos y escanealos.")
                        GameMode.SEQUENCE_CODES -> Text("Escanea los ${state.huntTargetCount} codigos en orden.")
                        GameMode.KING_OF_THE_QR -> Text("Selecciona equipo ROJO/VERDE y escanea el QR KING para capturar control.")
                    }
                }
                MatchPhase.BOMB_ARMED -> Text("Bomba armada. Espera la detonacion.")
                MatchPhase.DETONATED -> Text("Partida terminada por detonacion.")
                MatchPhase.CODES_COMPLETED -> Text("Partida completada por codigos.")
                MatchPhase.KING_COMPLETED -> Text("Partida finalizada por victoria en KING.")
                MatchPhase.ABORTED -> Text("Partida abortada por admin.")
                MatchPhase.TIME_OVER -> Text("Partida terminada por tiempo.")
                MatchPhase.ADMIN_SETUP -> Unit
            }
            if (state.phase == MatchPhase.MATCH_RUNNING && state.gameMode == GameMode.KING_OF_THE_QR) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onTeamSelected(TeamSide.RED) }, modifier = Modifier.weight(1f)) {
                        Text("Equipo ROJO")
                    }
                    Button(onClick = { onTeamSelected(TeamSide.GREEN) }, modifier = Modifier.weight(1f)) {
                        Text("Equipo VERDE")
                    }
                }
            }
            if (state.phase == MatchPhase.MATCH_RUNNING || state.phase == MatchPhase.BOMB_ARMED) {
                HoldToAbortButton(onAbortMission)
            }
            if (
                state.phase == MatchPhase.DETONATED ||
                state.phase == MatchPhase.CODES_COMPLETED ||
                state.phase == MatchPhase.KING_COMPLETED ||
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
