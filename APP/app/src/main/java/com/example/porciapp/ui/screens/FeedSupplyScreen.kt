package com.example.porciapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.example.porciapp.data.local.MockData
import com.example.porciapp.data.model.BarnLot
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.repository.CatalogStore
import com.example.porciapp.ui.components.*
import com.example.porciapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun FeedSupplyScreen(
    onBackClick: () -> Unit,
    activeProfile: UserProfile = UserProfile.defaultFor(UserRole.OPERARIO),
    onSaveRecord: (com.example.porciapp.data.model.FeedSupplyRecord) -> Unit = {}
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()
    val availableLots = remember(catalogState) {
        if (catalogState.lotes.isNotEmpty()) {
            catalogState.lotes.map { l ->
                BarnLot(
                    id = l.codigo,
                    galponNombre = l.galpon.ifBlank { "Galpón General" },
                    loteNombre = l.codigo,
                    etapa = l.etapa.ifBlank { "Engorde" },
                    animalesCount = if (l.animales > 0) l.animales else 25
                )
            }
        } else {
            MockData.sampleLots
        }
    }

    val availableFeeds = remember(catalogState.alimentos) {
        if (catalogState.alimentos.isNotEmpty()) {
            catalogState.alimentos.map { it.nombre to "${it.stock.roundToInt()} ${it.unidad.ifBlank { "Kg" }} disp." }
        } else {
            listOf(
                "Concentrado Precebo Fase 1" to "Etapa Destete / Precebo",
                "Concentrado Crecimiento / Levante" to "Etapa Levante",
                "Concentrado Engorde / Acabado" to "Etapa Engorde",
                "Concentrado Gestación / Lactancia" to "Etapa Reproductoras"
            )
        }
    }

    var selectedLotIndex by remember { mutableIntStateOf(0) }
    var showLotDialog by remember { mutableStateOf(false) }

    val currentLot = availableLots.getOrElse(selectedLotIndex) { availableLots.first() }
    var selectedFeedName by remember(availableFeeds) {
        mutableStateOf(availableFeeds.firstOrNull()?.first ?: MockData.defaultFeed.nombre)
    }

    // Auto-sugerir alimento según la etapa del lote seleccionado si coincide
    LaunchedEffect(currentLot.etapa, availableFeeds) {
        val etapaLower = currentLot.etapa.lowercase()
        val match = availableFeeds.firstOrNull { (nombre, _) ->
            val n = nombre.lowercase()
            (etapaLower.contains("precebo") && (n.contains("precebo") || n.contains("iniciador"))) ||
            (etapaLower.contains("crecimiento") && (n.contains("crecimiento") || n.contains("levante"))) ||
            (etapaLower.contains("engorde") && (n.contains("engorde") || n.contains("acabado"))) ||
            ((etapaLower.contains("maternidad") || etapaLower.contains("gestac")) && (n.contains("lactancia") || n.contains("gestac")))
        }
        if (match != null) {
            selectedFeedName = match.first
        }
    }

    var selectedSilo by remember { mutableStateOf("Silo Principal A") }

    // Unidades de medida estandarizadas
    val unidadesDisponibles = listOf("Kg", "Tn", "Bultos (40 kg)", "Sacos (25 kg)")
    var selectedUnit by remember { mutableStateOf("Kg") }
    var inputAmount by remember { mutableDoubleStateOf(250.0) }

    // Cálculo equivalente en Kg según la unidad de medida
    val totalEquivalentKg: Double = when (selectedUnit) {
        "Tn" -> inputAmount * 1000.0
        "Bultos (40 kg)" -> inputAmount * 40.0
        "Sacos (25 kg)" -> inputAmount * 25.0
        else -> inputAmount
    }

    // Costo estimado de ración ($0.48 USD / Kg)
    val costoKiloUsd = 0.48
    val costoTotalEstimado = totalEquivalentKg * costoKiloUsd

    // Observation tags
    var comederoLimpio by remember { mutableStateOf(false) }
    var pocoApetito by remember { mutableStateOf(false) }
    var ajusteTolva by remember { mutableStateOf(false) }
    var additionalNotes by remember { mutableStateOf("") }

    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val pigCount = currentLot.animalesCount
    val perPigRation = if (pigCount > 0) totalEquivalentKg / pigCount else 0.0

    Scaffold(
        topBar = {
            Column {
                FieldTopBar(
                    title = "Suministro de Alimento",
                    subtitle = "REGISTRO DE RACIÓN & SILO",
                    onBackClick = onBackClick,
                    statusText = "Modo Offline",
                    statusIcon = Icons.Default.CloudOff
                )
                // Sub-banner de rol y operador
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FieldSurfaceContainerHigh)
                        .border(BorderStroke(1.dp, FieldDarkBorder))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Engineering,
                            contentDescription = null,
                            tint = FieldPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Operario: ${activeProfile.nombre}",
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldOnSurface,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(FieldPrimaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = activeProfile.rol.titulo.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldOnPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = FieldSurfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(2.dp, FieldDarkBorder))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TactileButton(
                        onClick = {
                            if (!isSaving && !isSaved) {
                                isSaving = true
                                coroutineScope.launch {
                                    val record = com.example.porciapp.data.model.FeedSupplyRecord(
                                        galponLote = currentLot.galponNombre,
                                        loteNumero = currentLot.loteNombre,
                                        animalesCount = currentLot.animalesCount,
                                        tipoAlimento = selectedFeedName,
                                        siloOrigen = selectedSilo,
                                        cantidadKg = totalEquivalentKg,
                                        fechaHora = "Hoy, Registro en Galpón",
                                        operario = "${activeProfile.nombre} (${activeProfile.cargo})",
                                        notasConsumo = listOfNotNull(
                                            if (comederoLimpio) "Comedero limpio" else null,
                                            if (pocoApetito) "Poco apetito" else null,
                                            if (ajusteTolva) "Ajuste tolva" else null,
                                            "Unidad: $inputAmount $selectedUnit"
                                        ),
                                        observacionAdicional = additionalNotes
                                    )
                                    delay(400)
                                    onSaveRecord(record)
                                    isSaving = false
                                    isSaved = true
                                    delay(500)
                                    onBackClick()
                                }
                            }
                        },
                        backgroundColor = if (isSaved) FieldSecondaryFixed else FieldOliveCTA,
                        contentColor = FieldDarkBorder,
                        minHeight = 52.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = FieldPrimary,
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Guardando ración...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder
                                )
                            } else if (isSaved) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = FieldPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "¡Ración Guardada (${String.format("%.1f", totalEquivalentKg)} Kg)!",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = null,
                                    tint = FieldDarkBorder,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Guardar Ración (${String.format("%.1f", totalEquivalentKg)} Kg)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder
                                )
                            }
                        }
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
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Advertencia de rol si no es Operario/Admin
            if (activeProfile.rol == UserRole.VETERINARIO) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(FieldSecondaryContainer.copy(alpha = 0.5f))
                            .border(BorderStroke(1.5.dp, FieldPrimary), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = FieldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Supervisión Nutricional por Áreas: Monitoreo zootécnico de consumo de ración en Kg / Tn y calibración de tolvas en naves.",
                                style = MaterialTheme.typography.bodySmall,
                                color = FieldOnSurface
                            )
                        }
                    }
                }
            }

            // 2. Galpón / Lote Destino
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(FieldPrimary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "GALPÓN / LOTE DESTINO",
                            style = MaterialTheme.typography.labelMedium,
                            color = FieldOnSurface
                        )
                        Text(text = " *", color = FieldError, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FieldSurfaceContainerLowest)
                            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .clickable { showLotDialog = true }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(FieldSecondaryContainer)
                                        .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Store,
                                        contentDescription = null,
                                        tint = FieldPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = currentLot.galponNombre,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = FieldOnSurface,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${currentLot.loteNombre} • ${currentLot.animalesCount} animales en nave",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FieldOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = FieldOnSurface,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }

            // 3. Tipo de Alimento (Seleccionable desde Catálogo o por Etapa)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(FieldPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "TIPO DE ALIMENTO",
                                style = MaterialTheme.typography.labelMedium,
                                color = FieldOnSurface
                            )
                            Text(text = " *", color = FieldError, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(FieldPrimary)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ETAPA: ${currentLot.etapa}",
                                color = FieldSecondaryContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableFeeds.forEach { (feedName, feedMeta) ->
                            val isSelected = selectedFeedName == feedName
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) FieldPrimaryContainer else FieldSurfaceContainerLowest,
                                border = BorderStroke(if (isSelected) 2.dp else 1.dp, FieldDarkBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFeedName = feedName }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) FieldSecondaryFixed else FieldPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = feedName,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) FieldOnPrimary else FieldOnSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = feedMeta,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) FieldSecondaryFixed else FieldSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Silo / Tolva de Origen
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(FieldPrimary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SILO / TOLVA DE ORIGEN",
                            style = MaterialTheme.typography.labelMedium,
                            color = FieldOnSurface
                        )
                        Text(text = " *", color = FieldError, fontWeight = FontWeight.Bold)
                    }

                    val silosList = remember(catalogState.silos) {
                        if (catalogState.silos.isNotEmpty()) {
                            catalogState.silos.map { s ->
                                Triple(s.nombre, "${s.pctOcupacion.toInt()}% • ${s.diasAutonomia}d autonomía", s.nombre)
                            }
                        } else {
                            listOf(
                                Triple("Silo Principal A", "82% • 18d autonomía", "Silo Principal A"),
                                Triple("Tolva B", "45% • 6d autonomía", "Tolva B"),
                                Triple("Bolsas / Bodega", "Manual", "Bolsas / Bodega")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        silosList.forEach { (name, sub, id) ->
                            val isSelected = selectedSilo == id
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) FieldPrimary else FieldSurfaceContainerHigh)
                                    .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                    .clickable { selectedSilo = id },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
                                    Text(
                                        text = name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) FieldOnPrimary else FieldOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = sub,
                                        fontSize = 10.sp,
                                        color = if (isSelected) FieldOnPrimary.copy(alpha = 0.85f) else FieldSecondary,
                                        fontWeight = if (isSelected) FontWeight.Normal else FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. UNIDAD DE MEDIDA Y CANTIDAD DISPENSADA
            item {
                TactileCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Scale,
                                contentDescription = null,
                                tint = FieldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "UNIDAD DE MEDIDA & CANTIDAD",
                                style = MaterialTheme.typography.labelMedium,
                                color = FieldPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(FieldSecondaryFixed)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Conversión Automática",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = FieldPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Selector de unidad de medida
                    Text(
                        text = "Seleccione Unidad:",
                        style = MaterialTheme.typography.labelSmall,
                        color = FieldOnSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        unidadesDisponibles.forEach { unit ->
                            val isSelected = selectedUnit == unit
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) FieldPrimaryContainer else FieldSurfaceContainerLowest)
                                    .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedUnit = unit
                                        // Resetear cantidad razonable al cambiar de unidad
                                        inputAmount = when (unit) {
                                            "Tn" -> 0.5
                                            "Bultos (40 kg)" -> 6.0
                                            "Sacos (25 kg)" -> 10.0
                                            else -> 250.0
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = unit,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) FieldOnPrimary else FieldOnSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Stepper táctil adaptado a la unidad
                    val stepIncrement = when (selectedUnit) {
                        "Tn" -> 0.1
                        "Bultos (40 kg)" -> 1.0
                        "Sacos (25 kg)" -> 1.0
                        else -> 10.0
                    }

                    GloveFriendlyStepper(
                        valueText = if (selectedUnit == "Tn") String.format("%.1f", inputAmount) else inputAmount.roundToInt().toString(),
                        labelText = "Total $selectedUnit",
                        unitText = selectedUnit,
                        onDecrement = {
                            if (inputAmount > stepIncrement) {
                                inputAmount = Math.max(stepIncrement, inputAmount - stepIncrement)
                            }
                        },
                        onIncrement = {
                            inputAmount += stepIncrement
                        },
                        decrementEnabled = inputAmount > stepIncrement
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Atajos rápidos según unidad
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when (selectedUnit) {
                            "Tn" -> {
                                QuickPill(text = "+0.1 Tn", onClick = { inputAmount += 0.1 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "+0.5 Tn", onClick = { inputAmount += 0.5 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "+1.0 Tn", onClick = { inputAmount += 1.0 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "Cero", onClick = { inputAmount = 0.0 }, isReset = true, modifier = Modifier.weight(1f))
                            }
                            "Bultos (40 kg)" -> {
                                QuickPill(text = "+1", onClick = { inputAmount += 1.0 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "+5", onClick = { inputAmount += 5.0 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "+10", onClick = { inputAmount += 10.0 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "Cero", onClick = { inputAmount = 0.0 }, isReset = true, modifier = Modifier.weight(1f))
                            }
                            "Sacos (25 kg)" -> {
                                QuickPill(text = "+1", onClick = { inputAmount += 1.0 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "+4", onClick = { inputAmount += 4.0 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "+10", onClick = { inputAmount += 10.0 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "Cero", onClick = { inputAmount = 0.0 }, isReset = true, modifier = Modifier.weight(1f))
                            }
                            else -> {
                                QuickPill(text = "+10", onClick = { inputAmount += 10.0 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "+50", onClick = { inputAmount += 50.0 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "+100", onClick = { inputAmount += 100.0 }, modifier = Modifier.weight(1f))
                                QuickPill(text = "Cero", onClick = { inputAmount = 0.0 }, isReset = true, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = FieldOutlineVariant, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Badge de Conversión Oficial en Kg e Imputación de Costo
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(FieldSurfaceContainerLowest)
                            .border(BorderStroke(1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "EQUIVALENTE PARA BBDD:",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldOnSurfaceVariant
                                )
                                AnimatedDecimalCounterText(
                                    targetValue = totalEquivalentKg,
                                    suffix = " Kilogramos",
                                    decimals = 1,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = FieldPrimary
                                    )
                                )
                            }
                            if (activeProfile.rol.puedeVerCostos) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "GASTO ESTIMADO LOTE:",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldSecondary
                                    )
                                    AnimatedDecimalCounterText(
                                        targetValue = costoTotalEstimado,
                                        prefix = "$ ",
                                        suffix = " USD",
                                        decimals = 2,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = FieldOnSurface
                                        )
                                    )
                                }
                            } else {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "RACIÓN POR ANIMAL:",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldSecondary
                                    )
                                    AnimatedDecimalCounterText(
                                        targetValue = perPigRation,
                                        suffix = " Kg / cerdo",
                                        decimals = 2,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = FieldOnSurface
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 6. Notas de Consumo en Galpón
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(FieldSecondary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "NOTAS DE CONSUMO EN GALPÓN",
                            style = MaterialTheme.typography.labelMedium,
                            color = FieldOnSurface
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Check 1: Comedero limpio
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (comederoLimpio) FieldSecondaryContainer else FieldSurfaceContainerHigh)
                                .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                .clickable { comederoLimpio = !comederoLimpio }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = comederoLimpio,
                                    onCheckedChange = { comederoLimpio = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Text(text = "Comedero limpio", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FieldOnSurface)
                            }
                        }

                        // Check 2: Poco apetito
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (pocoApetito) FieldSecondaryContainer else FieldSurfaceContainerHigh)
                                .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                .clickable { pocoApetito = !pocoApetito }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = pocoApetito,
                                    onCheckedChange = { pocoApetito = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Text(text = "Poco apetito", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FieldOnSurface)
                            }
                        }

                        // Check 3: Ajuste tolva
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (ajusteTolva) FieldSecondaryContainer else FieldSurfaceContainerHigh)
                                .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                .clickable { ajusteTolva = !ajusteTolva }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = ajusteTolva,
                                    onCheckedChange = { ajusteTolva = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Text(text = "Ajuste tolva", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FieldOnSurface)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = additionalNotes,
                        onValueChange = { additionalNotes = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(FieldSurfaceContainerLowest, RoundedCornerShape(8.dp)),
                        placeholder = {
                            Text(
                                text = "Observación adicional o número de comedero dañado...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = FieldOutline
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FieldPrimary,
                            unfocusedBorderColor = FieldDarkBorder,
                            focusedContainerColor = FieldSurfaceContainerLowest,
                            unfocusedContainerColor = FieldSurfaceContainerLowest
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }
    }

    // Modal de Selección de Galpón / Lote
    if (showLotDialog) {
        AlertDialog(
            onDismissRequest = { showLotDialog = false },
            confirmButton = {
                TactileButton(
                    onClick = { showLotDialog = false },
                    text = "Aceptar Selección",
                    backgroundColor = FieldPrimary,
                    contentColor = FieldOnPrimary,
                    minHeight = 48.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SensorDoor,
                        contentDescription = null,
                        tint = FieldPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Seleccionar Galpón / Lote",
                        style = MaterialTheme.typography.titleLarge,
                        color = FieldPrimary
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableLots.forEachIndexed { index, lot ->
                        val isSelected = selectedLotIndex == index
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) FieldSecondaryContainer else FieldSurfaceContainerHigh)
                                .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedLotIndex = index
                                }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = lot.galponNombre,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface
                                    )
                                    Text(
                                        text = "${lot.loteNombre} • ${lot.animalesCount} animales",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) FieldOnSecondaryContainer else FieldOnSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = FieldPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            containerColor = FieldSurfaceContainerLowest,
            shape = RoundedCornerShape(12.dp)
        )
    }
}
