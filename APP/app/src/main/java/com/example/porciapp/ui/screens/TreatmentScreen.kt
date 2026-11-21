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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.data.local.entities.CatClinicoEntity
import com.example.porciapp.data.local.entities.CatGalponEntity
import com.example.porciapp.data.local.entities.CatInsumoEntity
import com.example.porciapp.data.local.entities.CatJaulaEntity
import com.example.porciapp.data.local.entities.CerdoEntity
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.model.WorkspaceUiPreferences
import com.example.porciapp.data.repository.CatalogStore
import com.example.porciapp.ui.components.*
import com.example.porciapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Catálogo de medicamentos con su capacidad nominal de frasco y costo por ml
private data class MedicamentoStockItem(
    val sku: String,
    val nombre: String,
    val especificacion: String,
    val volumenFrascoMl: Double,
    val stockFrascos: Int,
    val costoMlUsd: Double,
    val diasRetiro: Int
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TreatmentScreen(
    onBackClick: () -> Unit,
    activeProfile: UserProfile = UserProfile.defaultFor(UserRole.VETERINARIO),
    insumosCatalog: List<CatInsumoEntity> = emptyList(),
    clinicosCatalog: List<CatClinicoEntity> = emptyList(),
    galponesCatalog: List<CatGalponEntity> = emptyList(),
    jaulasCatalog: List<CatJaulaEntity> = emptyList(),
    cerdosFromDb: List<CerdoEntity> = emptyList(),
    uiPrefs: WorkspaceUiPreferences = WorkspaceUiPreferences.defaultFor(activeProfile.rol),
    onSaveRecord: (com.example.porciapp.data.model.MedicalTreatmentRecord) -> Unit = {}
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()

    val patientItems = remember(catalogState.cerdas, cerdosFromDb, catalogState.lotes) {
        val seen = mutableSetOf<String>()
        val cerdas = catalogState.cerdas.map { c ->
            seen.add(c.caravana.uppercase())
            CatalogChoiceItem(
                id = c.caravana,
                title = "Cerda ${c.caravana}",
                subtitle = listOf(c.estado.replace('_', ' '), c.galpon, c.jaula).filter { it.isNotBlank() }.joinToString(" • "),
                badge = "CERDA",
                category = "Cerdas"
            )
        }
        val indiv = cerdosFromDb.filter { seen.add(it.codigoCrotal.uppercase()) }.map { p ->
            CatalogChoiceItem(
                id = p.codigoCrotal,
                title = "Cerdo ${p.codigoCrotal}",
                subtitle = listOf(p.raza, p.sexo, p.jaula).filter { it.isNotBlank() }.joinToString(" • "),
                badge = p.sexo.ifBlank { "CERDO" },
                category = "Cerdos Individuales"
            )
        }
        val lotes = catalogState.lotes.map { l ->
            CatalogChoiceItem(
                id = l.codigo,
                title = "Lote ${l.codigo}",
                subtitle = "${l.galpon.ifBlank { l.etapa }} • ${l.animales} animales",
                badge = l.etapa,
                category = "Lotes"
            )
        }
        cerdas + indiv + lotes
    }

    val ubicacionItems = remember(jaulasCatalog, galponesCatalog, catalogState.jaulas, catalogState.galpones) {
        val jaulasRaw = if (jaulasCatalog.isNotEmpty()) {
            jaulasCatalog.map { j ->
                val txt = listOf(j.galponNombre, j.nombre).filter { it.isNotBlank() }.joinToString(" / ")
                CatalogChoiceItem(
                    id = txt,
                    title = txt,
                    subtitle = "${j.etapaZootecnica} • Ocup. ${j.ocupacionActual}/${j.capacidadMaxima}",
                    badge = j.codigo,
                    category = "Jaulas / Corrales"
                )
            }
        } else {
            catalogState.jaulas.map { j ->
                val txt = listOf(j.galponNombre, j.nombre).filter { it.isNotBlank() }.joinToString(" / ")
                CatalogChoiceItem(
                    id = txt,
                    title = txt,
                    subtitle = "${j.etapaZootecnica} • Ocup. ${j.ocupacionActual}/${j.capacidadMaxima}",
                    badge = j.codigo,
                    category = "Jaulas / Corrales"
                )
            }
        }
        val galpRaw = if (galponesCatalog.isNotEmpty()) {
            galponesCatalog.map { g ->
                CatalogChoiceItem(
                    id = g.nombre,
                    title = g.nombre,
                    subtitle = "${g.tipoSistema} • Cap. ${g.capacidad}",
                    badge = g.codigo,
                    category = "Galpones"
                )
            }
        } else {
            catalogState.galpones.map { g ->
                CatalogChoiceItem(
                    id = g.nombre,
                    title = g.nombre,
                    subtitle = "${g.tipoSistema} • Cap. ${g.capacidad}",
                    badge = g.codigo,
                    category = "Galpones"
                )
            }
        }
        jaulasRaw + galpRaw
    }

    var usarCrotalManual by remember { mutableStateOf(false) }
    var crotal by remember(patientItems) {
        mutableStateOf(patientItems.firstOrNull()?.id ?: "")
    }
    var galponJaula by remember(catalogState.cerdas, catalogState.lotes, uiPrefs.galponPredeterminado) {
        val firstLoc = catalogState.cerdas.firstOrNull()?.let { "${it.galpon} / ${it.jaula}".trim(' ', '/') }
            ?: catalogState.lotes.firstOrNull()?.galpon
            ?: uiPrefs.galponPredeterminado
        mutableStateOf(firstLoc)
    }

    // Vademécum 100% desde PostgreSQL / SQLite (categorías MED y BIO)
    val vademecum = remember(insumosCatalog, catalogState.insumosDetalle, catalogState.medicamentos) {
        val fromRoom = insumosCatalog.filter { it.categoriaCodigo in listOf("MED", "BIO") }
        val list = when {
            fromRoom.isNotEmpty() -> fromRoom.map { med ->
                MedicamentoStockItem(
                    sku = med.sku,
                    nombre = med.nombre,
                    especificacion = "${med.categoriaCodigo} • ${med.stockActual.toInt()} ${med.unidadMedida.ifBlank { "ml" }} disp.",
                    volumenFrascoMl = if (med.nombre.contains("Amoxicilina", true)) 250.0 else if (med.nombre.contains("Oxitocina", true)) 50.0 else 100.0,
                    stockFrascos = med.stockActual.toInt().coerceAtLeast(0),
                    costoMlUsd = med.costoUnitarioUsd.takeIf { it > 0.0 } ?: 0.08,
                    diasRetiro = when {
                        med.nombre.contains("Tilmicosina", true) -> 21
                        med.nombre.contains("Amoxicilina", true) -> 14
                        med.nombre.contains("Ivermectina", true) -> 28
                        med.nombre.contains("Enrofloxacina", true) -> 10
                        med.nombre.contains("Hierro", true) || med.nombre.contains("Oxitocina", true) || med.nombre.contains("Complejo", true) -> 0
                        else -> 7
                    }
                )
            }
            catalogState.insumosDetalle.any { it.categoriaCodigo in listOf("MED", "BIO") } -> {
                catalogState.insumosDetalle.filter { it.categoriaCodigo in listOf("MED", "BIO") }.map { med ->
                    MedicamentoStockItem(
                        sku = med.sku,
                        nombre = med.nombre,
                        especificacion = "${med.categoriaCodigo} • ${med.stockActual.toInt()} ${med.unidadMedida.ifBlank { "ml" }} disp.",
                        volumenFrascoMl = 100.0,
                        stockFrascos = med.stockActual.toInt().coerceAtLeast(0),
                        costoMlUsd = med.costoUnitarioUsd.takeIf { it > 0.0 } ?: 0.08,
                        diasRetiro = 7
                    )
                }
            }
            else -> catalogState.medicamentos.map { cat ->
                MedicamentoStockItem(
                    sku = cat.nombre,
                    nombre = cat.nombre,
                    especificacion = "Catálogo Granja (${cat.unidad.ifBlank { "ml" }})",
                    volumenFrascoMl = 100.0,
                    stockFrascos = cat.stock.toInt().coerceAtLeast(0),
                    costoMlUsd = 0.08,
                    diasRetiro = 7
                )
            }
        }
        list
    }

    val kitCatalog = remember(insumosCatalog, catalogState.insumosDetalle) {
        val roomKit = insumosCatalog.filter { it.categoriaCodigo == "KIT" }
        if (roomKit.isNotEmpty()) roomKit else catalogState.insumosDetalle.filter { it.categoriaCodigo == "KIT" }.map {
            CatInsumoEntity(
                sku = it.sku, id = it.id, nombre = it.nombre, categoriaCodigo = it.categoriaCodigo,
                unidadMedida = it.unidadMedida, stockActual = it.stockActual, stockMinimo = it.stockMinimo,
                costoUnitarioUsd = it.costoUnitarioUsd, siloCodigo = it.siloCodigo
            )
        }
    }

    var selectedMedIndex by remember { mutableIntStateOf(0) }
    val safeMedIndex = if (vademecum.isNotEmpty()) selectedMedIndex.coerceIn(0, vademecum.lastIndex) else 0
    val medSeleccionado = vademecum.getOrNull(safeMedIndex) ?: MedicamentoStockItem(
        sku = "",
        nombre = "Sin medicamentos sincronizados",
        especificacion = "Sincroniza el catálogo en Inicio para cargar vademécum",
        volumenFrascoMl = 100.0,
        stockFrascos = 0,
        costoMlUsd = 0.0,
        diasRetiro = 0
    )

    // Dosis aplicada y peso animal para cálculo zootécnico en tiempo real
    var dose by remember { mutableDoubleStateOf(5.0) }
    var selectedUnit by remember { mutableStateOf("ml") }
    var pesoAnimalKg by remember { mutableDoubleStateOf(45.0) }
    var showDosageCalculator by remember { mutableStateOf(false) }

    // Dosis recomendada calculada automáticamente según principio activo y peso
    val dosisRecomendadaZootecnica = remember(medSeleccionado.nombre, pesoAnimalKg) {
        val nom = medSeleccionado.nombre.lowercase()
        when {
            nom.contains("hierro") -> 2.0
            nom.contains("amoxicilina") -> Math.round((pesoAnimalKg / 10.0) * 10.0) / 10.0
            nom.contains("tilmicosina") -> Math.round((pesoAnimalKg / 15.0) * 10.0) / 10.0
            nom.contains("oxitocina") -> 2.0
            nom.contains("complejo b") -> Math.round((pesoAnimalKg / 20.0) * 10.0) / 10.0
            else -> Math.round((pesoAnimalKg / 15.0) * 10.0) / 10.0
        }.coerceAtLeast(0.5)
    }

    // Fecha proyectada de finalización de periodo de retiro
    val fechaFinRetiro = remember(medSeleccionado.diasRetiro) {
        if (medSeleccionado.diasRetiro > 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, medSeleccionado.diasRetiro)
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale("es", "ES"))
            sdf.format(cal.time)
        } else "Sin periodo de restricción (0 días)"
    }

    // Saldo dinámico del frasco
    val volumenInicialFrasco = medSeleccionado.volumenFrascoMl
    val volumenRestanteFrasco = Math.max(0.0, volumenInicialFrasco - dose)
    val porcentajeRestanteFrasco = if (volumenInicialFrasco > 0) (volumenRestanteFrasco / volumenInicialFrasco).toFloat() else 0f

    // Insumos Clínicos del Kit de Aplicación
    var usaJeringa by remember { mutableStateOf(true) }
    var tipoJeringa by remember { mutableStateOf("Jeringa 5 ml") }

    var usaAguja by remember { mutableStateOf(true) }
    var tipoAguja by remember { mutableStateOf("Aguja 16G x 1\"") }

    var usaYodo by remember { mutableStateOf(true) }
    var volumenYodoMl by remember { mutableDoubleStateOf(5.0) }

    var usaTorunda by remember { mutableStateOf(true) }
    var usaGuantes by remember { mutableStateOf(false) }

    // Costo total de medicamento + insumos
    val costoMedicamento = dose * medSeleccionado.costoMlUsd
    val costoJeringa = if (usaJeringa) 0.12 else 0.0
    val costoAguja = if (usaAguja) 0.06 else 0.0
    val costoYodo = if (usaYodo) (volumenYodoMl * 0.015) else 0.0
    val costoTorunda = if (usaTorunda) 0.03 else 0.0
    val costoGuantes = if (usaGuantes) 0.18 else 0.0
    val costoTotalTratamiento = costoMedicamento + costoJeringa + costoAguja + costoYodo + costoTorunda + costoGuantes

    // Vías y Diagnósticos desde Catálogo Clínico (SQLite / PostgreSQL)
    val viasItems = remember(clinicosCatalog, catalogState.catalogosClinicos) {
        val fromRoom = clinicosCatalog.filter { it.tipoCatalogo == "VIA_ADMINISTRACION" }.map {
            CatalogChoiceItem(
                id = it.nombre,
                title = it.nombre,
                subtitle = it.subcategoria.ifBlank { "Vía estándar" },
                badge = it.codigo
            )
        }
        if (fromRoom.isNotEmpty()) fromRoom else catalogState.catalogosClinicos.filter { it.tipoCatalogo == "VIA_ADMINISTRACION" }.map {
            CatalogChoiceItem(id = it.nombre, title = it.nombre, subtitle = it.subcategoria, badge = it.codigo)
        }.ifEmpty {
            listOf(
                CatalogChoiceItem("Intramuscular (IM)", "Intramuscular (IM)", "Tabla del cuello"),
                CatalogChoiceItem("Subcutánea (SC)", "Subcutánea (SC)", "Detrás de oreja"),
                CatalogChoiceItem("Oral Bebedero", "Oral Bebedero", "Dosificador en agua")
            )
        }
    }
    var selectedVia by remember(viasItems) {
        mutableStateOf(viasItems.firstOrNull()?.id ?: "Intramuscular (IM)")
    }

    val diagnosticosItems = remember(clinicosCatalog, catalogState.catalogosClinicos) {
        val fromRoom = clinicosCatalog.filter { it.tipoCatalogo == "DIAGNOSTICO_CLINICO" }.map {
            CatalogChoiceItem(
                id = it.nombre,
                title = it.nombre,
                subtitle = listOfNotNull(
                    it.subcategoria.takeIf { s -> s.isNotBlank() },
                    if (it.diasRetiroReferencia > 0) "Retiro ref: ${it.diasRetiroReferencia}d" else null
                ).joinToString(" • "),
                badge = it.subcategoria,
                category = it.subcategoria
            )
        }
        if (fromRoom.isNotEmpty()) fromRoom else catalogState.catalogosClinicos.filter { it.tipoCatalogo == "DIAGNOSTICO_CLINICO" }.map {
            CatalogChoiceItem(id = it.nombre, title = it.nombre, subtitle = it.subcategoria, badge = it.subcategoria, category = it.subcategoria)
        }.ifEmpty {
            listOf(
                CatalogChoiceItem("Preventivo / Deficiencia neonatal", "Preventivo / Deficiencia neonatal", "Preventivo", "PREVENTIVO", "PREVENTIVO"),
                CatalogChoiceItem("Neumonía / Respiratorio", "Neumonía / Respiratorio", "Respiratorio", "RESPIRATORIO", "RESPIRATORIO")
            )
        }
    }
    var selectedDiagnostico by remember(diagnosticosItems) {
        mutableStateOf(diagnosticosItems.firstOrNull()?.id ?: "")
    }

    var observations by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    var showPatientSheet by remember { mutableStateOf(false) }
    var showUbicacionSheet by remember { mutableStateOf(false) }
    var showMedSheet by remember { mutableStateOf(false) }
    var showDiagnosticoSheet by remember { mutableStateOf(false) }

    // Timestamp inmutable de auditoría
    val timestampActual = remember {
        val sdf = SimpleDateFormat("dd MMM yyyy - HH:mm:ss", Locale.getDefault())
        sdf.format(Date())
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                FieldTopBar(
                    title = "Nuevo Tratamiento Sanitario",
                    subtitle = "VADEMÉCUM & CONTROL DE INVENTARIO",
                    onBackClick = onBackClick,
                    statusText = "Offline Local",
                    statusIcon = Icons.Default.Save
                )
                // Sub-banner de trazabilidad del profesional
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
                            imageVector = Icons.Default.MedicalServices,
                            contentDescription = null,
                            tint = FieldPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Aplicador: ${activeProfile.nombre}",
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
                            .background(FieldSecondaryContainer)
                            .border(BorderStroke(1.dp, FieldDarkBorder), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = activeProfile.identificacion,
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldOnSecondaryContainer,
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
                        enabled = activeProfile.rol.puedeAplicarTratamientos && crotal.isNotBlank() && vademecum.isNotEmpty(),
                        onClick = {
                            if (!isSaving && !isSaved && activeProfile.rol.puedeAplicarTratamientos && crotal.isNotBlank() && vademecum.isNotEmpty()) {
                                isSaving = true
                                coroutineScope.launch {
                                    val insumosLista = mutableListOf<String>().apply {
                                        add("${String.format("%.1f", dose)} ml de ${medSeleccionado.nombre} (Saldo Frasco: ${String.format("%.1f", volumenRestanteFrasco)} ml)")
                                        if (usaJeringa) add("1 ud $tipoJeringa")
                                        if (usaAguja) add("1 ud $tipoAguja")
                                        if (usaYodo) add("${String.format("%.1f", volumenYodoMl)} ml Yodo Povidona 10% Antiséptico")
                                        if (usaTorunda) add("1 ud Torunda de algodón / gasa")
                                        if (usaGuantes) add("1 par Guantes examinación")
                                    }

                                    val record = com.example.porciapp.data.model.MedicalTreatmentRecord(
                                        crotal = crotal,
                                        medicamento = medSeleccionado.nombre,
                                        dosis = dose,
                                        unidad = selectedUnit,
                                        volumenFrascoInicial = volumenInicialFrasco,
                                        volumenFrascoRestante = volumenRestanteFrasco,
                                        insumosUtilizados = insumosLista,
                                        viaAdministracion = selectedVia,
                                        diagnostico = selectedDiagnostico,
                                        fechaHora = timestampActual,
                                        operario = activeProfile.nombre,
                                        veterinarioResponsable = activeProfile.nombre,
                                        matriculaVeterinario = activeProfile.identificacion,
                                        galponJaula = galponJaula,
                                        costoTotalEstimadoUsd = if (activeProfile.rol.puedeVerCostos) costoTotalTratamiento else 0.0,
                                        observaciones = observations
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
                                    text = "Guardando tratamiento...",
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
                                    text = "¡Tratamiento Guardado!",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldDarkBorder,
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
                                val btnText = if (activeProfile.rol.puedeVerCostos) {
                                    "Registrar Dosis ($ ${String.format("%.2f", costoTotalTratamiento)} USD)"
                                } else {
                                    "Registrar Tratamiento (${String.format("%.1f", dose)} $selectedUnit)"
                                }
                                Text(
                                    text = btnText,
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
                contentPadding = PaddingValues(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            // 0. Banner si el rol no tiene permiso clínico de prescripción
            if (!activeProfile.rol.puedeAplicarTratamientos) {
                item {
                    RoleRestrictionWarningBanner(
                        userRole = activeProfile.rol,
                        titulo = "Modo Solo Consulta Clínica",
                        mensaje = "Solo el Veterinario o Administrador puede prescribir y registrar tratamientos sanitarios en granja."
                    )
                }
            }

            // SECCIÓN 1: IDENTIFICACIÓN DEL ANIMAL Y AUDITORÍA (CUÁNDO Y A QUIÉN)
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
                                imageVector = Icons.Default.Tag,
                                contentDescription = null,
                                tint = FieldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ANIMAL DESTINO & AUDITORÍA",
                                style = MaterialTheme.typography.labelMedium,
                                color = FieldPrimary,
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
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Certificado",
                                color = FieldOnPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val patientSel = patientItems.firstOrNull { it.id.equals(crotal, ignoreCase = true) }
                    DatabaseSelectorField(
                        label = "PACIENTE / LOTE (CATÁLOGO BD) *",
                        selectedTitle = if (!usarCrotalManual) (patientSel?.title ?: crotal) else crotal,
                        selectedSubtitle = patientSel?.subtitle ?: if (galponJaula.isNotBlank()) "Ubicación: $galponJaula" else "",
                        selectedBadge = patientSel?.badge.orEmpty(),
                        placeholder = "Seleccionar cerda, cerdo o lote...",
                        leadingIcon = Icons.Default.CrueltyFree,
                        onClick = {
                            usarCrotalManual = false
                            showPatientSheet = true
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
                            text = "Ingresar crotal nuevo manualmente",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = FieldOnSurface
                        )
                        Switch(
                            checked = usarCrotalManual,
                            onCheckedChange = { usarCrotalManual = it }
                        )
                    }

                    if (usarCrotalManual || patientItems.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = crotal,
                            onValueChange = { crotal = it.uppercase() },
                            label = { Text("Crotal de Cerdo / Código de Lote *") },
                            placeholder = { Text("Ej. CRD-014 o LOT-ENG-01") },
                            leadingIcon = { Icon(Icons.Default.CrueltyFree, contentDescription = null, tint = FieldPrimary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val ubicacionSel = ubicacionItems.firstOrNull { it.id.equals(galponJaula, ignoreCase = true) }
                    DatabaseSelectorField(
                        label = "UBICACIÓN (GALPÓN / JAULA)",
                        selectedTitle = galponJaula,
                        selectedSubtitle = ubicacionSel?.subtitle.orEmpty(),
                        selectedBadge = ubicacionSel?.badge.orEmpty(),
                        placeholder = "Seleccionar galpón o jaula...",
                        leadingIcon = Icons.Default.Apartment,
                        compact = true,
                        onClick = { showUbicacionSheet = true }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Ficha de Auditoría: Cuándo y Quién
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
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = timestampActual,
                                fontSize = 11.sp,
                                color = FieldOnSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Text(
                            text = "Responsable: ${activeProfile.nombre}",
                            fontSize = 11.sp,
                            color = FieldPrimary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                    }
                }
            }

            // SECCIÓN 2: MEDICAMENTO & CONTROL DE SALDO DEL FRASCO (100ml -> 95ml)
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
                                imageVector = Icons.Default.Vaccines,
                                contentDescription = null,
                                tint = FieldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "MEDICAMENTO & SALDO DE FRASCO",
                                style = MaterialTheme.typography.labelMedium,
                                color = FieldPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Descuento en ml",
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    DatabaseSelectorField(
                        label = "MEDICAMENTO DEL VADEMÉCUM (BD) *",
                        selectedTitle = if (vademecum.isNotEmpty()) medSeleccionado.nombre else "",
                        selectedSubtitle = if (vademecum.isNotEmpty()) {
                            "${medSeleccionado.especificacion} • Cap. ${medSeleccionado.volumenFrascoMl.toInt()} ml • Retiro ${medSeleccionado.diasRetiro}d"
                        } else "",
                        selectedBadge = medSeleccionado.sku,
                        placeholder = "Sincroniza catálogo para cargar medicamentos...",
                        leadingIcon = Icons.Default.Medication,
                        enabled = vademecum.isNotEmpty(),
                        onClick = { showMedSheet = true }
                    )

                    if (kitCatalog.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Stock descartables BD: " + kitCatalog.take(4).joinToString(" • ") {
                                "${it.nombre.substringBefore(" (")} (${it.stockActual.toInt()})"
                            },
                            fontSize = 10.sp,
                            color = FieldOnSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // HERRAMIENTA REACTIVA: CALCULADORA DE DOSIS SEGÚN PESO VIVO
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick { showDosageCalculator = !showDosageCalculator },
                        shape = RoundedCornerShape(10.dp),
                        color = FieldPrimaryContainer.copy(alpha = 0.2f),
                        border = BorderStroke(1.5.dp, FieldPrimary)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
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
                                        imageVector = Icons.Default.Calculate,
                                        contentDescription = null,
                                        tint = FieldPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Cálculo Zootécnico por Peso Vivo",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = if (showDosageCalculator) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = FieldPrimary
                                )
                            }

                            AnimatedVisibility(
                                visible = showDosageCalculator,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier.padding(top = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Peso estimado del cerdo:",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = FieldOnSurface
                                        )
                                        Text(
                                            text = "${pesoAnimalKg.toInt()} Kg",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Black,
                                            color = FieldPrimary
                                        )
                                    }

                                    Slider(
                                        value = pesoAnimalKg.toFloat(),
                                        onValueChange = { pesoAnimalKg = Math.round(it.toDouble() * 2.0) / 2.0 },
                                        valueRange = 5f..180f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = FieldPrimary,
                                            activeTrackColor = FieldPrimary,
                                            inactiveTrackColor = FieldOutlineVariant
                                        )
                                    )

                                    Surface(
                                        color = FieldSurfaceContainerLowest,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, FieldDarkBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Dosis clínica calculada:",
                                                    fontSize = 11.sp,
                                                    color = FieldOnSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "$dosisRecomendadaZootecnica ml",
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = FieldGreenPositive,
                                                    maxLines = 1
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            TactileButton(
                                                text = "Aplicar Dosis",
                                                icon = Icons.Default.Check,
                                                minHeight = 40.dp,
                                                compact = true,
                                                backgroundColor = FieldPrimary,
                                                contentColor = FieldOnPrimary,
                                                onClick = {
                                                    dose = dosisRecomendadaZootecnica
                                                    showDosageCalculator = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ALERTA DINÁMICA DE PERIODO DE RETIRO
                    if (medSeleccionado.diasRetiro > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = FieldOrangeWarning.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.5.dp, FieldOrangeWarning),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = FieldOrangeWarning,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Periodo de Retiro: ${medSeleccionado.diasRetiro} Días",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldDarkBorder
                                    )
                                    Text(
                                        text = "Carne/leche no apta para faena hasta el $fechaFinRetiro.",
                                        fontSize = 11.sp,
                                        color = FieldDarkBorder.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Dosis a aplicar (Stepper táctil)
                    Text(
                        text = "Dosis Exacta a Aplicar:",
                        style = MaterialTheme.typography.labelSmall,
                        color = FieldOnSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    GloveFriendlyStepper(
                        valueText = String.format("%.1f", dose),
                        labelText = "Volumen a Extraer del Frasco",
                        unitText = "ml",
                        onDecrement = {
                            if (dose > 0.5) dose = Math.max(0.5, Math.round((dose - 0.5) * 10.0) / 10.0)
                        },
                        onIncrement = {
                            if (dose < volumenInicialFrasco) dose = Math.round((dose + 0.5) * 10.0) / 10.0
                        },
                        decrementEnabled = dose > 0.5,
                        incrementEnabled = dose < volumenInicialFrasco
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Atajos rápidos de dosis
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickPill(text = "2.0 ml", onClick = { dose = 2.0 }, modifier = Modifier.weight(1f))
                        QuickPill(text = "3.0 ml", onClick = { dose = 3.0 }, modifier = Modifier.weight(1f))
                        QuickPill(text = "5.0 ml", onClick = { dose = 5.0 }, modifier = Modifier.weight(1f))
                        QuickPill(text = "10.0 ml", onClick = { dose = 10.0 }, modifier = Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // DISPLAY MATEMÁTICO DEL SALDO DE FRASCO (Ej: 100 ml - 5 ml = 95 ml)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(FieldSurfaceContainerLowest)
                            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SALDO RESTANTE EN FRASCO:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = FieldOnSurfaceVariant
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(FieldSecondaryFixed)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${(porcentajeRestanteFrasco * 100).toInt()}% Disponible",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldPrimary
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = String.format("%.1f", volumenRestanteFrasco),
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Black,
                                        color = FieldPrimary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = " ml",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldPrimary,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = "(Inicial: ${volumenInicialFrasco.toInt()} ml - Dosis: ${String.format("%.1f", dose)} ml)",
                                    fontSize = 11.sp,
                                    color = FieldOnSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.End,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                )
                            }

                            // Barra visual de nivel de frasco
                            LinearProgressIndicator(
                                progress = porcentajeRestanteFrasco.coerceIn(0f, 1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(BorderStroke(1.dp, FieldDarkBorder), RoundedCornerShape(4.dp)),
                                color = if (porcentajeRestanteFrasco > 0.2f) FieldPrimaryContainer else FieldError,
                                trackColor = FieldSurfaceContainer
                            )
                        }
                    }
                }
            }

            // SECCIÓN 3: KIT DE INSUMOS CLÍNICOS & MATERIAL DESCARTABLE (JERINGA, AGUJA, YODO, TORUNDA)
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
                                imageVector = Icons.Default.MedicalInformation,
                                contentDescription = null,
                                tint = FieldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "KIT DE INSUMOS & MATERIAL DESCARTABLE",
                                style = MaterialTheme.typography.labelMedium,
                                color = FieldPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(FieldPrimary)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Salidas Kardex",
                                color = FieldSecondaryFixed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Seleccione los insumos consumidos que se descontarán del inventario de farmacia:",
                        style = MaterialTheme.typography.bodySmall,
                        color = FieldOnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // 1. JERINGA DESCARTABLE
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (usaJeringa) FieldSecondaryContainer else FieldSurfaceContainerLowest)
                            .border(BorderStroke(1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .clickable { usaJeringa = !usaJeringa }
                            .padding(10.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = usaJeringa,
                                    onCheckedChange = { usaJeringa = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Jeringa Descartable (1 unidad)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (activeProfile.rol.puedeVerCostos) "Descuenta: -1 ud de $tipoJeringa ($0.12 USD)" else "Descuenta: -1 ud de $tipoJeringa",
                                        fontSize = 11.sp,
                                        color = FieldOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (usaJeringa) {
                                Row(
                                    modifier = Modifier.padding(start = 44.dp, top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("3 ml", "5 ml", "10 ml").forEach { cap ->
                                        val isThis = tipoJeringa.contains(cap)
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isThis) FieldPrimary else FieldSurfaceContainerLowest)
                                                .border(BorderStroke(1.dp, FieldDarkBorder), RoundedCornerShape(6.dp))
                                                .clickable { tipoJeringa = "Jeringa $cap" }
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = cap,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isThis) FieldSecondaryFixed else FieldOnSurface,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2. AGUJA HIPODÉRMICA VETERINARIA
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (usaAguja) FieldSecondaryContainer else FieldSurfaceContainerLowest)
                            .border(BorderStroke(1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .clickable { usaAguja = !usaAguja }
                            .padding(10.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = usaAguja,
                                    onCheckedChange = { usaAguja = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Aguja Hipodérmica (1 unidad)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (activeProfile.rol.puedeVerCostos) "Descuenta: -1 ud de $tipoAguja ($0.06 USD)" else "Descuenta: -1 ud de $tipoAguja",
                                        fontSize = 11.sp,
                                        color = FieldOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (usaAguja) {
                                Row(
                                    modifier = Modifier.padding(start = 44.dp, top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("16G", "18G", "20G").forEach { cal ->
                                        val isThis = tipoAguja.contains(cal)
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isThis) FieldPrimary else FieldSurfaceContainerLowest)
                                                .border(BorderStroke(1.dp, FieldDarkBorder), RoundedCornerShape(6.dp))
                                                .clickable { tipoAguja = "Aguja $cal x 1\"" }
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = cal,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isThis) FieldSecondaryFixed else FieldOnSurface,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 3. ANTISÉPTICO YODO POVIDONA 10%
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (usaYodo) FieldSecondaryContainer else FieldSurfaceContainerLowest)
                            .border(BorderStroke(1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .clickable { usaYodo = !usaYodo }
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = usaYodo,
                                    onCheckedChange = { usaYodo = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Yodo Povidona 10% (${volumenYodoMl.toInt()} ml)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (activeProfile.rol.puedeVerCostos) "Desinfección y sellado: -${volumenYodoMl.toInt()} ml ($0.08 USD)" else "Desinfección y sellado: -${volumenYodoMl.toInt()} ml",
                                        fontSize = 11.sp,
                                        color = FieldOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(FieldPrimaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Antiséptico",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldOnPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 4. TORUNDA DE ALGODÓN & GUANTES
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Torunda
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (usaTorunda) FieldSecondaryContainer else FieldSurfaceContainerLowest)
                                .border(BorderStroke(1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                .clickable { usaTorunda = !usaTorunda }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (usaTorunda) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = if (usaTorunda) FieldPrimary else FieldOnSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Torunda Algodón",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (activeProfile.rol.puedeVerCostos) "-1 ud ($0.03)" else "-1 unidad",
                                        fontSize = 10.sp,
                                        color = FieldOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Guantes
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (usaGuantes) FieldSecondaryContainer else FieldSurfaceContainerLowest)
                                .border(BorderStroke(1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                .clickable { usaGuantes = !usaGuantes }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (usaGuantes) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = if (usaGuantes) FieldPrimary else FieldOnSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Guantes Nitrilo",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (activeProfile.rol.puedeVerCostos) "-1 par ($0.18)" else "-1 par",
                                        fontSize = 10.sp,
                                        color = FieldOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SECCIÓN 4: VÍA DE ADMINISTRACIÓN & DIAGNÓSTICO
            item {
                TactileCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Vía de Administración & Diagnóstico",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = FieldPrimary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    val viaSel = viasItems.firstOrNull { it.id == selectedVia }
                    Text(
                        text = "VÍA DE ADMINISTRACIÓN (BD):",
                        style = MaterialTheme.typography.labelSmall,
                        color = FieldOnSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viasItems.forEach { viaItem ->
                            val isSelected = selectedVia == viaItem.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) FieldPrimaryContainer else FieldSurfaceContainerLowest)
                                    .border(BorderStroke(if (isSelected) 2.dp else 1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                    .clickable { selectedVia = viaItem.id }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = viaItem.title,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) FieldOnPrimary else FieldOnSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val diagSel = diagnosticosItems.firstOrNull { it.id == selectedDiagnostico }
                    DatabaseSelectorField(
                        label = "DIAGNÓSTICO / MOTIVO CLÍNICO (BD) *",
                        selectedTitle = diagSel?.title ?: selectedDiagnostico,
                        selectedSubtitle = diagSel?.subtitle.orEmpty(),
                        selectedBadge = diagSel?.badge.orEmpty(),
                        placeholder = "Seleccionar diagnóstico clínico...",
                        leadingIcon = Icons.Default.MedicalInformation,
                        onClick = { showDiagnosticoSheet = true }
                    )
                }
            }

            // SECCIÓN 5: DESGLOSE PRECISO DE DEDUCCIÓN EN INVENTARIO (AUDITORÍA KARDEX)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(FieldSurfaceContainerLowest)
                        .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(10.dp))
                        .padding(14.dp)
                ) {
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
                                Icon(
                                    imageVector = Icons.Default.AssignmentTurnedIn,
                                    contentDescription = null,
                                    tint = FieldPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "RESUMEN DE DEDUCCIÓN KARDEX",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = FieldPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (activeProfile.rol.puedeVerCostos) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Total: $ ${String.format("%.2f", costoTotalTratamiento)} USD",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Black,
                                    color = FieldPrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        HorizontalDivider(color = FieldDarkBorder.copy(alpha = 0.2f), thickness = 1.dp)

                        // Items detallados
                        Text(
                            text = "• ${medSeleccionado.nombre}: -${String.format("%.1f", dose)} ml (Saldo Frasco: ${String.format("%.1f", volumenRestanteFrasco)} ml)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurface
                        )
                        if (usaJeringa) {
                            Text(
                                text = "• $tipoJeringa: -1 unidad del stock de farmacia",
                                fontSize = 11.sp,
                                color = FieldOnSurfaceVariant
                            )
                        }
                        if (usaAguja) {
                            Text(
                                text = "• $tipoAguja: -1 unidad del stock de farmacia",
                                fontSize = 11.sp,
                                color = FieldOnSurfaceVariant
                            )
                        }
                        if (usaYodo) {
                            Text(
                                text = "• Yodo Povidona 10%: -${volumenYodoMl.toInt()} ml del frasco antiséptico",
                                fontSize = 11.sp,
                                color = FieldOnSurfaceVariant
                            )
                        }
                        if (usaTorunda) {
                            Text(
                                text = "• Torunda de algodón / gasa: -1 unidad",
                                fontSize = 11.sp,
                                color = FieldOnSurfaceVariant
                            )
                        }
                        if (usaGuantes) {
                            Text(
                                text = "• Guantes de examinación: -1 par",
                                fontSize = 11.sp,
                                color = FieldOnSurfaceVariant
                            )
                        }

                        HorizontalDivider(color = FieldDarkBorder.copy(alpha = 0.2f), thickness = 1.dp)

                        Text(
                            text = "Auditoría: ${activeProfile.nombre} (${activeProfile.identificacion}) • $timestampActual",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = FieldPrimary
                        )
                    }
                }
            }
        }
        }
    }

    if (showPatientSheet) {
        SearchableCatalogBottomSheet(
            title = "Seleccionar Paciente o Lote",
            subtitle = "Cerdas, cerdos individuales y lotes activos",
            items = patientItems,
            selectedId = crotal,
            categories = patientItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            emptyMessage = "No hay animales o lotes sincronizados.",
            onSelect = { item ->
                crotal = item.id
                val foundCerda = catalogState.cerdas.firstOrNull { it.caravana.equals(item.id, ignoreCase = true) }
                val foundCerdo = cerdosFromDb.firstOrNull { it.codigoCrotal.equals(item.id, ignoreCase = true) }
                val foundLote = catalogState.lotes.firstOrNull { it.codigo.equals(item.id, ignoreCase = true) }
                when {
                    foundCerda != null -> {
                        galponJaula = listOf(foundCerda.galpon, foundCerda.jaula).filter { it.isNotBlank() }.joinToString(" / ")
                    }
                    foundCerdo != null -> {
                        galponJaula = listOf(foundCerdo.idGalpon, foundCerdo.jaula).filter { it.isNotBlank() }.joinToString(" / ")
                    }
                    foundLote != null -> {
                        galponJaula = "${foundLote.galpon.ifBlank { foundLote.etapa }} (${foundLote.animales} cab.)"
                    }
                }
                showPatientSheet = false
            },
            onDismiss = { showPatientSheet = false }
        )
    }

    if (showUbicacionSheet) {
        SearchableCatalogBottomSheet(
            title = "Ubicación en Granja",
            subtitle = "Galpones y jaulas activas",
            items = ubicacionItems,
            selectedId = galponJaula,
            categories = ubicacionItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                galponJaula = item.id
                showUbicacionSheet = false
            },
            onDismiss = { showUbicacionSheet = false }
        )
    }

    if (showMedSheet) {
        val medItems = vademecum.mapIndexed { idx, med ->
            CatalogChoiceItem(
                id = idx.toString(),
                title = med.nombre,
                subtitle = "${med.especificacion} • Retiro: ${med.diasRetiro}d",
                badge = med.sku.ifBlank { "MED" },
                category = if (med.especificacion.startsWith("BIO")) "BIO" else "MED"
            )
        }
        SearchableCatalogBottomSheet(
            title = "Vademécum Farmacéutico (BD)",
            subtitle = "Medicamentos y biológicos con stock",
            items = medItems,
            selectedId = safeMedIndex.toString(),
            categories = medItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                item.id.toIntOrNull()?.let { selectedMedIndex = it }
                showMedSheet = false
            },
            onDismiss = { showMedSheet = false }
        )
    }

    if (showDiagnosticoSheet) {
        SearchableCatalogBottomSheet(
            title = "Diagnóstico Clínico (BD)",
            subtitle = "Motivos estandarizados para trazabilidad",
            items = diagnosticosItems,
            selectedId = selectedDiagnostico,
            categories = diagnosticosItems.map { it.category }.filter { it.isNotBlank() }.distinct(),
            onSelect = { item ->
                selectedDiagnostico = item.id
                showDiagnosticoSheet = false
            },
            onDismiss = { showDiagnosticoSheet = false }
        )
    }
}
