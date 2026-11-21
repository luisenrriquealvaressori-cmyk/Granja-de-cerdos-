package com.example.porciapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.window.Dialog
import com.example.porciapp.data.local.entities.CatGalponEntity
import com.example.porciapp.data.local.entities.CatHorarioEntity
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.model.WorkspaceUiPreferences
import com.example.porciapp.ui.theme.*

/**
 * Chip táctil interactivo en la barra superior que muestra el rol y abre la ficha de sesión
 */
@Composable
fun RoleSwitcherChip(
    activeRole: UserRole,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (bgColor, contentColor) = when (activeRole) {
        UserRole.ADMINISTRADOR -> Pair(FieldPrimaryContainer, FieldOnPrimary)
        UserRole.VETERINARIO -> Pair(FieldSecondaryContainer, FieldOnSecondaryContainer)
        UserRole.OPERARIO -> Pair(FieldSurfaceContainerHigh, FieldOnSurface)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = activeRole.icon,
                contentDescription = activeRole.titulo,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "ROL ACTIVO",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    color = contentColor.copy(alpha = 0.8f),
                    lineHeight = 9.sp
                )
                Text(
                    text = activeRole.titulo,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = contentColor
                )
            }
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "Mi Perfil",
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Banner de contexto del usuario y rol en el Dashboard Principal
 */
@Composable
fun RoleContextBanner(
    profile: UserProfile,
    onSwitchRoleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TactileCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = when (profile.rol) {
            UserRole.ADMINISTRADOR -> FieldPrimaryContainer.copy(alpha = 0.15f)
            UserRole.VETERINARIO -> FieldSecondaryContainer.copy(alpha = 0.35f)
            UserRole.OPERARIO -> FieldSurfaceContainerHigh
        },
        shadowOffset = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Avatar con icono de rol
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when (profile.rol) {
                                UserRole.ADMINISTRADOR -> FieldPrimaryContainer
                                UserRole.VETERINARIO -> FieldSecondaryFixed
                                UserRole.OPERARIO -> FieldOliveCTA
                            }
                        )
                        .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = profile.rol.icon,
                        contentDescription = null,
                        tint = when (profile.rol) {
                            UserRole.ADMINISTRADOR -> FieldOnPrimary
                            UserRole.VETERINARIO -> FieldPrimary
                            UserRole.OPERARIO -> FieldDarkBorder
                        },
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = profile.nombre,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(FieldPrimary)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = profile.rol.titulo.uppercase(),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = FieldSecondaryFixed
                            )
                        }
                    }

                    Text(
                        text = "${profile.cargo} • ${profile.identificacion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FieldOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = FieldPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (profile.galponAsignado.isNotBlank()) profile.galponAsignado else "Granja Santa Elena (Global)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = FieldPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Botón táctil para ver perfil o gestionar sesión
            TactileButton(
                onClick = onSwitchRoleClick,
                text = "Perfil",
                icon = Icons.Default.Badge,
                backgroundColor = FieldSurfaceContainerLowest,
                contentColor = FieldOnSurface,
                minHeight = 40.dp,
                shadowOffset = 2.dp,
                compact = true,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = FieldDarkBorder.copy(alpha = 0.25f), thickness = 1.dp)
        Spacer(modifier = Modifier.height(6.dp))

        // Descripción de responsabilidades según rol
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = FieldPrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = profile.rol.descripcionCorta,
                style = MaterialTheme.typography.labelSmall,
                color = FieldOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Modal Neo-Brutalista para ver ficha del usuario autenticado o cambiar de cuenta
 */
@Composable
fun RoleSelectionDialog(
    currentRole: UserRole,
    activeProfile: UserProfile = UserProfile.defaultFor(currentRole),
    onRoleSelected: (UserRole) -> Unit = {},
    onDismissRequest: () -> Unit,
    onLogout: (() -> Unit)? = null
) {

    Dialog(onDismissRequest = onDismissRequest) {
        TactileCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = FieldSurfaceContainerLowest,
            shadowOffset = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Cabecera del diálogo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(FieldPrimaryContainer)
                                .border(BorderStroke(1.5.dp, FieldDarkBorder), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = currentRole.icon,
                                contentDescription = null,
                                tint = FieldOnPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ficha de Turno Activo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldOnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentRole.etiqueta,
                                style = MaterialTheme.typography.labelSmall,
                                color = FieldOnSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                HorizontalDivider(color = FieldDarkBorder.copy(alpha = 0.2f))

                // Tarjeta de información del operador autenticado
                Surface(
                    color = FieldSurfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, FieldDarkBorder.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Usuario:", style = MaterialTheme.typography.labelSmall, color = FieldOnSurfaceVariant)
                            Text(
                                text = activeProfile.nombre,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldOnSurface,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Rol Asignado:", style = MaterialTheme.typography.labelSmall, color = FieldOnSurfaceVariant)
                            Text(
                                text = currentRole.titulo,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldPrimary,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cargo / Área:", style = MaterialTheme.typography.labelSmall, color = FieldOnSurfaceVariant)
                            Text(
                                text = activeProfile.cargo,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = FieldOnSurface,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Identificación:", style = MaterialTheme.typography.labelSmall, color = FieldOnSurfaceVariant)
                            Text(
                                text = activeProfile.identificacion,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = FieldOnSurface,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Granja:", style = MaterialTheme.typography.labelSmall, color = FieldOnSurfaceVariant)
                            Text(
                                text = "Granja Porcina San José",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = FieldOnSurface,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            )
                        }
                    }
                }

                // Enfoque operativo del rol
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = "ENFOQUE OPERATIVO EN CAMPO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = FieldOnSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = FieldGreenPositive,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Alimentación, Corrales, Bajas y Agenda de Tareas", style = MaterialTheme.typography.bodySmall, color = FieldOnSurface)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (currentRole.puedeAplicarTratamientos) FieldGreenPositive else FieldSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (currentRole.puedeAplicarTratamientos) "Sanidad, Vademécum y Partos (Titular)" else "Apoyo en Partos y Sanidad (Asistencia en granja)",
                            style = MaterialTheme.typography.bodySmall,
                            color = FieldOnSurface
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (currentRole.puedeVerCostos) Icons.Default.CheckCircle else Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (currentRole.puedeVerCostos) FieldGreenPositive else FieldOnSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (currentRole.puedeVerCostos) "Control de Costos, Jornales y Finanzas (C$ / USD)" else "Costos y Jornales (Reservado a Administración)",
                            style = MaterialTheme.typography.bodySmall,
                            color = FieldOnSurface
                        )
                    }
                }

                // Acciones de salida
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onLogout != null) {
                        TactileButton(
                            onClick = {
                                onDismissRequest()
                                onLogout()
                            },
                            text = "Cerrar Turno",
                            icon = Icons.Default.Logout,
                            backgroundColor = FieldErrorContainer,
                            contentColor = FieldOnErrorContainer,
                            minHeight = 46.dp,
                            compact = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TactileButton(
                        onClick = onDismissRequest,
                        text = "Aceptar",
                        backgroundColor = FieldPrimaryContainer,
                        contentColor = FieldOnPrimary,
                        minHeight = 46.dp,
                        compact = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Banner de advertencia cuando un Operario ingresa a un formulario clínico o reproductivo
 */
@Composable
fun RoleRestrictionWarningBanner(
    userRole: UserRole,
    titulo: String,
    mensaje: String,
    modifier: Modifier = Modifier
) {
    if (userRole == UserRole.OPERARIO) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(FieldOrangeWarning.copy(alpha = 0.2f))
                .border(BorderStroke(2.dp, FieldOrangeWarning), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(FieldOrangeWarning),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = FieldDarkBorder,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titulo,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = FieldDarkBorder
                    )
                    Text(
                        text = mensaje,
                        style = MaterialTheme.typography.bodySmall,
                        color = FieldDarkBorder.copy(alpha = 0.9f),
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Modal personalizable de espacio de trabajo por rol, acotado por los permisos RBAC del rol activo.
 */
@Composable
fun CustomizeWorkspaceDialog(
    activeRole: UserRole,
    currentPrefs: WorkspaceUiPreferences,
    galpones: List<CatGalponEntity>,
    turnos: List<CatHorarioEntity>,
    onSave: (WorkspaceUiPreferences) -> Unit,
    onResetDefault: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val allowedModules = remember(activeRole) { WorkspaceUiPreferences.allowedModulesFor(activeRole) }
    val allowedActions = remember(activeRole) { WorkspaceUiPreferences.allowedActionsFor(activeRole) }
    val allowedNavTabs = remember(activeRole) { WorkspaceUiPreferences.allowedNavTabsFor(activeRole) }

    var galponSel by remember(currentPrefs) { mutableStateOf(currentPrefs.galponPredeterminado) }
    var turnoSel by remember(currentPrefs) { mutableStateOf(currentPrefs.turnoPredeterminado) }
    var modulosSel by remember(currentPrefs) { mutableStateOf(currentPrefs.modulosVisibles.ifEmpty { WorkspaceUiPreferences.defaultFor(activeRole).modulosVisibles }) }
    var accionesSel by remember(currentPrefs) { mutableStateOf(currentPrefs.accionesRapidas.ifEmpty { WorkspaceUiPreferences.defaultFor(activeRole).accionesRapidas }) }
    var navSel by remember(currentPrefs) { mutableStateOf(currentPrefs.navInferior.ifEmpty { WorkspaceUiPreferences.defaultFor(activeRole).navInferior }) }
    var monedaSel by remember(currentPrefs) { mutableStateOf(if (activeRole.puedeVerCostos) currentPrefs.monedaPreferida else "NIO") }
    var modoCompacto by remember(currentPrefs) { mutableStateOf(currentPrefs.modoCompacto) }

    fun moduleLabel(key: String) = when (key) {
        "ACCIONES_RAPIDAS" -> "Accesos Rápidos (2x2)"
        "TAREAS_HOY" -> "Agenda & Tareas de Hoy"
        "METRICAS_VIVO" -> "Resumen de Granja"
        "SILOS_NIVEL" -> "Nivel de Silos & Bodega"
        "BUSCADOR_CAMPO" -> "Buscador Rápido de Campo"
        "RESUMEN_FINANCIERO" -> "Resumen Financiero (Admin)"
        else -> key
    }

    fun actionLabel(key: String) = when (key) {
        "FEED_SUPPLY" -> "Alimento"
        "REPRODUCTIVE_EVENT" -> "Partos & IA"
        "TREATMENT" -> "Sanidad"
        "FIELD_OPERATIONS" -> if (activeRole == UserRole.OPERARIO) "Labores" else "Gastos/Labores"
        "ANIMALS" -> "Animales"
        "TASKS_SCHEDULE" -> "Agenda"
        "MORTALITY" -> "Mortalidad"
        else -> key
    }

    fun navLabel(key: String) = when (key) {
        "DASHBOARD" -> "Inicio (Fijo)"
        "FEED_SUPPLY" -> "Alimento"
        "FIELD_OPERATIONS" -> if (activeRole == UserRole.OPERARIO) "Labores" else "Gastos"
        "TASKS_SCHEDULE" -> "Tareas"
        "TREATMENT" -> "Sanidad"
        "ANIMALS" -> "Cerdos"
        "REPRODUCTIVE_EVENT" -> "Partos"
        "MORTALITY" -> "Bajas"
        else -> key
    }

    Dialog(onDismissRequest = onDismissRequest) {
        TactileCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = FieldSurfaceContainerLowest,
            shadowOffset = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Personalizar Vista (${activeRole.titulo})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = FieldOnSurface
                        )
                        Text(
                            text = "Se guarda en SQLite y se sincroniza con tu cuenta.",
                            style = MaterialTheme.typography.labelSmall,
                            color = FieldOnSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                HorizontalDivider(color = FieldOutlineVariant)

                // Galpón predeterminado
                Text(
                    text = "GALPÓN PREDETERMINADO",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FieldPrimary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = galponSel.isBlank(),
                            onClick = { galponSel = "" },
                            label = { Text("Toda la Granja") }
                        )
                    }
                    items(galpones, key = { it.codigo }) { g ->
                        FilterChip(
                            selected = galponSel.equals(g.codigo, ignoreCase = true),
                            onClick = { galponSel = g.codigo },
                            label = { Text(g.nombre) }
                        )
                    }
                }

                // Turno predeterminado
                if (turnos.isNotEmpty()) {
                    Text(
                        text = "TURNO HABITUAL DE JORNADA",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = FieldPrimary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(turnos, key = { it.codigo }) { t ->
                            FilterChip(
                                selected = turnoSel.equals(t.codigo, ignoreCase = true),
                                onClick = { turnoSel = t.codigo },
                                label = { Text("${t.nombre.substringBefore(" (")} (${t.horaInicio})") }
                            )
                        }
                    }
                }

                // Secciones visibles
                Text(
                    text = "SECCIONES VISIBLES EN INICIO",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FieldPrimary
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    allowedModules.forEach { key ->
                        val checked = key in modulosSel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(FieldSurfaceContainerLow)
                                .clickable {
                                    modulosSel = if (checked) {
                                        val next = modulosSel - key
                                        if (next.isEmpty()) modulosSel else next
                                    } else modulosSel + key
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = moduleLabel(key),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = FieldOnSurface
                            )
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    modulosSel = if (on) (modulosSel + key).distinct()
                                    else (modulosSel - key).ifEmpty { modulosSel }
                                }
                            )
                        }
                    }
                }

                // 4 Acciones rápidas
                Text(
                    text = "ACCIONES RÁPIDAS FAVORITAS (${accionesSel.size}/4)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FieldPrimary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(allowedActions, key = { it }) { key ->
                        val selected = key in accionesSel
                        FilterChip(
                            selected = selected,
                            onClick = {
                                accionesSel = if (selected) {
                                    if (accionesSel.size > 1) accionesSel - key else accionesSel
                                } else {
                                    (accionesSel + key).take(4)
                                }
                            },
                            label = { Text(actionLabel(key)) }
                        )
                    }
                }

                // 4 Pestañas barra inferior
                Text(
                    text = "BARRA INFERIOR (${navSel.size}/4)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FieldPrimary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(allowedNavTabs, key = { it }) { key ->
                        val selected = key in navSel
                        FilterChip(
                            selected = selected,
                            enabled = key != "DASHBOARD",
                            onClick = {
                                if (key == "DASHBOARD") return@FilterChip
                                navSel = if (selected) {
                                    if (navSel.size > 2) navSel - key else navSel
                                } else {
                                    (navSel + key).take(4)
                                }
                            },
                            label = { Text(navLabel(key)) }
                        )
                    }
                }

                // Modo compacto + Moneda Admin
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(FieldSurfaceContainerLow)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Modo Compacto (Tarjetas densas)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = FieldOnSurface
                    )
                    Switch(checked = modoCompacto, onCheckedChange = { modoCompacto = it })
                }

                if (activeRole.puedeVerCostos) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Moneda:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurface
                        )
                        FilterChip(
                            selected = monedaSel == "NIO",
                            onClick = { monedaSel = "NIO" },
                            label = { Text("Córdobas (C$)") }
                        )
                        FilterChip(
                            selected = monedaSel == "USD",
                            onClick = { monedaSel = "USD" },
                            label = { Text("Dólares ($)") }
                        )
                    }
                }

                HorizontalDivider(color = FieldOutlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onResetDefault()
                            onDismissRequest()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Por rol", maxLines = 1)
                    }
                    Button(
                        onClick = {
                            onSave(
                                currentPrefs.copy(
                                    rolBase = activeRole,
                                    galponPredeterminado = galponSel,
                                    turnoPredeterminado = turnoSel,
                                    modulosVisibles = modulosSel,
                                    accionesRapidas = accionesSel,
                                    navInferior = navSel,
                                    monedaPreferida = monedaSel,
                                    modoCompacto = modoCompacto
                                ).sanitizedFor(activeRole)
                            )
                            onDismissRequest()
                        },
                        modifier = Modifier.weight(1.4f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Guardar Vista", maxLines = 1)
                    }
                }
            }
        }
    }
}
