package com.example.porciapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.data.model.ReproductiveEventRecord
import com.example.porciapp.data.model.ReproductiveEventType
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.repository.CatalogStore
import com.example.porciapp.ui.components.*
import com.example.porciapp.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ReproductiveEventScreen(
    onBackClick: () -> Unit,
    activeProfile: UserProfile = UserProfile.defaultFor(UserRole.VETERINARIO),
    onSaveRecord: (ReproductiveEventRecord) -> Unit = {}
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()

    var eventType by remember { mutableStateOf(ReproductiveEventType.SERVICIO_IA) }
    var filtroCerda by remember { mutableStateOf("") }
    var crotal by remember(catalogState.cerdas) {
        mutableStateOf(catalogState.cerdas.firstOrNull()?.caravana ?: "")
    }
    var jaulaActual by remember(catalogState.cerdas) {
        mutableStateOf(catalogState.cerdas.firstOrNull()?.jaula ?: "")
    }

    // Campos SERVICIO_IA
    var verracoCodigo by remember(catalogState.verracos) {
        mutableStateOf(catalogState.verracos.firstOrNull()?.codigo ?: "")
    }
    var tecnicaIa by remember { mutableStateOf("IA Tradicional") }
    var turnoIa by remember { mutableStateOf("AM") }

    // Campos PARTO y DESTETE
    var nacidosVivos by remember { mutableIntStateOf(12) }
    var nacidosMuertos by remember { mutableIntStateOf(0) }
    var momificados by remember { mutableIntStateOf(0) }
    var lechonesDestetados by remember { mutableIntStateOf(11) }
    var pesoCamadaText by remember { mutableStateOf("16.5") }
    var asistenciaSeleccionada by remember { mutableStateOf("Natural") }
    var oxitocinaAplicada by remember { mutableStateOf(false) }
    var madreEnObservacion by remember { mutableStateOf(false) }
    var condicionCorporal by remember { mutableIntStateOf(3) }

    var showConfirmModal by remember { mutableStateOf(false) }

    val cerdasFiltradas = remember(catalogState.cerdas, filtroCerda, eventType) {
        val base = catalogState.cerdas
        val ordenadas = when (eventType) {
            ReproductiveEventType.SERVICIO_IA -> base.sortedBy {
                if (it.estado.contains("vacia", true) || it.estado.contains("celo", true)) 0 else 1
            }
            ReproductiveEventType.PARTO -> base.sortedBy {
                if (it.estado.contains("gestante", true) || it.estado.contains("servida", true)) 0 else 1
            }
            ReproductiveEventType.DESTETE -> base.sortedBy {
                if (it.estado.contains("lactante", true) || it.estado.contains("maternidad", true)) 0 else 1
            }
        }
        if (filtroCerda.isBlank()) ordenadas
        else ordenadas.filter {
            it.caravana.contains(filtroCerda, ignoreCase = true) ||
                it.galpon.contains(filtroCerda, ignoreCase = true) ||
                it.jaula.contains(filtroCerda, ignoreCase = true)
        }
    }

    val fechaPartoProyectada = remember {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 114)
        SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag("es-NI")).format(cal.time)
    }
    val fechaRevisionCelo = remember {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 21)
        SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag("es-NI")).format(cal.time)
    }

    val pesoCamadaKg = pesoCamadaText.toDecimalOrNull() ?: 0.0
    val totalNacidos = nacidosVivos + nacidosMuertos + momificados
    val promedioLechon = when (eventType) {
        ReproductiveEventType.PARTO -> if (nacidosVivos > 0) pesoCamadaKg / nacidosVivos else 0.0
        ReproductiveEventType.DESTETE -> if (lechonesDestetados > 0) pesoCamadaKg / lechonesDestetados else 0.0
        ReproductiveEventType.SERVICIO_IA -> 0.0
    }
    val mortalidadPartoPct = if (totalNacidos > 0) ((nacidosMuertos + momificados) * 100.0) / totalNacidos else 0.0

    val formularioValido = when (eventType) {
        ReproductiveEventType.SERVICIO_IA -> crotal.isNotBlank()
        ReproductiveEventType.PARTO -> crotal.isNotBlank() && totalNacidos > 0
        ReproductiveEventType.DESTETE -> crotal.isNotBlank() && lechonesDestetados > 0
    }

    Scaffold(
        topBar = {
            Column {
                FieldTopBar(
                    title = "Eventos Reproductivos",
                    subtitle = "MONTA IA • PARTO • DESTETE (CICLO 114d)",
                    onBackClick = onBackClick,
                    statusText = "Outbox OK",
                    statusIcon = Icons.Default.CloudDone
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FieldSurfaceContainerHigh)
                        .border(BorderStroke(1.dp, FieldOutlineVariant))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Responsable: ${activeProfile.nombre} (${activeProfile.rol.titulo})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = FieldOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (jaulaActual.isNotBlank()) {
                        Text(
                            text = "Jaula $jaulaActual",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = FieldPrimary
                        )
                    }
                }
            }
        },
        containerColor = FieldBackground
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Selector 3 modos
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(FieldSurfaceContainerHigh)
                        .border(BorderStroke(1.dp, FieldOutlineVariant), RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ReproductiveEventType.entries.forEach { tipo ->
                        val selected = eventType == tipo
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) FieldPrimary else Color.Transparent)
                                .selectable(selected = selected, role = Role.Tab) {
                                    eventType = tipo
                                    pesoCamadaText = if (tipo == ReproductiveEventType.DESTETE) "68.0" else "16.5"
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tipo.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) Color.White else FieldOnSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Selección de Cerda desde Catálogo Real
            item {
                TactileCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "1. Identificación de Cerda",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = FieldDarkBorder
                        )

                        if (catalogState.cerdas.isNotEmpty()) {
                            OutlinedTextField(
                                value = filtroCerda,
                                onValueChange = { filtroCerda = it },
                                label = { Text("Filtrar catálogo (${catalogState.cerdas.size} cerdas)") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                cerdasFiltradas.take(25).forEach { c ->
                                    val selected = crotal.equals(c.caravana, ignoreCase = true)
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            crotal = c.caravana
                                            jaulaActual = c.jaula.ifBlank { c.galpon }
                                        },
                                        label = {
                                            Text("${c.caravana} · ${c.estado.replace('_', ' ')}", maxLines = 1)
                                        }
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Sin catálogo sincronizado todavía. Escribe la caravana manualmente o presiona Sincronizar en Inicio.",
                                style = MaterialTheme.typography.labelSmall,
                                color = FieldWarning
                            )
                        }

                        OutlinedTextField(
                            value = crotal,
                            onValueChange = { crotal = it.uppercase() },
                            label = { Text("Caravana / Crotal de Cerda *") },
                            placeholder = { Text("Ej. CRD-014") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Formulario condicional según modo
            when (eventType) {
                ReproductiveEventType.SERVICIO_IA -> {
                    item {
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "2. Datos de Servicio / Inseminación Artificial",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder
                                )

                                if (catalogState.verracos.isNotEmpty()) {
                                    Text(
                                        text = "Verraco o dosis seminal (catálogo)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurfaceVariant
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        catalogState.verracos.forEach { v ->
                                            FilterChip(
                                                selected = verracoCodigo.equals(v.codigo, ignoreCase = true),
                                                onClick = { verracoCodigo = v.codigo },
                                                label = { Text("${v.codigo} · ${v.nombre}") }
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = verracoCodigo,
                                    onValueChange = { verracoCodigo = it.uppercase() },
                                    label = { Text("Código de Verraco / Lote Semen") },
                                    placeholder = { Text("Opcional si es monta directa") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    text = "Técnica aplicada",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldOnSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("IA Tradicional", "IA Post-Cervical", "Monta Natural").forEach { t ->
                                        FilterChip(
                                            selected = tecnicaIa == t,
                                            onClick = { tecnicaIa = t },
                                            label = { Text(t) }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Turno de dosis",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = FieldOnSurfaceVariant
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("AM", "PM").forEach { turno ->
                                                FilterChip(
                                                    selected = turnoIa == turno,
                                                    onClick = { turnoIa = turno },
                                                    label = { Text(turno) }
                                                )
                                            }
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Condición Corporal (1-5)",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = FieldOnSurfaceVariant
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            (1..5).forEach { cc ->
                                                FilterChip(
                                                    selected = condicionCorporal == cc,
                                                    onClick = { condicionCorporal = cc },
                                                    label = { Text("$cc") }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Cálculos zootécnicos en vivo
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(FieldPrimary.copy(alpha = 0.08f))
                                        .border(BorderStroke(1.dp, FieldPrimary.copy(alpha = 0.35f)), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "PROYECCIÓN ZOOTÉCNICA AUTOMÁTICA (114 DÍAS)",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = FieldPrimary
                                        )
                                        Text(
                                            text = "Fecha Probable de Parto: $fechaPartoProyectada",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = FieldOnSurface
                                        )
                                        Text(
                                            text = "Chequeo de retorno a celo (+21d): $fechaRevisionCelo",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = FieldOnSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                ReproductiveEventType.PARTO -> {
                    item {
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "2. Conteo y Pesaje de Camada al Parto",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder
                                )

                                CounterStepperRow("Nacidos Vivos", nacidosVivos, FieldGreenPositive) { nacidosVivos = it }
                                CounterStepperRow("Nacidos Muertos", nacidosMuertos, FieldWarning) { nacidosMuertos = it }
                                CounterStepperRow("Momificados", momificados, FieldError) { momificados = it }

                                OutlinedTextField(
                                    value = pesoCamadaText,
                                    onValueChange = { pesoCamadaText = it },
                                    label = { Text("Peso Total Camada (Kg)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    supportingText = {
                                        Text(
                                            "Total: $totalNacidos • Promedio/vivo: ${String.format(Locale.US, "%.2f", promedioLechon)} Kg • Mort. intraparto: ${String.format(Locale.US, "%.1f", mortalidadPartoPct)}%"
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    text = "Asistencia obstétrica y condición corporal",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldOnSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("Natural", "Asistido Leve", "Distocico").forEach { a ->
                                        FilterChip(
                                            selected = asistenciaSeleccionada == a,
                                            onClick = { asistenciaSeleccionada = a },
                                            label = { Text(a) }
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (1..5).forEach { cc ->
                                        FilterChip(
                                            selected = condicionCorporal == cc,
                                            onClick = { condicionCorporal = cc },
                                            label = { Text("CC $cc") }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Oxitocina aplicada post-parto", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = oxitocinaAplicada, onCheckedChange = { oxitocinaAplicada = it })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Dejar madre en observación clínica", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = madreEnObservacion, onCheckedChange = { madreEnObservacion = it })
                                }
                            }
                        }
                    }
                }

                ReproductiveEventType.DESTETE -> {
                    item {
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "2. Cierre de Lactancia y Destete",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder
                                )

                                CounterStepperRow("Lechones Destetados", lechonesDestetados, FieldPrimary) {
                                    lechonesDestetados = it
                                }

                                OutlinedTextField(
                                    value = pesoCamadaText,
                                    onValueChange = { pesoCamadaText = it },
                                    label = { Text("Peso Total de Camada al Destete (Kg)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    supportingText = {
                                        Text(
                                            "Peso promedio por lechón destetado: ${String.format(Locale.US, "%.2f", promedioLechon)} Kg"
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    text = "Condición Corporal de Madre al Destete (1-5)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldOnSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (1..5).forEach { cc ->
                                        FilterChip(
                                            selected = condicionCorporal == cc,
                                            onClick = { condicionCorporal = cc },
                                            label = { Text("CC $cc") }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Madre en observación / recuperación", style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = madreEnObservacion, onCheckedChange = { madreEnObservacion = it })
                                }
                            }
                        }
                    }
                }
            }

            item {
                TactileButton(
                    text = "Confirmar ${eventType.label}",
                    icon = Icons.Default.Save,
                    enabled = formularioValido,
                    onClick = { showConfirmModal = true },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showConfirmModal) {
        val fechaAhora = SimpleDateFormat("dd MMM yyyy - HH:mm", Locale.getDefault()).format(Date())
        AlertDialog(
            onDismissRequest = { showConfirmModal = false },
            title = { Text("Confirmar ${eventType.label}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cerda: $crotal", fontWeight = FontWeight.Bold)
                    when (eventType) {
                        ReproductiveEventType.SERVICIO_IA -> {
                            Text("Verraco: ${verracoCodigo.ifBlank { "Monta / Dosis estándar" }}")
                            Text("Técnica: $tecnicaIa • Turno: $turnoIa • CC $condicionCorporal")
                            Text("Parto estimado (+114d): $fechaPartoProyectada", color = FieldPrimary, fontWeight = FontWeight.Bold)
                        }
                        ReproductiveEventType.PARTO -> {
                            Text("Vivos: $nacidosVivos • Muertos: $nacidosMuertos • Momias: $momificados")
                            Text("Peso camada: ${String.format(Locale.US, "%.1f", pesoCamadaKg)} Kg (Prom: ${String.format(Locale.US, "%.2f", promedioLechon)} Kg)")
                            Text("Asistencia: $asistenciaSeleccionada • Oxitocina: ${if (oxitocinaAplicada) "Sí" else "No"}")
                        }
                        ReproductiveEventType.DESTETE -> {
                            Text("Destetados: $lechonesDestetados lechones")
                            Text("Peso lote: ${String.format(Locale.US, "%.1f", pesoCamadaKg)} Kg (Prom: ${String.format(Locale.US, "%.2f", promedioLechon)} Kg)")
                            Text("Condición corporal madre: CC $condicionCorporal")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmModal = false
                        onSaveRecord(
                            ReproductiveEventRecord(
                                crotalCerda = crotal.trim(),
                                tipoEvento = eventType,
                                fechaEvento = fechaAhora,
                                lechonesVivos = if (eventType == ReproductiveEventType.PARTO) nacidosVivos else 0,
                                lechonesMuertos = if (eventType == ReproductiveEventType.PARTO) nacidosMuertos else 0,
                                momificados = if (eventType == ReproductiveEventType.PARTO) momificados else 0,
                                lechonesDestetados = if (eventType == ReproductiveEventType.DESTETE) lechonesDestetados else 0,
                                pesoCamadaKg = if (eventType == ReproductiveEventType.SERVICIO_IA) 0.0 else pesoCamadaKg,
                                tipoAsistencia = if (eventType == ReproductiveEventType.PARTO) asistenciaSeleccionada else "Natural",
                                oxitocinaAplicada = eventType == ReproductiveEventType.PARTO && oxitocinaAplicada,
                                madreEnObservacion = madreEnObservacion,
                                verracoCodigo = if (eventType == ReproductiveEventType.SERVICIO_IA) verracoCodigo.trim() else "",
                                tecnicaIa = tecnicaIa,
                                turnoIa = turnoIa,
                                condicionCorporal = condicionCorporal.toDouble(),
                                sincronizado = false
                            )
                        )
                    }
                ) {
                    Text("Guardar en Outbox")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmModal = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun CounterStepperRow(
    label: String,
    value: Int,
    accent: Color,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FieldSurfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = FieldOnSurface
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalIconButton(
                onClick = { if (value > 0) onValueChange(value - 1) },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Disminuir", modifier = Modifier.size(18.dp))
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = accent,
                fontSize = 18.sp
            )
            FilledTonalIconButton(
                onClick = { onValueChange(value + 1) },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Aumentar", modifier = Modifier.size(18.dp))
            }
        }
    }
}
