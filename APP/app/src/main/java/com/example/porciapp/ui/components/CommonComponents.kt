package com.example.porciapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.ui.theme.*

/**
 * Tarjeta Rural Moderna con bordes redondeados suaves y contorno orgánico limpio
 */
@Composable
fun TactileCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = FieldSurfaceContainerLowest,
    borderColor: Color = FieldOutlineVariant,
    borderWidth: Dp = 1.dp,
    cornerRadius: Dp = 16.dp,
    shadowOffset: Dp = 2.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .drawBehind {
                if (shadowOffset > 0.dp) {
                    drawRoundRect(
                        color = FieldDarkBorder.copy(alpha = 0.09f),
                        topLeft = Offset(0f, shadowOffset.toPx()),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
                    )
                }
            }
            .clip(shape)
            .background(backgroundColor)
            .border(BorderStroke(borderWidth, borderColor), shape)
            .padding(14.dp)
    ) {
        Column(content = content)
    }
}

/**
 * Botón Ergonómico de Campo (apto para uso con una mano, bordes suaves de 14dp)
 */
@Composable
fun TactileButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    backgroundColor: Color = FieldOliveCTA,
    contentColor: Color = FieldOnPrimary,
    borderColor: Color = FieldDarkBorder.copy(alpha = 0.25f),
    borderWidth: Dp = 1.dp,
    minHeight: Dp = 52.dp,
    cornerRadius: Dp = 14.dp,
    shadowOffset: Dp = 2.dp,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(cornerRadius)

    val currentOffset = if (isPressed) 0.dp else shadowOffset
    val currentTranslation = if (isPressed) 1.dp else 0.dp

    Box(
        modifier = modifier
            .offset(y = currentTranslation)
            .drawBehind {
                if (!isPressed && currentOffset > 0.dp) {
                    drawRoundRect(
                        color = FieldDarkBorder.copy(alpha = 0.14f),
                        topLeft = Offset(0f, currentOffset.toPx()),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
                    )
                }
            }
            .clip(shape)
            .background(if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.5f))
            .border(BorderStroke(borderWidth, borderColor), shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (content != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                content = content
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (text != null) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = if (icon != null || trailingIcon != null) Modifier.weight(1f, fill = false) else Modifier
                    )
                }
                if (trailingIcon != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * TopBar matching the exact sub-flow header with Back button & Offline chip
 */
@Composable
fun FieldTopBar(
    title: String,
    subtitle: String,
    onBackClick: (() -> Unit)? = null,
    statusText: String = "Offline Local",
    statusIcon: ImageVector = Icons.Default.Save,
    menuIcon: ImageVector? = null,
    onMenuClick: (() -> Unit)? = null,
    syncBadgeCount: Int? = null,
    onSyncClick: (() -> Unit)? = null,
    notificationsBadgeCount: Int? = null,
    onNotificationsClick: (() -> Unit)? = null,
    showLogo: Boolean = false
) {
    Surface(
        color = FieldSurfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = FieldPrimary,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 3.dp.toPx()
                )
                drawLine(
                    color = FieldDarkBorder,
                    start = Offset(0f, size.height + 3.dp.toPx()),
                    end = Offset(size.width, size.height + 3.dp.toPx()),
                    strokeWidth = 2.dp.toPx()
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (onBackClick != null) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FieldSurfaceContainerHigh)
                            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onBackClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = FieldPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                } else if (showLogo) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(FieldSurfaceContainerLowest)
                            .border(BorderStroke(2.dp, FieldPrimary.copy(alpha = 0.5f)), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = com.example.porciapp.R.drawable.app_logo),
                            contentDescription = "Logo Porky Gestión",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                } else if (menuIcon != null && onMenuClick != null) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FieldSurfaceContainerLowest)
                            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onMenuClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = menuIcon,
                            contentDescription = "Menú",
                            tint = FieldPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = FieldOnSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = FieldPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showLogo && menuIcon != null && onMenuClick != null) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FieldSurfaceContainerLowest)
                            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onMenuClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = menuIcon,
                            contentDescription = "Menú",
                            tint = FieldPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                if (onNotificationsClick != null) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FieldSurfaceContainerHigh)
                            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onNotificationsClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notificaciones",
                            tint = FieldPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        if (notificationsBadgeCount != null && notificationsBadgeCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(FieldError)
                                    .border(BorderStroke(1.5.dp, FieldSurfaceContainerLowest), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (notificationsBadgeCount > 9) "9+" else notificationsBadgeCount.toString(),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (syncBadgeCount != null && onSyncClick != null) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FieldSurfaceContainerHigh)
                            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onSyncClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = "Sincronizar",
                            tint = FieldPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                        if (syncBadgeCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(FieldError)
                                    .border(BorderStroke(1.5.dp, FieldSurfaceContainerLowest), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = syncBadgeCount.toString(),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else if (!showLogo || (menuIcon == null && syncBadgeCount == null)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(FieldSurfaceContainerHigh)
                            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(FieldSecondary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = FieldOnSurface,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurface
                        )
                    }
                }
            }
        }
    }
}
}

/**
 * Hero Branding Card with centered official Porky Gestión logo, responsive sizing,
 * and adaptive layout taking into account different screen sizes and insets.
 */
@Composable
fun PorkyHeroBrandCard(
    activeProfile: com.example.porciapp.data.model.UserProfile,
    pendingCount: Int,
    onSwitchRoleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TactileCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = FieldSurfaceContainerLowest,
        borderColor = FieldDarkBorder,
        borderWidth = 2.dp,
        cornerRadius = 16.dp,
        shadowOffset = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Centered Logo badge with soft border & elevation
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(FieldSurfaceContainerHigh)
                    .border(BorderStroke(2.dp, FieldPrimary), RoundedCornerShape(22.dp))
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = com.example.porciapp.R.drawable.ic_porky_badge),
                    contentDescription = "Logo Porky Gestión App Móvil",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Title & Subtitle centered
            Text(
                text = "PORKY GESTIÓN",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = FieldPrimary,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text = "APP MÓVIL • CONTROL PORCINO OFFLINE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = FieldOnSurfaceVariant,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Adaptive metadata badges row (Location & Sync status)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = FieldSurfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, FieldDarkBorder.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = FieldPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Granja Porcina San José",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    color = if (pendingCount > 0) FieldWarningContainer.copy(alpha = 0.2f) else FieldGreenPositive.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (pendingCount > 0) FieldWarning else FieldGreenPositive)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (pendingCount > 0) FieldWarning else FieldGreenPositive)
                        )
                        Text(
                            text = if (pendingCount > 0) "$pendingCount en Outbox" else "100% Sincronizado",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (pendingCount > 0) FieldWarning else FieldGreenPositive
                        )
                    }
                }
            }
        }
    }
}

/**
 * Industrial Offline Pending Banner (Naranja con botón táctil)
 */
@Composable
fun IndustrialOfflineBanner(
    pendingCount: Int = 4,
    onSyncNowClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .drawBehind {
                drawRoundRect(
                    color = FieldDarkBorder,
                    topLeft = Offset(0f, 3.dp.toPx()),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx())
                )
            }
            .clip(RoundedCornerShape(8.dp))
            .background(FieldOrangeWarning)
            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(FieldDarkBorder),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = FieldBackground,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "MODO OFFLINE",
                            style = MaterialTheme.typography.labelLarge,
                            color = FieldDarkBorder
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(FieldSurfaceContainerLowest)
                                .border(BorderStroke(1.dp, FieldDarkBorder), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "$pendingCount pendientes",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = FieldDarkBorder
                            )
                        }
                    }
                    Text(
                        text = "Datos protegidos en almacenamiento local",
                        style = MaterialTheme.typography.bodySmall,
                        color = FieldDarkBorder.copy(alpha = 0.9f)
                    )
                }
            }

            TactileButton(
                onClick = onSyncNowClick,
                text = "Sincronizar Ahora",
                icon = Icons.Default.Sync,
                backgroundColor = FieldPrimaryContainer,
                contentColor = FieldOnPrimary,
                minHeight = 44.dp,
                shadowOffset = 2.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Glove-Friendly Large Stepper for numeric inputs (±0.5, ±1, ±10, etc.)
 */
@Composable
fun GloveFriendlyStepper(
    valueText: String,
    labelText: String = "Volumen Total",
    unitText: String? = null,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
    valueColor: Color = FieldPrimary
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Botón Menos [-]
        TactileButton(
            onClick = onDecrement,
            enabled = decrementEnabled,
            backgroundColor = FieldSurfaceContainerLowest,
            contentColor = FieldDarkBorder,
            minHeight = 64.dp,
            modifier = Modifier.width(76.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Disminuir",
                tint = FieldDarkBorder,
                modifier = Modifier.size(32.dp)
            )
        }

        // Display Central
        Box(
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(FieldSurfaceContainerLowest)
                .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Black,
                        color = valueColor,
                        letterSpacing = (-1).sp
                    )
                    if (unitText != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = unitText,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = FieldPrimary
                        )
                    }
                }
                Text(
                    text = labelText.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = FieldOnSurfaceVariant,
                    lineHeight = 11.sp
                )
            }
        }

        // Botón Más [+]
        TactileButton(
            onClick = onIncrement,
            enabled = incrementEnabled,
            backgroundColor = FieldPrimaryContainer,
            contentColor = FieldOnPrimary,
            minHeight = 64.dp,
            modifier = Modifier.width(76.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Aumentar",
                tint = FieldOnPrimary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * Quick increment pill button (e.g. +1, +2, +5 or +10, +50, +100)
 */
@Composable
fun QuickPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isReset: Boolean = false
) {
    val bg = when {
        isReset -> FieldErrorContainer
        isSelected -> FieldSecondaryContainer
        else -> FieldSurfaceContainerLowest
    }
    val contentColor = when {
        isReset -> FieldOnErrorContainer
        else -> FieldOnSurface
    }

    Box(
        modifier = modifier
            .height(44.dp)
            .drawBehind {
                drawRoundRect(
                    color = FieldDarkBorder,
                    topLeft = Offset(0f, 2.dp.toPx()),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx())
                )
            }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(BorderStroke(2.dp, FieldDarkBorder), RoundedCornerShape(8.dp))
            .bounceClick(scaleDown = 0.93f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

/**
 * Bottom Nav Bar with 4 primary destinations
 */
data class FieldNavTab(
    val id: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * Bottom Nav Bar dynamically tailored to the user's active role (4 primary destinations per role).
 * - Operario: Inicio, Alimento, Mis Tareas, Corrales
 * - Veterinario: Inicio, Sanidad, Maternidad, Agenda
 * - Administrador: Inicio, Alimento, Gastos, Agenda
 */
@Composable
fun FieldBottomNavBar(
    selectedTabId: String = "inicio",
    onTabSelectedById: (String) -> Unit = {},
    selectedTabIndex: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    activeRole: com.example.porciapp.data.model.UserRole = com.example.porciapp.data.model.UserRole.ADMINISTRADOR
) {
    val tabs = remember(activeRole) {
        when (activeRole) {
            com.example.porciapp.data.model.UserRole.OPERARIO -> listOf(
                FieldNavTab("inicio", "Inicio", Icons.Default.Dashboard),
                FieldNavTab("alimento", "Alimento", Icons.Default.Scale),
                FieldNavTab("labores", "Labores", Icons.Default.Engineering),
                FieldNavTab("tareas", "Tareas", Icons.Default.AssignmentTurnedIn)
            )
            com.example.porciapp.data.model.UserRole.VETERINARIO -> listOf(
                FieldNavTab("inicio", "Inicio", Icons.Default.Dashboard),
                FieldNavTab("sanidad", "Aplicar", Icons.Default.Vaccines),
                FieldNavTab("partos", "Eventos", Icons.Default.ChildCare),
                FieldNavTab("tareas", "Tareas", Icons.Default.AssignmentTurnedIn)
            )
            com.example.porciapp.data.model.UserRole.ADMINISTRADOR -> listOf(
                FieldNavTab("inicio", "Inicio", Icons.Default.Dashboard),
                FieldNavTab("alimento", "Alimento", Icons.Default.Scale),
                FieldNavTab("gastos", "Labores", Icons.Default.Payments),
                FieldNavTab("tareas", "Agenda", Icons.Default.AssignmentTurnedIn)
            )
        }
    }

    val roleAccent = when (activeRole) {
        com.example.porciapp.data.model.UserRole.OPERARIO -> RoleOperarioAccent
        com.example.porciapp.data.model.UserRole.VETERINARIO -> RoleVeterinarioAccent
        com.example.porciapp.data.model.UserRole.ADMINISTRADOR -> RoleAdminAccent
    }

    Surface(
        color = FieldSurfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = FieldOutlineVariant,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = if (selectedTabId.isNotBlank()) tab.id == selectedTabId else selectedTabIndex == index
                    val onClickAction = {
                        onTabSelectedById(tab.id)
                        onTabSelected(index)
                    }

                    if (isSelected) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(roleAccent)
                                .selectable(selected = isSelected, role = Role.Tab, onClick = onClickAction)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(selected = isSelected, role = Role.Tab, onClick = onClickAction)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                tint = FieldOnSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = FieldOnSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Banner de advertencia y auditoría operativa de campo
 */
@Composable
fun AuditWarningBanner(
    mensaje: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = FieldWarningContainer.copy(alpha = 0.25f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, FieldWarning),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = FieldWarning,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = mensaje,
                style = MaterialTheme.typography.bodySmall,
                color = FieldOnSurface
            )
        }
    }
}

