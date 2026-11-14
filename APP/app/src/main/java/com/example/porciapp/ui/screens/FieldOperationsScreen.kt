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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
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
import com.example.porciapp.data.local.entities.OperacionGastoEntity
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
fun FieldOperationsScreen(
    activeProfile: UserProfile,
    onBackClick: () -> Unit,
    onSaveRecord: (OperacionGastoEntity) -> Unit,
    registrosGuardados: List<OperacionGastoEntity> = emptyList()
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()
    val puedeVerCostos = activeProfile.rol.puedeVerCostos
    val tipoCambio = 36.80

    // 0: Labor de Campo / Turno (todos los roles), 1: Gasto Menor (solo Admin)
    var selectedSectionTab by remember(puedeVerCostos) { mutableIntStateOf(0) }

    // Campos de Labor de Campo
    val laboresDisponibles = remember {
        listOf(
            "Limpieza y desinfección de galpón",
            "Regulación de tolvas y comederos",
            "Pesaje y loteo de cerdos",
            "Mantenimiento de bebederos y fosas",
            "Descarga y estiba de alimento",
            "Bioseguridad y pediluvios",
            "Vigilancia y ronda de nave"
        )
    }
    var laborSeleccionada by remember { mutableStateOf(laboresDisponibles.first()) }
    var detalleLabor by remember { mutableStateOf("") }
    var galponLoteLabor by remember(catalogState.lotes) {
        mutableStateOf(catalogState.lotes.firstOrNull()?.let { "${it.codigo} · ${it.galpon}" } ?: "")
    }
    val duracionesJornal = remember {
        listOf(
            0.25 to "2h (0.25 j)",
            0.50 to "4h (0.5 j)",
            1.00 to "8h (1.0 j)",
            1.50 to "12h (1.5 j)"
        )
    }
    var jornalSeleccionado by remember { mutableDoubleStateOf(0.50) }
    var montoCordobasLabor by remember { mutableStateOf("") }
    var notasLabor by remember { mutableStateOf("") }

    // Campos de Gasto Menor (solo Administrador)
    val categoriasGasto = remember {
        listOf("Sanitario", "Combustible", "Mantenimiento", "Alimentación", "Bioseguridad", "Otros")
    }
    var categoriaGasto by remember { mutableStateOf("Sanitario") }
    var conceptoGasto by remember { mutableStateOf("") }
    var galponLoteGasto by remember(catalogState.lotes) {
        mutableStateOf(catalogState.lotes.firstOrNull()?.let { "${it.codigo} · ${it.galpon}" } ?: "")
    }
    var montoCordobasGasto by remember { mutableStateOf("") }
    var numeroComprobante by remember { mutableStateOf("") }
    var notasGasto by remember { mutableStateOf("") }

    val galponesFallback = remember {
        listOf("Maternidad", "Gestación", "Precebo", "Engorde", "Bodega / Silos", "Granja General")
    }
    val sdfDisplay = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (puedeVerCostos) "Labores & Gastos de Campo" else "Labores de Campo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurface
                        )
                        Text(
                            text = if (puedeVerCostos)
                                "Turnos operativos, jornales y caja menor (C$ / USD)"
                            else
                                "Registro operativo del turno • ${activeProfile.nombre}",
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldOnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = FieldOnSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FieldSurfaceContainerLowest),
                actions = {
                    if (puedeVerCostos) {
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(FieldPrimary.copy(alpha = 0.1f))
                                .border(BorderStroke(1.dp, FieldPrimary.copy(alpha = 0.4f)), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "1 USD = 36.80 C$",
                                style = MaterialTheme.typography.labelSmall,
                                color = FieldPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            )
        },
        containerColor = FieldBackground
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (puedeVerCostos) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(FieldSurfaceContainerHigh)
                            .border(BorderStroke(1.dp, FieldOutlineVariant), RoundedCornerShape(10.dp))
                            .padding(4.dp)
                    ) {
                        listOf(
                            0 to "Labor / Jornal",
                            1 to "Gasto Menor"
                        ).forEach { (idx, label) ->
                            val selected = selectedSectionTab == idx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) FieldPrimary else Color.Transparent)
                                    .selectable(selected = selected, role = Role.Tab) { selectedSectionTab = idx }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color.White else FieldOnSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (selectedSectionTab == 0 || !puedeVerCostos) {
                item {
                    TactileCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Engineering, contentDescription = null, tint = FieldPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Registrar Labor Realizada",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder
                                )
                            }

                            Text(
                                text = "1. Tipo de labor en nave",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldOnSurfaceVariant
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                laboresDisponibles.forEach { labor ->
                                    FilterChip(
                                        selected = laborSeleccionada == labor,
                                        onClick = { laborSeleccionada = labor },
                                        label = { Text(labor, maxLines = 1) }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = detalleLabor,
                                onValueChange = { detalleLabor = it },
                                label = { Text("Detalle adicional (opcional)") },
                                placeholder = { Text("Ej. Lavado a presión de 6 parideras...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = "2. Lote o galpón donde se ejecutó",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldOnSurfaceVariant
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (catalogState.lotes.isNotEmpty()) {
                                    catalogState.lotes.forEach { lote ->
                                        val tag = "${lote.codigo} · ${lote.galpon.ifBlank { lote.etapa }}"
                                        FilterChip(
                                            selected = galponLoteLabor == tag,
                                            onClick = { galponLoteLabor = tag },
                                            label = { Text(tag, maxLines = 1) }
                                        )
                                    }
                                } else {
                                    galponesFallback.forEach { g ->
                                        FilterChip(
                                            selected = galponLoteLabor == g,
                                            onClick = { galponLoteLabor = g },
                                            label = { Text(g, maxLines = 1) }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = galponLoteLabor,
                                onValueChange = { galponLoteLabor = it },
                                label = { Text("Galpón / Lote / Zona") },
                                placeholder = { Text("Ej. LOT-ENG-01 o Maternidad") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = "3. Duración del trabajo",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldOnSurfaceVariant
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                duracionesJornal.forEach { (valor, etiqueta) ->
                                    FilterChip(
                                        selected = jornalSeleccionado == valor,
                                        onClick = { jornalSeleccionado = valor },
                                        label = { Text(etiqueta) }
                                    )
                                }
                            }

                            if (puedeVerCostos) {
                                val montoNum = montoCordobasLabor.toDecimalOrNull() ?: 0.0
                                OutlinedTextField(
                                    value = montoCordobasLabor,
                                    onValueChange = { montoCordobasLabor = it },
                                    label = { Text("Tarifa / Jornal en C$ (vacío = tarifa estándar)") },
                                    placeholder = { Text("320.00") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    supportingText = {
                                        Text(
                                            if (montoNum > 0.0)
                                                "Equivalente: $${String.format(Locale.US, "%.2f", montoNum / tipoCambio)} USD"
                                            else
                                                "Al sincronizar la Web imputa tarifa estándar (C$ 320 / jornal)"
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            OutlinedTextField(
                                value = notasLabor,
                                onValueChange = { notasLabor = it },
                                label = { Text("Observaciones de campo") },
                                placeholder = { Text("Novedades en corrales, equipo, etc.") },
                                minLines = 2,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )

                            TactileButton(
                                text = "Guardar Labor en Turno",
                                icon = Icons.Default.CheckCircle,
                                onClick = {
                                    val montoC = if (puedeVerCostos) (montoCordobasLabor.toDecimalOrNull() ?: 0.0) else 0.0
                                    val conceptoFinal = if (detalleLabor.isNotBlank()) {
                                        "$laborSeleccionada — ${detalleLabor.trim()}"
                                    } else {
                                        laborSeleccionada
                                    }
                                    val entity = OperacionGastoEntity(
                                        tipoRegistro = "LABOR_CAMPO",
                                        concepto = conceptoFinal,
                                        categoria = "Mano de Obra",
                                        responsableNombre = activeProfile.nombreCompleto,
                                        galponLote = galponLoteLabor.ifBlank { "General Granja" },
                                        montoCordobas = montoC,
                                        montoDolares = if (montoC > 0.0) montoC / tipoCambio else 0.0,
                                        horasOJornal = jornalSeleccionado,
                                        fechaDisplay = sdfDisplay.format(Date()),
                                        observaciones = notasLabor.trim(),
                                        sincronizado = false
                                    )
                                    onSaveRecord(entity)
                                    detalleLabor = ""
                                    montoCordobasLabor = ""
                                    notasLabor = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            } else {
                // Tab 1: Gasto Menor (exclusivo Administrador)
                item {
                    TactileCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, tint = FieldSecondary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Registrar Gasto Operativo Menor",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                categoriasGasto.forEach { cat ->
                                    FilterChip(
                                        selected = categoriaGasto == cat,
                                        onClick = { categoriaGasto = cat },
                                        label = { Text(cat) }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = conceptoGasto,
                                onValueChange = { conceptoGasto = it },
                                label = { Text("Concepto del gasto *") },
                                placeholder = { Text("Ej. Repuesto de chupete, combustible generador...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (catalogState.lotes.isNotEmpty()) {
                                    catalogState.lotes.forEach { lote ->
                                        val tag = "${lote.codigo} · ${lote.galpon.ifBlank { lote.etapa }}"
                                        FilterChip(
                                            selected = galponLoteGasto == tag,
                                            onClick = { galponLoteGasto = tag },
                                            label = { Text(tag, maxLines = 1) }
                                        )
                                    }
                                } else {
                                    galponesFallback.forEach { g ->
                                        FilterChip(
                                            selected = galponLoteGasto == g,
                                            onClick = { galponLoteGasto = g },
                                            label = { Text(g, maxLines = 1) }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = galponLoteGasto,
                                onValueChange = { galponLoteGasto = it },
                                label = { Text("Lote o galpón imputado") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            val gastoNum = montoCordobasGasto.toDecimalOrNull() ?: 0.0
                            OutlinedTextField(
                                value = montoCordobasGasto,
                                onValueChange = { montoCordobasGasto = it },
                                label = { Text("Monto en Córdobas (C$) *") },
                                placeholder = { Text("0.00") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                supportingText = {
                                    Text("Equivalente: $${String.format(Locale.US, "%.2f", gastoNum / tipoCambio)} USD (TC 36.80)")
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = numeroComprobante,
                                onValueChange = { numeroComprobante = it },
                                label = { Text("N° Recibo / Factura (opcional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = notasGasto,
                                onValueChange = { notasGasto = it },
                                label = { Text("Observaciones") },
                                minLines = 2,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )

                            val valido = conceptoGasto.isNotBlank() && (montoCordobasGasto.toDecimalOrNull() ?: 0.0) > 0.0
                            TactileButton(
                                text = "Registrar Gasto Bimonetario",
                                icon = Icons.Default.Payments,
                                enabled = valido,
                                onClick = {
                                    val montoC = montoCordobasGasto.toDecimalOrNull() ?: 0.0
                                    val entity = OperacionGastoEntity(
                                        tipoRegistro = "GASTO_OPERATIVO",
                                        concepto = conceptoGasto.trim(),
                                        categoria = categoriaGasto,
                                        responsableNombre = activeProfile.nombreCompleto,
                                        galponLote = galponLoteGasto.ifBlank { "General Granja" },
                                        montoCordobas = montoC,
                                        montoDolares = montoC / tipoCambio,
                                        horasOJornal = 0.0,
                                        fechaDisplay = sdfDisplay.format(Date()),
                                        numeroComprobante = numeroComprobante.trim(),
                                        observaciones = notasGasto.trim(),
                                        sincronizado = false
                                    )
                                    onSaveRecord(entity)
                                    conceptoGasto = ""
                                    montoCordobasGasto = ""
                                    numeroComprobante = ""
                                    notasGasto = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Historial local reciente (oculta montos si !puedeVerCostos)
            item {
                Text(
                    text = if (puedeVerCostos) "MOVIMIENTOS RECIENTES EN DISPOSITIVO" else "MIS LABORES REGISTRADAS HOY",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = FieldDarkBorder
                )
            }

            val registrosVisibles = registrosGuardados.filter {
                puedeVerCostos || it.tipoRegistro == "LABOR_CAMPO" || it.tipoRegistro == "MANO_OBRA"
            }

            if (registrosVisibles.isEmpty()) {
                item {
                    TactileCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, tint = FieldOutline)
                            Text(
                                text = "Aún no hay labores registradas en esta sesión.",
                                style = MaterialTheme.typography.bodySmall,
                                color = FieldOnSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(registrosVisibles) { reg ->
                    val esLabor = reg.tipoRegistro == "LABOR_CAMPO" || reg.tipoRegistro == "MANO_OBRA"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = FieldSurfaceContainerLowest),
                        border = BorderStroke(1.dp, FieldOutlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
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
                                        .background(if (esLabor) FieldPrimary.copy(alpha = 0.15f) else FieldSecondary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (esLabor) Icons.Default.Engineering else Icons.Default.Receipt,
                                        contentDescription = null,
                                        tint = if (esLabor) FieldPrimary else FieldSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = reg.concepto,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${reg.responsableNombre} • ${reg.galponLote}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = FieldOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = reg.fechaDisplay,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = FieldOutline
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                if (puedeVerCostos && reg.montoCordobas > 0.0) {
                                    Text(
                                        text = "C$ ${String.format(Locale.US, "%.2f", reg.montoCordobas)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Black,
                                        color = FieldOnSurface
                                    )
                                    Text(
                                        text = "$${String.format(Locale.US, "%.2f", reg.montoDolares)} USD",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldPrimary
                                    )
                                } else if (esLabor) {
                                    Text(
                                        text = "${String.format(Locale.US, "%.2f", reg.horasOJornal)} j",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Black,
                                        color = FieldPrimary
                                    )
                                    Text(
                                        text = "Jornada",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = FieldOnSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (reg.sincronizado) FieldGreenPositive.copy(alpha = 0.15f)
                                            else FieldWarning.copy(alpha = 0.15f)
                                        )
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (reg.sincronizado) "Sincronizado" else "Outbox",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = if (reg.sincronizado) FieldGreenPositive else FieldWarning,
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
