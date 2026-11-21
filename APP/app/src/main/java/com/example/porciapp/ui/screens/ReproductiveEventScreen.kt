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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.data.local.entities.CatClinicoEntity
import com.example.porciapp.data.local.entities.CatHorarioEntity
import com.example.porciapp.data.local.entities.CatJaulaEntity
import com.example.porciapp.data.local.entities.CerdoEntity
import com.example.porciapp.data.model.ReproductiveEventRecord
import com.example.porciapp.data.model.ReproductiveEventType
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.model.WorkspaceUiPreferences
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
    clinicosCatalog: List<CatClinicoEntity> = emptyList(),
    horariosCatalog: List<CatHorarioEntity> = emptyList(),
    jaulasCatalog: List<CatJaulaEntity> = emptyList(),
    cerdosFromDb: List<CerdoEntity> = emptyList(),
    uiPrefs: WorkspaceUiPreferences = WorkspaceUiPreferences.defaultFor(activeProfile.rol),
    onSaveRecord: (ReproductiveEventRecord) -> Unit = {}
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()

    var eventType by remember { mutableStateOf(ReproductiveEventType.SERVICIO_IA) }
    var usarCrotalManual by remember { mutableStateOf(false) }

    val cerdasItems = remember(catalogState.cerdas, cerdosFromDb) {
        val seen = mutableSetOf<String>()
        val list = catalogState.cerdas.map { c ->
            seen.add(c.caravana.uppercase())
            CatalogChoiceItem(
                id = c.caravana,
                title = "Cerda ${c.caravana}",
                subtitle = "${c.estado.replace('_', ' ')} • ${c.galpon} ${c.jaula} • Parto #${c.paridad}".trim(),
                badge = c.estado.replace('_', ' '),
                category = c.galpon.ifBlank { "Cerdas" }
            )
        }
        val extraHembras = cerdosFromDb
            .filter { it.sexo.equals("Hembra", ignoreCase = true) && seen.add(it.codigoCrotal.uppercase()) }
            .map { p ->
                CatalogChoiceItem(
                    id = p.codigoCrotal,
                    title = "Hembra ${p.codigoCrotal}",
                    subtitle = "${p.raza} • ${p.jaula}".trim(),
                    badge = "LOCAL",
                    category = "Registro Individual"
                )
            }
        list + extraHembras
    }

    val verracosItems = remember(catalogState.verracos) {
        catalogState.verracos.map { v ->
            CatalogChoiceItem(
                id = v.codigo,
                title = "${v.codigo} • ${v.nombre}",
                subtitle = "Dosis seminal certificada en granja",
                badge = v.codigo
            )
        }
    }

    val tecnicasItems = remember(clinicosCatalog, catalogState.catalogosClinicos) {
        val fromRoom = clinicosCatalog.filter { it.tipoCatalogo == "TECNICA_IA" }.map {
            CatalogChoiceItem(id = it.nombre, title = it.nombre, subtitle = it.subcategoria, badge = it.codigo)
        }
        if (fromRoom.isNotEmpty()) fromRoom else catalogState.catalogosClinicos.filter { it.tipoCatalogo == "TECNICA_IA" }.map {
            CatalogChoiceItem(id = it.nombre, title = it.nombre, subtitle = it.subcategoria, badge = it.codigo)
        }.ifEmpty {
            listOf(
                CatalogChoiceItem("IA Tradicional", "IA Tradicional", "Cervical estándar"),
                CatalogChoiceItem("IA Post-Cervical", "IA Post-Cervical", "Profunda"),
                CatalogChoiceItem("Monta Natural", "Monta Natural", "Servicio directo")
            )
        }
    }

    val dosisIaItems = remember(horariosCatalog, catalogState.horarios) {
        val fromRoom = horariosCatalog.filter { it.tipoHorario == "DOSIS_IA" }.map {
            CatalogChoiceItem(
                id = it.nombre,
                title = it.nombre,
                subtitle = "${it.horaInicio} - ${it.horaFin}".trim(' ', '-')
            )
        }
        if (fromRoom.isNotEmpty()) fromRoom else catalogState.horarios.filter { it.tipoHorario == "DOSIS_IA" }.map {
            CatalogChoiceItem(id = it.nombre, title = it.nombre, subtitle = "${it.horaInicio} - ${it.horaFin}".trim(' ', '-'))
        }.ifEmpty {
            listOf(
                CatalogChoiceItem("AM", "Dosis Mañana (AM)", "06:30 - 09:00"),
                CatalogChoiceItem("PM", "Dosis Tarde (PM)", "16:00 - 18:00")
            )
        }
    }

    val jaulasMaternidadItems = remember(jaulasCatalog, catalogState.jaulas) {
        val all = if (jaulasCatalog.isNotEmpty()) {
            jaulasCatalog.map {
                Triple(it.nombre, "${it.galponNombre} • ${it.etapaZootecnica}", it.codigo to it.galponNombre)
            }
        } else {
            catalogState.jaulas.map {
                Triple(it.nombre, "${it.galponNombre} • ${it.etapaZootecnica}", it.codigo to it.galponNombre)
            }
        }
        val maternidad = all.filter {
            it.second.contains("Maternidad", ignoreCase = true) || it.first.contains("MAT", ignoreCase = true)
        }
        (maternidad.ifEmpty { all }).map { (nombre, sub, pair) ->
            CatalogChoiceItem(
                id = nombre,
                title = nombre,
                subtitle = sub,
                badge = pair.first,
                category = pair.second
            )
        }
    }

    var crotal by remember(cerdasItems) {
        mutableStateOf(cerdasItems.firstOrNull()?.id ?: "")
    }
    var jaulaActual by remember(catalogState.cerdas, jaulasMaternidadItems) {
        mutableStateOf(catalogState.cerdas.firstOrNull()?.jaula ?: jaulasMaternidadItems.firstOrNull()?.id.orEmpty())
    }
    var jaulaMaternidad by remember(jaulasMaternidadItems) {
        mutableStateOf(jaulasMaternidadItems.firstOrNull()?.id.orEmpty())
    }

    // Campos SERVICIO_IA
    var verracoCodigo by remember(verracosItems) {
        mutableStateOf(verracosItems.firstOrNull()?.id ?: "")
    }
    var tecnicaIa by remember(tecnicasItems) {
        mutableStateOf(tecnicasItems.firstOrNull()?.id ?: "IA Tradicional")
    }
    var turnoIa by remember(dosisIaItems, uiPrefs.turnoPredeterminado) {
        val pref = uiPrefs.turnoPredeterminado
        val match = if (pref.contains("TAR", true)) {
            dosisIaItems.firstOrNull { it.id.contains("PM", true) || it.id.contains("Tarde", true) }
        } else {
            dosisIaItems.firstOrNull()
        }
        mutableStateOf(match?.id ?: dosisIaItems.firstOrNull()?.id ?: "AM")
    }

    var showCerdaSheet by remember { mutableStateOf(false) }
    var showVerracoSheet by remember { mutableStateOf(false) }
    var showTecnicaSheet by remember { mutableStateOf(false) }
    var showJaulaMaternidadSheet by remember { mutableStateOf(false) }

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

    val puedeRegistrar = activeProfile.rol.puedeRegistrarPartos
    val formularioValido = when (eventType) {
        ReproductiveEventType.SERVICIO_IA -> crotal.isNotBlank()
        ReproductiveEventType.PARTO -> crotal.isNotBlank() && totalNacidos > 0
        ReproductiveEventType.DESTETE -> crotal.isNotBlank() && lechonesDestetados > 0
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (jaulaActual.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Jaula $jaulaActual",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = FieldPrimary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
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
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            if (!puedeRegistrar) {
                item {
                    RoleRestrictionWarningBanner(
                        userRole = activeProfile.rol,
                        titulo = "Módulo Exclusivo de Maternidad Clínica",
                        mensaje = "Solo el Veterinario o Administrador puede registrar Servicios IA, Partos o Destetes."
                    )
                }
            }

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
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tipo.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) Color.White else FieldOnSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 1. Selección de Cerda desde Catálogo Real
            item {
                val cerdaSel = cerdasItems.firstOrNull { it.id.equals(crotal, ignoreCase = true) }
                TactileCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "1. Identificación de Cerda (Base de Datos)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = FieldDarkBorder
                        )

                        DatabaseSelectorField(
                            label = "CERDA REPRODUCTORA / CARAVANA *",
                            selectedTitle = cerdaSel?.title ?: crotal,
                            selectedSubtitle = cerdaSel?.subtitle ?: if (jaulaActual.isNotBlank()) "Ubicación: $jaulaActual" else "",
                            selectedBadge = cerdaSel?.badge.orEmpty(),
                            placeholder = "Seleccionar cerda del inventario...",
                            leadingIcon = Icons.Default.Pets,
                            onClick = {
                                usarCrotalManual = false
                                showCerdaSheet = true
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(FieldSurfaceContainerHigh)
                                .clickable { usarCrotalManual = !usarCrotalManual }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Ingresar caravana nueva / manual",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = FieldOnSurface
                            )
                            Switch(
                                checked = usarCrotalManual,
                                onCheckedChange = { usarCrotalManual = it }
                            )
                        }

                        if (usarCrotalManual || cerdasItems.isEmpty()) {
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
            }

            // Formulario condicional según modo
            when (eventType) {
                ReproductiveEventType.SERVICIO_IA -> {
                    item {
                        val verracoSel = verracosItems.firstOrNull { it.id.equals(verracoCodigo, ignoreCase = true) }
                        val tecnicaSel = tecnicasItems.firstOrNull { it.id.equals(tecnicaIa, ignoreCase = true) }
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "2. Datos de Servicio / Inseminación Artificial",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder
                                )

                                DatabaseSelectorField(
                                    label = "VERRACO / DOSIS SEMINAL (CATÁLOGO BD)",
                                    selectedTitle = verracoSel?.title ?: verracoCodigo,
                                    selectedSubtitle = verracoSel?.subtitle.orEmpty(),
                                    selectedBadge = verracoSel?.badge.orEmpty(),
                                    placeholder = "Seleccionar verraco registrado...",
                                    leadingIcon = Icons.Default.Biotech,
                                    compact = true,
                                    enabled = verracosItems.isNotEmpty(),
                                    onClick = { showVerracoSheet = true }
                                )

                                DatabaseSelectorField(
                                    label = "TÉCNICA DE INSEMINACIÓN *",
                                    selectedTitle = tecnicaSel?.title ?: tecnicaIa,
                                    selectedSubtitle = tecnicaSel?.subtitle.orEmpty(),
                                    selectedBadge = tecnicaSel?.badge.orEmpty(),
                                    leadingIcon = Icons.Default.Science,
                                    compact = true,
                                    onClick = { showTecnicaSheet = true }
                                )

                                ScheduleChipSelector(
                                    label = "HORARIO / DOSIS IA *",
                                    items = dosisIaItems,
                                    selectedId = turnoIa,
                                    onSelect = { turnoIa = it.id }
                                )

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Condición Corporal (1-5)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    BodyConditionSelector(
                                        selectedCondition = condicionCorporal,
                                        onSelect = { condicionCorporal = it },
                                        prefix = ""
                                    )
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
                        val jaulaMatSel = jaulasMaternidadItems.firstOrNull { it.id.equals(jaulaMaternidad, ignoreCase = true) }
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "2. Conteo y Pesaje de Camada al Parto",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder
                                )

                                DatabaseSelectorField(
                                    label = "JAULA DE MATERNIDAD ASIGNADA *",
                                    selectedTitle = jaulaMatSel?.title ?: jaulaMaternidad,
                                    selectedSubtitle = jaulaMatSel?.subtitle.orEmpty(),
                                    selectedBadge = jaulaMatSel?.badge.orEmpty(),
                                    placeholder = "Seleccionar jaula paridera...",
                                    leadingIcon = Icons.Default.Fence,
                                    compact = true,
                                    enabled = jaulasMaternidadItems.isNotEmpty(),
                                    onClick = { showJaulaMaternidadSheet = true }
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

                                BodyConditionSelector(
                                    selectedCondition = condicionCorporal,
                                    onSelect = { condicionCorporal = it }
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Oxitocina aplicada post-parto",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    )
                                    Switch(checked = oxitocinaAplicada, onCheckedChange = { oxitocinaAplicada = it })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Dejar madre en observación clínica",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    )
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
                                BodyConditionSelector(
                                    selectedCondition = condicionCorporal,
                                    onSelect = { condicionCorporal = it }
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Madre en observación / recuperación",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    )
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
                    enabled = puedeRegistrar && formularioValido,
                    onClick = { showConfirmModal = true },
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
                            Text("Jaula Maternidad: ${jaulaMaternidad.ifBlank { jaulaActual }}")
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

    if (showCerdaSheet) {
        SearchableCatalogBottomSheet(
            title = "Seleccionar Cerda Reproductora",
            subtitle = "Plantel reproductor sincronizado",
            items = cerdasItems,
            selectedId = crotal,
            categories = cerdasItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            emptyMessage = "No hay cerdas sincronizadas.",
            onSelect = { item ->
                crotal = item.id
                val found = catalogState.cerdas.firstOrNull { it.caravana.equals(item.id, ignoreCase = true) }
                if (found != null) {
                    jaulaActual = found.jaula.ifBlank { found.galpon }
                }
                showCerdaSheet = false
            },
            onDismiss = { showCerdaSheet = false }
        )
    }

    if (showVerracoSheet) {
        SearchableCatalogBottomSheet(
            title = "Seleccionar Verraco / Dosis Seminal",
            subtitle = "Sementales y lotes de semen disponibles",
            items = verracosItems,
            selectedId = verracoCodigo,
            onSelect = { item ->
                verracoCodigo = item.id
                showVerracoSheet = false
            },
            onDismiss = { showVerracoSheet = false }
        )
    }

    if (showTecnicaSheet) {
        SearchableCatalogBottomSheet(
            title = "Técnica de Inseminación Artificial",
            subtitle = "Protocolo clínico de servicio",
            items = tecnicasItems,
            selectedId = tecnicaIa,
            onSelect = { item ->
                tecnicaIa = item.id
                showTecnicaSheet = false
            },
            onDismiss = { showTecnicaSheet = false }
        )
    }

    if (showJaulaMaternidadSheet) {
        SearchableCatalogBottomSheet(
            title = "Seleccionar Jaula de Maternidad",
            subtitle = "Jaulas parideras disponibles",
            items = jaulasMaternidadItems,
            selectedId = jaulaMaternidad,
            categories = jaulasMaternidadItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                jaulaMaternidad = item.id
                jaulaActual = item.id
                showJaulaMaternidadSheet = false
            },
            onDismiss = { showJaulaMaternidadSheet = false }
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
            color = FieldOnSurface,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
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
                fontSize = 18.sp,
                modifier = Modifier.widthIn(min = 28.dp),
                textAlign = TextAlign.Center,
                maxLines = 1
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

@Composable
private fun BodyConditionSelector(
    selectedCondition: Int,
    onSelect: (Int) -> Unit,
    prefix: String = "CC "
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        (1..5).forEach { cc ->
            val selected = selectedCondition == cc
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) FieldPrimary else FieldSurfaceContainerHigh)
                    .border(
                        BorderStroke(1.dp, if (selected) FieldPrimary else FieldOutlineVariant),
                        RoundedCornerShape(8.dp)
                    )
                    .selectable(selected = selected, role = Role.RadioButton) {
                        onSelect(cc)
                    }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$prefix$cc",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Color.White else FieldOnSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
