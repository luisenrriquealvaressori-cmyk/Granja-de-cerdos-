package com.example.porciapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.data.local.entities.CatGalponEntity
import com.example.porciapp.data.local.entities.CatHorarioEntity
import com.example.porciapp.data.local.entities.CatLaborEntity
import com.example.porciapp.data.local.entities.CatOperarioEntity
import com.example.porciapp.data.local.entities.TareaEntity
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.model.WorkspaceUiPreferences
import com.example.porciapp.data.repository.CatalogStore
import com.example.porciapp.ui.components.CatalogChoiceItem
import com.example.porciapp.ui.components.DatabaseSelectorField
import com.example.porciapp.ui.components.ScheduleChipSelector
import com.example.porciapp.ui.components.SearchableCatalogBottomSheet
import com.example.porciapp.ui.components.TactileButton
import com.example.porciapp.ui.components.TactileCard
import com.example.porciapp.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TasksScheduleScreen(
    activeProfile: UserProfile,
    tareas: List<TareaEntity>,
    onBackClick: () -> Unit,
    onToggleTask: (id: String, completed: Boolean, observaciones: String) -> Unit,
    onSaveNewTask: (TareaEntity) -> Unit,
    galponesCatalog: List<CatGalponEntity> = emptyList(),
    laboresCatalog: List<CatLaborEntity> = emptyList(),
    horariosCatalog: List<CatHorarioEntity> = emptyList(),
    operariosCatalog: List<CatOperarioEntity> = emptyList(),
    uiPrefs: WorkspaceUiPreferences = WorkspaceUiPreferences.defaultFor(activeProfile.rol)
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()

    var selectedCategory by remember { mutableStateOf("Todas") }
    var showCompletedOnly by remember { mutableStateOf(false) }
    var showNewTaskDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var onlyMyTasks by remember(activeProfile.rol) { mutableStateOf(activeProfile.rol == UserRole.OPERARIO) }
    var selectedGalpon by remember(uiPrefs.galponPredeterminado) { mutableStateOf(uiPrefs.galponPredeterminado) }

    val galponFilterOptions = remember(galponesCatalog, catalogState.galpones) {
        if (galponesCatalog.isNotEmpty()) galponesCatalog.map { it.codigo to it.nombre }
        else catalogState.galpones.map { it.codigo to it.nombre }
    }
    val selectedGalponNombre = remember(selectedGalpon, galponFilterOptions) {
        galponFilterOptions.firstOrNull {
            it.first.equals(selectedGalpon, ignoreCase = true) ||
                it.second.equals(selectedGalpon, ignoreCase = true)
        }?.second ?: selectedGalpon
    }

    val categories = when (activeProfile.rol) {
        UserRole.OPERARIO -> listOf("Todas", "Nutrición", "Manejo", "Bioseguridad", "Mantenimiento")
        else -> listOf("Todas", "Sanidad", "Reproducción", "Nutrición", "Manejo", "Bioseguridad", "Mantenimiento")
    }

    val filteredTareas = remember(tareas, selectedCategory, showCompletedOnly, searchQuery, onlyMyTasks, selectedGalpon, selectedGalponNombre, activeProfile.nombre, activeProfile.rol) {
        fun matchCat(cat: String, tipo: String): Boolean {
            if (cat == "Todas") return true
            val t = tipo.trim().lowercase()
            return when (cat) {
                "Nutrición" -> t in setOf("nutrición", "nutricion", "alimentacion", "alimentación")
                "Reproducción" -> t in setOf("reproducción", "reproduccion")
                else -> tipo.equals(cat, ignoreCase = true)
            }
        }
        val firstWord = activeProfile.nombre.substringBefore(" ").trim()
        tareas.filter { tarea ->
            val matchCategory = matchCat(selectedCategory, tarea.tipoActividad)
            val matchStatus = if (showCompletedOnly) tarea.completada else !tarea.completada
            val matchScope = if (!onlyMyTasks) true else {
                tarea.asignadoA.isBlank() ||
                    tarea.asignadoA.equals(activeProfile.rol.titulo, ignoreCase = true) ||
                    tarea.asignadoA.equals("Ambos", ignoreCase = true) ||
                    tarea.asignadoA.contains(activeProfile.nombre, ignoreCase = true) ||
                    (firstWord.length >= 3 && tarea.asignadoA.contains(firstWord, ignoreCase = true))
            }
            val matchGalpon = if (selectedGalpon.isBlank()) true else {
                tarea.galponEtapa.isBlank() ||
                    tarea.galponEtapa.equals("Área General", ignoreCase = true) ||
                    tarea.galponEtapa.contains(selectedGalpon, ignoreCase = true) ||
                    (selectedGalponNombre.isNotBlank() && tarea.galponEtapa.contains(selectedGalponNombre, ignoreCase = true))
            }
            val matchSearch = searchQuery.isBlank() ||
                    tarea.titulo.contains(searchQuery, ignoreCase = true) ||
                    tarea.galponEtapa.contains(searchQuery, ignoreCase = true) ||
                    (tarea.loteCodigo?.contains(searchQuery, ignoreCase = true) == true) ||
                    tarea.asignadoA.contains(searchQuery, ignoreCase = true)
            matchCategory && matchStatus && matchScope && matchGalpon && matchSearch
        }.sortedBy { it.horaProgramada }
    }

    val totalPendientes = remember(tareas) { tareas.count { !it.completada } }
    val totalCompletadas = remember(tareas) { tareas.count { it.completada } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Agenda & Horario de Actividades",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldDarkBorder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Jornada Técnica • ${activeProfile.rol.titulo}: ${activeProfile.nombre}",
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldOnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Regresar", tint = FieldDarkBorder)
                    }
                },
                actions = {
                    if (activeProfile.rol.puedeProgramarTareas) {
                        IconButton(onClick = { showNewTaskDialog = true }) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Programar Tarea", tint = FieldPrimary, modifier = Modifier.size(28.dp))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FieldSurfaceContainerHigh)
            )
        },
        floatingActionButton = {
            if (activeProfile.rol.puedeProgramarTareas) {
                ExtendedFloatingActionButton(
                    onClick = { showNewTaskDialog = true },
                    containerColor = FieldPrimary,
                    contentColor = FieldOnPrimary,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Programar Tarea", fontWeight = FontWeight.Bold) }
                )
            }
        },
        containerColor = FieldBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
            ) {
                // 1. Selector de Estado equilibrado al 50/50: Pendientes vs Completadas
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FieldSurfaceContainerHigh)
                        .border(BorderStroke(1.dp, FieldOutlineVariant))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val pendingSelected = !showCompletedOnly
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (pendingSelected) FieldPrimary else FieldSurfaceContainerLowest)
                            .border(
                                BorderStroke(1.dp, if (pendingSelected) FieldPrimary else FieldOutlineVariant),
                                RoundedCornerShape(10.dp)
                            )
                            .selectable(selected = pendingSelected, role = Role.Tab) { showCompletedOnly = false }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = if (pendingSelected) Color.White else FieldPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Pendientes ($totalPendientes)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (pendingSelected) Color.White else FieldDarkBorder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    val completedSelected = showCompletedOnly
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (completedSelected) FieldSecondary else FieldSurfaceContainerLowest)
                            .border(
                                BorderStroke(1.dp, if (completedSelected) FieldSecondary else FieldOutlineVariant),
                                RoundedCornerShape(10.dp)
                            )
                            .selectable(selected = completedSelected, role = Role.Tab) { showCompletedOnly = true }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (completedSelected) Color.White else FieldSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Completadas ($totalCompletadas)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (completedSelected) Color.White else FieldDarkBorder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 2. Buscador y Filtros por Categoría adaptables (FlowRow)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filtrar por tarea, operario, galpón o lote...", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = FieldPrimary) },
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

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (onlyMyTasks) FieldSecondary else FieldSurfaceContainerHigh,
                            border = BorderStroke(1.dp, if (onlyMyTasks) FieldSecondary else FieldOutlineVariant),
                            modifier = Modifier.clickable { onlyMyTasks = true }
                        ) {
                            Text(
                                text = "Mis Tareas",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (onlyMyTasks) Color.White else FieldDarkBorder,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (!onlyMyTasks) FieldSecondary else FieldSurfaceContainerHigh,
                            border = BorderStroke(1.dp, if (!onlyMyTasks) FieldSecondary else FieldOutlineVariant),
                            modifier = Modifier.clickable { onlyMyTasks = false }
                        ) {
                            Text(
                                text = "Todas",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (!onlyMyTasks) Color.White else FieldDarkBorder,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        if (galponFilterOptions.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selectedGalpon.isBlank()) FieldPrimary else FieldSurfaceContainerHigh,
                                border = BorderStroke(1.dp, if (selectedGalpon.isBlank()) FieldPrimary else FieldOutlineVariant),
                                modifier = Modifier.clickable { selectedGalpon = "" }
                            ) {
                                Text(
                                    text = "Galpón: Todos",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedGalpon.isBlank()) Color.White else FieldDarkBorder,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                            galponFilterOptions.take(4).forEach { (cod, nom) ->
                                val isSelG = selectedGalpon.equals(cod, true) || selectedGalpon.equals(nom, true)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelG) FieldPrimary else FieldSurfaceContainerHigh,
                                    border = BorderStroke(1.dp, if (isSelG) FieldPrimary else FieldOutlineVariant),
                                    modifier = Modifier.clickable { selectedGalpon = cod }
                                ) {
                                    Text(
                                        text = nom,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelG) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelG) Color.White else FieldDarkBorder,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { cat ->
                            val isSelected = selectedCategory == cat
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) FieldPrimary else FieldSurfaceContainerHigh,
                                border = BorderStroke(1.dp, if (isSelected) FieldPrimary else FieldOutlineVariant),
                                modifier = Modifier.clickable { selectedCategory = cat }
                            ) {
                                Text(
                                    text = cat,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else FieldDarkBorder,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // 3. Lista Timeline de Tareas
                if (filteredTareas.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (showCompletedOnly) Icons.Default.DoneAll else Icons.Default.EventAvailable,
                                contentDescription = null,
                                tint = FieldSecondary,
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = if (showCompletedOnly) "No hay tareas completadas para este filtro" else "¡Al día! No hay tareas pendientes",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldDarkBorder,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = if (activeProfile.rol.puedeProgramarTareas) {
                                    "Presiona el botón '+' para programar una nueva actividad."
                                } else {
                                    "Tus tareas programadas aparecerán aquí tras sincronizar."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = FieldOnSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredTareas, key = { it.id }) { tarea ->
                            TactileTaskItemCard(
                                tarea = tarea,
                                onToggle = { isChecked ->
                                    onToggleTask(tarea.id, isChecked, tarea.observaciones)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewTaskDialog) {
        NewTaskDialog(
            defaultAsignado = activeProfile.nombre,
            galponesCatalog = galponesCatalog,
            laboresCatalog = laboresCatalog,
            horariosCatalog = horariosCatalog,
            operariosCatalog = operariosCatalog,
            uiPrefs = uiPrefs,
            onDismiss = { showNewTaskDialog = false },
            onConfirm = { nuevaTarea ->
                onSaveNewTask(nuevaTarea)
                showNewTaskDialog = false
            }
        )
    }
}

@Composable
private fun TactileTaskItemCard(
    tarea: TareaEntity,
    onToggle: (Boolean) -> Unit
) {
    val priorityColor = when (tarea.prioridad.lowercase()) {
        "urgente" -> FieldError
        "alta" -> FieldOrangeWarning
        "media" -> FieldPrimary
        else -> FieldSecondary
    }

    val categoryIcon = when (tarea.tipoActividad.lowercase()) {
        "sanidad" -> Icons.Default.Vaccines
        "reproduccion", "reproducción" -> Icons.Default.ChildCare
        "nutricion", "nutrición", "alimentacion" -> Icons.Default.Inventory2
        "bioseguridad" -> Icons.Default.Shield
        "mantenimiento" -> Icons.Default.Build
        else -> Icons.Default.Assignment
    }

    TactileCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (tarea.completada) FieldSurfaceDim.copy(alpha = 0.5f) else FieldSurfaceContainerLowest,
        borderColor = if (tarea.completada) FieldOutlineVariant else FieldDarkBorder
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Botón Táctil de Completado (Apto para Guantes de Campo)
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (tarea.completada) FieldGreenPositive else FieldSurfaceContainerHigh)
                    .border(BorderStroke(2.dp, if (tarea.completada) FieldGreenPositive else FieldDarkBorder), RoundedCornerShape(8.dp))
                    .clickable { onToggle(!tarea.completada) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (tarea.completada) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (tarea.completada) "Completada" else "Marcar completada",
                    tint = if (tarea.completada) Color.White else FieldOnSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Fila Superior: Hora & Categoría & Prioridad
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = FieldPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = tarea.horaProgramada,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                            color = FieldPrimary,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(4.dp))
                                .background(FieldSurfaceContainerHigh)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = categoryIcon,
                                    contentDescription = null,
                                    tint = FieldSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = tarea.tipoActividad,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FieldSecondary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Badge de prioridad
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(priorityColor.copy(alpha = 0.15f))
                            .border(BorderStroke(1.dp, priorityColor), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = tarea.prioridad.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = priorityColor,
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Título de la tarea
                Text(
                    text = tarea.titulo,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (tarea.completada) FieldOutline else FieldDarkBorder,
                    textDecoration = if (tarea.completada) TextDecoration.LineThrough else null
                )

                if (tarea.descripcion.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tarea.descripcion,
                        style = MaterialTheme.typography.bodySmall,
                        color = FieldOnSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Detalles de ubicación y responsable
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            tint = FieldPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = tarea.galponEtapa + (tarea.loteCodigo?.let { " • Lote $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldDarkBorder,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.widthIn(max = 130.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = FieldOutline,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = tarea.asignadoA,
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldOnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (tarea.completada && !tarea.horaCompletada.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Completada hoy a las ${tarea.horaCompletada}",
                        style = MaterialTheme.typography.labelSmall,
                        color = FieldSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewTaskDialog(
    defaultAsignado: String,
    galponesCatalog: List<CatGalponEntity> = emptyList(),
    laboresCatalog: List<CatLaborEntity> = emptyList(),
    horariosCatalog: List<CatHorarioEntity> = emptyList(),
    operariosCatalog: List<CatOperarioEntity> = emptyList(),
    uiPrefs: WorkspaceUiPreferences = WorkspaceUiPreferences.defaultFor(com.example.porciapp.data.model.UserRole.OPERARIO),
    onDismiss: () -> Unit,
    onConfirm: (TareaEntity) -> Unit
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()

    val plantillasHorarios = remember(horariosCatalog, catalogState.horarios) {
        val src = if (horariosCatalog.isNotEmpty()) horariosCatalog else catalogState.horarios.map {
            CatHorarioEntity(
                codigo = it.codigo, nombre = it.nombre, tipoHorario = it.tipoHorario,
                horaInicio = it.horaInicio, horaFin = it.horaFin,
                categoriaActividad = it.categoriaActividad, etapaAplicable = it.etapaAplicable,
                rolResponsable = it.rolResponsable, prioridadDefault = it.prioridadDefault,
                descripcion = it.descripcion
            )
        }
        src
    }
    val laboresSource = remember(laboresCatalog, catalogState.labores) {
        val src = if (laboresCatalog.isNotEmpty()) laboresCatalog else catalogState.labores.map {
            CatLaborEntity(
                codigo = it.codigo, nombre = it.nombre, tipoRegistro = it.tipoRegistro,
                categoria = it.categoria, etapaSugerida = it.etapaSugerida,
                galponSugerido = it.galponSugerido, duracionSugerida = it.duracionSugerida,
                tarifaReferenciaNio = it.tarifaReferenciaNio, centroCostoCodigo = it.centroCostoCodigo,
                rolSugerido = it.rolSugerido, requiereInsumo = it.requiereInsumo
            )
        }
        src.filter { it.tipoRegistro == "LABOR_CAMPO" }
    }

    val plantillasItems = remember(plantillasHorarios, laboresSource) {
        val fromPlantillas = plantillasHorarios.filter { it.tipoHorario == "PLANTILLA_AGENDA" }.map { h ->
            CatalogChoiceItem(
                id = "H:${h.codigo}",
                title = h.nombre,
                subtitle = listOfNotNull(
                    h.categoriaActividad.takeIf { it.isNotBlank() },
                    "${h.horaInicio}-${h.horaFin}".trim('-').takeIf { it.isNotBlank() },
                    h.rolResponsable.takeIf { it.isNotBlank() }
                ).joinToString(" • "),
                badge = h.prioridadDefault.ifBlank { "Media" },
                category = "Plantillas Agenda"
            )
        }
        val fromLabores = laboresSource.map { l ->
            CatalogChoiceItem(
                id = "L:${l.codigo}",
                title = l.nombre,
                subtitle = "${l.categoria} • ${l.etapaSugerida.ifBlank { "General" }} • ${l.duracionSugerida}",
                badge = l.categoria,
                category = "Labores Campo"
            )
        }
        fromPlantillas + fromLabores
    }

    val galponItems = remember(galponesCatalog, catalogState.galpones) {
        val fromDb = if (galponesCatalog.isNotEmpty()) {
            galponesCatalog.map {
                CatalogChoiceItem(
                    id = it.nombre,
                    title = it.nombre,
                    subtitle = "${it.tipoSistema} • Cap. ${it.capacidad}",
                    badge = it.codigo,
                    category = "Galpones"
                )
            }
        } else {
            catalogState.galpones.map {
                CatalogChoiceItem(
                    id = it.nombre,
                    title = it.nombre,
                    subtitle = "${it.tipoSistema} • Cap. ${it.capacidad}",
                    badge = it.codigo,
                    category = "Galpones"
                )
            }
        }
        fromDb + listOf(
            CatalogChoiceItem(id = "Área General", title = "Área General", subtitle = "Toda la granja", badge = "GEN", category = "Áreas")
        )
    }

    val operarioItems = remember(operariosCatalog, catalogState.operarios, defaultAsignado) {
        val base = if (operariosCatalog.isNotEmpty()) {
            operariosCatalog.map {
                CatalogChoiceItem(
                    id = it.nombre,
                    title = it.nombre,
                    subtitle = "${it.rol} • ${it.matricula.ifBlank { it.nombreUsuario }}",
                    badge = it.rol,
                    category = it.rol
                )
            }
        } else {
            catalogState.operarios.map {
                CatalogChoiceItem(
                    id = it.nombre,
                    title = it.nombre,
                    subtitle = "${it.rol} • ${it.matricula.ifBlank { it.nombreUsuario }}",
                    badge = it.rol,
                    category = it.rol
                )
            }
        }
        if (base.any { it.id.equals(defaultAsignado, ignoreCase = true) }) base
        else listOf(CatalogChoiceItem(id = defaultAsignado, title = defaultAsignado, subtitle = "Usuario activo", badge = "ACTIVO")) + base
    }

    val horarioItems = remember(plantillasHorarios) {
        val list = plantillasHorarios
            .filter { it.horaInicio.isNotBlank() }
            .distinctBy { it.horaInicio }
            .map {
                CatalogChoiceItem(
                    id = it.horaInicio,
                    title = it.horaInicio,
                    subtitle = it.nombre
                )
            }
        list.ifEmpty {
            listOf(
                CatalogChoiceItem("06:30", "06:30", "Inicio Mañana"),
                CatalogChoiceItem("08:00", "08:00", "Mañana"),
                CatalogChoiceItem("14:00", "14:00", "Tarde"),
                CatalogChoiceItem("16:30", "16:30", "Cierre")
            )
        }
    }

    var selectedPlantillaId by remember { mutableStateOf("") }
    var usarTituloPersonalizado by remember { mutableStateOf(false) }
    var titulo by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }
    var tipoActividad by remember { mutableStateOf("Sanidad") }
    var prioridad by remember { mutableStateOf("Media") }
    var horaProgramada by remember(horarioItems) {
        mutableStateOf(horarioItems.firstOrNull()?.id ?: "08:00")
    }
    var galponEtapa by remember(galponItems, uiPrefs.galponPredeterminado) {
        val pref = uiPrefs.galponPredeterminado.trim()
        val matched = if (pref.isNotBlank()) {
            galponItems.firstOrNull {
                it.id.equals(pref, ignoreCase = true) || it.badge.equals(pref, ignoreCase = true)
            }?.id
        } else null
        mutableStateOf(matched ?: galponItems.firstOrNull()?.id ?: "Área General")
    }
    var loteCodigo by remember(catalogState.lotes) {
        mutableStateOf(catalogState.lotes.firstOrNull()?.codigo ?: "")
    }
    var asignadoA by remember(operarioItems, defaultAsignado) {
        mutableStateOf(operarioItems.firstOrNull { it.id.equals(defaultAsignado, ignoreCase = true) }?.id ?: defaultAsignado)
    }

    val loteCerdaItems = remember(catalogState.lotes, catalogState.cerdas, galponEtapa) {
        val lotes = catalogState.lotes.map {
            CatalogChoiceItem(
                id = it.codigo,
                title = "${it.codigo} • ${it.galpon.ifBlank { it.etapa }}",
                subtitle = "${it.etapa} • ${it.animales} animales",
                badge = "LOTE",
                category = "Lotes"
            )
        }.sortedByDescending { it.title.contains(galponEtapa, ignoreCase = true) }
        val cerdas = catalogState.cerdas.map {
            CatalogChoiceItem(
                id = it.caravana,
                title = "Cerda ${it.caravana}",
                subtitle = "${it.estado} • ${it.galpon} ${it.jaula}".trim(),
                badge = "CERDA",
                category = "Cerdas"
            )
        }
        listOf(CatalogChoiceItem(id = "", title = "Sin lote específico", subtitle = "Aplica a todo el galpón", badge = "GEN", category = "General")) + lotes + cerdas
    }

    val tipos = listOf("Sanidad", "Reproducción", "Nutrición", "Bioseguridad", "Mantenimiento")
    val prioridades = listOf("Baja", "Media", "Alta", "Urgente")

    var showPlantillaSheet by remember { mutableStateOf(false) }
    var showGalponSheet by remember { mutableStateOf(false) }
    var showLoteSheet by remember { mutableStateOf(false) }
    var showAsignadoSheet by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EventNote, contentDescription = null, tint = FieldPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Programar Tarea de Granja", fontWeight = FontWeight.Bold, color = FieldDarkBorder)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DatabaseSelectorField(
                    label = "ACTIVIDAD / PLANTILLA (CATÁLOGO BD) *",
                    selectedTitle = if (!usarTituloPersonalizado) titulo else "Actividad personalizada",
                    selectedSubtitle = plantillasItems.firstOrNull { it.id == selectedPlantillaId }?.subtitle.orEmpty(),
                    selectedBadge = plantillasItems.firstOrNull { it.id == selectedPlantillaId }?.badge.orEmpty(),
                    placeholder = "Seleccionar plantilla o labor estándar...",
                    leadingIcon = Icons.Default.PlaylistAddCheck,
                    compact = true,
                    onClick = {
                        usarTituloPersonalizado = false
                        showPlantillaSheet = true
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(FieldSurfaceContainerHigh)
                        .clickable { usarTituloPersonalizado = !usarTituloPersonalizado }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Actividad personalizada",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = FieldOnSurface
                    )
                    Switch(
                        checked = usarTituloPersonalizado,
                        onCheckedChange = { usarTituloPersonalizado = it }
                    )
                }

                if (usarTituloPersonalizado) {
                    OutlinedTextField(
                        value = titulo,
                        onValueChange = { titulo = it },
                        label = { Text("Título personalizado *") },
                        placeholder = { Text("Ej. Revisión puntual de bebedero...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Categoría
                Text("Tipo de Actividad:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tipos.forEach { tipo ->
                        FilterChip(
                            selected = tipoActividad == tipo,
                            onClick = { tipoActividad = tipo },
                            label = { Text(tipo, fontSize = 12.sp) }
                        )
                    }
                }

                // Prioridad
                Text("Prioridad:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    prioridades.forEach { p ->
                        FilterChip(
                            selected = prioridad == p,
                            onClick = { prioridad = p },
                            label = { Text(p, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        )
                    }
                }

                ScheduleChipSelector(
                    label = "HORA PROGRAMADA (CATÁLOGO)",
                    items = horarioItems,
                    selectedId = horaProgramada,
                    onSelect = { horaProgramada = it.id }
                )

                DatabaseSelectorField(
                    label = "GALPÓN / UBICACIÓN *",
                    selectedTitle = galponEtapa,
                    selectedSubtitle = galponItems.firstOrNull { it.id == galponEtapa }?.subtitle.orEmpty(),
                    selectedBadge = galponItems.firstOrNull { it.id == galponEtapa }?.badge.orEmpty(),
                    leadingIcon = Icons.Default.Apartment,
                    compact = true,
                    onClick = { showGalponSheet = true }
                )

                DatabaseSelectorField(
                    label = "LOTE / CERDA (OPCIONAL)",
                    selectedTitle = loteCerdaItems.firstOrNull { it.id == loteCodigo }?.title ?: "Sin lote específico",
                    selectedSubtitle = loteCerdaItems.firstOrNull { it.id == loteCodigo }?.subtitle.orEmpty(),
                    selectedBadge = loteCerdaItems.firstOrNull { it.id == loteCodigo }?.badge.orEmpty(),
                    leadingIcon = Icons.Default.Pets,
                    compact = true,
                    onClick = { showLoteSheet = true }
                )

                DatabaseSelectorField(
                    label = "RESPONSABLE ASIGNADO *",
                    selectedTitle = asignadoA,
                    selectedSubtitle = operarioItems.firstOrNull { it.id == asignadoA }?.subtitle.orEmpty(),
                    selectedBadge = operarioItems.firstOrNull { it.id == asignadoA }?.badge.orEmpty(),
                    leadingIcon = Icons.Default.Badge,
                    compact = true,
                    onClick = { showAsignadoSheet = true }
                )

                // Observaciones
                OutlinedTextField(
                    value = descripcion,
                    onValueChange = { descripcion = it },
                    label = { Text("Instrucciones adicionales (opcional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TactileButton(
                text = "Guardar Tarea",
                icon = Icons.Default.Save,
                enabled = titulo.isNotBlank(),
                minHeight = 46.dp,
                compact = true,
                onClick = {
                    val tarea = TareaEntity(
                        id = UUID.randomUUID().toString(),
                        titulo = titulo.trim(),
                        descripcion = descripcion.trim(),
                        prioridad = prioridad,
                        tipoActividad = tipoActividad,
                        galponEtapa = galponEtapa.trim(),
                        loteCodigo = loteCodigo.trim().ifBlank { null },
                        completada = false,
                        horaProgramada = horaProgramada,
                        asignadoA = asignadoA.trim().ifBlank { "Operario" },
                        sincronizado = false
                    )
                    onConfirm(tarea)
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = FieldDarkBorder)
            }
        },
        containerColor = FieldSurfaceContainerLowest
    )

    if (showPlantillaSheet) {
        SearchableCatalogBottomSheet(
            title = "Plantillas y Labores de Granja",
            subtitle = "Selecciona una actividad estándar",
            items = plantillasItems,
            selectedId = selectedPlantillaId,
            categories = plantillasItems.map { it.category }.distinct(),
            onSelect = { item ->
                selectedPlantillaId = item.id
                if (item.id.startsWith("H:")) {
                    val cod = item.id.removePrefix("H:")
                    val h = plantillasHorarios.firstOrNull { it.codigo == cod }
                    if (h != null) {
                        titulo = h.nombre
                        if (h.categoriaActividad.isNotBlank()) {
                            tipoActividad = tipos.firstOrNull {
                                it.equals(h.categoriaActividad, ignoreCase = true)
                            } ?: h.categoriaActividad
                        }
                        if (h.prioridadDefault in prioridades) prioridad = h.prioridadDefault
                        if (h.horaInicio.isNotBlank()) horaProgramada = h.horaInicio
                        if (h.descripcion.isNotBlank() && descripcion.isBlank()) descripcion = h.descripcion
                    }
                } else if (item.id.startsWith("L:")) {
                    val cod = item.id.removePrefix("L:")
                    val l = laboresSource.firstOrNull { it.codigo == cod }
                    if (l != null) {
                        titulo = l.nombre
                        tipoActividad = tipos.firstOrNull {
                            it.equals(l.categoria, ignoreCase = true) ||
                                (l.categoria.contains("Bio", true) && it == "Bioseguridad") ||
                                (l.categoria.contains("Manten", true) && it == "Mantenimiento") ||
                                (l.categoria.contains("Nutri", true) && it == "Nutrición") ||
                                (l.categoria.contains("Sanid", true) && it == "Sanidad") ||
                                (l.categoria.contains("Reprod", true) && it == "Reproducción")
                        } ?: tipoActividad
                        if (l.galponSugerido.isNotBlank()) {
                            galponEtapa = galponItems.firstOrNull {
                                it.id.contains(l.galponSugerido, ignoreCase = true)
                            }?.id ?: l.galponSugerido
                        }
                    }
                }
                showPlantillaSheet = false
            },
            onDismiss = { showPlantillaSheet = false }
        )
    }

    if (showGalponSheet) {
        SearchableCatalogBottomSheet(
            title = "Galpón / Ubicación",
            subtitle = "Naves activas en Neon / SQLite",
            items = galponItems,
            selectedId = galponEtapa,
            onSelect = { item ->
                galponEtapa = item.id
                showGalponSheet = false
            },
            onDismiss = { showGalponSheet = false }
        )
    }

    if (showLoteSheet) {
        SearchableCatalogBottomSheet(
            title = "Lote o Cerda Destino",
            subtitle = "Opcional según alcance de la tarea",
            items = loteCerdaItems,
            selectedId = loteCodigo,
            categories = loteCerdaItems.map { it.category }.distinct(),
            onSelect = { item ->
                loteCodigo = item.id
                showLoteSheet = false
            },
            onDismiss = { showLoteSheet = false }
        )
    }

    if (showAsignadoSheet) {
        SearchableCatalogBottomSheet(
            title = "Asignar Responsable",
            subtitle = "Operarios y técnicos registrados",
            items = operarioItems,
            selectedId = asignadoA,
            categories = operarioItems.map { it.category }.distinct(),
            onSelect = { item ->
                asignadoA = item.id
                showAsignadoSheet = false
            },
            onDismiss = { showAsignadoSheet = false }
        )
    }
}
