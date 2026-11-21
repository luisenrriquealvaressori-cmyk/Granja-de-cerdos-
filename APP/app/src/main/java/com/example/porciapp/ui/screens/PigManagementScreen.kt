package com.example.porciapp.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.example.porciapp.data.local.entities.CatClinicoEntity
import com.example.porciapp.data.local.entities.CatGalponEntity
import com.example.porciapp.data.local.entities.CatJaulaEntity
import com.example.porciapp.data.local.entities.CerdoEntity
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.model.WorkspaceUiPreferences
import com.example.porciapp.data.repository.CatalogStore
import com.example.porciapp.ui.components.*
import com.example.porciapp.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// Modelo local para Jaula con capacidad por etapa
data class JaulaInfo(
    val codigo: String,
    val nombre: String,
    val etapa: String,
    val capacidadMaxima: Int,
    val ocupacionActual: Int,
    val tipoInstalacion: String
) {
    val cupoDisponible: Int get() = maxOf(0, capacidadMaxima - ocupacionActual)
    val porcentajeOcupacion: Float get() = if (capacidadMaxima > 0) (ocupacionActual.toFloat() / capacidadMaxima) * 100f else 0f
}

// Modelo de Vacuna zootécnica
data class VacunaZootecnica(
    val diaVida: Int,
    val nombre: String,
    val enfermedad: String,
    val dosis: String,
    val via: String
)

// Modelo para cerdo con cálculo zootécnico en tiempo real
data class CerdoZootecnicoItem(
    val id: String,
    val crotal: String,
    val rfid: String,
    val sexo: String,
    val raza: String,
    val jaula: String,
    val fechaNacimientoLong: Long,
    val pesoKg: Double,
    val estatusSanitario: String = "Saludable"
) {
    val diasVida: Int
        get() {
            val hoy = System.currentTimeMillis()
            val diff = hoy - fechaNacimientoLong
            return maxOf(0, (diff / (1000L * 60 * 60 * 24)).toInt())
        }

    val etapaClasificada: String
        get() = when {
            diasVida <= 28 -> "Lactancia (Maternidad)"
            diasVida <= 45 -> "Precebo Fase 1"
            diasVida <= 70 -> "Precebo Fase 2"
            diasVida <= 115 -> "Crecimiento / Levante"
            diasVida <= 165 -> "Engorde / Acabado Final"
            else -> "Gestación / Adulto"
        }

    val tomasAlDia: Int
        get() = when {
            diasVida <= 28 -> 5
            diasVida <= 70 -> 4
            diasVida <= 115 -> 3
            else -> 2
        }

    val racionDiariaKg: Double
        get() = when {
            diasVida <= 28 -> 0.10
            diasVida <= 45 -> 0.50
            diasVida <= 70 -> 1.10
            diasVida <= 115 -> 1.95
            diasVida <= 165 -> 2.80
            else -> 2.20
        }

    val gramosPorToma: Int
        get() = ((racionDiariaKg * 1000.0) / tomasAlDia).toInt()

    val horariosSugeridos: String
        get() = when (tomasAlDia) {
            5 -> "06:30, 09:30, 12:30, 15:30, 18:30"
            4 -> "07:00, 11:00, 15:00, 19:00"
            3 -> "07:00, 13:00, 18:00"
            else -> "07:30, 16:30"
        }

    val proximaVacuna: VacunaZootecnica
        get() {
            val vacunas = obtenerCalendarioVacunal()
            return vacunas.firstOrNull { it.diaVida >= diasVida } ?: vacunas.last()
        }
}

fun obtenerCalendarioVacunal(): List<VacunaZootecnica> = listOf(
    VacunaZootecnica(3, "Hierro Dextrano + Toltrazuril", "Anemia e Isosporosis Coccidiosis", "2.0 ml IM + 1.0 ml VO", "Intramuscular profunda / Oral"),
    VacunaZootecnica(7, "Mycoplasma hyopneumoniae Dosis 1", "Neumonía Enzoótica Porcina", "2.0 ml IM", "Intramuscular tabla del cuello"),
    VacunaZootecnica(21, "Circovirus PCV2 + Mycoplasma Refuerzo", "Desmedro multisistémico y Neumonía", "2.0 ml IM", "Intramuscular tabla del cuello"),
    VacunaZootecnica(35, "Peste Porcina Clásica (PPC Cepa China)", "Inmunización preventiva obligatoria", "2.0 ml IM", "Intramuscular profunda"),
    VacunaZootecnica(50, "Erisipela Porcina + Actinobacillus (APP)", "Mal Rojo y Pleuroneumonía aguda", "2.0 ml IM", "Intramuscular tabla del cuello"),
    VacunaZootecnica(70, "Desparasitación Interna y Externa", "Ascaris suum y Sarna sarcóptica", "1.0 ml / 33 kg SC", "Subcutánea retroauricular"),
    VacunaZootecnica(90, "Refuerzo Complejo Respiratorio PRDC", "Complejo respiratorio intensivo", "2.0 ml IM", "Intramuscular"),
    VacunaZootecnica(150, "Vacuna PLE (Parvo + Lepto + Erisipela)", "Fallas reproductivas en vientres", "2.0 ml IM", "Intramuscular profunda")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PigManagementScreen(
    onBackClick: () -> Unit,
    activeProfile: UserProfile = UserProfile.defaultFor(UserRole.ADMINISTRADOR),
    cerdosFromDb: List<CerdoEntity> = emptyList(),
    galponesCatalog: List<CatGalponEntity> = emptyList(),
    jaulasCatalog: List<CatJaulaEntity> = emptyList(),
    clinicosCatalog: List<CatClinicoEntity> = emptyList(),
    uiPrefs: WorkspaceUiPreferences = WorkspaceUiPreferences.defaultFor(activeProfile.rol),
    onSaveCerdo: (CerdoEntity) -> Unit = {}
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Cerdos, 1: Jaulas & Capacidad
    var filtroEtapa by remember { mutableStateOf("Todas") }
    var busquedaQuery by remember { mutableStateOf("") }

    // Lista de jaulas/corrales reales desde el catálogo del servidor (Room jaulasCatalog / catalogState.jaulas)
    val jaulasList = remember(jaulasCatalog, catalogState.jaulas, catalogState.lotes) {
        mutableStateListOf<JaulaInfo>().apply {
            when {
                jaulasCatalog.isNotEmpty() -> {
                    jaulasCatalog.forEach { j ->
                        add(
                            JaulaInfo(
                                codigo = j.codigo,
                                nombre = "${j.galponNombre.ifBlank { j.galponCodigo }} · ${j.nombre}",
                                etapa = j.etapaZootecnica.ifBlank { "Producción" },
                                capacidadMaxima = maxOf(1, j.capacidadMaxima),
                                ocupacionActual = j.ocupacionActual,
                                tipoInstalacion = "Jaula / Corral (${j.galponNombre})"
                            )
                        )
                    }
                }
                catalogState.jaulas.isNotEmpty() -> {
                    catalogState.jaulas.forEach { j ->
                        add(
                            JaulaInfo(
                                codigo = j.codigo,
                                nombre = "${j.galponNombre.ifBlank { j.galponCodigo }} · ${j.nombre}",
                                etapa = j.etapaZootecnica.ifBlank { "Producción" },
                                capacidadMaxima = maxOf(1, j.capacidadMaxima),
                                ocupacionActual = j.ocupacionActual,
                                tipoInstalacion = "Jaula / Corral (${j.galponNombre})"
                            )
                        )
                    }
                }
                catalogState.lotes.isNotEmpty() -> {
                    catalogState.lotes.forEach { lote ->
                        add(
                            JaulaInfo(
                                codigo = lote.codigo,
                                nombre = "${lote.galpon.ifBlank { "Galpón" }} · ${lote.codigo}",
                                etapa = lote.etapa.ifBlank { "Producción" },
                                capacidadMaxima = maxOf(20, lote.animales),
                                ocupacionActual = lote.animales,
                                tipoInstalacion = "Corral / Lote activo"
                            )
                        )
                    }
                }
            }
        }
    }

    // Lista real de cerdos (Room SQLite local + catálogo sincronizado del servidor)
    val cerdosList = remember { mutableStateListOf<CerdoZootecnicoItem>() }

    LaunchedEffect(cerdosFromDb, catalogState.cerdas) {
        val oneDay = 24L * 60L * 60L * 1000L
        val now = System.currentTimeMillis()
        cerdosList.clear()
        val seenCrotales = mutableSetOf<String>()

        for (c in cerdosFromDb) {
            seenCrotales.add(c.codigoCrotal.uppercase(Locale.getDefault()))
            cerdosList.add(
                CerdoZootecnicoItem(
                    id = c.id,
                    crotal = c.codigoCrotal,
                    rfid = "RFID-${c.codigoCrotal}",
                    sexo = c.sexo,
                    raza = c.raza,
                    jaula = c.jaula,
                    fechaNacimientoLong = c.fechaNacimiento,
                    pesoKg = if (c.sexo.equals("MACHO", true)) 35.0 else 28.0,
                    estatusSanitario = if (c.sincronizado) "Sincronizado (API)" else "Pendiente Sync (SQLite)"
                )
            )
        }

        for (sow in catalogState.cerdas) {
            if (sow.caravana.uppercase(Locale.getDefault()) !in seenCrotales) {
                val estDays = (240L + sow.paridad.coerceAtLeast(0) * 145L) * oneDay
                cerdosList.add(
                    CerdoZootecnicoItem(
                        id = "CAT-${sow.caravana}",
                        crotal = sow.caravana,
                        rfid = "CAT-${sow.caravana}",
                        sexo = "Hembra",
                        raza = "Plantel Reproductor (${sow.estado.replace('_', ' ')})",
                        jaula = sow.jaula.ifBlank { sow.galpon.ifBlank { "Gestación" } },
                        fechaNacimientoLong = now - estDays,
                        pesoKg = 185.0,
                        estatusSanitario = sow.estado.replace('_', ' ')
                    )
                )
            }
        }
    }

    // Estados para Modales
    var showEditDialog by remember { mutableStateOf(false) }
    var editingCerdo by remember { mutableStateOf<CerdoZootecnicoItem?>(null) }
    var viewingFichaCerdo by remember { mutableStateOf<CerdoZootecnicoItem?>(null) }

    // Filtrar cerdos
    val cerdosFiltrados = remember(cerdosList.toList(), filtroEtapa, busquedaQuery) {
        cerdosList.filter { c ->
            val matchEtapa = (filtroEtapa == "Todas" || c.etapaClasificada.contains(filtroEtapa, ignoreCase = true))
            val matchBusqueda = (busquedaQuery.isBlank() || c.crotal.contains(busquedaQuery, ignoreCase = true) || c.jaula.contains(busquedaQuery, ignoreCase = true))
            matchEtapa && matchBusqueda
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Padrón & Zootecnia Porcina",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldTextPrimary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${cerdosList.size} cerdos identificados | Ocupación Jaulas",
                            style = MaterialTheme.typography.bodySmall,
                            color = FieldTextSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = FieldTextPrimary
                        )
                    }
                },
                actions = {
                    // Badge del rol activo
                    Surface(
                        modifier = Modifier.padding(end = 8.dp),
                        color = when (activeProfile.rol) {
                            UserRole.ADMINISTRADOR -> FieldPrimary.copy(alpha = 0.15f)
                            UserRole.VETERINARIO -> Color(0xFF0F766E).copy(alpha = 0.15f)
                            UserRole.OPERARIO -> Color(0xFFB45309).copy(alpha = 0.15f)
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = activeProfile.rol.etiqueta,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = when (activeProfile.rol) {
                                UserRole.ADMINISTRADOR -> FieldPrimary
                                UserRole.VETERINARIO -> Color(0xFF0F766E)
                                UserRole.OPERARIO -> Color(0xFFB45309)
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FieldSurface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingCerdo = null
                    showEditDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = "Nuevo") },
                text = { Text("Registrar Cerdo", fontWeight = FontWeight.Bold) },
                containerColor = FieldPrimary,
                contentColor = Color.White
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
            // Banner de Auditoría Operativa
            AuditWarningBanner(
                mensaje = "Edición zootécnica de piara. Cambiar la fecha de nacimiento recalcula automáticamente la edad, el plan de vacunas y las tomas de alimento."
            )

            // Selector de Pestañas (Cerdos vs Jaulas)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = FieldSurface,
                contentColor = FieldPrimary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "Padrón de Cerdos (${cerdosList.size})",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    icon = { Icon(Icons.Default.Pets, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Jaulas & Capacidad (${jaulasList.size})",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    icon = { Icon(Icons.Default.Fence, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            if (selectedTab == 0) {
                // ==========================================
                // VISTA 1: LISTADO Y FILTRO DE CERDOS
                // ==========================================
                // Barra de Búsqueda y Chips de Etapa
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FieldSurface)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = busquedaQuery,
                        onValueChange = { busquedaQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Buscar por crotal o jaula...", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = FieldTextSecondary) },
                        trailingIcon = {
                            if (busquedaQuery.isNotBlank()) {
                                IconButton(onClick = { busquedaQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Limpiar")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val etapasChips = listOf(
                        Pair("Todas", cerdosList.size),
                        Pair("Maternidad", cerdosList.count { it.etapaClasificada.contains("Maternidad", ignoreCase = true) }),
                        Pair("Precebo", cerdosList.count { it.etapaClasificada.contains("Precebo", ignoreCase = true) }),
                        Pair("Crecimiento", cerdosList.count { it.etapaClasificada.contains("Crecimiento", ignoreCase = true) }),
                        Pair("Engorde", cerdosList.count { it.etapaClasificada.contains("Engorde", ignoreCase = true) }),
                        Pair("Gestación", cerdosList.count { it.etapaClasificada.contains("Gestación", ignoreCase = true) })
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(etapasChips) { (etapa, count) ->
                            DynamicFilterChip(
                                text = etapa,
                                selected = filtroEtapa == etapa,
                                badgeCount = count,
                                onClick = { filtroEtapa = etapa }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Mostrando ${cerdosFiltrados.size} de ${cerdosList.size} cerdos",
                            style = MaterialTheme.typography.bodySmall,
                            color = FieldTextSecondary,
                            fontSize = 11.sp
                        )
                        if (busquedaQuery.isNotBlank() || filtroEtapa != "Todas") {
                            Text(
                                text = "Restablecer filtros",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = FieldPrimary,
                                modifier = Modifier
                                    .bounceClick {
                                        busquedaQuery = ""
                                        filtroEtapa = "Todas"
                                    }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = FieldBorder)

                // Lista de Cerdos con Animaciones
                if (cerdosFiltrados.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = FieldTextSecondary,
                                modifier = Modifier.size(54.dp)
                            )
                            Text(
                                text = if (cerdosList.isEmpty()) "Sin cerdos en caché local" else "No se encontraron cerdos",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldTextPrimary
                            )
                            Text(
                                text = if (cerdosList.isEmpty())
                                    "Pulsa \"+ Registrar Cerdo\" para crear una ficha offline o sincroniza el catálogo desde la pantalla de Inicio."
                                else
                                    "No hay registros con el criterio de búsqueda o filtro seleccionado.",
                                style = MaterialTheme.typography.bodySmall,
                                color = FieldTextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            TactileButton(
                                text = "Limpiar Filtros",
                                icon = Icons.Default.FilterAltOff,
                                minHeight = 44.dp,
                                compact = true,
                                onClick = {
                                    busquedaQuery = ""
                                    filtroEtapa = "Todas"
                                }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(cerdosFiltrados, key = { it.id }) { cerdo ->
                            Box(modifier = Modifier.bounceClick { viewingFichaCerdo = cerdo }) {
                                CerdoCardItem(
                                    cerdo = cerdo,
                                    onEditClick = {
                                        editingCerdo = cerdo
                                        showEditDialog = true
                                    },
                                    onViewDetail = {
                                        viewingFichaCerdo = cerdo
                                    }
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(72.dp))
                        }
                    }
                }
            } else {
                // ==========================================
                // VISTA 2: RADAR DE JAULAS Y CAPACIDAD
                // ==========================================
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(jaulasList) { jaula ->
                        JaulaCapacidadCard(jaula = jaula)
                    }

                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }
        }
        }
    }

    // =========================================================================
    // DIÁLOGO: REGISTRAR O EDITAR DATOS DEL CERDO CON CÁLCULO EN VIVO
    // =========================================================================
    if (showEditDialog) {
        CerdoEditDialog(
            cerdoActual = editingCerdo,
            jaulasDisponibles = jaulasList,
            galponesCatalog = galponesCatalog,
            jaulasCatalog = jaulasCatalog,
            clinicosCatalog = clinicosCatalog,
            catalogGalpones = catalogState.galpones,
            catalogJaulas = catalogState.jaulas,
            catalogLineas = catalogState.lineasGeneticas,
            uiPrefs = uiPrefs,
            onDismiss = { showEditDialog = false },
            onConfirm = { nuevoCerdo, galponCodigoResuelto ->
                val index = cerdosList.indexOfFirst { it.id == nuevoCerdo.id }
                if (index >= 0) {
                    cerdosList[index] = nuevoCerdo
                } else {
                    cerdosList.add(0, nuevoCerdo)
                }

                val resolvedGalponId = galponCodigoResuelto.ifBlank {
                    jaulasCatalog.firstOrNull { it.codigo.equals(nuevoCerdo.jaula, ignoreCase = true) }?.galponCodigo
                        ?: catalogState.jaulas.firstOrNull { it.codigo.equals(nuevoCerdo.jaula, ignoreCase = true) }?.galponCodigo
                        ?: uiPrefs.galponPredeterminado.ifBlank { "GALP-01" }
                }

                // Guardar en Room SQLite y Outbox
                val entity = CerdoEntity(
                    id = nuevoCerdo.id,
                    codigoCrotal = nuevoCerdo.crotal,
                    sexo = nuevoCerdo.sexo.uppercase(Locale.getDefault()),
                    raza = nuevoCerdo.raza,
                    idGalpon = resolvedGalponId,
                    jaula = nuevoCerdo.jaula,
                    estado = nuevoCerdo.etapaClasificada.uppercase(Locale.getDefault()),
                    fechaNacimiento = nuevoCerdo.fechaNacimientoLong,
                    sincronizado = false
                )
                onSaveCerdo(entity)
                showEditDialog = false
            }
        )
    }

    // =========================================================================
    // MODAL DE DETALLE: FICHA TÉCNICA ZOOTÉCNICA COMPLETA
    // =========================================================================
    viewingFichaCerdo?.let { cerdo ->
        FichaTecnicaModal(
            cerdo = cerdo,
            onDismiss = { viewingFichaCerdo = null }
        )
    }
}

// Tarjeta Individual de Cerdo
@Composable
private fun CerdoCardItem(
    cerdo: CerdoZootecnicoItem,
    onEditClick: () -> Unit,
    onViewDetail: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onViewDetail() },
        colors = CardDefaults.cardColors(containerColor = FieldSurface),
        border = BorderStroke(1.dp, FieldBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Cabecera: Crotal, Sexo y Badge de Etapa
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = FieldPrimary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Pets, contentDescription = null, tint = FieldPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cerdo.crotal,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldTextPrimary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${cerdo.sexo} | ${cerdo.raza}",
                            style = MaterialTheme.typography.bodySmall,
                            color = FieldTextSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    color = when {
                        cerdo.etapaClasificada.contains("Lactancia") -> Color(0xFFFCE7F3)
                        cerdo.etapaClasificada.contains("Precebo") -> Color(0xFFFEF3C7)
                        cerdo.etapaClasificada.contains("Crecimiento") -> Color(0xFFE0E7FF)
                        cerdo.etapaClasificada.contains("Engorde") -> Color(0xFFD1FAE5)
                        else -> Color(0xFFEDE9FE)
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text(
                        text = cerdo.etapaClasificada,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = when {
                            cerdo.etapaClasificada.contains("Lactancia") -> Color(0xFF9D174D)
                            cerdo.etapaClasificada.contains("Precebo") -> Color(0xFF92400E)
                            cerdo.etapaClasificada.contains("Crecimiento") -> Color(0xFF3730A3)
                            cerdo.etapaClasificada.contains("Engorde") -> Color(0xFF065F46)
                            else -> Color(0xFF5B21B6)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Parámetros Clave: Edad en días y Peso actual
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FieldBackground, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Edad de Vida", fontSize = 10.sp, color = FieldTextSecondary)
                    Text("${cerdo.diasVida} días", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = FieldTextPrimary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Peso Actual", fontSize = 10.sp, color = FieldTextSecondary)
                    Text("${cerdo.pesoKg} kg", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = FieldTextPrimary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ubicación", fontSize = 10.sp, color = FieldTextSecondary)
                    Text(cerdo.jaula, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = FieldPrimary)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Racionamiento Nutricional por Tomas
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Restaurant, contentDescription = null, tint = FieldPrimary, modifier = Modifier.size(16.dp))
                Text(
                    text = "${cerdo.racionDiariaKg} kg/día dividido en ${cerdo.tomasAlDia} tomas de ${cerdo.gramosPorToma} g",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FieldTextPrimary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Próxima Vacuna
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Vaccines, contentDescription = null, tint = Color(0xFFE11D48), modifier = Modifier.size(16.dp))
                    Text(
                        text = "Próx: ${cerdo.proximaVacuna.nombre} (Día ${cerdo.proximaVacuna.diaVida})",
                        fontSize = 11.sp,
                        color = FieldTextSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onEditClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar", tint = FieldPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// Tarjeta de Capacidad de Jaula
@Composable
private fun JaulaCapacidadCard(jaula: JaulaInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FieldSurface),
        border = BorderStroke(1.dp, FieldBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Fence, contentDescription = null, tint = Color(0xFF0F766E))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            jaula.codigo,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = FieldTextPrimary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            jaula.nombre,
                            fontSize = 11.sp,
                            color = FieldTextSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    color = Color(0xFFF0FDFA),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text(
                        text = jaula.etapa,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F766E),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Barra de Capacidad
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Ocupación: ${jaula.ocupacionActual} / ${jaula.capacidadMaxima} cerdos",
                    fontSize = 11.sp,
                    color = FieldTextSecondary
                )
                Text(
                    text = "Cupo: ${jaula.cupoDisponible}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (jaula.cupoDisponible > 0) Color(0xFF059669) else Color(0xFFDC2626)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = (jaula.porcentajeOcupacion / 100f).coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = when {
                    jaula.porcentajeOcupacion > 90f -> Color(0xFFDC2626)
                    jaula.porcentajeOcupacion > 70f -> Color(0xFFD97706)
                    else -> Color(0xFF059669)
                },
                trackColor = FieldBackground
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Instalación: ${jaula.tipoInstalacion}",
                fontSize = 10.sp,
                color = FieldTextSecondary
            )
        }
    }
}

// Diálogo de Registro y Edición de Cerdo con Cálculo en Vivo y Selectores de Base de Datos
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CerdoEditDialog(
    cerdoActual: CerdoZootecnicoItem?,
    jaulasDisponibles: List<JaulaInfo>,
    galponesCatalog: List<CatGalponEntity> = emptyList(),
    jaulasCatalog: List<CatJaulaEntity> = emptyList(),
    clinicosCatalog: List<CatClinicoEntity> = emptyList(),
    catalogGalpones: List<com.example.porciapp.data.repository.CatalogGalpon> = emptyList(),
    catalogJaulas: List<com.example.porciapp.data.repository.CatalogJaula> = emptyList(),
    catalogLineas: List<String> = emptyList(),
    uiPrefs: WorkspaceUiPreferences = WorkspaceUiPreferences(),
    onDismiss: () -> Unit,
    onConfirm: (CerdoZootecnicoItem, String) -> Unit
) {
    val galponesOpciones = remember(galponesCatalog, catalogGalpones) {
        when {
            galponesCatalog.isNotEmpty() -> galponesCatalog.map { g ->
                CatalogChoiceItem(
                    id = g.codigo,
                    title = "${g.nombre} (${g.codigo})",
                    subtitle = "${g.tipoSistema.ifBlank { "Producción" }} • Cap: ${g.capacidad}",
                    badge = g.codigo,
                    category = g.tipoSistema.ifBlank { "General" }
                )
            }
            catalogGalpones.isNotEmpty() -> catalogGalpones.map { g ->
                CatalogChoiceItem(
                    id = g.codigo,
                    title = "${g.nombre} (${g.codigo})",
                    subtitle = "${g.tipoSistema.ifBlank { "Producción" }} • Cap: ${g.capacidad}",
                    badge = g.codigo,
                    category = g.tipoSistema.ifBlank { "General" }
                )
            }
            else -> emptyList()
        }
    }

    val allJaulasConGalpon = remember(jaulasCatalog, catalogJaulas, jaulasDisponibles) {
        when {
            jaulasCatalog.isNotEmpty() -> jaulasCatalog.map { j ->
                j.galponCodigo to CatalogChoiceItem(
                    id = j.codigo,
                    title = "${j.nombre} (${j.codigo})",
                    subtitle = "${j.galponNombre.ifBlank { j.galponCodigo }} • ${j.etapaZootecnica.ifBlank { "General" }}",
                    badge = "${j.ocupacionActual}/${j.capacidadMaxima}",
                    category = j.galponNombre.ifBlank { j.galponCodigo }
                )
            }
            catalogJaulas.isNotEmpty() -> catalogJaulas.map { j ->
                j.galponCodigo to CatalogChoiceItem(
                    id = j.codigo,
                    title = "${j.nombre} (${j.codigo})",
                    subtitle = "${j.galponNombre.ifBlank { j.galponCodigo }} • ${j.etapaZootecnica.ifBlank { "General" }}",
                    badge = "${j.ocupacionActual}/${j.capacidadMaxima}",
                    category = j.galponNombre.ifBlank { j.galponCodigo }
                )
            }
            else -> jaulasDisponibles.map { j ->
                "" to CatalogChoiceItem(
                    id = j.codigo,
                    title = j.codigo,
                    subtitle = "${j.nombre} • ${j.etapa}",
                    badge = "${j.ocupacionActual}/${j.capacidadMaxima}",
                    category = j.etapa.ifBlank { "General" }
                )
            }
        }
    }

    val lineasOpciones = remember(clinicosCatalog, catalogLineas) {
        val fromRoom = clinicosCatalog.filter { it.tipoCatalogo == "LINEA_GENETICA" }.map {
            CatalogChoiceItem(
                id = it.nombre,
                title = it.nombre,
                subtitle = it.subcategoria.ifBlank { "Línea Genética" },
                badge = it.codigo,
                category = it.subcategoria.ifBlank { "Genética" }
            )
        }
        when {
            fromRoom.isNotEmpty() -> fromRoom
            catalogLineas.isNotEmpty() -> catalogLineas.map { linea ->
                CatalogChoiceItem(
                    id = linea,
                    title = linea,
                    subtitle = "Línea Genética / Raza registrada",
                    category = "Genética"
                )
            }
            else -> listOf(
                "Topigs Norsvin TN70",
                "PIC Camborough",
                "Landrace",
                "Yorkshire",
                "Duroc",
                "F1 (Landrace x Yorkshire)"
            ).map { linea ->
                CatalogChoiceItem(id = linea, title = linea, subtitle = "Línea Genética estándar", category = "Genética")
            }
        }
    }

    val etapasOpciones = remember(clinicosCatalog) {
        clinicosCatalog.filter { it.tipoCatalogo == "ETAPA_ZOOTECNICA" }
            .map { it.nombre }
            .ifEmpty { listOf("Lactancia", "Precebo", "Crecimiento", "Engorde", "Reemplazo", "Gestación") }
    }

    val initialGalpon = remember(cerdoActual, allJaulasConGalpon, galponesOpciones, uiPrefs) {
        val fromJaula = cerdoActual?.jaula?.let { jCode ->
            allJaulasConGalpon.firstOrNull { it.second.id.equals(jCode, ignoreCase = true) }?.first
        }.orEmpty()
        when {
            fromJaula.isNotBlank() -> fromJaula
            uiPrefs.galponPredeterminado.isNotBlank() -> uiPrefs.galponPredeterminado
            else -> galponesOpciones.firstOrNull()?.id ?: ""
        }
    }

    var galponSeleccionado by remember { mutableStateOf(initialGalpon) }
    val jaulasFiltradas = remember(allJaulasConGalpon, galponSeleccionado) {
        val byGalpon = if (galponSeleccionado.isNotBlank()) {
            allJaulasConGalpon.filter { it.first.equals(galponSeleccionado, ignoreCase = true) }
        } else {
            emptyList()
        }
        (if (byGalpon.isNotEmpty()) byGalpon else allJaulasConGalpon).map { it.second }
    }

    var crotal by remember { mutableStateOf(cerdoActual?.crotal ?: "CR-") }
    var rfid by remember { mutableStateOf(cerdoActual?.rfid ?: "") }
    var sexo by remember { mutableStateOf(cerdoActual?.sexo ?: "Hembra") }
    var raza by remember { mutableStateOf(cerdoActual?.raza ?: lineasOpciones.firstOrNull()?.id ?: "Topigs Norsvin TN70") }
    var pesoStr by remember { mutableStateOf(cerdoActual?.pesoKg?.toString() ?: "12.0") }
    var diasVidaInput by remember { mutableStateOf(cerdoActual?.diasVida ?: 35) }
    var jaulaSeleccionada by remember {
        mutableStateOf(
            cerdoActual?.jaula
                ?: jaulasFiltradas.firstOrNull()?.id
                ?: jaulasDisponibles.firstOrNull()?.codigo
                ?: "C-PREC-01"
        )
    }

    var showGalponSheet by remember { mutableStateOf(false) }
    var showJaulaSheet by remember { mutableStateOf(false) }
    var showRazaSheet by remember { mutableStateOf(false) }

    val pesoDouble = pesoStr.toDoubleOrNull() ?: 10.0
    val fechaCalculadaLong = remember(diasVidaInput) {
        System.currentTimeMillis() - (diasVidaInput * 24L * 60L * 60L * 1000L)
    }

    // Cerdo temporal para ver el cálculo reactivo instantáneo en la pantalla
    val previewCerdo = remember(crotal, rfid, sexo, raza, jaulaSeleccionada, fechaCalculadaLong, pesoDouble) {
        CerdoZootecnicoItem(
            id = cerdoActual?.id ?: UUID.randomUUID().toString(),
            crotal = crotal,
            rfid = rfid,
            sexo = sexo,
            raza = raza,
            jaula = jaulaSeleccionada,
            fechaNacimientoLong = fechaCalculadaLong,
            pesoKg = pesoDouble
        )
    }

    val selectedGalponItem = galponesOpciones.firstOrNull { it.id.equals(galponSeleccionado, ignoreCase = true) }
    val selectedJaulaItem = jaulasFiltradas.firstOrNull { it.id.equals(jaulaSeleccionada, ignoreCase = true) }
        ?: allJaulasConGalpon.firstOrNull { it.second.id.equals(jaulaSeleccionada, ignoreCase = true) }?.second
    val selectedRazaItem = lineasOpciones.firstOrNull { it.id.equals(raza, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Pets, contentDescription = null, tint = FieldPrimary)
                Text(
                    text = if (cerdoActual != null) "Editar Cerdo ${cerdoActual.crotal}" else "Registrar Nuevo Cerdo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = crotal,
                        onValueChange = { crotal = it.uppercase() },
                        label = { Text(if (cerdoActual != null) "Crotal / Arete (Bloqueado)" else "Crotal / Arete *") },
                        enabled = cerdoActual == null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    OutlinedTextField(
                        value = rfid,
                        onValueChange = { rfid = it },
                        label = { Text("RFID / Microchip") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                item {
                    Text("Sexo:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FieldTextPrimary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Hembra", "Macho", "Castrado").forEach { s ->
                            FilterChip(
                                selected = sexo == s,
                                onClick = { sexo = s },
                                label = { Text(s, fontSize = 11.sp, maxLines = 1) }
                            )
                        }
                    }
                }

                if (galponesOpciones.isNotEmpty()) {
                    item {
                        DatabaseSelectorField(
                            label = "Galpón / Nave",
                            selectedTitle = selectedGalponItem?.title ?: galponSeleccionado,
                            selectedSubtitle = selectedGalponItem?.subtitle ?: "",
                            selectedBadge = selectedGalponItem?.badge ?: "",
                            placeholder = "Seleccionar galpón de la granja...",
                            leadingIcon = Icons.Default.Warehouse,
                            compact = true,
                            onClick = { showGalponSheet = true }
                        )
                    }
                }

                item {
                    DatabaseSelectorField(
                        label = "Jaula / Corral Asignado *",
                        selectedTitle = selectedJaulaItem?.title ?: jaulaSeleccionada,
                        selectedSubtitle = selectedJaulaItem?.subtitle ?: "",
                        selectedBadge = selectedJaulaItem?.badge ?: "",
                        placeholder = "Seleccionar jaula/corral...",
                        leadingIcon = Icons.Default.GridView,
                        compact = true,
                        onClick = { showJaulaSheet = true }
                    )
                }

                item {
                    DatabaseSelectorField(
                        label = "Línea Genética / Raza *",
                        selectedTitle = selectedRazaItem?.title ?: raza,
                        selectedSubtitle = selectedRazaItem?.subtitle ?: "",
                        selectedBadge = selectedRazaItem?.badge ?: "",
                        placeholder = "Seleccionar genética...",
                        leadingIcon = Icons.Default.Biotech,
                        compact = true,
                        onClick = { showRazaSheet = true }
                    )
                }

                item {
                    Text("Etapa Zootécnica Sugerida:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FieldTextPrimary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        etapasOpciones.forEach { etapa ->
                            val isMatched = previewCerdo.etapaClasificada.contains(etapa.take(5), ignoreCase = true)
                            FilterChip(
                                selected = isMatched,
                                onClick = {
                                    diasVidaInput = when {
                                        etapa.contains("Lactan", ignoreCase = true) || etapa.contains("Mater", ignoreCase = true) -> 10
                                        etapa.contains("Preceb", ignoreCase = true) -> 35
                                        etapa.contains("Crecim", ignoreCase = true) -> 80
                                        etapa.contains("Engor", ignoreCase = true) || etapa.contains("Ceba", ignoreCase = true) -> 130
                                        etapa.contains("Reemp", ignoreCase = true) -> 190
                                        else -> 260
                                    }
                                },
                                label = { Text(etapa, fontSize = 11.sp, maxLines = 1) }
                            )
                        }
                    }
                }

                item {
                    // Control de Días de Nacido
                    Text("Días de Nacido (Edad Actual):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FieldTextPrimary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { if (diasVidaInput > 1) diasVidaInput -= 1 }) {
                            Icon(Icons.Default.Remove, contentDescription = "-1 d")
                        }
                        Text(
                            text = "$diasVidaInput días",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = FieldPrimary
                        )
                        IconButton(onClick = { diasVidaInput += 1 }) {
                            Icon(Icons.Default.Add, contentDescription = "+1 d")
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = pesoStr,
                        onValueChange = { pesoStr = it },
                        label = { Text("Peso Corporal (Kg) *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                // TARJETA DE CÁLCULO DINÁMICO EN EL MODAL
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = FieldBackground,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, FieldPrimary.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Clasificación Automática:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = FieldPrimary)
                            Text(
                                text = "Etapa: ${previewCerdo.etapaClasificada}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = FieldTextPrimary
                            )
                            Text(
                                text = "Alimento: ${previewCerdo.racionDiariaKg} kg/día en ${previewCerdo.tomasAlDia} tomas de ${previewCerdo.gramosPorToma} g",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0F766E)
                            )
                            Text(
                                text = "Próxima vacuna: ${previewCerdo.proximaVacuna.nombre} (Día ${previewCerdo.proximaVacuna.diaVida})",
                                fontSize = 11.sp,
                                color = Color(0xFFE11D48)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (crotal.isNotBlank()) {
                        onConfirm(previewCerdo, galponSeleccionado)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = FieldPrimary)
            ) {
                Text("Guardar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = FieldTextSecondary)
            }
        }
    )

    if (showGalponSheet) {
        SearchableCatalogBottomSheet(
            title = "Seleccionar Galpón",
            subtitle = "Infraestructura sincronizada desde PostgreSQL",
            items = galponesOpciones,
            selectedId = galponSeleccionado,
            onSelect = { chosen ->
                galponSeleccionado = chosen.id
                val matchingJaula = allJaulasConGalpon.firstOrNull { it.first.equals(chosen.id, ignoreCase = true) }?.second
                if (matchingJaula != null) {
                    jaulaSeleccionada = matchingJaula.id
                }
                showGalponSheet = false
            },
            onDismiss = { showGalponSheet = false }
        )
    }

    if (showJaulaSheet) {
        SearchableCatalogBottomSheet(
            title = "Seleccionar Jaula / Corral",
            subtitle = if (galponSeleccionado.isNotBlank()) "Galpón $galponSeleccionado" else "Todos los corrales activos",
            items = jaulasFiltradas,
            selectedId = jaulaSeleccionada,
            onSelect = { chosen ->
                jaulaSeleccionada = chosen.id
                val resolvedGalpon = allJaulasConGalpon.firstOrNull { it.second.id.equals(chosen.id, ignoreCase = true) }?.first
                if (!resolvedGalpon.isNullOrBlank()) {
                    galponSeleccionado = resolvedGalpon
                }
                showJaulaSheet = false
            },
            onDismiss = { showJaulaSheet = false }
        )
    }

    if (showRazaSheet) {
        SearchableCatalogBottomSheet(
            title = "Seleccionar Línea Genética / Raza",
            subtitle = "Catálogo genético de granja",
            items = lineasOpciones,
            selectedId = raza,
            onSelect = { chosen ->
                raza = chosen.id
                showRazaSheet = false
            },
            onDismiss = { showRazaSheet = false }
        )
    }
}

// Modal de Ficha Técnica Completa
@Composable
private fun FichaTecnicaModal(
    cerdo: CerdoZootecnicoItem,
    onDismiss: () -> Unit
) {
    val vacunas = remember { obtenerCalendarioVacunal() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Description, contentDescription = null, tint = FieldPrimary)
                Text("Ficha Zootécnica ${cerdo.crotal}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    // Resumen Zootécnico
                    Surface(
                        color = FieldBackground,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Edad: ${cerdo.diasVida} días | Peso: ${cerdo.pesoKg} kg", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Etapa clasificada: ${cerdo.etapaClasificada}", fontSize = 11.sp, color = FieldPrimary, fontWeight = FontWeight.Bold)
                            Text("Jaula: ${cerdo.jaula} | Sexo: ${cerdo.sexo}", fontSize = 11.sp, color = FieldTextSecondary)
                        }
                    }
                }

                item {
                    // Plan de Alimentación Dividido
                    Surface(
                        color = Color(0xFFF0FDF4),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color(0xFFBBF7D0))
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Plan de Alimentación Diario:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF166534))
                            Text("Ración: ${cerdo.racionDiariaKg} kg/día", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF15803D))
                            Text("Dividido en: ${cerdo.tomasAlDia} tomas de ${cerdo.gramosPorToma} gramos", fontSize = 11.sp, color = Color(0xFF166534))
                            Text("Horarios: ${cerdo.horariosSugeridos}", fontSize = 10.sp, color = Color(0xFF166534))
                        }
                    }
                }

                item {
                    Text("Calendario Vacunal Proyectado:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = FieldTextPrimary)
                }

                items(vacunas) { vac ->
                    val esAplicada = cerdo.diasVida > vac.diaVida + 3
                    val esUrgente = Math.abs(cerdo.diasVida - vac.diaVida) <= 3

                    Surface(
                        color = FieldSurface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, FieldBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Día ${vac.diaVida}: ${vac.nombre}", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = FieldTextPrimary)
                                Text("${vac.dosis} (${vac.via})", fontSize = 10.sp, color = FieldTextSecondary)
                            }
                            Surface(
                                color = when {
                                    esAplicada -> Color(0xFFD1FAE5)
                                    esUrgente -> Color(0xFFFEE2E2)
                                    else -> Color(0xFFE0E7FF)
                                },
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = when {
                                        esAplicada -> "Aplicada"
                                        esUrgente -> "URGENTE"
                                        else -> "Programada"
                                    },
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        esAplicada -> Color(0xFF065F46)
                                        esUrgente -> Color(0xFF991B1B)
                                        else -> Color(0xFF3730A3)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = FieldPrimary)) {
                Text("Cerrar")
            }
        }
    )
}
