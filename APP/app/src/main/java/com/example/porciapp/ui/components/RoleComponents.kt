package com.example.porciapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.porciapp.data.model.UserProfile
import com.example.porciapp.data.model.UserRole
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
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Cabecera del diálogo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Column {
                            Text(
                                text = "Ficha de Turno Activo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = FieldOnSurface
                            )
                            Text(
                                text = currentRole.etiqueta,
                                style = MaterialTheme.typography.labelSmall,
                                color = FieldOnSurfaceVariant
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
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Usuario:", style = MaterialTheme.typography.labelSmall, color = FieldOnSurfaceVariant)
                            Text(activeProfile.nombre, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = FieldOnSurface)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Rol Asignado:", style = MaterialTheme.typography.labelSmall, color = FieldOnSurfaceVariant)
                            Text(currentRole.titulo, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = FieldPrimary)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Cargo / Área:", style = MaterialTheme.typography.labelSmall, color = FieldOnSurfaceVariant)
                            Text(activeProfile.cargo, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = FieldOnSurface)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Identificación:", style = MaterialTheme.typography.labelSmall, color = FieldOnSurfaceVariant)
                            Text(activeProfile.identificacion, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = FieldOnSurface)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Granja:", style = MaterialTheme.typography.labelSmall, color = FieldOnSurfaceVariant)
                            Text("Granja Porcina San José", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = FieldOnSurface)
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
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TactileButton(
                        onClick = onDismissRequest,
                        text = "Aceptar",
                        backgroundColor = FieldPrimaryContainer,
                        contentColor = FieldOnPrimary,
                        minHeight = 46.dp,
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
