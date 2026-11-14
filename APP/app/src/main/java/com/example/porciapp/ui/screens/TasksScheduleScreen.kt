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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.data.local.entities.TareaEntity
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.repository.CatalogStore
import com.example.porciapp.ui.components.TactileButton
import com.example.porciapp.ui.components.TactileCard
import com.example.porciapp.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScheduleScreen(
    activeProfile: UserProfile,
    tareas: List<TareaEntity>,
    onBackClick: () -> Unit,
    onToggleTask: (id: String, completed: Boolean, observaciones: String) -> Unit,
    onSaveNewTask: (TareaEntity) -> Unit
) {
    var selectedCategory by remember { mutableStateOf("Todas") }
    var showCompletedOnly by remember { mutableStateOf(false) }
    var showNewTaskDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val categories = listOf("Todas", "Sanidad", "Reproducción", "Nutrición", "Bioseguridad", "Mantenimiento")

    val filteredTareas = remember(tareas, selectedCategory, showCompletedOnly, searchQuery) {
        tareas.filter { tarea ->
            val matchCategory = selectedCategory == "Todas" || tarea.tipoActividad.equals(selectedCategory, ignoreCase = true)
            val matchStatus = if (showCompletedOnly) tarea.completada else !tarea.completada
            val matchSearch = searchQuery.isBlank() ||
                    tarea.titulo.contains(searchQuery, ignoreCase = true) ||
                    tarea.galponEtapa.contains(searchQuery, ignoreCase = true) ||
                    (tarea.loteCodigo?.contains(searchQuery, ignoreCase = true) == true) ||
                    tarea.asignadoA.contains(searchQuery, ignoreCase = true)
            matchCategory && matchStatus && matchSearch
        }.sortedBy { it.horaProgramada }
    }

    val totalPendientes = remember(tareas) { tareas.count { !it.completada } }
    val totalCompletadas = remember(tareas) { tareas.count { it.completada } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Agenda & Horario de Actividades",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldDarkBorder
                        )
                        Text(
                            text = "Jornada Técnica • ${activeProfile.rol.titulo}: ${activeProfile.nombre}",
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldOnSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Regresar", tint = FieldDarkBorder)
                    }
                },
                actions = {
                    IconButton(onClick = { showNewTaskDialog = true }) {
                        Icon(Icons.Default.AddCircle, contentDescription = "Programar Tarea", tint = FieldPrimary, modifier = Modifier.size(28.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FieldSurfaceContainerHigh)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewTaskDialog = true },
                containerColor = FieldPrimary,
                contentColor = FieldOnPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Programar Tarea", fontWeight = FontWeight.Bold) }
            )
        },
        containerColor = FieldBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Selector de Estado: Pendientes vs Completadas
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FieldSurfaceContainerHigh)
                    .border(BorderStroke(1.dp, FieldDarkBorder))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = !showCompletedOnly,
                    onClick = { showCompletedOnly = false },
                    label = { Text("Pendientes ($totalPendientes)", fontWeight = FontWeight.Bold) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FieldPrimary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    )
                )

                FilterChip(
                    selected = showCompletedOnly,
                    onClick = { showCompletedOnly = true },
                    label = { Text("Completadas ($totalCompletadas)", fontWeight = FontWeight.Bold) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FieldSecondary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    )
                )
            }

            // 2. Buscador y Filtros por Categoría
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

                // Chips de categoría
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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
                            color = FieldDarkBorder
                        )
                        Text(
                            text = "Presiona el botón '+' para programar una nueva actividad.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FieldOnSurfaceVariant
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

    if (showNewTaskDialog) {
        NewTaskDialog(
            defaultAsignado = activeProfile.nombre,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            color = FieldPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
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
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

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
                            fontSize = 10.sp
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            color = FieldOnSurfaceVariant
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskDialog(
    defaultAsignado: String,
    onDismiss: () -> Unit,
    onConfirm: (TareaEntity) -> Unit
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()
    var titulo by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }
    var tipoActividad by remember { mutableStateOf("Sanidad") }
    var prioridad by remember { mutableStateOf("Media") }
    var horaProgramada by remember { mutableStateOf("08:00") }
    var galponEtapa by remember(catalogState.lotes) {
        mutableStateOf(catalogState.lotes.firstOrNull()?.galpon ?: "Nave Maternidad A")
    }
    var loteCodigo by remember(catalogState.lotes) {
        mutableStateOf(catalogState.lotes.firstOrNull()?.codigo ?: "")
    }
    var asignadoA by remember { mutableStateOf(defaultAsignado) }

    val tipos = listOf("Sanidad", "Reproducción", "Nutrición", "Bioseguridad", "Mantenimiento")
    val prioridades = listOf("Baja", "Media", "Alta", "Urgente")
    val galpones = listOf("Nave Maternidad A", "Nave Gestación", "Precebo Lote 14", "Engorde Galpón 3", "Área General")
    val horasSugeridas = listOf("06:30", "07:30", "08:00", "10:30", "14:00", "16:30")

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
                OutlinedTextField(
                    value = titulo,
                    onValueChange = { titulo = it },
                    label = { Text("Título de la Actividad *") },
                    placeholder = { Text("Ej. Aplicar hierro a lechones 3d") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    prioridades.forEach { p ->
                        FilterChip(
                            selected = prioridad == p,
                            onClick = { prioridad = p },
                            label = { Text(p, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Horas sugeridas
                Text("Hora Programada:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    horasSugeridas.forEach { h ->
                        FilterChip(
                            selected = horaProgramada == h,
                            onClick = { horaProgramada = h },
                            label = { Text(h, fontSize = 11.sp) }
                        )
                    }
                }

                // Selección rápida de Lote / Galpón desde Catálogo Real
                Text("Lote / Galpón:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (catalogState.lotes.isNotEmpty()) {
                        catalogState.lotes.forEach { lote ->
                            FilterChip(
                                selected = loteCodigo == lote.codigo,
                                onClick = {
                                    loteCodigo = lote.codigo
                                    galponEtapa = lote.galpon.ifBlank { lote.etapa }
                                },
                                label = { Text("${lote.codigo} · ${lote.galpon.ifBlank { lote.etapa }}", fontSize = 11.sp) }
                            )
                        }
                    } else {
                        galpones.forEach { g ->
                            FilterChip(
                                selected = galponEtapa == g,
                                onClick = { galponEtapa = g },
                                label = { Text(g, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                // Galpón / Nave
                OutlinedTextField(
                    value = galponEtapa,
                    onValueChange = { galponEtapa = it },
                    label = { Text("Galpón / Ubicación") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Lote / Animal
                OutlinedTextField(
                    value = loteCodigo,
                    onValueChange = { loteCodigo = it },
                    label = { Text("Lote / Cerda (Opcional)") },
                    placeholder = { Text("Ej. LOT-ENG-01 o caravana de cerda") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Asignado
                OutlinedTextField(
                    value = asignadoA,
                    onValueChange = { asignadoA = it },
                    label = { Text("Asignado a (Operario / Técnico)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Observaciones
                OutlinedTextField(
                    value = descripcion,
                    onValueChange = { descripcion = it },
                    label = { Text("Instrucciones detalladas") },
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
}
