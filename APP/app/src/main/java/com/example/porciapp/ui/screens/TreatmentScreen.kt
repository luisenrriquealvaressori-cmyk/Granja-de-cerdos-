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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
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
    val nombre: String,
    val especificacion: String,
    val volumenFrascoMl: Double,
    val stockFrascos: Int,
    val costoMlUsd: Double,
    val diasRetiro: Int
)

@Composable
fun TreatmentScreen(
    onBackClick: () -> Unit,
    activeProfile: UserProfile = UserProfile.defaultFor(UserRole.VETERINARIO),
    onSaveRecord: (com.example.porciapp.data.model.MedicalTreatmentRecord) -> Unit = {}
) {
    val context = LocalContext.current
    val catalogState by CatalogStore.get(context).catalog.collectAsState()
    var crotal by remember(catalogState.cerdas, catalogState.lotes) {
        mutableStateOf(
            catalogState.cerdas.firstOrNull()?.caravana
                ?: catalogState.lotes.firstOrNull()?.codigo
                ?: ""
        )
    }
    var galponJaula by remember(catalogState.cerdas, catalogState.lotes) {
        mutableStateOf(
            catalogState.cerdas.firstOrNull()?.let { "${it.galpon} / ${it.jaula}".trim(' ', '/') }
                ?: catalogState.lotes.firstOrNull()?.galpon
                ?: ""
        )
    }

    // Vademécum sincronizado con el catálogo del servidor + fármacos base
    val vademecum = remember(catalogState.medicamentos) {
        val base = listOf(
            MedicamentoStockItem("Hierro Dextrano 200mg/ml", "Solución Inyectable Antianémica", 100.0, 5, 0.08, 0),
            MedicamentoStockItem("Amoxicilina Trihidrato L.A.", "Antibiótico Betalactámico Larga Acción", 250.0, 3, 0.09, 14),
            MedicamentoStockItem("Tilmicosina 300mg/ml", "Macrólido Respiratorio Porcino", 100.0, 2, 0.16, 21),
            MedicamentoStockItem("Oxitocina Sintética 10 UI/ml", "Hormona Inductora y Contráctil", 50.0, 4, 0.05, 0),
            MedicamentoStockItem("Complejo B + Aminoácidos", "Estimulante Metabólico Post-Parto", 100.0, 6, 0.06, 0)
        )
        val fromCatalog = catalogState.medicamentos
            .filter { cat -> base.none { it.nombre.equals(cat.nombre, ignoreCase = true) } }
            .map { cat ->
                MedicamentoStockItem(
                    nombre = cat.nombre,
                    especificacion = "Catálogo Granja (${cat.unidad.ifBlank { "ml" }})",
                    volumenFrascoMl = 100.0,
                    stockFrascos = cat.stock.toInt().coerceAtLeast(1),
                    costoMlUsd = 0.08,
                    diasRetiro = 7
                )
            }
        fromCatalog + base
    }

    var selectedMedIndex by remember { mutableIntStateOf(0) }
    val medSeleccionado = vademecum[selectedMedIndex]

    // Dosis aplicada y peso animal para cálculo zootécnico en tiempo real
    var dose by remember { mutableDoubleStateOf(5.0) }
    var selectedUnit by remember { mutableStateOf("ml") }
    var pesoAnimalKg by remember { mutableDoubleStateOf(45.0) }
    var showDosageCalculator by remember { mutableStateOf(false) }

    // Dosis recomendada calculada automáticamente según principio activo y peso
    val dosisRecomendadaZootecnica = remember(selectedMedIndex, pesoAnimalKg) {
        when (selectedMedIndex) {
            0 -> 2.0 // Hierro neonatal (fijo)
            1 -> Math.round((pesoAnimalKg / 10.0) * 10.0) / 10.0 // Amoxicilina 1 ml/10kg
            2 -> Math.round((pesoAnimalKg / 15.0) * 10.0) / 10.0 // Tilmicosina 1 ml/15kg
            3 -> 2.0 // Oxitocina neonatal
            4 -> Math.round((pesoAnimalKg / 20.0) * 10.0) / 10.0 // Complejo B 1 ml/20kg
            else -> 3.0
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

    // Vía de Administración
    val viasAdministracion = listOf("Intramuscular (IM)", "Subcutánea (SC)", "Oral Bebedero", "Tópica", "Intravenosa (IV)")
    var selectedVia by remember { mutableStateOf("Intramuscular (IM)") }

    // Diagnóstico o Motivo Clínico
    val diagnosticos = listOf("Preventivo / Deficiencia neonatal", "Neumonía / Respiratorio", "Diarrea Neonatal", "Cojera / Artritis", "Metritis Postparto")
    var selectedDiagnostico by remember { mutableStateOf("Preventivo / Deficiencia neonatal") }

    var observations by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Timestamp inmutable de auditoría
    val timestampActual = remember {
        val sdf = SimpleDateFormat("dd MMM yyyy - HH:mm:ss", Locale.getDefault())
        sdf.format(Date())
    }

    Scaffold(
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
                        enabled = crotal.isNotBlank(),
                        onClick = {
                            if (!isSaving && !isSaved && crotal.isNotBlank()) {
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
                                        costoTotalEstimadoUsd = costoTotalTratamiento,
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
                                    text = "¡Tratamiento Guardado!",
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
                                val btnText = if (activeProfile.rol.puedeVerCostos) {
                                    "Registrar Dosis ($ ${String.format("%.2f", costoTotalTratamiento)} USD)"
                                } else {
                                    "Registrar Tratamiento (${String.format("%.1f", dose)} $selectedUnit)"
                                }
                                Text(
                                    text = btnText,
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
            contentPadding = PaddingValues(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 0. Banner si es rol Operario
            if (activeProfile.rol == UserRole.OPERARIO) {
                item {
                    RoleRestrictionWarningBanner(
                        userRole = activeProfile.rol,
                        titulo = "Protocolo de Prescripción Veterinaria",
                        mensaje = "Atención: La administración de fármacos e insumos requiere supervisión médica. Este tratamiento quedará auditado a tu nombre."
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (catalogState.lotes.isNotEmpty() || catalogState.cerdas.isNotEmpty()) {
                        Text(
                            text = "Selección rápida de lote o cerda:",
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
                            catalogState.lotes.forEach { lote ->
                                FilterChip(
                                    selected = crotal.equals(lote.codigo, ignoreCase = true),
                                    onClick = {
                                        crotal = lote.codigo
                                        galponJaula = "${lote.galpon.ifBlank { lote.etapa }} (${lote.animales} cab.)"
                                    },
                                    label = { Text("${lote.codigo} · ${lote.etapa}", fontSize = 11.sp) }
                                )
                            }
                            catalogState.cerdas.take(15).forEach { cerda ->
                                FilterChip(
                                    selected = crotal.equals(cerda.caravana, ignoreCase = true),
                                    onClick = {
                                        crotal = cerda.caravana
                                        galponJaula = listOf(cerda.galpon, cerda.jaula).filter { it.isNotBlank() }.joinToString(" / ")
                                    },
                                    label = { Text(cerda.caravana, fontSize = 11.sp) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = crotal,
                        onValueChange = { crotal = it.uppercase() },
                        label = { Text("Crotal de Cerdo / Código de Lote *") },
                        placeholder = { Text("Ej. CRD-014 o LOT-ENG-01") },
                        leadingIcon = { Icon(Icons.Default.CrueltyFree, contentDescription = null, tint = FieldPrimary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = galponJaula,
                        onValueChange = { galponJaula = it },
                        label = { Text("Galpón / Jaula (opcional)") },
                        placeholder = { Text("Ubicación en granja") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Responsable: ${activeProfile.nombre}",
                            fontSize = 11.sp,
                            color = FieldPrimary,
                            fontWeight = FontWeight.Bold
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Descuento en ml",
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Selector de medicamento
                    vademecum.forEachIndexed { index, med ->
                        val isSelected = selectedMedIndex == index
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) FieldPrimaryContainer else FieldSurfaceContainerLowest)
                                .border(
                                    BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) FieldDarkBorder else FieldOutline
                                    ),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedMedIndex = index }
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = Icons.Default.Medication,
                                        contentDescription = null,
                                        tint = if (isSelected) FieldOnPrimary else FieldPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f, fill = false)) {
                                        Text(
                                            text = med.nombre,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) FieldOnPrimary else FieldOnSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${med.especificacion} • Capacidad: ${med.volumenFrascoMl.toInt()} ml",
                                            fontSize = 11.sp,
                                            color = if (isSelected) FieldOnPrimary.copy(alpha = 0.85f) else FieldOnSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = FieldOnPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                        color = FieldPrimary
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
                                            Column {
                                                Text(
                                                    text = "Dosis clínica calculada:",
                                                    fontSize = 11.sp,
                                                    color = FieldOnSurfaceVariant
                                                )
                                                Text(
                                                    text = "$dosisRecomendadaZootecnica ml",
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = FieldGreenPositive
                                                )
                                            }

                                            TactileButton(
                                                text = "Aplicar Dosis",
                                                icon = Icons.Default.Check,
                                                minHeight = 40.dp,
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
                                        style = MaterialTheme.typography.displayLarge,
                                        fontWeight = FontWeight.Black,
                                        color = FieldPrimary
                                    )
                                    Text(
                                        text = " ml",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldPrimary
                                    )
                                }
                                Text(
                                    text = "(Inicial: ${volumenInicialFrasco.toInt()} ml - Dosis: ${String.format("%.1f", dose)} ml)",
                                    fontSize = 11.sp,
                                    color = FieldOnSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                                fontWeight = FontWeight.Bold
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = usaJeringa,
                                    onCheckedChange = { usaJeringa = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = "Jeringa Descartable (1 unidad)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface
                                    )
                                    Text(
                                        text = if (activeProfile.rol.puedeVerCostos) "Descuenta: -1 ud de $tipoJeringa ($0.12 USD)" else "Descuenta: -1 ud de $tipoJeringa",
                                        fontSize = 11.sp,
                                        color = FieldOnSurfaceVariant
                                    )
                                }
                            }
                            if (usaJeringa) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("3 ml", "5 ml", "10 ml").forEach { cap ->
                                        val isThis = tipoJeringa.contains(cap)
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isThis) FieldPrimary else FieldSurfaceContainerLowest)
                                                .border(BorderStroke(1.dp, FieldDarkBorder), RoundedCornerShape(4.dp))
                                                .clickable { tipoJeringa = "Jeringa $cap" }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = cap,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isThis) FieldSecondaryFixed else FieldOnSurface
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = usaAguja,
                                    onCheckedChange = { usaAguja = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = "Aguja Hipodérmica (1 unidad)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface
                                    )
                                    Text(
                                        text = if (activeProfile.rol.puedeVerCostos) "Descuenta: -1 ud de $tipoAguja ($0.06 USD)" else "Descuenta: -1 ud de $tipoAguja",
                                        fontSize = 11.sp,
                                        color = FieldOnSurfaceVariant
                                    )
                                }
                            }
                            if (usaAguja) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("16G", "18G", "20G").forEach { cal ->
                                        val isThis = tipoAguja.contains(cal)
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isThis) FieldPrimary else FieldSurfaceContainerLowest)
                                                .border(BorderStroke(1.dp, FieldDarkBorder), RoundedCornerShape(4.dp))
                                                .clickable { tipoAguja = "Aguja $cal x 1\"" }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = cal,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isThis) FieldSecondaryFixed else FieldOnSurface
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = usaYodo,
                                    onCheckedChange = { usaYodo = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = "Yodo Povidona 10% Tópico (${volumenYodoMl.toInt()} ml)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface
                                    )
                                    Text(
                                        text = if (activeProfile.rol.puedeVerCostos) "Desinfección previa y sellado: -${volumenYodoMl.toInt()} ml ($0.08 USD)" else "Desinfección previa y sellado: -${volumenYodoMl.toInt()} ml",
                                        fontSize = 11.sp,
                                        color = FieldOnSurfaceVariant
                                    )
                                }
                            }
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
                                    color = FieldOnPrimary
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (usaTorunda) FieldSecondaryContainer else FieldSurfaceContainerLowest)
                                .border(BorderStroke(1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                .clickable { usaTorunda = !usaTorunda }
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = usaTorunda,
                                    onCheckedChange = { usaTorunda = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(text = "Torunda Algodón", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FieldOnSurface)
                                    Text(
                                        text = if (activeProfile.rol.puedeVerCostos) "-1 ud ($0.03)" else "-1 unidad",
                                        fontSize = 10.sp,
                                        color = FieldOnSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Guantes
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (usaGuantes) FieldSecondaryContainer else FieldSurfaceContainerLowest)
                                .border(BorderStroke(1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                .clickable { usaGuantes = !usaGuantes }
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = usaGuantes,
                                    onCheckedChange = { usaGuantes = it },
                                    colors = CheckboxDefaults.colors(checkedColor = FieldPrimary)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(text = "Guantes Nitrilo", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FieldOnSurface)
                                    Text(
                                        text = if (activeProfile.rol.puedeVerCostos) "-1 par ($0.18)" else "-1 par",
                                        fontSize = 10.sp,
                                        color = FieldOnSurfaceVariant
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

                    Text(
                        text = "Vía Seleccionada:",
                        style = MaterialTheme.typography.labelSmall,
                        color = FieldOnSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        viasAdministracion.take(3).forEach { via ->
                            val isSelected = selectedVia == via
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) FieldPrimaryContainer else FieldSurfaceContainerLowest)
                                    .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                                    .clickable { selectedVia = via },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = via,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) FieldOnPrimary else FieldOnSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Diagnóstico / Motivo Clínico:",
                        style = MaterialTheme.typography.labelSmall,
                        color = FieldOnSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    diagnosticos.forEach { diag ->
                        val isSelected = selectedDiagnostico == diag
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) FieldSecondaryContainer else FieldSurfaceContainerLowest)
                                .border(BorderStroke(1.dp, FieldDarkBorder), RoundedCornerShape(6.dp))
                                .clickable { selectedDiagnostico = diag }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (isSelected) FieldPrimary else FieldOnSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = diag,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = FieldOnSurface
                                )
                            }
                        }
                    }
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    color = FieldPrimary
                                )
                            }
                            Text(
                                text = "Total: $ ${String.format("%.2f", costoTotalTratamiento)} USD",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Black,
                                color = FieldPrimary
                            )
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
