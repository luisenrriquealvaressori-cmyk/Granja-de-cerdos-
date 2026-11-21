package com.example.porciapp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.example.porciapp.data.local.entities.CatGalponEntity
import com.example.porciapp.data.local.entities.CatHorarioEntity
import com.example.porciapp.data.local.entities.CatInsumoEntity
import com.example.porciapp.data.model.BarnLot
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.model.WorkspaceUiPreferences
import com.example.porciapp.data.repository.CatalogStore
import com.example.porciapp.ui.components.*
import com.example.porciapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class FeedOptionItem(
    val sku: String,
    val nombre: String,
    val meta: String,
    val siloCodigo: String,
    val categoria: String,
    val costoUsd: Double
)

@Composable
fun FeedSupplyScreen(
    onBackClick: () -> Unit,
    activeProfile: UserProfile = UserProfile.defaultFor(UserRole.OPERARIO),
    galponesCatalog: List<CatGalponEntity> = emptyList(),
    insumosCatalog: List<CatInsumoEntity> = emptyList(),
    horariosCatalog: List<CatHorarioEntity> = emptyList(),
    uiPrefs: WorkspaceUiPreferences = WorkspaceUiPreferences.defaultFor(activeProfile.rol),
    onSaveRecord: (com.example.porciapp.data.model.FeedSupplyRecord) -> Unit = {}
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()

    val galponesList = remember(galponesCatalog, catalogState.galpones, catalogState.lotes) {
        val base = when {
            galponesCatalog.isNotEmpty() -> galponesCatalog.map {
                CatalogChoiceItem(
                    id = it.nombre,
                    title = it.nombre,
                    subtitle = "${it.tipoSistema} • Cap. ${it.capacidad}",
                    badge = it.codigo,
                    category = it.codigo
                )
            }
            catalogState.galpones.isNotEmpty() -> catalogState.galpones.map {
                CatalogChoiceItem(
                    id = it.nombre,
                    title = it.nombre,
                    subtitle = "${it.tipoSistema} • Cap. ${it.capacidad}",
                    badge = it.codigo,
                    category = it.codigo
                )
            }
            else -> catalogState.lotes.mapNotNull { l ->
                l.galpon.takeIf { it.isNotBlank() }?.let { g ->
                    CatalogChoiceItem(id = g, title = g, subtitle = "Galpón activo en catálogo")
                }
            }.distinctBy { it.id }
        }
        listOf(
            CatalogChoiceItem(
                id = "ALL",
                title = "Todos los galpones",
                subtitle = "Mostrar todos los lotes activos de la granja"
            )
        ) + base
    }

    var selectedGalponName by remember(uiPrefs.galponPredeterminado, galponesList) {
        val pref = uiPrefs.galponPredeterminado.trim()
        val matched = if (pref.isNotBlank()) {
            galponesList.firstOrNull {
                it.id != "ALL" && (it.id.equals(pref, ignoreCase = true) || it.badge.equals(pref, ignoreCase = true))
            }?.id
        } else null
        mutableStateOf(matched ?: "ALL")
    }

    val allCatalogLots = remember(catalogState.lotes) {
        catalogState.lotes.map { l ->
            BarnLot(
                id = l.codigo,
                galponNombre = l.galpon.ifBlank { "Galpón General" },
                loteNombre = l.codigo,
                etapa = l.etapa.ifBlank { "Engorde" },
                animalesCount = if (l.animales > 0) l.animales else 0
            )
        }
    }

    val availableLots = remember(allCatalogLots, selectedGalponName) {
        if (selectedGalponName == "ALL") {
            allCatalogLots
        } else {
            val filtered = allCatalogLots.filter { it.galponNombre.equals(selectedGalponName, ignoreCase = true) }
            filtered.ifEmpty { allCatalogLots }
        }
    }

    var showGalponSheet by remember { mutableStateOf(false) }
    var showLotSheet by remember { mutableStateOf(false) }
    var showFeedSheet by remember { mutableStateOf(false) }

    var selectedLotCode by remember(availableLots) {
        mutableStateOf(availableLots.firstOrNull()?.loteNombre.orEmpty())
    }
    val currentLot = availableLots.firstOrNull { it.loteNombre == selectedLotCode } ?: availableLots.firstOrNull()

    val availableFeeds = remember(insumosCatalog, catalogState.insumosDetalle, catalogState.alimentos) {
        val fromRoom = insumosCatalog.filter { it.categoriaCodigo in listOf("ALIM", "PRE") }
        when {
            fromRoom.isNotEmpty() -> fromRoom.map {
                FeedOptionItem(
                    sku = it.sku,
                    nombre = it.nombre,
                    meta = "${it.stockActual.roundToInt()} ${it.unidadMedida.ifBlank { "Kg" }} disp.",
                    siloCodigo = it.siloCodigo,
                    categoria = it.categoriaCodigo,
                    costoUsd = it.costoUnitarioUsd
                )
            }
            catalogState.insumosDetalle.any { it.categoriaCodigo in listOf("ALIM", "PRE") } -> {
                catalogState.insumosDetalle.filter { it.categoriaCodigo in listOf("ALIM", "PRE") }.map {
                    FeedOptionItem(
                        sku = it.sku,
                        nombre = it.nombre,
                        meta = "${it.stockActual.roundToInt()} ${it.unidadMedida.ifBlank { "Kg" }} disp.",
                        siloCodigo = it.siloCodigo,
                        categoria = it.categoriaCodigo,
                        costoUsd = it.costoUnitarioUsd
                    )
                }
            }
            else -> catalogState.alimentos.map {
                FeedOptionItem(
                    sku = it.nombre,
                    nombre = it.nombre,
                    meta = "${it.stock.roundToInt()} ${it.unidad.ifBlank { "Kg" }} disp.",
                    siloCodigo = "",
                    categoria = "ALIM",
                    costoUsd = 0.48
                )
            }
        }
    }

    var selectedFeedName by remember(availableFeeds) {
        mutableStateOf(availableFeeds.firstOrNull()?.nombre.orEmpty())
    }

    // Auto-sugerir alimento según la etapa del lote seleccionado si coincide
    LaunchedEffect(currentLot?.etapa, availableFeeds) {
        val etapaLower = currentLot?.etapa?.lowercase().orEmpty()
        if (etapaLower.isBlank()) return@LaunchedEffect
        val match = availableFeeds.firstOrNull { item ->
            val n = item.nombre.lowercase()
            (etapaLower.contains("precebo") && (n.contains("precebo") || n.contains("iniciador"))) ||
            (etapaLower.contains("crecimiento") && (n.contains("crecimiento") || n.contains("levante"))) ||
            (etapaLower.contains("engorde") && (n.contains("engorde") || n.contains("acabado"))) ||
            ((etapaLower.contains("maternidad") || etapaLower.contains("gestac")) && (n.contains("lactancia") || n.contains("gestac")))
        }
        if (match != null) {
            selectedFeedName = match.nombre
        }
    }

    val silosList = remember(catalogState.silos) {
        catalogState.silos.map { s ->
            Triple(s.codigo, s.nombre, "${s.pctOcupacion.toInt()}% • ${s.diasAutonomia}d autonomía")
        } + listOf(
            Triple("", "Bolsas / Bodega Manual", "Despacho directo de sacos")
        )
    }

    var selectedSiloCodigo by remember(silosList) {
        mutableStateOf(silosList.firstOrNull()?.first.orEmpty())
    }
    var selectedSiloName by remember(silosList) {
        mutableStateOf(silosList.firstOrNull()?.second ?: "Bolsas / Bodega Manual")
    }

    LaunchedEffect(selectedFeedName, availableFeeds, catalogState.silos) {
        val chosenFeed = availableFeeds.firstOrNull { it.nombre == selectedFeedName }
        val targetSiloCode = chosenFeed?.siloCodigo.orEmpty()
        val matchedSilo = when {
            targetSiloCode.isNotBlank() -> catalogState.silos.firstOrNull {
                it.codigo.equals(targetSiloCode, ignoreCase = true) ||
                    it.insumo.equals(selectedFeedName, ignoreCase = true)
            }
            selectedFeedName.isNotBlank() -> catalogState.silos.firstOrNull {
                it.insumo.equals(selectedFeedName, ignoreCase = true)
            }
            else -> null
        }
        if (matchedSilo != null) {
            selectedSiloCodigo = matchedSilo.codigo
            selectedSiloName = matchedSilo.nombre
        }
    }

    val tomaItems = remember(horariosCatalog, catalogState.horarios) {
        val list = if (horariosCatalog.any { it.tipoHorario == "TOMA_ALIMENTO" }) {
            horariosCatalog.filter { it.tipoHorario == "TOMA_ALIMENTO" }.map {
                CatalogChoiceItem(
                    id = it.codigo,
                    title = it.nombre,
                    subtitle = "${it.horaInicio} - ${it.horaFin}".trim(' ', '-')
                )
            }
        } else {
            catalogState.horarios.filter { it.tipoHorario == "TOMA_ALIMENTO" }.map {
                CatalogChoiceItem(
                    id = it.codigo,
                    title = it.nombre,
                    subtitle = "${it.horaInicio} - ${it.horaFin}".trim(' ', '-')
                )
            }
        }
        list.ifEmpty {
            listOf(
                CatalogChoiceItem(id = "TOMA-AM", title = "1ra Toma Mañana", subtitle = "06:30 - 08:30"),
                CatalogChoiceItem(id = "TOMA-PM", title = "2da Toma Tarde", subtitle = "14:30 - 16:30")
            )
        }
    }

    var selectedHorarioCodigo by remember(tomaItems, uiPrefs.turnoPredeterminado) {
        val pref = uiPrefs.turnoPredeterminado
        val match = tomaItems.firstOrNull { it.id.equals(pref, ignoreCase = true) }
            ?: when {
                pref.contains("TAR", ignoreCase = true) -> tomaItems.firstOrNull {
                    it.id.contains("PM", true) || it.title.contains("Tarde", true)
                }
                else -> tomaItems.firstOrNull()
            }
        mutableStateOf(match?.id ?: tomaItems.firstOrNull()?.id.orEmpty())
    }
    val selectedHorarioLabel = tomaItems.firstOrNull { it.id == selectedHorarioCodigo }?.title ?: selectedHorarioCodigo

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

    val costoKiloUsd = availableFeeds.firstOrNull { it.nombre == selectedFeedName }
        ?.costoUsd?.takeIf { it > 0.0 } ?: 0.48
    val costoTotalEstimado = totalEquivalentKg * costoKiloUsd

    // Observation tags
    var comederoLimpio by remember { mutableStateOf(false) }
    var pocoApetito by remember { mutableStateOf(false) }
    var ajusteTolva by remember { mutableStateOf(false) }
    var additionalNotes by remember { mutableStateOf("") }

    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val pigCount = currentLot?.animalesCount ?: 0
    val perPigRation = if (pigCount > 0) totalEquivalentKg / pigCount else 0.0
    val canSave = currentLot != null && selectedFeedName.isNotBlank() && totalEquivalentKg > 0.0

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                        enabled = canSave && !isSaving && !isSaved,
                        onClick = {
                            val lotToSave = currentLot ?: return@TactileButton
                            if (canSave && !isSaving && !isSaved) {
                                isSaving = true
                                coroutineScope.launch {
                                    val record = com.example.porciapp.data.model.FeedSupplyRecord(
                                        galponLote = lotToSave.galponNombre,
                                        loteNumero = lotToSave.loteNombre,
                                        animalesCount = lotToSave.animalesCount,
                                        tipoAlimento = selectedFeedName,
                                        siloOrigen = selectedSiloName,
                                        siloCodigo = selectedSiloCodigo,
                                        horarioToma = selectedHorarioLabel,
                                        cantidadKg = totalEquivalentKg,
                                        fechaHora = "Hoy • $selectedHorarioLabel",
                                        operario = "${activeProfile.nombre} (${activeProfile.cargo})",
                                        notasConsumo = listOfNotNull(
                                            if (comederoLimpio) "Comedero limpio" else null,
                                            if (pocoApetito) "Poco apetito" else null,
                                            if (ajusteTolva) "Ajuste tolva" else null,
                                            "Toma: $selectedHorarioLabel",
                                            "Unidad: $inputAmount $selectedUnit"
                                        ),
                                        observacionAdicional = additionalNotes
                                    )
                                    delay(350)
                                    onSaveRecord(record)
                                    isSaving = false
                                    isSaved = true
                                    delay(450)
                                    onBackClick()
                                }
                            }
                        },
                        backgroundColor = when {
                            isSaved -> FieldSecondaryFixed
                            !canSave -> FieldSurfaceContainerHigh
                            else -> FieldOliveCTA
                        },
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
                                    color = FieldDarkBorder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                                    color = FieldDarkBorder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else if (currentLot == null) {
                                Icon(
                                    imageVector = Icons.Default.SyncProblem,
                                    contentDescription = null,
                                    tint = FieldError,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Sincroniza Lotes Activos para Guardar",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldError,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                                    color = FieldDarkBorder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
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

            // 2. Filtro de Galpón y Lote Destino (100% Base de Datos)
            item {
                val galponSelectedItem = galponesList.firstOrNull { it.id == selectedGalponName }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DatabaseSelectorField(
                        label = "FILTRAR POR GALPÓN / NAVE",
                        selectedTitle = galponSelectedItem?.title ?: "Todos los galpones",
                        selectedSubtitle = galponSelectedItem?.subtitle.orEmpty(),
                        selectedBadge = galponSelectedItem?.badge.orEmpty(),
                        leadingIcon = Icons.Default.Apartment,
                        compact = true,
                        onClick = { showGalponSheet = true }
                    )

                    if (currentLot == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(FieldErrorContainer.copy(alpha = 0.25f))
                                .border(BorderStroke(1.5.dp, FieldError), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = FieldError,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Sincroniza el catálogo para cargar lotes activos desde la nube antes de dispensar alimento.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = FieldOnSurface
                                )
                            }
                        }
                    } else {
                        DatabaseSelectorField(
                            label = "GALPÓN / LOTE DESTINO *",
                            selectedTitle = "${currentLot.galponNombre} • ${currentLot.loteNombre}",
                            selectedSubtitle = "${currentLot.etapa} • ${currentLot.animalesCount} animales en nave",
                            selectedBadge = currentLot.etapa,
                            leadingIcon = Icons.Default.Store,
                            onClick = { showLotSheet = true }
                        )
                    }
                }
            }

            // 3. Tipo de Alimento (Catálogo Maestro ALIM / PRE)
            item {
                val selectedFeedItem = availableFeeds.firstOrNull { it.nombre == selectedFeedName }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(FieldPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "TIPO DE ALIMENTO (CATÁLOGO BD)",
                                style = MaterialTheme.typography.labelMedium,
                                color = FieldOnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(text = " *", color = FieldError, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(FieldPrimary)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ETAPA: ${currentLot?.etapa ?: "Sin lote"}",
                                color = FieldSecondaryContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    DatabaseSelectorField(
                        label = "CONCENTRADO / PREINICIADOR *",
                        selectedTitle = selectedFeedItem?.nombre ?: "",
                        selectedSubtitle = listOfNotNull(
                            selectedFeedItem?.meta?.takeIf { it.isNotBlank() },
                            selectedFeedItem?.siloCodigo?.takeIf { it.isNotBlank() }?.let { "Silo: $it" }
                        ).joinToString(" • "),
                        selectedBadge = selectedFeedItem?.categoria.orEmpty(),
                        placeholder = "Seleccionar alimento de catálogo...",
                        leadingIcon = Icons.Default.Inventory2,
                        enabled = availableFeeds.isNotEmpty(),
                        onClick = { showFeedSheet = true }
                    )
                }
            }

            // 4. Horario / Toma Programada de Alimento
            item {
                ScheduleChipSelector(
                    label = "HORARIO / TOMA DE ALIMENTO *",
                    items = tomaItems,
                    selectedId = selectedHorarioCodigo,
                    onSelect = { selectedHorarioCodigo = it.id }
                )
            }

            // 5. Silo / Tolva de Origen
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

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        silosList.chunked(2).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { (codigo, name, sub) ->
                                    val isSelected = selectedSiloName == name
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 58.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) FieldPrimary else FieldSurfaceContainerHigh)
                                            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                            .clickable {
                                                selectedSiloCodigo = codigo
                                                selectedSiloName = name
                                            }
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = name,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) FieldOnPrimary else FieldOnSurface,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = sub,
                                                fontSize = 10.sp,
                                                color = if (isSelected) FieldOnPrimary.copy(alpha = 0.85f) else FieldSecondary,
                                                fontWeight = if (isSelected) FontWeight.Normal else FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                if (rowItems.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
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
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
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
                                color = FieldPrimary,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Selector de unidad de medida (Grilla 2x2 adaptable)
                    Text(
                        text = "Seleccione Unidad:",
                        style = MaterialTheme.typography.labelSmall,
                        color = FieldOnSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        unidadesDisponibles.chunked(2).forEach { unitRow ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                unitRow.forEach { unit ->
                                    val isSelected = selectedUnit == unit
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 46.dp)
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
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = unit,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) FieldOnPrimary else FieldOnSurface,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (unitRow.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
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
                    val shortUnit = when (selectedUnit) {
                        "Bultos (40 kg)" -> "bultos"
                        "Sacos (25 kg)" -> "sacos"
                        else -> selectedUnit
                    }

                    GloveFriendlyStepper(
                        valueText = if (selectedUnit == "Tn") String.format("%.1f", inputAmount) else inputAmount.roundToInt().toString(),
                        labelText = "Total $selectedUnit",
                        unitText = shortUnit,
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "EQUIVALENTE PARA BBDD:",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldOnSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                AnimatedDecimalCounterText(
                                    targetValue = totalEquivalentKg,
                                    suffix = " Kg",
                                    decimals = 1,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = FieldPrimary
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (activeProfile.rol.puedeVerCostos) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = "GASTO ESTIMADO LOTE:",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    AnimatedDecimalCounterText(
                                        targetValue = costoTotalEstimado,
                                        prefix = "$ ",
                                        suffix = " USD",
                                        decimals = 2,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = FieldOnSurface
                                        )
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = "RACIÓN POR ANIMAL:",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    AnimatedDecimalCounterText(
                                        targetValue = perPigRation,
                                        suffix = " Kg/cerdo",
                                        decimals = 2,
                                        style = MaterialTheme.typography.titleSmall.copy(
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

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FeedNoteToggleChip(
                                label = "Comedero limpio",
                                checked = comederoLimpio,
                                onToggle = { comederoLimpio = !comederoLimpio },
                                modifier = Modifier.weight(1f)
                            )
                            FeedNoteToggleChip(
                                label = "Poco apetito",
                                checked = pocoApetito,
                                onToggle = { pocoApetito = !pocoApetito },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        FeedNoteToggleChip(
                            label = "Ajuste tolva / nivel",
                            checked = ajusteTolva,
                            onToggle = { ajusteTolva = !ajusteTolva },
                            modifier = Modifier.fillMaxWidth()
                        )
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
    }

    // Hojas de Selección desde Base de Datos
    if (showGalponSheet) {
        SearchableCatalogBottomSheet(
            title = "Filtrar por Galpón / Nave",
            subtitle = "Catálogo de naves activas en Neon / SQLite",
            items = galponesList,
            selectedId = selectedGalponName,
            onSelect = { item ->
                selectedGalponName = item.id
                showGalponSheet = false
            },
            onDismiss = { showGalponSheet = false }
        )
    }

    if (showLotSheet) {
        val lotItems = availableLots.map { lot ->
            CatalogChoiceItem(
                id = lot.loteNombre,
                title = "${lot.galponNombre} • ${lot.loteNombre}",
                subtitle = "${lot.animalesCount} animales activos",
                badge = lot.etapa,
                category = lot.etapa
            )
        }
        SearchableCatalogBottomSheet(
            title = "Seleccionar Galpón / Lote",
            subtitle = "Solo lotes activos sincronizados desde base de datos",
            items = lotItems,
            selectedId = currentLot?.loteNombre.orEmpty(),
            categories = availableLots.map { it.etapa }.distinct(),
            emptyMessage = "No hay lotes activos sincronizados.",
            onSelect = { item ->
                selectedLotCode = item.id
                showLotSheet = false
            },
            onDismiss = { showLotSheet = false }
        )
    }

    if (showFeedSheet) {
        val feedItems = availableFeeds.map { f ->
            CatalogChoiceItem(
                id = f.nombre,
                title = f.nombre,
                subtitle = listOfNotNull(f.meta, f.siloCodigo.takeIf { it.isNotBlank() }?.let { "Silo: $it" }).joinToString(" • "),
                badge = f.categoria,
                category = f.categoria
            )
        }
        SearchableCatalogBottomSheet(
            title = "Seleccionar Alimento (ALIM / PRE)",
            subtitle = "Catálogo maestro de alimentos e inventario en bodega",
            items = feedItems,
            selectedId = selectedFeedName,
            categories = availableFeeds.map { it.categoria }.distinct(),
            emptyMessage = "No hay alimentos registrados en el catálogo.",
            onSelect = { item ->
                selectedFeedName = item.id
                showFeedSheet = false
            },
            onDismiss = { showFeedSheet = false }
        )
    }
}

@Composable
private fun FeedNoteToggleChip(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) FieldSecondaryContainer else FieldSurfaceContainerHigh)
            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) FieldPrimary else FieldOnSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = FieldOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
