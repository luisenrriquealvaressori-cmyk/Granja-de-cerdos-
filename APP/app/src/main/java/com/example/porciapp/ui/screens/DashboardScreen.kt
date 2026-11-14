package com.example.porciapp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.data.local.entities.CerdoEntity
import com.example.porciapp.data.local.entities.NotificacionEntity
import com.example.porciapp.data.local.entities.TareaEntity
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.repository.CatalogStore
import com.example.porciapp.ui.components.*
import com.example.porciapp.ui.theme.*
import kotlin.math.roundToInt

private data class RoleActionItem(
    val title: String,
    val subtitle: String,
    val badge: String,
    val icon: ImageVector,
    val isPrimaryEmphasis: Boolean = false,
    val onClick: () -> Unit
)

private data class QuickSearchEntry(
    val code: String,
    val description: String,
    val category: String,
    val actionLabel: String,
    val onSelect: () -> Unit
)

@Composable
fun DashboardScreen(
    pendingSyncCount: Int = 0,
    failedSyncCount: Int = 0,
    partosHoy: Int = 0,
    lechonesHoy: Int = 0,
    alimentoKgHoy: Double = 0.0,
    tratamientosHoy: Int = 0,
    tareasPendientesCount: Int = 0,
    notificacionesNoLeidasCount: Int = 0,
    notificaciones: List<NotificacionEntity> = emptyList(),
    tareas: List<TareaEntity> = emptyList(),
    cerdosFromDb: List<CerdoEntity> = emptyList(),
    activeRole: UserRole = UserRole.ADMINISTRADOR,
    activeProfile: UserProfile = UserProfile.defaultFor(activeRole),
    onRoleChanged: (UserRole) -> Unit = {},
    onNavigateToTreatment: () -> Unit,
    onNavigateToReproductiveEvent: () -> Unit,
    onNavigateToFeedSupply: () -> Unit,
    onNavigateToFieldOperations: () -> Unit = {},
    onNavigateToAnimals: () -> Unit = {},
    onNavigateToTasksSchedule: () -> Unit = {},
    onNavigateToMortality: () -> Unit = {},
    onToggleTask: (id: String, completed: Boolean, observaciones: String) -> Unit = { _, _, _ -> },
    onMarcarNotificacionLeida: (String) -> Unit = {},
    onSyncNow: () -> Unit = {},
    onRetryFailed: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()

    var showProfileDialog by remember { mutableStateOf(false) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var showExtraTools by remember(activeRole) { mutableStateOf(activeRole == UserRole.ADMINISTRADOR) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf("Todas") }

    // Datos reales derivados del catálogo + Room
    val totalAnimalesLotes = remember(catalogState.lotes, cerdosFromDb) {
        val enLotes = catalogState.lotes.sumOf { it.animales }
        val enCerdos = cerdosFromDb.size
        when {
            enLotes > 0 -> enLotes + catalogState.cerdas.size
            enCerdos > 0 -> enCerdos + catalogState.cerdas.size
            else -> 0
        }
    }

    val silosDisplay = remember(catalogState.silos) {
        catalogState.silos
    }

    val minDiasAutonomia = remember(silosDisplay) {
        silosDisplay.minOfOrNull { it.diasAutonomia } ?: 0
    }

    // Acciones principales (2x2) y secundarias adaptadas al Rol
    val primaryActions = remember(
        activeRole,
        alimentoKgHoy,
        tareasPendientesCount,
        totalAnimalesLotes,
        tratamientosHoy,
        partosHoy
    ) {
        when (activeRole) {
            UserRole.OPERARIO -> listOf(
                RoleActionItem(
                    title = "Dar Alimento",
                    subtitle = "Dispensar ración a tolva/lote",
                    badge = if (alimentoKgHoy > 0) "${alimentoKgHoy.roundToInt()} Kg hoy" else "Silos y tolvas",
                    icon = Icons.Default.Scale,
                    isPrimaryEmphasis = true,
                    onClick = onNavigateToFeedSupply
                ),
                RoleActionItem(
                    title = "Labores de Campo",
                    subtitle = "Limpieza, tolvas y rutina",
                    badge = "Turno operativo",
                    icon = Icons.Default.Engineering,
                    isPrimaryEmphasis = true,
                    onClick = onNavigateToFieldOperations
                ),
                RoleActionItem(
                    title = "Eventos Reprod.",
                    subtitle = "Monta IA, parto o destete",
                    badge = if (partosHoy > 0) "$partosHoy partos hoy" else "Maternidad",
                    icon = Icons.Default.ChildCare,
                    onClick = onNavigateToReproductiveEvent
                ),
                RoleActionItem(
                    title = "Mis Tareas",
                    subtitle = "Agenda y check del día",
                    badge = if (tareasPendientesCount > 0) "$tareasPendientesCount pendientes" else "Al día",
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    onClick = onNavigateToTasksSchedule
                )
            )
            UserRole.VETERINARIO -> listOf(
                RoleActionItem(
                    title = "Aplicar Vacuna / Med.",
                    subtitle = "Dosis, vía y días retiro",
                    badge = if (tratamientosHoy > 0) "$tratamientosHoy aplic. hoy" else "Vademécum",
                    icon = Icons.Default.Vaccines,
                    isPrimaryEmphasis = true,
                    onClick = onNavigateToTreatment
                ),
                RoleActionItem(
                    title = "Eventos Reprod.",
                    subtitle = "Monta IA, parto y destete",
                    badge = if (partosHoy > 0) "$partosHoy partos hoy" else "Maternidad",
                    icon = Icons.Default.ChildCare,
                    isPrimaryEmphasis = true,
                    onClick = onNavigateToReproductiveEvent
                ),
                RoleActionItem(
                    title = "Baja y Necropsia",
                    subtitle = "Diagnóstico y causa",
                    badge = "Bioseguridad",
                    icon = Icons.Default.MedicalServices,
                    onClick = onNavigateToMortality
                ),
                RoleActionItem(
                    title = "Agenda Clínica",
                    subtitle = "Tareas y calendario",
                    badge = if (tareasPendientesCount > 0) "$tareasPendientesCount pendientes" else "Al día",
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    onClick = onNavigateToTasksSchedule
                )
            )
            UserRole.ADMINISTRADOR -> listOf(
                RoleActionItem(
                    title = "Labores y Gastos",
                    subtitle = "Jornales, campo y compras",
                    badge = "Campo / C$",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    isPrimaryEmphasis = true,
                    onClick = onNavigateToFieldOperations
                ),
                RoleActionItem(
                    title = "Dar Alimento",
                    subtitle = "Consumo de silos y lotes",
                    badge = if (alimentoKgHoy > 0) "${alimentoKgHoy.roundToInt()} Kg hoy" else "Raciones",
                    icon = Icons.Default.Scale,
                    onClick = onNavigateToFeedSupply
                ),
                RoleActionItem(
                    title = "Eventos Reprod.",
                    subtitle = "Monta IA, parto y destete",
                    badge = if (partosHoy > 0) "$partosHoy partos hoy" else "Reproducción",
                    icon = Icons.Default.ChildCare,
                    onClick = onNavigateToReproductiveEvent
                ),
                RoleActionItem(
                    title = "Aplicar Tratamiento",
                    subtitle = "Vacunas, dosis e insumos",
                    badge = if (tratamientosHoy > 0) "$tratamientosHoy aplic. hoy" else "Clínica",
                    icon = Icons.Default.Vaccines,
                    onClick = onNavigateToTreatment
                )
            )
        }
    }

    // Herramientas secundarias flexibles (para apoyar en otras áreas de una pequeña granja sin saturar la vista)
    val secondaryActions = remember(activeRole, tareasPendientesCount) {
        when (activeRole) {
            UserRole.OPERARIO -> listOf(
                Triple("Aplicar Vacuna / Med.", Icons.Default.Vaccines, onNavigateToTreatment),
                Triple("Corrales y Cerdos", Icons.Default.Pets, onNavigateToAnimals),
                Triple("Reportar Baja", Icons.Default.ReportProblem, onNavigateToMortality)
            )
            UserRole.VETERINARIO -> listOf(
                Triple("Labores de Campo", Icons.Default.Engineering, onNavigateToFieldOperations),
                Triple("Corrales y Cerdos", Icons.Default.Pets, onNavigateToAnimals),
                Triple("Dar Alimento", Icons.Default.Scale, onNavigateToFeedSupply)
            )
            UserRole.ADMINISTRADOR -> listOf(
                Triple("Corrales y Cerdos", Icons.Default.Pets, onNavigateToAnimals),
                Triple("Agenda ($tareasPendientesCount)", Icons.AutoMirrored.Filled.Assignment, onNavigateToTasksSchedule),
                Triple("Bajas / Mortalidad", Icons.Default.ReportProblem, onNavigateToMortality)
            )
        }
    }

    // Próximas 3 tareas pendientes reales para marcar desde el Inicio
    val proximasTareas = remember(tareas) {
        tareas.filter { !it.completada }
            .sortedBy { it.horaProgramada }
            .take(3)
    }

    // Entradas reales para el buscador rápido y filtro por categoría
    val searchCatalogEntries = remember(catalogState, cerdosFromDb) {
        buildList {
            catalogState.cerdas.forEach { c ->
                add(
                    QuickSearchEntry(
                        code = c.caravana,
                        description = "Cerda (${c.estado.replace('_', ' ')}) • Jaula ${c.jaula.ifBlank { "Maternidad" }}",
                        category = "Reproducción",
                        actionLabel = "Maternidad",
                        onSelect = onNavigateToReproductiveEvent
                    )
                )
            }
            catalogState.lotes.forEach { l ->
                add(
                    QuickSearchEntry(
                        code = l.codigo,
                        description = "${l.galpon.ifBlank { "Galpón" }} • ${l.etapa} (${l.animales} animales)",
                        category = "Nutrición",
                        actionLabel = "Alimento",
                        onSelect = onNavigateToFeedSupply
                    )
                )
            }
            cerdosFromDb.forEach { cerdo ->
                add(
                    QuickSearchEntry(
                        code = cerdo.codigoCrotal,
                        description = "${cerdo.raza} (${cerdo.sexo}) • Jaula ${cerdo.jaula}",
                        category = "Sanidad",
                        actionLabel = "Ver Ficha",
                        onSelect = onNavigateToAnimals
                    )
                )
            }
            catalogState.silos.forEach { s ->
                add(
                    QuickSearchEntry(
                        code = s.nombre,
                        description = "${s.insumo.ifBlank { "Alimento" }} • ${s.nivelActualKg.roundToInt()} Kg (${s.diasAutonomia}d)",
                        category = "Silos",
                        actionLabel = "Dispensar",
                        onSelect = onNavigateToFeedSupply
                    )
                )
            }
        }
    }

    val filteredSearchEntries = remember(searchCatalogEntries, searchQuery, selectedCategoryFilter) {
        searchCatalogEntries.filter { item ->
            val matchCat = selectedCategoryFilter == "Todas" || item.category.equals(selectedCategoryFilter, ignoreCase = true)
            val matchQuery = searchQuery.isBlank() ||
                    item.code.contains(searchQuery, ignoreCase = true) ||
                    item.description.contains(searchQuery, ignoreCase = true)
            matchCat && matchQuery
        }.take(6)
    }

    Scaffold(
        topBar = {
            DashboardCompactTopBar(
                pendingSyncCount = pendingSyncCount,
                failedSyncCount = failedSyncCount,
                notificacionesNoLeidasCount = notificacionesNoLeidasCount,
                onSyncClick = onSyncNow,
                onNotificationsClick = { showNotificationsSheet = true }
            )
        },
        containerColor = FieldBackground
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. TARJETA COMPACTA DE ROL, TURNO Y SINCRONIZACIÓN
            item {
                RoleShiftHeaderCard(
                    profile = activeProfile,
                    pendingSyncCount = pendingSyncCount,
                    failedSyncCount = failedSyncCount,
                    onProfileClick = { showProfileDialog = true },
                    onSyncNow = onSyncNow,
                    onRetryFailed = onRetryFailed
                )
            }

            // 2. GRILLA 2x2 DE ACCIONES PRINCIPALES SEGÚN EL ROL
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val sectionTitle = when (activeRole) {
                            UserRole.OPERARIO -> "ACCIONES PRINCIPALES • GALPÓN"
                            UserRole.VETERINARIO -> "ACCIONES PRINCIPALES • SANIDAD"
                            UserRole.ADMINISTRADOR -> "ACCIONES PRINCIPALES • GESTIÓN"
                        }
                        Text(
                            text = sectionTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = FieldDarkBorder
                        )
                        Text(
                            text = "Acceso directo",
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Fila 1 de la cuadrícula 2x2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        primaryActions.take(2).forEach { action ->
                            RoleActionGridCard(
                                item = action,
                                activeRole = activeRole,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Fila 2 de la cuadrícula 2x2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        primaryActions.drop(2).take(2).forEach { action ->
                            RoleActionGridCard(
                                item = action,
                                activeRole = activeRole,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Herramientas secundarias / apoyo flexible en granja pequeña
                    if (activeRole == UserRole.ADMINISTRADOR) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            secondaryActions.forEach { (label, icon, action) ->
                                CompactSupportToolPill(
                                    label = label,
                                    icon = icon,
                                    onClick = action,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = FieldSurfaceContainerHigh,
                                border = BorderStroke(1.dp, FieldOutlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showExtraTools = !showExtraTools }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Apps,
                                            contentDescription = null,
                                            tint = FieldPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (activeRole == UserRole.OPERARIO)
                                                "Herramientas de apoyo (Sanidad / Corrales / Bajas)"
                                            else
                                                "Herramientas de apoyo (Labores / Corrales / Alimento)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = FieldOnSurface
                                        )
                                    }
                                    Icon(
                                        imageVector = if (showExtraTools) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = FieldPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            AnimatedVisibility(visible = showExtraTools) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    secondaryActions.forEach { (label, icon, action) ->
                                        CompactSupportToolPill(
                                            label = label,
                                            icon = icon,
                                            onClick = action,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. WIDGET RÁPIDO: MIS TAREAS PENDIENTES DE HOY (1-TAP CHECK)
            item {
                TactileCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = FieldSurfaceContainerLowest
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.TaskAlt,
                                contentDescription = null,
                                tint = FieldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "TAREAS PENDIENTES HOY ($tareasPendientesCount)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = FieldDarkBorder
                            )
                        }
                        TextButton(
                            onClick = onNavigateToTasksSchedule,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Ver agenda",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldPrimary
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = FieldPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (proximasTareas.isEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(FieldGreenPositive.copy(alpha = 0.12f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = FieldGreenPositive,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "¡Todo al día! No tienes tareas pendientes en este momento.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = FieldDarkBorder
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            proximasTareas.forEach { tarea ->
                                QuickDashboardTaskRow(
                                    tarea = tarea,
                                    onCompleteClick = {
                                        onToggleTask(tarea.id, true, tarea.observaciones)
                                    },
                                    onOpenClick = onNavigateToTasksSchedule
                                )
                            }
                        }
                    }
                }
            }

            // 4. RESUMEN DEL DÍA Y ESTADO DE SILOS EN TIEMPO REAL
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "RESUMEN DE GRANJA EN TIEMPO REAL",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = FieldDarkBorder
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompactMetricTile(
                            title = "ALIMENTO HOY",
                            mainValue = "${alimentoKgHoy.roundToInt()} Kg",
                            subText = if (minDiasAutonomia > 0) "Autonomía mín: ${minDiasAutonomia}d" else "Silos operativos",
                            icon = Icons.Default.Grain,
                            onClick = onNavigateToFeedSupply,
                            modifier = Modifier.weight(1f)
                        )
                        CompactMetricTile(
                            title = "MATERNIDAD HOY",
                            mainValue = "$partosHoy Partos",
                            subText = "$lechonesHoy nacidos • ${catalogState.cerdas.size} cerdas",
                            icon = Icons.Default.ChildCare,
                            onClick = onNavigateToReproductiveEvent,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompactMetricTile(
                            title = "SANIDAD HOY",
                            mainValue = "$tratamientosHoy Dosis",
                            subText = "${catalogState.medicamentos.size} med. en catálogo",
                            icon = Icons.Default.Vaccines,
                            onClick = onNavigateToTreatment,
                            modifier = Modifier.weight(1f)
                        )
                        CompactMetricTile(
                            title = "LOTES & CORRALES",
                            mainValue = "${catalogState.lotes.size} Lotes",
                            subText = if (totalAnimalesLotes > 0) "$totalAnimalesLotes animales activos" else "Ver corrales",
                            icon = Icons.Default.Pets,
                            onClick = onNavigateToAnimals,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Silos reales si están cargados en el catálogo
                    if (silosDisplay.isNotEmpty()) {
                        TactileCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = FieldSurfaceContainerLowest
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = FieldPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "NIVEL DE SILOS & BODEGA",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = FieldDarkBorder
                                    )
                                }
                                Text(
                                    text = "Dispensar →",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldPrimary,
                                    modifier = Modifier.clickable { onNavigateToFeedSupply() }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                silosDisplay.forEach { silo ->
                                    val pct = (silo.pctOcupacion.toFloat() / 100f).coerceIn(0f, 1f)
                                    val barColor = when {
                                        silo.pctOcupacion < 25 -> FieldError
                                        silo.pctOcupacion < 50 -> FieldOrangeWarning
                                        else -> FieldGreenPositive
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "${silo.nombre} (${silo.insumo.ifBlank { "Concentrado" }})",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = FieldOnSurface
                                            )
                                            Text(
                                                text = "${silo.nivelActualKg.roundToInt()} Kg • ${silo.pctOcupacion.roundToInt()}%",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = FieldPrimary
                                            )
                                        }
                                        LinearProgressIndicator(
                                            progress = { pct },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp)),
                                            color = barColor,
                                            trackColor = FieldSurfaceContainerHigh
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. BUSCADOR RÁPIDO DE LOTES, CERDAS Y CORRALES REALES
            item {
                TactileCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = FieldSurfaceContainerLowest
                ) {
                    Text(
                        text = "BUSCADOR RÁPIDO DE CAMPO",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = FieldDarkBorder
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "Buscar caravana, lote, corral o silo...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = FieldPrimary)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val categorias = listOf("Todas", "Reproducción", "Nutrición", "Sanidad", "Silos")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(categorias) { cat ->
                            FilterChip(
                                selected = selectedCategoryFilter == cat,
                                onClick = { selectedCategoryFilter = cat },
                                label = {
                                    Text(
                                        text = cat,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selectedCategoryFilter == cat) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    if (searchQuery.isNotBlank() || selectedCategoryFilter != "Todas") {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (filteredSearchEntries.isEmpty()) {
                            Text(
                                text = "Sin coincidencias en el catálogo local.",
                                style = MaterialTheme.typography.bodySmall,
                                color = FieldOnSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                filteredSearchEntries.forEach { entry ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = FieldSurfaceContainerHigh,
                                        border = BorderStroke(1.dp, FieldOutlineVariant),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { entry.onSelect() }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = entry.code,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = FieldPrimary
                                                )
                                                Text(
                                                    text = entry.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = FieldOnSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = FieldPrimaryContainer
                                            ) {
                                                Text(
                                                    text = entry.actionLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = FieldOnPrimary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
        }
    }

    // Modal de Ficha de Usuario / Cerrar Turno
    if (showProfileDialog) {
        RoleSelectionDialog(
            currentRole = activeRole,
            activeProfile = activeProfile,
            onRoleSelected = onRoleChanged,
            onDismissRequest = { showProfileDialog = false },
            onLogout = onLogout
        )
    }

    // Centro de Notificaciones de Campo
    if (showNotificationsSheet) {
        val listNotifs = if (notificaciones.isNotEmpty()) notificaciones else {
            catalogState.notificaciones.map { n ->
                NotificacionEntity(
                    id = n.id,
                    titulo = n.titulo,
                    mensaje = n.mensaje,
                    tipo = n.tipo,
                    severidad = n.severidad,
                    fechaHora = if (n.fechaHora > 0) n.fechaHora else System.currentTimeMillis(),
                    leida = n.leida
                )
            }
        }

        AlertDialog(
            onDismissRequest = { showNotificationsSheet = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = FieldPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Notificaciones de Granja", fontWeight = FontWeight.Bold, color = FieldDarkBorder)
                }
            },
            text = {
                if (listNotifs.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.NotificationsNone,
                            contentDescription = null,
                            tint = FieldSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text("No hay alertas pendientes", fontWeight = FontWeight.Bold, color = FieldDarkBorder)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(listNotifs.size) { idx ->
                            val n = listNotifs[idx]
                            val severityColor = when (n.severidad.lowercase()) {
                                "critica", "crítica", "alta" -> FieldError
                                "preventiva", "media" -> FieldOrangeWarning
                                else -> FieldPrimary
                            }
                            TactileCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = if (n.leida) FieldSurfaceDim.copy(alpha = 0.4f) else FieldSurfaceContainerLowest,
                                borderColor = if (n.leida) FieldOutlineVariant else severityColor
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = n.titulo,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldDarkBorder,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!n.leida) {
                                        TextButton(
                                            onClick = { onMarcarNotificacionLeida(n.id) },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                        ) {
                                            Text("Leída", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FieldPrimary)
                                        }
                                    }
                                }
                                Text(
                                    text = n.mensaje,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FieldOnSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TactileButton(
                    text = "Cerrar",
                    icon = Icons.Default.Close,
                    minHeight = 44.dp,
                    onClick = { showNotificationsSheet = false }
                )
            },
            containerColor = FieldSurfaceContainerLowest
        )
    }
}

@Composable
private fun DashboardCompactTopBar(
    pendingSyncCount: Int,
    failedSyncCount: Int,
    notificacionesNoLeidasCount: Int,
    onSyncClick: () -> Unit,
    onNotificationsClick: () -> Unit
) {
    Surface(
        color = FieldSurfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = FieldDarkBorder,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FieldPrimary)
                        .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Agriculture,
                        contentDescription = null,
                        tint = FieldSecondaryFixed,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "GRANJA SAN JOSÉ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = FieldPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Control Porcino Móvil",
                        style = MaterialTheme.typography.labelSmall,
                        color = FieldOnSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pastilla de estado de sincronización (1 toque para sincronizar)
                val hasPending = pendingSyncCount > 0 || failedSyncCount > 0
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (hasPending) FieldOrangeWarning.copy(alpha = 0.2f) else FieldGreenPositive.copy(alpha = 0.15f),
                    border = BorderStroke(1.5.dp, if (hasPending) FieldOrangeWarning else FieldGreenPositive),
                    modifier = Modifier.clickable { onSyncClick() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (hasPending) Icons.Default.Sync else Icons.Default.CloudDone,
                            contentDescription = "Sincronización",
                            tint = if (hasPending) FieldDarkBorder else FieldGreenPositive,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (hasPending) "${pendingSyncCount + failedSyncCount} Sync" else "Al día",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = FieldDarkBorder
                        )
                    }
                }

                // Campana de notificaciones
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FieldSurfaceContainerHigh)
                        .border(BorderStroke(1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                        .clickable { onNotificationsClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Notificaciones",
                        tint = FieldDarkBorder,
                        modifier = Modifier.size(20.dp)
                    )
                    if (notificacionesNoLeidasCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(FieldError),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = notificacionesNoLeidasCount.coerceAtMost(9).toString(),
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleShiftHeaderCard(
    profile: UserProfile,
    pendingSyncCount: Int,
    failedSyncCount: Int,
    onProfileClick: () -> Unit,
    onSyncNow: () -> Unit,
    onRetryFailed: () -> Unit
) {
    val roleAccent = when (profile.rol) {
        UserRole.OPERARIO -> RoleOperarioAccent
        UserRole.VETERINARIO -> RoleVeterinarioAccent
        UserRole.ADMINISTRADOR -> RoleAdminAccent
    }
    val roleBadgeBg = when (profile.rol) {
        UserRole.OPERARIO -> RoleOperarioContainer
        UserRole.VETERINARIO -> RoleVeterinarioContainer
        UserRole.ADMINISTRADOR -> RoleAdminContainer
    }

    TactileCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = FieldSurfaceContainerLowest,
        borderColor = roleAccent.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(roleAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = profile.rol.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(roleBadgeBg)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = profile.rol.titulo.uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = roleAccent
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = profile.rol.etiqueta,
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldOnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = profile.nombre,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = FieldOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
            TactileButton(
                onClick = onProfileClick,
                text = "Turno",
                icon = Icons.Default.Badge,
                backgroundColor = FieldSurfaceContainer,
                contentColor = FieldOnSurface,
                minHeight = 40.dp,
                shadowOffset = 1.dp
            )
        }

        // Si hay registros pendientes o fallidos en cola Outbox, mostrar barra directa aquí
        if (pendingSyncCount > 0 || failedSyncCount > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = FieldOutlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (failedSyncCount > 0)
                            "$failedSyncCount con error • $pendingSyncCount pendientes"
                        else
                            "$pendingSyncCount registro(s) guardados en el teléfono",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (failedSyncCount > 0) FieldError else FieldDarkBorder
                    )
                    Text(
                        text = "Listos para enviar al servidor central",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = FieldOnSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                TactileButton(
                    onClick = if (failedSyncCount > 0) onRetryFailed else onSyncNow,
                    text = if (failedSyncCount > 0) "Reintentar" else "Sincronizar",
                    icon = Icons.Default.Sync,
                    backgroundColor = roleAccent,
                    contentColor = Color.White,
                    minHeight = 38.dp,
                    shadowOffset = 1.dp
                )
            }
        }
    }
}

@Composable
private fun RoleActionGridCard(
    item: RoleActionItem,
    activeRole: UserRole = UserRole.ADMINISTRADOR,
    modifier: Modifier = Modifier
) {
    val roleAccent = when (activeRole) {
        UserRole.OPERARIO -> RoleOperarioAccent
        UserRole.VETERINARIO -> RoleVeterinarioAccent
        UserRole.ADMINISTRADOR -> RoleAdminAccent
    }
    val roleContainer = when (activeRole) {
        UserRole.OPERARIO -> RoleOperarioContainer
        UserRole.VETERINARIO -> RoleVeterinarioContainer
        UserRole.ADMINISTRADOR -> RoleAdminContainer
    }

    val bgColor = if (item.isPrimaryEmphasis) roleAccent else FieldSurfaceContainerLowest
    val titleColor = if (item.isPrimaryEmphasis) Color.White else FieldOnSurface
    val subColor = if (item.isPrimaryEmphasis) Color.White.copy(alpha = 0.88f) else FieldOnSurfaceVariant

    TactileCard(
        modifier = modifier
            .height(126.dp)
            .clickable { item.onClick() },
        backgroundColor = bgColor,
        borderColor = if (item.isPrimaryEmphasis) roleAccent else FieldOutlineVariant,
        shadowOffset = 2.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (item.isPrimaryEmphasis) Color.White.copy(alpha = 0.2f) else roleContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = if (item.isPrimaryEmphasis) Color.White else roleAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (item.isPrimaryEmphasis) Color.White else FieldSurfaceContainer)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = item.badge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = roleAccent,
                        maxLines = 1
                    )
                }
            }

            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CompactSupportToolPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = FieldSurfaceContainerLowest,
        border = BorderStroke(1.5.dp, FieldDarkBorder),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = FieldPrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = FieldDarkBorder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuickDashboardTaskRow(
    tarea: TareaEntity,
    onCompleteClick: () -> Unit,
    onOpenClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = FieldSurfaceContainerHigh,
        border = BorderStroke(1.dp, FieldDarkBorder.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(FieldSurfaceContainerLowest)
                    .border(BorderStroke(1.5.dp, FieldPrimary), RoundedCornerShape(6.dp))
                    .clickable { onCompleteClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completar tarea",
                    tint = FieldPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tarea.titulo,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = FieldDarkBorder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (tarea.completada) TextDecoration.LineThrough else TextDecoration.None
                )
                Text(
                    text = "${tarea.horaProgramada} • ${tarea.galponEtapa}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = FieldOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (tarea.prioridad.equals("Urgente", true) || tarea.prioridad.equals("Alta", true))
                    FieldErrorContainer else FieldSecondaryContainer
            ) {
                Text(
                    text = tarea.prioridad.uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (tarea.prioridad.equals("Urgente", true) || tarea.prioridad.equals("Alta", true))
                        FieldOnErrorContainer else FieldPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun CompactMetricTile(
    title: String,
    mainValue: String,
    subText: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TactileCard(
        modifier = modifier.clickable { onClick() },
        backgroundColor = FieldSurfaceContainerLowest,
        shadowOffset = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = FieldOnSurfaceVariant
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = FieldPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = mainValue,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = FieldPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subText,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = FieldOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
