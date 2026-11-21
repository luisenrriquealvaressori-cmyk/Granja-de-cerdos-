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
import com.example.porciapp.data.local.entities.CatGalponEntity
import com.example.porciapp.data.local.entities.CatHorarioEntity
import com.example.porciapp.data.local.entities.CatInsumoEntity
import com.example.porciapp.data.local.entities.CatLaborEntity
import com.example.porciapp.data.local.entities.CatOperarioEntity
import com.example.porciapp.data.local.entities.OperacionGastoEntity
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
    registrosGuardados: List<OperacionGastoEntity> = emptyList(),
    galponesCatalog: List<CatGalponEntity> = emptyList(),
    laboresCatalog: List<CatLaborEntity> = emptyList(),
    horariosCatalog: List<CatHorarioEntity> = emptyList(),
    operariosCatalog: List<CatOperarioEntity> = emptyList(),
    insumosCatalog: List<CatInsumoEntity> = emptyList(),
    uiPrefs: WorkspaceUiPreferences = WorkspaceUiPreferences.defaultFor(activeProfile.rol)
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()
    val puedeVerCostos = activeProfile.rol.puedeVerCostos
    val tipoCambio = 36.80

    // 0: Labor de Campo / Turno (todos los roles), 1: Gasto Menor (solo Admin)
    var selectedSectionTab by remember(puedeVerCostos) { mutableIntStateOf(0) }

    // Catálogos maestros desde PostgreSQL / SQLite
    val allLaboresCampo = remember(laboresCatalog, catalogState.labores, activeProfile.rol) {
        val raw = if (laboresCatalog.isNotEmpty()) {
            laboresCatalog
        } else {
            catalogState.labores.map {
                CatLaborEntity(
                    codigo = it.codigo,
                    nombre = it.nombre,
                    tipoRegistro = it.tipoRegistro,
                    categoria = it.categoria,
                    etapaSugerida = it.etapaSugerida,
                    galponSugerido = it.galponSugerido,
                    duracionSugerida = it.duracionSugerida,
                    tarifaReferenciaNio = it.tarifaReferenciaNio,
                    centroCostoCodigo = it.centroCostoCodigo,
                    rolSugerido = it.rolSugerido,
                    requiereInsumo = it.requiereInsumo
                )
            }
        }
        raw.filter {
            it.tipoRegistro == "LABOR_CAMPO" &&
                (activeProfile.rol != UserRole.OPERARIO || !it.rolSugerido.equals("Veterinario", ignoreCase = true))
        }
    }

    val gastosCatalog = remember(laboresCatalog, catalogState.labores) {
        val raw = if (laboresCatalog.isNotEmpty()) {
            laboresCatalog
        } else {
            catalogState.labores.map {
                CatLaborEntity(
                    codigo = it.codigo,
                    nombre = it.nombre,
                    tipoRegistro = it.tipoRegistro,
                    categoria = it.categoria,
                    etapaSugerida = it.etapaSugerida,
                    galponSugerido = it.galponSugerido,
                    duracionSugerida = it.duracionSugerida,
                    tarifaReferenciaNio = it.tarifaReferenciaNio,
                    centroCostoCodigo = it.centroCostoCodigo,
                    rolSugerido = it.rolSugerido,
                    requiereInsumo = it.requiereInsumo
                )
            }
        }
        raw.filter { it.tipoRegistro == "GASTO_OPERATIVO" }
    }

    val ubicacionesItems = remember(galponesCatalog, catalogState.galpones, catalogState.lotes) {
        val galpones = if (galponesCatalog.isNotEmpty()) {
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
        val lotes = catalogState.lotes.map {
            val tag = "${it.codigo} · ${it.galpon.ifBlank { it.etapa }}"
            CatalogChoiceItem(
                id = tag,
                title = tag,
                subtitle = "${it.etapa} • ${it.animales} animales",
                badge = "LOTE",
                category = "Lotes Activos"
            )
        }
        galpones + lotes + listOf(
            CatalogChoiceItem(
                id = "General Granja",
                title = "General Granja",
                subtitle = "Toda la instalación y áreas comunes",
                badge = "GEN",
                category = "Áreas"
            )
        )
    }

    val turnosItems = remember(horariosCatalog, catalogState.horarios) {
        val raw = if (horariosCatalog.any { it.tipoHorario == "TURNO_JORNADA" }) {
            horariosCatalog.filter { it.tipoHorario == "TURNO_JORNADA" }.map {
                CatalogChoiceItem(
                    id = it.codigo,
                    title = it.nombre,
                    subtitle = "${it.horaInicio} - ${it.horaFin}".trim(' ', '-')
                )
            }
        } else {
            catalogState.horarios.filter { it.tipoHorario == "TURNO_JORNADA" }.map {
                CatalogChoiceItem(
                    id = it.codigo,
                    title = it.nombre,
                    subtitle = "${it.horaInicio} - ${it.horaFin}".trim(' ', '-')
                )
            }
        }
        raw.ifEmpty {
            listOf(
                CatalogChoiceItem("TUR-MAN", "Turno Mañana", "06:00 - 12:00"),
                CatalogChoiceItem("TUR-TAR", "Turno Tarde", "12:00 - 18:00")
            )
        }
    }

    val bioInsumosItems = remember(insumosCatalog, catalogState.insumosDetalle) {
        val list = if (insumosCatalog.any { it.categoriaCodigo in listOf("BIO", "KIT") }) {
            insumosCatalog.filter { it.categoriaCodigo in listOf("BIO", "KIT") }.map {
                CatalogChoiceItem(
                    id = it.sku,
                    title = it.nombre,
                    subtitle = "${it.stockActual.toInt()} ${it.unidadMedida} disp.",
                    badge = it.categoriaCodigo,
                    category = it.categoriaCodigo
                )
            }
        } else {
            catalogState.insumosDetalle.filter { it.categoriaCodigo in listOf("BIO", "KIT") }.map {
                CatalogChoiceItem(
                    id = it.sku,
                    title = it.nombre,
                    subtitle = "${it.stockActual.toInt()} ${it.unidadMedida} disp.",
                    badge = it.categoriaCodigo,
                    category = it.categoriaCodigo
                )
            }
        }
        list
    }

    val operariosItems = remember(operariosCatalog, catalogState.operarios, activeProfile) {
        val list = if (operariosCatalog.isNotEmpty()) {
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
        if (list.any { it.title.equals(activeProfile.nombre, ignoreCase = true) }) {
            list
        } else {
            listOf(
                CatalogChoiceItem(
                    id = activeProfile.nombreCompleto,
                    title = activeProfile.nombre,
                    subtitle = "${activeProfile.cargo} • Sesión activa",
                    badge = activeProfile.rol.titulo,
                    category = activeProfile.rol.titulo
                )
            ) + list
        }
    }

    // Estados de Labor de Campo
    var selectedLaborCode by remember(allLaboresCampo) {
        mutableStateOf(allLaboresCampo.firstOrNull()?.codigo.orEmpty())
    }
    val selectedLaborObj = allLaboresCampo.firstOrNull { it.codigo == selectedLaborCode }

    var galponLoteLabor by remember(ubicacionesItems, uiPrefs.galponPredeterminado) {
        val pref = uiPrefs.galponPredeterminado.trim()
        val matched = if (pref.isNotBlank()) {
            ubicacionesItems.firstOrNull {
                it.id.contains(pref, ignoreCase = true) || it.badge.equals(pref, ignoreCase = true)
            }?.id
        } else null
        mutableStateOf(matched ?: ubicacionesItems.firstOrNull()?.id ?: "General Granja")
    }
    var selectedTurnoCode by remember(turnosItems, uiPrefs.turnoPredeterminado) {
        val match = turnosItems.firstOrNull { it.id.equals(uiPrefs.turnoPredeterminado, ignoreCase = true) }
        mutableStateOf(match?.id ?: turnosItems.firstOrNull()?.id.orEmpty())
    }
    var selectedResponsable by remember(activeProfile, operariosItems) {
        val match = operariosItems.firstOrNull { it.title.equals(activeProfile.nombre, ignoreCase = true) }
        mutableStateOf(match?.id ?: activeProfile.nombreCompleto)
    }
    var selectedInsumoSku by remember { mutableStateOf("") }
    var detalleLabor by remember { mutableStateOf("") }
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

    // Estados de Gasto Menor (solo Administrador)
    var selectedGastoCode by remember(gastosCatalog) {
        mutableStateOf(gastosCatalog.firstOrNull()?.codigo.orEmpty())
    }
    var usarConceptoPersonalizado by remember { mutableStateOf(false) }
    var categoriaGasto by remember(gastosCatalog) {
        mutableStateOf(gastosCatalog.firstOrNull()?.categoria ?: "Mantenimiento")
    }
    var conceptoGasto by remember(gastosCatalog) {
        mutableStateOf(gastosCatalog.firstOrNull()?.nombre.orEmpty())
    }
    var galponLoteGasto by remember(ubicacionesItems, uiPrefs.galponPredeterminado) {
        val pref = uiPrefs.galponPredeterminado.trim()
        val matched = if (pref.isNotBlank()) {
            ubicacionesItems.firstOrNull {
                it.id.contains(pref, ignoreCase = true) || it.badge.equals(pref, ignoreCase = true)
            }?.id
        } else null
        mutableStateOf(matched ?: ubicacionesItems.firstOrNull()?.id ?: "Administración General")
    }
    var montoCordobasGasto by remember { mutableStateOf("") }
    var numeroComprobante by remember { mutableStateOf("") }
    var notasGasto by remember { mutableStateOf("") }

    // Hojas modales desde BD
    var showLaborSheet by remember { mutableStateOf(false) }
    var showUbicacionLaborSheet by remember { mutableStateOf(false) }
    var showInsumoSheet by remember { mutableStateOf(false) }
    var showResponsableSheet by remember { mutableStateOf(false) }
    var showGastoSheet by remember { mutableStateOf(false) }
    var showUbicacionGastoSheet by remember { mutableStateOf(false) }

    val sdfDisplay = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (puedeVerCostos) "Labores & Gastos de Campo" else "Labores de Campo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize(),
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
                    val laborItems = remember(allLaboresCampo) {
                        allLaboresCampo.map { l ->
                            CatalogChoiceItem(
                                id = l.codigo,
                                title = l.nombre,
                                subtitle = listOfNotNull(
                                    l.etapaSugerida.takeIf { it.isNotBlank() },
                                    l.duracionSugerida.takeIf { it.isNotBlank() },
                                    if (l.requiereInsumo) "Requiere insumo" else null
                                ).joinToString(" • "),
                                badge = l.categoria,
                                category = l.categoria
                            )
                        }
                    }
                    val insumoSelectedItem = bioInsumosItems.firstOrNull { it.id == selectedInsumoSku }
                    val responsableSelectedItem = operariosItems.firstOrNull { it.id == selectedResponsable }
                    val turnoSelectedItem = turnosItems.firstOrNull { it.id == selectedTurnoCode }
                    val laborDisplayTitle = selectedLaborObj?.nombre.orEmpty()

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

                            DatabaseSelectorField(
                                label = "1. LABOR DEL CATÁLOGO (BASE DE DATOS) *",
                                selectedTitle = laborDisplayTitle,
                                selectedSubtitle = selectedLaborObj?.let {
                                    "${it.categoria} • ${it.etapaSugerida.ifBlank { "General" }} • ${it.duracionSugerida}"
                                }.orEmpty(),
                                selectedBadge = selectedLaborObj?.categoria.orEmpty(),
                                placeholder = "Seleccionar labor estándar...",
                                leadingIcon = Icons.Default.Engineering,
                                onClick = { showLaborSheet = true }
                            )

                            DatabaseSelectorField(
                                label = "2. GALPÓN / LOTE DE EJECUCIÓN *",
                                selectedTitle = galponLoteLabor,
                                selectedSubtitle = ubicacionesItems.firstOrNull { it.id == galponLoteLabor }?.subtitle.orEmpty(),
                                selectedBadge = ubicacionesItems.firstOrNull { it.id == galponLoteLabor }?.badge.orEmpty(),
                                placeholder = "Seleccionar nave o lote...",
                                leadingIcon = Icons.Default.Apartment,
                                onClick = { showUbicacionLaborSheet = true }
                            )

                            ScheduleChipSelector(
                                label = "3. TURNO / HORARIO DE JORNADA *",
                                items = turnosItems,
                                selectedId = selectedTurnoCode,
                                onSelect = { selectedTurnoCode = it.id }
                            )

                            DatabaseSelectorField(
                                label = "4. OPERARIO / RESPONSABLE",
                                selectedTitle = responsableSelectedItem?.title ?: selectedResponsable,
                                selectedSubtitle = responsableSelectedItem?.subtitle ?: activeProfile.cargo,
                                selectedBadge = responsableSelectedItem?.badge ?: activeProfile.rol.titulo,
                                leadingIcon = Icons.Default.Badge,
                                compact = true,
                                enabled = activeProfile.rol != UserRole.OPERARIO || operariosItems.size > 1,
                                onClick = { showResponsableSheet = true }
                            )

                            if (selectedLaborObj?.requiereInsumo == true && bioInsumosItems.isNotEmpty()) {
                                DatabaseSelectorField(
                                    label = "5. INSUMO BIO / KIT UTILIZADO (OPCIONAL)",
                                    selectedTitle = insumoSelectedItem?.title.orEmpty(),
                                    selectedSubtitle = insumoSelectedItem?.subtitle.orEmpty(),
                                    selectedBadge = insumoSelectedItem?.badge.orEmpty(),
                                    placeholder = "Seleccionar insumo de inventario...",
                                    leadingIcon = Icons.Default.MedicalServices,
                                    compact = true,
                                    onClick = { showInsumoSheet = true }
                                )
                            }

                            Text(
                                text = "DURACIÓN DE LA LABOR:",
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

                            OutlinedTextField(
                                value = detalleLabor,
                                onValueChange = { detalleLabor = it },
                                label = { Text("Detalle adicional (opcional)") },
                                placeholder = { Text("Ej. Lavado de 6 parideras...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (puedeVerCostos) {
                                val montoNum = montoCordobasLabor.toDecimalOrNull() ?: 0.0
                                OutlinedTextField(
                                    value = montoCordobasLabor,
                                    onValueChange = { montoCordobasLabor = it },
                                    label = { Text("Tarifa / Jornal en C$ (vacío = tarifa catálogo)") },
                                    placeholder = {
                                        val ref = selectedLaborObj?.tarifaReferenciaNio?.takeIf { it > 0.0 } ?: 320.0
                                        Text(String.format(Locale.US, "%.2f", ref))
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    supportingText = {
                                        val ref = selectedLaborObj?.tarifaReferenciaNio?.takeIf { it > 0.0 } ?: 320.0
                                        Text(
                                            if (montoNum > 0.0)
                                                "Equivalente: $${String.format(Locale.US, "%.2f", montoNum / tipoCambio)} USD"
                                            else
                                                "Tarifa referencia BD: C$ ${String.format(Locale.US, "%.2f", ref)} / jornal"
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            OutlinedTextField(
                                value = notasLabor,
                                onValueChange = { notasLabor = it },
                                label = { Text("Observaciones de campo (opcional)") },
                                placeholder = { Text("Novedades en corrales, equipo, etc.") },
                                minLines = 2,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )

                            val canSaveLabor = laborDisplayTitle.isNotBlank() || detalleLabor.isNotBlank()
                            TactileButton(
                                text = "Guardar Labor en Turno",
                                icon = Icons.Default.CheckCircle,
                                enabled = canSaveLabor,
                                onClick = {
                                    val montoC = if (puedeVerCostos) {
                                        montoCordobasLabor.toDecimalOrNull()
                                            ?: selectedLaborObj?.tarifaReferenciaNio?.takeIf { it > 0.0 }
                                            ?: 0.0
                                    } else 0.0
                                    val baseNombre = laborDisplayTitle.ifBlank { "Labor de campo" }
                                    val conceptoFinal = if (detalleLabor.isNotBlank()) {
                                        "$baseNombre — ${detalleLabor.trim()}"
                                    } else {
                                        baseNombre
                                    }
                                    val notasFinales = listOfNotNull(
                                        turnoSelectedItem?.let { "Turno: ${it.title}" },
                                        insumoSelectedItem?.let { "Insumo: ${it.title} (${it.id})" },
                                        notasLabor.trim().takeIf { it.isNotBlank() }
                                    ).joinToString(" • ")

                                    val entity = OperacionGastoEntity(
                                        tipoRegistro = "LABOR_CAMPO",
                                        concepto = conceptoFinal,
                                        categoria = selectedLaborObj?.categoria ?: "Mano de Obra",
                                        responsableNombre = selectedResponsable.ifBlank { activeProfile.nombreCompleto },
                                        galponLote = galponLoteLabor.ifBlank { "General Granja" },
                                        montoCordobas = montoC,
                                        montoDolares = if (montoC > 0.0) montoC / tipoCambio else 0.0,
                                        horasOJornal = jornalSeleccionado,
                                        fechaDisplay = sdfDisplay.format(Date()),
                                        observaciones = notasFinales,
                                        sincronizado = false
                                    )
                                    onSaveRecord(entity)
                                    detalleLabor = ""
                                    montoCordobasLabor = ""
                                    notasLabor = ""
                                    selectedInsumoSku = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            } else {
                // Tab 1: Gasto Menor (exclusivo Administrador)
                item {
                    val selectedGastoObj = gastosCatalog.firstOrNull { it.codigo == selectedGastoCode }
                    val ubiGastoItem = ubicacionesItems.firstOrNull { it.id == galponLoteGasto }
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

                            DatabaseSelectorField(
                                label = "1. CONCEPTO DE GASTO (CATÁLOGO BD) *",
                                selectedTitle = if (!usarConceptoPersonalizado) selectedGastoObj?.nombre ?: conceptoGasto else "Otro concepto puntual",
                                selectedSubtitle = if (!usarConceptoPersonalizado && selectedGastoObj != null) {
                                    "${selectedGastoObj.categoria} • CC: ${selectedGastoObj.centroCostoCodigo.ifBlank { "General" }}"
                                } else {
                                    "Ingreso manual extraordinario"
                                },
                                selectedBadge = if (!usarConceptoPersonalizado) selectedGastoObj?.categoria ?: categoriaGasto else "MANUAL",
                                placeholder = "Seleccionar concepto de gasto...",
                                leadingIcon = Icons.AutoMirrored.Filled.ReceiptLong,
                                onClick = {
                                    usarConceptoPersonalizado = false
                                    showGastoSheet = true
                                }
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(FieldSurfaceContainerHigh)
                                    .clickable { usarConceptoPersonalizado = !usarConceptoPersonalizado }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Otro concepto puntual (fuera de catálogo)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = FieldOnSurface
                                )
                                Switch(
                                    checked = usarConceptoPersonalizado,
                                    onCheckedChange = { usarConceptoPersonalizado = it }
                                )
                            }

                            if (usarConceptoPersonalizado) {
                                OutlinedTextField(
                                    value = conceptoGasto,
                                    onValueChange = { conceptoGasto = it },
                                    label = { Text("Concepto puntual del gasto *") },
                                    placeholder = { Text("Ej. Repuesto de emergencia...") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            DatabaseSelectorField(
                                label = "2. GALPÓN / CENTRO DE COSTO *",
                                selectedTitle = galponLoteGasto,
                                selectedSubtitle = ubiGastoItem?.subtitle.orEmpty(),
                                selectedBadge = ubiGastoItem?.badge.orEmpty(),
                                placeholder = "Seleccionar área o galpón...",
                                leadingIcon = Icons.Default.Apartment,
                                onClick = { showUbicacionGastoSheet = true }
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
                                label = { Text("Observaciones (opcional)") },
                                minLines = 2,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )

                            val conceptoFinal = if (!usarConceptoPersonalizado) {
                                selectedGastoObj?.nombre ?: conceptoGasto.trim()
                            } else {
                                conceptoGasto.trim()
                            }
                            val categoriaFinal = if (!usarConceptoPersonalizado) {
                                selectedGastoObj?.categoria ?: categoriaGasto
                            } else {
                                categoriaGasto
                            }
                            val valido = conceptoFinal.isNotBlank() && (montoCordobasGasto.toDecimalOrNull() ?: 0.0) > 0.0
                            TactileButton(
                                text = "Registrar Gasto Bimonetario",
                                icon = Icons.Default.Payments,
                                enabled = valido,
                                onClick = {
                                    val montoC = montoCordobasGasto.toDecimalOrNull() ?: 0.0
                                    val entity = OperacionGastoEntity(
                                        tipoRegistro = "GASTO_OPERATIVO",
                                        concepto = conceptoFinal,
                                        categoria = categoriaFinal,
                                        responsableNombre = activeProfile.nombreCompleto,
                                        galponLote = galponLoteGasto.ifBlank { "Administración General" },
                                        montoCordobas = montoC,
                                        montoDolares = montoC / tipoCambio,
                                        horasOJornal = 0.0,
                                        fechaDisplay = sdfDisplay.format(Date()),
                                        numeroComprobante = numeroComprobante.trim(),
                                        observaciones = notasGasto.trim(),
                                        sincronizado = false
                                    )
                                    onSaveRecord(entity)
                                    if (usarConceptoPersonalizado) conceptoGasto = ""
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
                                Column(modifier = Modifier.weight(1f)) {
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

    if (showLaborSheet) {
        val laborItems = allLaboresCampo.map { l ->
            CatalogChoiceItem(
                id = l.codigo,
                title = l.nombre,
                subtitle = listOfNotNull(
                    l.etapaSugerida.takeIf { it.isNotBlank() },
                    l.duracionSugerida.takeIf { it.isNotBlank() },
                    l.galponSugerido.takeIf { it.isNotBlank() }
                ).joinToString(" • "),
                badge = l.categoria,
                category = l.categoria
            )
        }
        SearchableCatalogBottomSheet(
            title = "Catálogo de Labores de Campo",
            subtitle = "Actividades estandarizadas por área y rol",
            items = laborItems,
            selectedId = selectedLaborCode,
            categories = laborItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            emptyMessage = "Sincroniza el catálogo para descargar labores.",
            onSelect = { item ->
                selectedLaborCode = item.id
                val l = allLaboresCampo.firstOrNull { it.codigo == item.id }
                if (l != null) {
                    jornalSeleccionado = when {
                        l.duracionSugerida.contains("0.25") || l.duracionSugerida.contains("2h") -> 0.25
                        l.duracionSugerida.contains("1.0") || l.duracionSugerida.contains("8h") -> 1.0
                        l.duracionSugerida.contains("1.5") || l.duracionSugerida.contains("12h") -> 1.5
                        else -> 0.50
                    }
                    if (galponLoteLabor.isBlank() && l.galponSugerido.isNotBlank()) {
                        galponLoteLabor = ubicacionesItems.firstOrNull {
                            it.id.contains(l.galponSugerido, ignoreCase = true)
                        }?.id ?: l.galponSugerido
                    }
                    if (!l.requiereInsumo) {
                        selectedInsumoSku = ""
                    }
                }
                showLaborSheet = false
            },
            onDismiss = { showLaborSheet = false }
        )
    }

    if (showUbicacionLaborSheet) {
        SearchableCatalogBottomSheet(
            title = "Galpón / Lote de Ejecución",
            subtitle = "Galpones y lotes activos sincronizados",
            items = ubicacionesItems,
            selectedId = galponLoteLabor,
            categories = ubicacionesItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                galponLoteLabor = item.id
                showUbicacionLaborSheet = false
            },
            onDismiss = { showUbicacionLaborSheet = false }
        )
    }

    if (showInsumoSheet) {
        SearchableCatalogBottomSheet(
            title = "Insumos de Bioseguridad / Kit",
            subtitle = "Insumos registrados en bodega (BIO / KIT)",
            items = bioInsumosItems,
            selectedId = selectedInsumoSku,
            categories = bioInsumosItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                selectedInsumoSku = item.id
                showInsumoSheet = false
            },
            onDismiss = { showInsumoSheet = false }
        )
    }

    if (showResponsableSheet) {
        SearchableCatalogBottomSheet(
            title = "Operario / Técnico Responsable",
            subtitle = "Personal registrado en base de datos",
            items = operariosItems,
            selectedId = selectedResponsable,
            categories = operariosItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                selectedResponsable = item.id
                showResponsableSheet = false
            },
            onDismiss = { showResponsableSheet = false }
        )
    }

    if (showGastoSheet) {
        val gastoItems = gastosCatalog.map { g ->
            CatalogChoiceItem(
                id = g.codigo,
                title = g.nombre,
                subtitle = "CC: ${g.centroCostoCodigo.ifBlank { "General" }} • Ref: C$ ${String.format(Locale.US, "%.2f", g.tarifaReferenciaNio)}",
                badge = g.categoria,
                category = g.categoria
            )
        }
        SearchableCatalogBottomSheet(
            title = "Catálogo de Conceptos de Gasto",
            subtitle = "Centros de costo y rubros aprobados",
            items = gastoItems,
            selectedId = selectedGastoCode,
            categories = gastoItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            emptyMessage = "Sincroniza el catálogo o activa 'Otro concepto puntual'.",
            onSelect = { item ->
                selectedGastoCode = item.id
                val g = gastosCatalog.firstOrNull { it.codigo == item.id }
                if (g != null) {
                    conceptoGasto = g.nombre
                    categoriaGasto = g.categoria
                    if (montoCordobasGasto.isBlank() && g.tarifaReferenciaNio > 0.0) {
                        montoCordobasGasto = String.format(Locale.US, "%.2f", g.tarifaReferenciaNio)
                    }
                }
                showGastoSheet = false
            },
            onDismiss = { showGastoSheet = false }
        )
    }

    if (showUbicacionGastoSheet) {
        val adminArea = listOf(
            CatalogChoiceItem(
                id = "Administración General",
                title = "Administración General",
                subtitle = "Gastos administrativos centrales de granja",
                badge = "ADM",
                category = "Áreas"
            )
        ) + ubicacionesItems
        SearchableCatalogBottomSheet(
            title = "Imputar Gasto a Galpón / Área",
            subtitle = "Centro de costo operativo",
            items = adminArea,
            selectedId = galponLoteGasto,
            categories = adminArea.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                galponLoteGasto = item.id
                showUbicacionGastoSheet = false
            },
            onDismiss = { showUbicacionGastoSheet = false }
        )
    }
}
