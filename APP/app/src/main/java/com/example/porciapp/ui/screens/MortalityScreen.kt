package com.example.porciapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.data.local.entities.BajaMortalidadEntity
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.repository.CatalogStore
import com.example.porciapp.ui.components.TactileButton
import com.example.porciapp.ui.components.TactileCard
import com.example.porciapp.ui.components.toDecimalOrNull
import com.example.porciapp.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MortalityScreen(
    activeProfile: UserProfile,
    historialBajas: List<BajaMortalidadEntity>,
    onBackClick: () -> Unit,
    onSaveBaja: (BajaMortalidadEntity) -> Unit
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()
    var crotal by remember { mutableStateOf("") }
    var loteCodigo by remember(catalogState.lotes) { mutableStateOf(catalogState.lotes.firstOrNull()?.codigo ?: "") }
    var galponLote by remember(catalogState.lotes) { mutableStateOf(catalogState.lotes.firstOrNull()?.galpon ?: "Nave Maternidad A") }
    var causaMuerte by remember { mutableStateOf("Aplastamiento perinatal") }
    var pesoKgText by remember { mutableStateOf("1.5") }
    var etapaZootecnica by remember { mutableStateOf("Maternidad") }
    var necropsiaRealizada by remember { mutableStateOf(false) }
    var hallazgosNecropsia by remember { mutableStateOf("") }
    var observaciones by remember { mutableStateOf("") }

    var activeTab by remember { mutableIntStateOf(0) } // 0: Formulario, 1: Historial

    val causasComunes = listOf(
        "Aplastamiento perinatal",
        "Diarrea colibacilar neonatal",
        "Complejo respiratorio porcino",
        "Desmedro / Circovirus (PCV2)",
        "Enfermedad de Glässer / Estreptococo",
        "Hernia / Trauma locomotor",
        "Torsión intestinal / Muerte súbita",
        "Otra causa clínica"
    )

    val etapas = listOf("Maternidad", "Precebo / Destete", "Levante / Crecimiento", "Engorde", "Gestación")
    val galpones = listOf("Nave Maternidad A", "Nave Gestación", "Precebo Lote 14", "Engorde Galpón 3", "Galpón 1")

    val sdfDisplay = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Registro de Baja & Mortalidad",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldDarkBorder
                        )
                        Text(
                            text = "Auditoría Sanitaria y Necropsia en Campo",
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FieldSurfaceContainerHigh)
            )
        },
        containerColor = FieldBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Selector de pestaña: Nuevo Registro vs Historial
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = FieldSurfaceContainerHigh,
                contentColor = FieldDarkBorder
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text("Registrar Baja", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text("Historial (${historialBajas.size})", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            if (activeTab == 0) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Advertencia de bioseguridad
                    item {
                        TactileCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = FieldOrangeWarning.copy(alpha = 0.15f),
                            borderColor = FieldOrangeWarning
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = FieldDarkBorder)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "PROTOCOLO SANITARIO DE MORTALIDAD",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldDarkBorder
                                    )
                                    Text(
                                        text = "Retirar el cadáver inmediatamente a la fosa de compostaje o incineración para evitar proliferación de patógenos.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FieldDarkBorder,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    // Identificación
                    item {
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "1. IDENTIFICACIÓN DEL ANIMAL O LOTE",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = crotal,
                                onValueChange = { crotal = it },
                                label = { Text("Crotal / Caravana del Cerdo *") },
                                placeholder = { Text("Ej. CR-2026-9041 o selecciona abajo") },
                                leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null, tint = FieldPrimary) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Atajos táctiles para cerdos del catálogo o categorías frecuentes
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                catalogState.cerdas.take(10).forEach { cerda ->
                                    FilterChip(
                                        selected = crotal.equals(cerda.caravana, ignoreCase = true),
                                        onClick = {
                                            crotal = cerda.caravana
                                            if (cerda.galpon.isNotBlank()) galponLote = cerda.galpon
                                        },
                                        label = { Text(cerda.caravana, fontSize = 11.sp) }
                                    )
                                }
                                listOf("Lechón Neonato (Sin Crotal)", "Lechón Maternidad", "Cerdo Precebo", "Cerdo Engorde").forEach { sugerencia ->
                                    FilterChip(
                                        selected = crotal == sugerencia,
                                        onClick = { crotal = sugerencia },
                                        label = { Text(sugerencia, fontSize = 11.sp) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = loteCodigo,
                                    onValueChange = { loteCodigo = it },
                                    label = { Text("Código de Lote") },
                                    placeholder = { Text("LOT-2026-04") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )

                                OutlinedTextField(
                                    value = pesoKgText,
                                    onValueChange = { pesoKgText = com.example.porciapp.ui.components.filtrarDecimal(it) },
                                    label = { Text("Peso (Kg)") },
                                    singleLine = true,
                                    keyboardOptions = com.example.porciapp.ui.components.DecimalKeyboard,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Atajos táctiles de peso estimado
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("1.2", "1.5", "6.5", "15.0", "45.0", "95.0").forEach { p ->
                                    FilterChip(
                                        selected = pesoKgText == p,
                                        onClick = { pesoKgText = p },
                                        label = { Text("$p Kg", fontSize = 11.sp) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Galpón / Lote desde Catálogo Real
                            Text("Lote / Galpón:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (catalogState.lotes.isNotEmpty()) {
                                    catalogState.lotes.forEach { l ->
                                        FilterChip(
                                            selected = loteCodigo == l.codigo,
                                            onClick = {
                                                loteCodigo = l.codigo
                                                galponLote = l.galpon.ifBlank { l.etapa }
                                                val et = l.etapa.lowercase()
                                                etapaZootecnica = when {
                                                    et.contains("maternidad") -> "Maternidad"
                                                    et.contains("precebo") || et.contains("destete") -> "Precebo / Destete"
                                                    et.contains("crecimiento") || et.contains("levante") -> "Levante / Crecimiento"
                                                    et.contains("gestac") -> "Gestación"
                                                    else -> "Engorde"
                                                }
                                            },
                                            label = { Text("${l.codigo} (${l.galpon.ifBlank { l.etapa }})", fontSize = 11.sp) }
                                        )
                                    }
                                } else {
                                    galpones.forEach { g ->
                                        FilterChip(
                                            selected = galponLote == g,
                                            onClick = { galponLote = g },
                                            label = { Text(g, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Etapa Zootécnica
                    item {
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "2. ETAPA ZOOTÉCNICA",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                etapas.forEach { etapa ->
                                    FilterChip(
                                        selected = etapaZootecnica == etapa,
                                        onClick = { etapaZootecnica = etapa },
                                        label = { Text(etapa, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }

                    // Causa de Muerte
                    item {
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "3. CAUSA CLÍNICA DE MUERTE",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                causasComunes.forEach { causa ->
                                    val isSelected = causaMuerte == causa
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) FieldError.copy(alpha = 0.12f) else FieldSurfaceContainerHigh,
                                        border = BorderStroke(1.dp, if (isSelected) FieldError else FieldOutlineVariant),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(selected = isSelected, role = Role.RadioButton) { causaMuerte = causa }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (isSelected) FieldError else FieldOutline,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = causa,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) FieldError else FieldDarkBorder
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Necropsia Clínica
                    item {
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "4. NECROPSIA DE CAMPO",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldPrimary
                                    )
                                    Text(
                                        text = "¿Se realizó apertura y exploración anatómica?",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FieldOnSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = necropsiaRealizada,
                                    onCheckedChange = { necropsiaRealizada = it }
                                )
                            }

                            if (necropsiaRealizada) {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = hallazgosNecropsia,
                                    onValueChange = { hallazgosNecropsia = it },
                                    label = { Text("Hallazgos Patológicos / Lesiones") },
                                    placeholder = { Text("Ej. Hepatización pulmonar apical, hemorragias petequiales en riñón...") },
                                    minLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // Observaciones y Guardar
                    item {
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = observaciones,
                                onValueChange = { observaciones = it },
                                label = { Text("Observaciones Sanitarias Adicionales") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            TactileButton(
                                text = "Guardar Baja en Outbox",
                                icon = Icons.Default.Save,
                                enabled = crotal.isNotBlank(),
                                backgroundColor = FieldError,
                                contentColor = Color.White,
                                onClick = {
                                    val now = System.currentTimeMillis()
                                    val entidad = BajaMortalidadEntity(
                                        id = UUID.randomUUID().toString(),
                                        crotal = crotal.trim(),
                                        loteCodigo = loteCodigo.trim(),
                                        galponLote = galponLote,
                                        causaMuerte = causaMuerte,
                                        pesoKg = pesoKgText.toDecimalOrNull() ?: 0.0,
                                        etapaZootecnica = etapaZootecnica,
                                        necropsiaRealizada = necropsiaRealizada,
                                        hallazgosNecropsia = if (necropsiaRealizada) hallazgosNecropsia.trim() else "",
                                        fechaBaja = now,
                                        fechaDisplay = sdfDisplay.format(Date(now)),
                                        registradoPor = activeProfile.nombre,
                                        observaciones = observaciones.trim(),
                                        sincronizado = false
                                    )
                                    onSaveBaja(entidad)
                                }
                            )
                        }
                    }
                }
            } else {
                // Historial de bajas
                if (historialBajas.isEmpty()) {
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
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = FieldSecondary, modifier = Modifier.size(50.dp))
                            Text("No hay registros de mortalidad en el dispositivo", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        contentPadding = PaddingValues(top = 10.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(historialBajas, key = { it.id }) { baja ->
                            TactileCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Crotal: ${baja.crotal}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldDarkBorder
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(FieldError.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = baja.causaMuerte,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = FieldError,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "${baja.galponLote} • Etapa: ${baja.etapaZootecnica} • Peso: ${baja.pesoKg} Kg",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FieldOnSurfaceVariant
                                )

                                if (baja.necropsiaRealizada) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Necropsia: ${baja.hallazgosNecropsia.ifBlank { "Sin lesiones específicas" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = FieldPrimary
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = baja.fechaDisplay,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = FieldOutline
                                    )
                                    Text(
                                        text = if (baja.sincronizado) "Sincronizado" else "Pendiente de Sync",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (baja.sincronizado) FieldSecondary else FieldOrangeWarning,
                                        fontWeight = FontWeight.Bold
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
