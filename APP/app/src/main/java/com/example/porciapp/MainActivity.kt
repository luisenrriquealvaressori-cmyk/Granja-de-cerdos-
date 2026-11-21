package com.example.porciapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.model.WorkspaceUiPreferences
import com.example.porciapp.data.repository.FarmRepository
import com.example.porciapp.data.sync.SyncManager
import com.example.porciapp.ui.components.FieldBottomNavBar
import com.example.porciapp.ui.navigation.Screen
import com.example.porciapp.ui.screens.*
import com.example.porciapp.ui.theme.FieldBackground
import com.example.porciapp.ui.theme.PORCIAPPTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var farmRepository: FarmRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        farmRepository = FarmRepository(applicationContext)

        // Configurar sincronización periódica con WorkManager
        SyncManager.setupPeriodicSync(applicationContext)

        setContent {
            PORCIAPPTheme(darkTheme = false) {
                val coroutineScope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }
                val context = LocalContext.current

                // Observar sesión JWT cifrada en Keystore
                val activeSession by farmRepository.sessionManager.session.collectAsState()

                // Si no hay sesión válida, la pantalla de inicio es Login
                var currentScreen by remember(activeSession) {
                    mutableStateOf(if (activeSession != null && !activeSession!!.isExpired()) Screen.Dashboard else Screen.Login)
                }
                var selectedTabIndex by remember { mutableIntStateOf(0) }

                val activeRole = activeSession?.rol ?: UserRole.ADMINISTRADOR
                val activeProfile = activeSession?.toProfile() ?: UserProfile.defaultFor(activeRole)

                // Sincronizar automáticamente el catálogo, tareas y cola pendiente desde el servidor al iniciar con sesión activa
                LaunchedEffect(activeSession?.token) {
                    if (activeSession != null && !activeSession!!.isExpired()) {
                        try {
                            farmRepository.refreshCatalog()
                        } catch (_: Exception) {}
                        try {
                            if (farmRepository.getPendingCount() > 0) {
                                farmRepository.syncOutboxNow()
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Observar métricas dinámicas reactivas desde Room SQLite
                val pendingCount by farmRepository.pendingSyncCount.collectAsState(initial = 0)
                val failedItems by farmRepository.failedSyncItems.collectAsState(initial = emptyList())
                val partosHoy by farmRepository.partosHoyFlow.collectAsState(initial = 0)
                val lechonesHoy by farmRepository.lechonesHoyFlow.collectAsState(initial = 0)
                val alimentoKgHoy by farmRepository.alimentoKgHoyFlow.collectAsState(initial = 0.0)
                val tratamientosHoy by farmRepository.tratamientosHoyFlow.collectAsState(initial = 0)
                val registrosOperaciones by remember(activeRole) {
                    farmRepository.getOperacionesForRoleFlow(activeRole)
                }.collectAsState(initial = emptyList())
                val tareasPendientes by farmRepository.tareasPendientesCount.collectAsState(initial = 0)
                val listaTareas by farmRepository.tareasFlow.collectAsState(initial = emptyList())
                val listaBajas by farmRepository.bajasFlow.collectAsState(initial = emptyList())
                val listaNotificaciones by remember(activeRole) {
                    farmRepository.getNotificacionesForRoleFlow(activeRole)
                }.collectAsState(initial = emptyList())
                val notificacionesNoLeidas = remember(listaNotificaciones) { listaNotificaciones.count { !it.leida } }
                val cerdosDb by farmRepository.cerdosFlow.collectAsState(initial = emptyList())

                // Flujos de catálogos maestros y preferencias de UI personalizables por rol
                val galponesCat by farmRepository.galponesCatFlow.collectAsState(initial = emptyList())
                val jaulasCat by farmRepository.jaulasCatFlow.collectAsState(initial = emptyList())
                val insumosCat by farmRepository.insumosCatFlow.collectAsState(initial = emptyList())
                val laboresCat by farmRepository.laboresCatFlow.collectAsState(initial = emptyList())
                val horariosCat by farmRepository.horariosCatFlow.collectAsState(initial = emptyList())
                val operariosCat by farmRepository.operariosCatFlow.collectAsState(initial = emptyList())
                val clinicosCat by farmRepository.clinicosCatFlow.collectAsState(initial = emptyList())
                val uiPrefsFlow = remember(activeProfile.id, activeRole) {
                    farmRepository.getUiPreferencesFlow(activeProfile.id, activeRole)
                }
                val uiPrefs by uiPrefsFlow.collectAsState(
                    initial = WorkspaceUiPreferences.defaultFor(activeRole)
                )

                // Interceptar botón atrás de hardware/gestos
                BackHandler(enabled = currentScreen != Screen.Dashboard && currentScreen != Screen.Login) {
                    currentScreen = Screen.Dashboard
                    selectedTabIndex = 0
                }

                val syncAndNotify: suspend (String) -> Unit = { accion ->
                    when (val res = farmRepository.syncOutboxNow()) {
                        is com.example.porciapp.data.repository.FarmRepository.DirectSyncResult.Success -> {
                            snackbarHostState.showSnackbar("$accion • ¡Sincronizado con la API (${res.savedCount} confirmados)!")
                        }
                        is com.example.porciapp.data.repository.FarmRepository.DirectSyncResult.Partial -> {
                            snackbarHostState.showSnackbar("$accion • Parcial: ${res.message}")
                        }
                        is com.example.porciapp.data.repository.FarmRepository.DirectSyncResult.Failure -> {
                            snackbarHostState.showSnackbar("$accion • Guardado en SQLite (Servidor offline)")
                        }
                        is com.example.porciapp.data.repository.FarmRepository.DirectSyncResult.NoPending -> {
                            snackbarHostState.showSnackbar(accion)
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = FieldBackground,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        val currentTabId = when (currentScreen) {
                            Screen.Dashboard -> "inicio"
                            Screen.FeedSupply, Screen.FeedList -> "alimento"
                            Screen.Treatment, Screen.Health -> "sanidad"
                            Screen.ReproductiveEvent -> "partos"
                            Screen.FieldOperations -> if (activeRole == UserRole.OPERARIO) "labores" else "gastos"
                            Screen.TasksSchedule -> "tareas"
                            Screen.Animals -> "animales"
                            Screen.Mortality -> "bajas"
                            else -> "inicio"
                        }

                        AnimatedVisibility(
                            visible = currentScreen !is Screen.Login,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                        ) {
                            FieldBottomNavBar(
                                selectedTabId = currentTabId,
                                activeRole = activeRole,
                                customTabIds = uiPrefs.navInferior,
                                onTabSelectedById = { tabId ->
                                    currentScreen = when (tabId) {
                                        "inicio" -> Screen.Dashboard
                                        "alimento" -> Screen.FeedSupply
                                        "sanidad" -> Screen.Treatment
                                        "partos" -> Screen.ReproductiveEvent
                                        "labores", "gastos" -> Screen.FieldOperations
                                        "tareas" -> Screen.TasksSchedule
                                        "animales" -> Screen.Animals
                                        "bajas" -> Screen.Mortality
                                        else -> Screen.Dashboard
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = if (currentScreen is Screen.Login) 0.dp else innerPadding.calculateBottomPadding())
                    ) {
                        AnimatedContent(
                            targetState = currentScreen,
                            transitionSpec = {
                                if (targetState is Screen.Login || initialState is Screen.Login) {
                                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                                } else if (targetState is Screen.Dashboard) {
                                    (slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(320)) + fadeIn(animationSpec = tween(280))) togetherWith
                                            (slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(320)) + fadeOut(animationSpec = tween(250)))
                                } else if (initialState is Screen.Dashboard) {
                                    (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(320)) + fadeIn(animationSpec = tween(280))) togetherWith
                                            (slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(320)) + fadeOut(animationSpec = tween(250)))
                                } else {
                                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(200))
                                }
                            },
                            label = "screenTransition"
                        ) { screen ->
                            when (screen) {
                            is Screen.Login -> {
                                LoginScreen(
                                    onLoginSuccess = { role, profile ->
                                        currentScreen = Screen.Dashboard
                                        selectedTabIndex = 0
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Bienvenido(a), ${profile.nombre} (${role.titulo})")
                                            // Limpiar caché del perfil anterior y refrescar catálogo del rol actual
                                            try {
                                                farmRepository.clearRoleScopedCache()
                                            } catch (_: Exception) {}
                                            try {
                                                farmRepository.refreshCatalog()
                                            } catch (_: Exception) {}
                                            try {
                                                if (farmRepository.getPendingCount() > 0) {
                                                    farmRepository.syncOutboxNow()
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                )
                            }
                            is Screen.Dashboard -> {
                                DashboardScreen(
                                    pendingSyncCount = pendingCount,
                                    failedSyncCount = failedItems.size,
                                    partosHoy = partosHoy,
                                    lechonesHoy = lechonesHoy,
                                    alimentoKgHoy = alimentoKgHoy,
                                    tratamientosHoy = tratamientosHoy,
                                    tareasPendientesCount = tareasPendientes,
                                    notificacionesNoLeidasCount = notificacionesNoLeidas,
                                    notificaciones = listaNotificaciones,
                                    tareas = listaTareas,
                                    cerdosFromDb = cerdosDb,
                                    operacionesGastos = registrosOperaciones,
                                    galponesCatalog = galponesCat,
                                    horariosCatalog = horariosCat,
                                    insumosCatalog = insumosCat,
                                    activeRole = activeRole,
                                    activeProfile = activeProfile,
                                    uiPrefs = uiPrefs,
                                    onSaveUiPreferences = { prefs ->
                                        coroutineScope.launch {
                                            farmRepository.updateUiPreferences(activeProfile.id, activeRole, prefs)
                                            snackbarHostState.showSnackbar("Interfaz personalizada guardada")
                                        }
                                    },
                                    onResetUiPreferences = {
                                        coroutineScope.launch {
                                            farmRepository.updateUiPreferences(
                                                activeProfile.id,
                                                activeRole,
                                                WorkspaceUiPreferences.defaultFor(activeRole)
                                            )
                                            snackbarHostState.showSnackbar("Interfaz restaurada a valores del rol ${activeRole.titulo}")
                                        }
                                    },
                                    onRoleChanged = { /* El rol lo define el token del servidor */ },
                                    onNavigateToTreatment = { currentScreen = Screen.Treatment },
                                    onNavigateToReproductiveEvent = { currentScreen = Screen.ReproductiveEvent },
                                    onNavigateToFeedSupply = { currentScreen = Screen.FeedSupply },
                                    onNavigateToFieldOperations = { currentScreen = Screen.FieldOperations },
                                    onNavigateToAnimals = {
                                        selectedTabIndex = 1
                                        currentScreen = Screen.Animals
                                    },
                                    onNavigateToTasksSchedule = { currentScreen = Screen.TasksSchedule },
                                    onNavigateToMortality = { currentScreen = Screen.Mortality },
                                    onToggleTask = { id, completed, obs ->
                                        coroutineScope.launch {
                                            farmRepository.toggleTask(id, completed, obs)
                                            syncAndNotify("Tarea completada")
                                        }
                                    },
                                    onMarcarNotificacionLeida = { notifId ->
                                        coroutineScope.launch {
                                            farmRepository.marcarNotificacionLeida(notifId)
                                        }
                                    },
                                    onSyncNow = {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Transmitiendo cola Outbox al servidor...")
                                            syncAndNotify("Sincronización")
                                        }
                                    },
                                    onRetryFailed = {
                                        coroutineScope.launch {
                                            farmRepository.retryFailedSync()
                                            syncAndNotify("Reintento de cola")
                                        }
                                    },
                                    onDiscardFailed = {
                                        coroutineScope.launch {
                                            farmRepository.discardFailedSync()
                                            snackbarHostState.showSnackbar("Registros con error descartados de la cola local")
                                        }
                                    },
                                    onLogout = {
                                        farmRepository.sessionManager.clear()
                                        currentScreen = Screen.Login
                                        coroutineScope.launch {
                                            try {
                                                farmRepository.clearRoleScopedCache()
                                            } catch (_: Exception) {}
                                            snackbarHostState.showSnackbar("Turno finalizado y sesión cerrada")
                                        }
                                    }
                                )
                            }
                            is Screen.Treatment -> {
                                TreatmentScreen(
                                    activeProfile = activeProfile,
                                    insumosCatalog = insumosCat,
                                    clinicosCatalog = clinicosCat,
                                    galponesCatalog = galponesCat,
                                    jaulasCatalog = jaulasCat,
                                    cerdosFromDb = cerdosDb,
                                    uiPrefs = uiPrefs,
                                    onBackClick = { currentScreen = Screen.Dashboard },
                                    onSaveRecord = { record ->
                                        coroutineScope.launch {
                                            farmRepository.saveTreatment(record)
                                            currentScreen = Screen.Dashboard
                                            syncAndNotify("Tratamiento de ${record.crotal}")
                                        }
                                    }
                                )
                            }
                            is Screen.ReproductiveEvent -> {
                                ReproductiveEventScreen(
                                    activeProfile = activeProfile,
                                    clinicosCatalog = clinicosCat,
                                    horariosCatalog = horariosCat,
                                    jaulasCatalog = jaulasCat,
                                    cerdosFromDb = cerdosDb,
                                    uiPrefs = uiPrefs,
                                    onBackClick = { currentScreen = Screen.Dashboard },
                                    onSaveRecord = { record ->
                                        coroutineScope.launch {
                                            farmRepository.saveReproductiveEvent(record)
                                            currentScreen = Screen.Dashboard
                                            syncAndNotify("${record.tipoEvento.label} • Cerda ${record.crotalCerda}")
                                        }
                                    }
                                )
                            }
                            is Screen.FeedSupply -> {
                                FeedSupplyScreen(
                                    activeProfile = activeProfile,
                                    galponesCatalog = galponesCat,
                                    insumosCatalog = insumosCat,
                                    horariosCatalog = horariosCat,
                                    uiPrefs = uiPrefs,
                                    onBackClick = { currentScreen = Screen.Dashboard },
                                    onSaveRecord = { record ->
                                        coroutineScope.launch {
                                            farmRepository.saveFeedSupply(record)
                                            currentScreen = Screen.Dashboard
                                            syncAndNotify("Ración dispensada (${record.cantidadKg} Kg)")
                                        }
                                    }
                                )
                            }
                            is Screen.Animals -> {
                                PigManagementScreen(
                                    activeProfile = activeProfile,
                                    cerdosFromDb = cerdosDb,
                                    galponesCatalog = galponesCat,
                                    jaulasCatalog = jaulasCat,
                                    clinicosCatalog = clinicosCat,
                                    uiPrefs = uiPrefs,
                                    onBackClick = {
                                        currentScreen = Screen.Dashboard
                                        selectedTabIndex = 0
                                    },
                                    onSaveCerdo = { entity ->
                                        coroutineScope.launch {
                                            farmRepository.saveCerdo(entity)
                                            syncAndNotify("Cerdo ${entity.codigoCrotal} registrado")
                                        }
                                    }
                                )
                            }
                            is Screen.Health -> {
                                TreatmentScreen(
                                    activeProfile = activeProfile,
                                    insumosCatalog = insumosCat,
                                    clinicosCatalog = clinicosCat,
                                    galponesCatalog = galponesCat,
                                    jaulasCatalog = jaulasCat,
                                    cerdosFromDb = cerdosDb,
                                    uiPrefs = uiPrefs,
                                    onBackClick = {
                                        currentScreen = Screen.Dashboard
                                        selectedTabIndex = 0
                                    },
                                    onSaveRecord = { record ->
                                        coroutineScope.launch {
                                            farmRepository.saveTreatment(record)
                                            currentScreen = Screen.Dashboard
                                            selectedTabIndex = 0
                                            syncAndNotify("Tratamiento ${record.medicamento}")
                                        }
                                    }
                                )
                            }
                            is Screen.FeedList -> {
                                FeedSupplyScreen(
                                    activeProfile = activeProfile,
                                    galponesCatalog = galponesCat,
                                    insumosCatalog = insumosCat,
                                    horariosCatalog = horariosCat,
                                    uiPrefs = uiPrefs,
                                    onBackClick = {
                                        currentScreen = Screen.Dashboard
                                        selectedTabIndex = 0
                                    },
                                    onSaveRecord = { record ->
                                        coroutineScope.launch {
                                            farmRepository.saveFeedSupply(record)
                                            currentScreen = Screen.Dashboard
                                            selectedTabIndex = 0
                                            syncAndNotify("Ración dispensada (${record.cantidadKg} Kg)")
                                        }
                                    }
                                )
                            }
                            is Screen.FieldOperations -> {
                                FieldOperationsScreen(
                                    activeProfile = activeProfile,
                                    onBackClick = {
                                        currentScreen = Screen.Dashboard
                                        selectedTabIndex = 0
                                    },
                                    registrosGuardados = registrosOperaciones,
                                    galponesCatalog = galponesCat,
                                    laboresCatalog = laboresCat,
                                    horariosCatalog = horariosCat,
                                    operariosCatalog = operariosCat,
                                    insumosCatalog = insumosCat,
                                    uiPrefs = uiPrefs,
                                    onSaveRecord = { record ->
                                        coroutineScope.launch {
                                            farmRepository.saveOperacionGasto(record)
                                            syncAndNotify("${record.categoria} • ${record.concepto.take(28)}")
                                        }
                                    }
                                )
                            }
                            is Screen.TasksSchedule -> {
                                TasksScheduleScreen(
                                    activeProfile = activeProfile,
                                    tareas = listaTareas,
                                    galponesCatalog = galponesCat,
                                    laboresCatalog = laboresCat,
                                    horariosCatalog = horariosCat,
                                    operariosCatalog = operariosCat,
                                    uiPrefs = uiPrefs,
                                    onBackClick = {
                                        currentScreen = Screen.Dashboard
                                        selectedTabIndex = 0
                                    },
                                    onToggleTask = { id, completed, obs ->
                                        coroutineScope.launch {
                                            farmRepository.toggleTask(id, completed, obs)
                                            syncAndNotify("Estado de tarea actualizado")
                                        }
                                    },
                                    onSaveNewTask = { nuevaTarea ->
                                        coroutineScope.launch {
                                            farmRepository.scheduleTask(nuevaTarea)
                                            syncAndNotify("Tarea '${nuevaTarea.titulo}' programada")
                                        }
                                    }
                                )
                            }
                            is Screen.Mortality -> {
                                MortalityScreen(
                                    activeProfile = activeProfile,
                                    historialBajas = listaBajas,
                                    clinicosCatalog = clinicosCat,
                                    galponesCatalog = galponesCat,
                                    cerdosFromDb = cerdosDb,
                                    uiPrefs = uiPrefs,
                                    onBackClick = {
                                        currentScreen = Screen.Dashboard
                                        selectedTabIndex = 0
                                    },
                                    onSaveBaja = { baja ->
                                        coroutineScope.launch {
                                            farmRepository.saveBajaMortalidad(baja)
                                            currentScreen = Screen.Dashboard
                                            selectedTabIndex = 0
                                            syncAndNotify("Baja del cerdo #${baja.crotal}")
                                        }
                                    }
                                )
                            }
                        }
                            }
                    }
                }
            }
        }
    }
}