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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.data.local.entities.BajaMortalidadEntity
import com.example.porciapp.data.local.entities.CatClinicoEntity
import com.example.porciapp.data.local.entities.CatGalponEntity
import com.example.porciapp.data.local.entities.CerdoEntity
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.WorkspaceUiPreferences
import com.example.porciapp.data.repository.CatalogStore
import com.example.porciapp.ui.components.CatalogChoiceItem
import com.example.porciapp.ui.components.DatabaseSelectorField
import com.example.porciapp.ui.components.RoleRestrictionWarningBanner
import com.example.porciapp.ui.components.SearchableCatalogBottomSheet
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
    onSaveBaja: (BajaMortalidadEntity) -> Unit,
    clinicosCatalog: List<CatClinicoEntity> = emptyList(),
    galponesCatalog: List<CatGalponEntity> = emptyList(),
    cerdosFromDb: List<CerdoEntity> = emptyList(),
    uiPrefs: WorkspaceUiPreferences = WorkspaceUiPreferences.defaultFor(activeProfile.rol)
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()

    val causasItems = remember(clinicosCatalog, catalogState.catalogosClinicos) {
        val fromRoom = clinicosCatalog.filter { it.tipoCatalogo == "CAUSA_MORTALIDAD" }.map {
            CatalogChoiceItem(
                id = it.nombre,
                title = it.nombre,
                subtitle = it.subcategoria.ifBlank { "Causa clínica" },
                badge = it.subcategoria,
                category = it.subcategoria.ifBlank { "General" }
            )
        }
        if (fromRoom.isNotEmpty()) fromRoom else catalogState.catalogosClinicos.filter { it.tipoCatalogo == "CAUSA_MORTALIDAD" }.map {
            CatalogChoiceItem(id = it.nombre, title = it.nombre, subtitle = it.subcategoria, badge = it.subcategoria, category = it.subcategoria)
        }.ifEmpty {
            listOf(
                CatalogChoiceItem("Aplastamiento perinatal", "Aplastamiento perinatal", "Perinatal", "PERINATAL", "Perinatal"),
                CatalogChoiceItem("Complejo respiratorio porcino", "Complejo respiratorio porcino", "Respiratorio", "RESPIRATORIO", "Respiratorio")
            )
        }
    }

    val etapas = remember(clinicosCatalog, catalogState.catalogosClinicos) {
        val fromRoom = clinicosCatalog.filter { it.tipoCatalogo == "ETAPA_ZOOTECNICA" }.map { it.nombre }
        val fromCat = catalogState.catalogosClinicos.filter { it.tipoCatalogo == "ETAPA_ZOOTECNICA" }.map { it.nombre }
        (fromRoom.ifEmpty { fromCat }).ifEmpty {
            listOf("Maternidad", "Precebo / Destete", "Levante / Crecimiento", "Engorde", "Gestación")
        }
    }

    val galponesItems = remember(galponesCatalog, catalogState.galpones) {
        val list = if (galponesCatalog.isNotEmpty()) {
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
        list.ifEmpty {
            listOf(
                CatalogChoiceItem("Nave Maternidad A", "Nave Maternidad A", "Maternidad"),
                CatalogChoiceItem("Galpón 1", "Galpón 1", "Producción")
            )
        }
    }

    val loteItems = remember(catalogState.lotes) {
        catalogState.lotes.map { l ->
            CatalogChoiceItem(
                id = l.codigo,
                title = "${l.codigo} • ${l.galpon.ifBlank { l.etapa }}",
                subtitle = "${l.etapa} • ${l.animales} animales",
                badge = l.etapa,
                category = l.etapa
            )
        }
    }

    val animalItems = remember(cerdosFromDb, catalogState.cerdas) {
        val seen = mutableSetOf<String>()
        val indiv = cerdosFromDb.filter { seen.add(it.codigoCrotal.uppercase()) }.map { p ->
            CatalogChoiceItem(
                id = p.codigoCrotal,
                title = "Cerdo ${p.codigoCrotal}",
                subtitle = listOf(p.raza, p.sexo, p.jaula).filter { it.isNotBlank() }.joinToString(" • "),
                badge = p.sexo,
                category = "Individuales"
            )
        }
        val cerdas = catalogState.cerdas.filter { seen.add(it.caravana.uppercase()) }.map { c ->
            CatalogChoiceItem(
                id = c.caravana,
                title = "Cerda ${c.caravana}",
                subtitle = listOf(c.galpon, c.jaula, c.estado.replace('_', ' ')).filter { it.isNotBlank() }.joinToString(" • "),
                badge = "CERDA",
                category = "Cerdas"
            )
        }
        indiv + cerdas
    }

    // Modo 0 = Por Lote, Modo 1 = Animal Individual
    var modoBaja by remember { mutableIntStateOf(0) }
    var usarCrotalManual by remember { mutableStateOf(false) }

    var loteCodigo by remember(catalogState.lotes) {
        mutableStateOf(catalogState.lotes.firstOrNull()?.codigo ?: "")
    }
    var galponLote by remember(catalogState.lotes, uiPrefs.galponPredeterminado, galponesItems) {
        val pref = uiPrefs.galponPredeterminado.trim()
        val matchedPref = if (pref.isNotBlank()) {
            galponesItems.firstOrNull {
                it.id.equals(pref, ignoreCase = true) || it.badge.equals(pref, ignoreCase = true)
            }?.id
        } else null
        mutableStateOf(catalogState.lotes.firstOrNull()?.galpon?.ifBlank { null } ?: matchedPref ?: galponesItems.firstOrNull()?.id ?: "")
    }
    var etapaZootecnica by remember(etapas, catalogState.lotes) {
        val etLote = catalogState.lotes.firstOrNull()?.etapa.orEmpty()
        val matched = etapas.firstOrNull { it.contains(etLote, ignoreCase = true) }
        mutableStateOf(matched ?: etapas.firstOrNull() ?: "Maternidad")
    }
    var crotal by remember(modoBaja, loteCodigo, animalItems) {
        mutableStateOf(
            if (modoBaja == 0) {
                if (loteCodigo.isNotBlank()) "Baja $loteCodigo" else "Lechón Neonato"
            } else {
                animalItems.firstOrNull()?.id.orEmpty()
            }
        )
    }
    var causaMuerte by remember(causasItems) {
        mutableStateOf(causasItems.firstOrNull()?.id ?: "Aplastamiento perinatal")
    }
    var pesoKgText by remember { mutableStateOf("1.5") }
    var necropsiaRealizada by remember { mutableStateOf(false) }
    var hallazgosNecropsia by remember { mutableStateOf("") }
    var observaciones by remember { mutableStateOf("") }

    var activeTab by remember { mutableIntStateOf(0) } // 0: Formulario, 1: Historial
    var showLoteSheet by remember { mutableStateOf(false) }
    var showAnimalSheet by remember { mutableStateOf(false) }
    var showGalponSheet by remember { mutableStateOf(false) }
    var showCausaSheet by remember { mutableStateOf(false) }

    val puedeRegistrar = activeProfile.rol.puedeRegistrarBajas
    val sdfDisplay = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Registro de Baja & Mortalidad",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldDarkBorder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Auditoría Sanitaria y Necropsia en Campo",
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FieldSurfaceContainerHigh)
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
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
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
                    text = {
                        Text(
                            text = "Registrar Baja",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    icon = { Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = {
                        Text(
                            text = "Historial (${historialBajas.size})",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
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
                    if (!puedeRegistrar) {
                        item {
                            RoleRestrictionWarningBanner(
                                userRole = activeProfile.rol,
                                titulo = "Módulo Clínico Restringido",
                                mensaje = "El registro oficial de bajas y necropsia corresponde al Veterinario o Administrador."
                            )
                        }
                    }

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
                                text = "1. IDENTIFICACIÓN DE BAJA (BASE DE DATOS)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Selector de alcance: Por Lote vs Animal Individual
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(FieldSurfaceContainerHigh)
                                    .border(BorderStroke(1.dp, FieldOutlineVariant), RoundedCornerShape(8.dp))
                                    .padding(4.dp)
                            ) {
                                listOf(0 to "Por Lote / Grupo", 1 to "Animal Individual").forEach { (idx, label) ->
                                    val selected = modoBaja == idx
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (selected) FieldError else Color.Transparent)
                                            .selectable(selected = selected, role = Role.Tab) {
                                                modoBaja = idx
                                                usarCrotalManual = false
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) Color.White else FieldOnSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (modoBaja == 0) {
                                val loteSel = loteItems.firstOrNull { it.id == loteCodigo }
                                DatabaseSelectorField(
                                    label = "LOTE ACTIVO AFECTADO *",
                                    selectedTitle = loteSel?.title ?: loteCodigo,
                                    selectedSubtitle = loteSel?.subtitle.orEmpty(),
                                    selectedBadge = loteSel?.badge.orEmpty(),
                                    placeholder = "Seleccionar lote activo...",
                                    leadingIcon = Icons.Default.GroupWork,
                                    compact = true,
                                    enabled = loteItems.isNotEmpty(),
                                    onClick = { showLoteSheet = true }
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "Referencia / categoría de baja en lote:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldOnSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        "Baja ${loteCodigo.ifBlank { "Lote" }}",
                                        "Lechón Neonato (Sin Crotal)",
                                        "Lechón Maternidad",
                                        "Cerdo Precebo",
                                        "Cerdo Engorde"
                                    ).forEach { tag ->
                                        FilterChip(
                                            selected = crotal == tag,
                                            onClick = { crotal = tag },
                                            label = { Text(tag, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            } else {
                                val animalSel = animalItems.firstOrNull { it.id.equals(crotal, ignoreCase = true) }
                                DatabaseSelectorField(
                                    label = "CROTAL DEL ANIMAL (BD) *",
                                    selectedTitle = if (!usarCrotalManual) (animalSel?.title ?: crotal) else crotal,
                                    selectedSubtitle = animalSel?.subtitle.orEmpty(),
                                    selectedBadge = animalSel?.badge.orEmpty(),
                                    placeholder = "Seleccionar cerda o cerdo individual...",
                                    leadingIcon = Icons.Default.Tag,
                                    compact = true,
                                    onClick = {
                                        usarCrotalManual = false
                                        showAnimalSheet = true
                                    }
                                )

                                Spacer(modifier = Modifier.height(6.dp))

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
                                        text = "Ingresar crotal manual / sin arete",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = FieldOnSurface
                                    )
                                    Switch(
                                        checked = usarCrotalManual,
                                        onCheckedChange = { usarCrotalManual = it }
                                    )
                                }

                                if (usarCrotalManual || animalItems.isEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = crotal,
                                        onValueChange = { crotal = it.uppercase() },
                                        label = { Text("Crotal / Identificador *") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            val galponSel = galponesItems.firstOrNull { it.id == galponLote }
                            DatabaseSelectorField(
                                label = "GALPÓN DE UBICACIÓN *",
                                selectedTitle = galponLote,
                                selectedSubtitle = galponSel?.subtitle.orEmpty(),
                                selectedBadge = galponSel?.badge.orEmpty(),
                                placeholder = "Seleccionar galpón...",
                                leadingIcon = Icons.Default.Apartment,
                                compact = true,
                                onClick = { showGalponSheet = true }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = pesoKgText,
                                onValueChange = { pesoKgText = com.example.porciapp.ui.components.filtrarDecimal(it) },
                                label = { Text("Peso estimado a la baja (Kg)") },
                                singleLine = true,
                                keyboardOptions = com.example.porciapp.ui.components.DecimalKeyboard,
                                modifier = Modifier.fillMaxWidth()
                            )

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
                        }
                    }

                    // Etapa Zootécnica
                    item {
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "2. ETAPA ZOOTÉCNICA (CATÁLOGO BD)",
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
                        val causaSel = causasItems.firstOrNull { it.id == causaMuerte }
                        TactileCard(modifier = Modifier.fillMaxWidth()) {
                            DatabaseSelectorField(
                                label = "3. CAUSA CLÍNICA DE MUERTE (BD) *",
                                selectedTitle = causaSel?.title ?: causaMuerte,
                                selectedSubtitle = causaSel?.subtitle.orEmpty(),
                                selectedBadge = causaSel?.badge.orEmpty(),
                                placeholder = "Seleccionar diagnóstico de baja...",
                                leadingIcon = Icons.Default.MedicalInformation,
                                onClick = { showCausaSheet = true }
                            )
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
                                modifier = Modifier.fillMaxWidth(),
                                enabled = puedeRegistrar && crotal.isNotBlank(),
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Crotal: ${baja.crotal}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldDarkBorder,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f, fill = false)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(FieldError.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = baja.causaMuerte,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = FieldError,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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

    if (showLoteSheet) {
        SearchableCatalogBottomSheet(
            title = "Seleccionar Lote Activo",
            subtitle = "Lotes sincronizados en granja",
            items = loteItems,
            selectedId = loteCodigo,
            categories = loteItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                val l = catalogState.lotes.firstOrNull { it.codigo == item.id }
                if (l != null) {
                    loteCodigo = l.codigo
                    if (l.galpon.isNotBlank()) galponLote = l.galpon
                    val matchedEtapa = etapas.firstOrNull { it.contains(l.etapa, ignoreCase = true) }
                    if (matchedEtapa != null) etapaZootecnica = matchedEtapa
                    if (modoBaja == 0 && (crotal.isBlank() || crotal.startsWith("Baja "))) {
                        crotal = "Baja ${l.codigo}"
                    }
                }
                showLoteSheet = false
            },
            onDismiss = { showLoteSheet = false }
        )
    }

    if (showAnimalSheet) {
        SearchableCatalogBottomSheet(
            title = "Seleccionar Cerdo o Cerda Individual",
            subtitle = "Padrón activo de Neon / SQLite",
            items = animalItems,
            selectedId = crotal,
            categories = animalItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                crotal = item.id
                val foundCerda = catalogState.cerdas.firstOrNull { it.caravana.equals(item.id, ignoreCase = true) }
                if (foundCerda != null && foundCerda.galpon.isNotBlank()) {
                    galponLote = foundCerda.galpon
                }
                showAnimalSheet = false
            },
            onDismiss = { showAnimalSheet = false }
        )
    }

    if (showGalponSheet) {
        SearchableCatalogBottomSheet(
            title = "Seleccionar Galpón",
            subtitle = "Naves registradas en base de datos",
            items = galponesItems,
            selectedId = galponLote,
            onSelect = { item ->
                galponLote = item.id
                showGalponSheet = false
            },
            onDismiss = { showGalponSheet = false }
        )
    }

    if (showCausaSheet) {
        SearchableCatalogBottomSheet(
            title = "Causa Clínica de Mortalidad",
            subtitle = "Catálogo patológico estandarizado",
            items = causasItems,
            selectedId = causaMuerte,
            categories = causasItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                causaMuerte = item.id
                showCausaSheet = false
            },
            onDismiss = { showCausaSheet = false }
        )
    }
}
