package com.example.porciapp.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.porciapp.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Modifier que agrega efecto de rebote táctil (bounce micro-interaction) al presionar
 */
@Composable
fun Modifier.bounceClick(
    scaleDown: Float = 0.94f,
    onClick: (() -> Unit)? = null
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "bounceScale"
    )

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onClick
                )
            } else Modifier
        )
}

/**
 * Contador numérico con animación suave y conteo reactivo
 */
@Composable
fun AnimatedCounterText(
    targetValue: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = FieldPrimary,
    suffix: String = "",
    prefix: String = ""
) {
    val animatedValue by animateIntAsState(
        targetValue = targetValue,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "counterInt"
    )

    AnimatedContent(
        targetState = animatedValue,
        transitionSpec = {
            if (targetState > initialState) {
                (slideInVertically { height -> height } + fadeIn()) togetherWith
                        (slideOutVertically { height -> -height } + fadeOut())
            } else {
                (slideInVertically { height -> -height } + fadeIn()) togetherWith
                        (slideOutVertically { height -> height } + fadeOut())
            }.using(SizeTransform(clip = false))
        },
        label = "animatedCounter"
    ) { value ->
        Text(
            text = "$prefix$value$suffix",
            style = style,
            color = color,
            modifier = modifier
        )
    }
}

/**
 * Contador numérico para decimales (ej. kg de alimento o peso)
 */
@Composable
fun AnimatedDecimalCounterText(
    targetValue: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = FieldPrimary,
    decimals: Int = 1,
    prefix: String = "",
    suffix: String = ""
) {
    val multiplier = if (decimals == 2) 100 else if (decimals == 0) 1 else 10
    val targetInt = (targetValue * multiplier).toInt()
    val animatedInt by animateIntAsState(
        targetValue = targetInt,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "counterDecimal"
    )

    val displayValue = animatedInt.toDouble() / multiplier
    val formatted = when (decimals) {
        0 -> animatedInt.toString()
        2 -> String.format(java.util.Locale.US, "%.2f", displayValue)
        else -> String.format(java.util.Locale.US, "%.1f", displayValue)
    }

    Text(
        text = "$prefix$formatted$suffix",
        style = style,
        color = color,
        modifier = modifier
    )
}

@Composable
fun AnimatedDecimalCounterText(
    targetValue: Float,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = FieldPrimary,
    decimals: Int = 1,
    prefix: String = "",
    suffix: String = ""
) {
    AnimatedDecimalCounterText(
        targetValue = targetValue.toDouble(),
        modifier = modifier,
        style = style,
        color = color,
        decimals = decimals,
        prefix = prefix,
        suffix = suffix
    )
}

/**
 * Indicador pulsante en vivo con brillo dinámico
 */
@Composable
fun DynamicLiveIndicator(
    text: String = "En Vivo",
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FieldPrimary)
            .border(BorderStroke(1.dp, FieldDarkBorder), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(FieldSecondaryFixed.copy(alpha = alpha))
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = FieldSecondaryFixed,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Contenedor Pull-to-Refresh dinámico con indicador visual y rotación interactiva
 */
@Composable
fun DynamicPullToRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var pullDistance by remember { mutableFloatStateOf(0f) }
    val maxPullDistance = 240f
    val triggerDistance = 150f
    val coroutineScope = rememberCoroutineScope()

    val animatedOffset by animateFloatAsState(
        targetValue = if (isRefreshing) triggerDistance else pullDistance,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pullOffset"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isRefreshing) {
                if (!isRefreshing) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val resistance = 0.5f
                            pullDistance = (pullDistance + dragAmount * resistance)
                                .coerceIn(0f, maxPullDistance)
                        },
                        onDragEnd = {
                            if (pullDistance >= triggerDistance) {
                                onRefresh()
                            }
                            coroutineScope.launch {
                                pullDistance = 0f
                            }
                        },
                        onDragCancel = {
                            pullDistance = 0f
                        }
                    )
                }
            }
    ) {
        // Indicador de arrastre superior
        if (animatedOffset > 10f || isRefreshing) {
            val progress = (animatedOffset / triggerDistance).coerceIn(0f, 1f)
            val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "refreshSpin"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (animatedOffset * 0.45f).dp)
                    .zIndex(10f),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    color = FieldSurfaceContainerHighest,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, FieldDarkBorder),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refrescar",
                            tint = FieldPrimary,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(if (isRefreshing) rotation else progress * 180f)
                        )
                        Text(
                            text = when {
                                isRefreshing -> "Sincronizando con el servidor..."
                                progress >= 1f -> "¡Suelta para actualizar!"
                                else -> "Desliza para actualizar"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = FieldOnSurface
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = (animatedOffset * 0.35f).dp)
        ) {
            content()
        }
    }
}

/**
 * Tarjeta interactiva de búsqueda instantánea con resultados categorizados
 */
data class QuickSearchResult(
    val title: String,
    val subtitle: String,
    val category: String,
    val icon: ImageVector,
    val badge: String,
    val badgeColor: Color,
    val onClick: () -> Unit
)

@Composable
fun DynamicSearchResultsOverlay(
    query: String,
    results: List<QuickSearchResult>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = query.isNotBlank() && results.isNotEmpty(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = FieldSurfaceContainerHighest,
            border = BorderStroke(2.dp, FieldPrimary),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Resultados en Vivo (${results.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = FieldPrimary
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            modifier = Modifier.size(16.dp),
                            tint = FieldOnSurfaceVariant
                        )
                    }
                }

                results.take(5).forEach { result ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick {
                                result.onClick()
                                onDismiss()
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = FieldSurfaceContainerHigh,
                        border = BorderStroke(1.dp, FieldDarkBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(FieldPrimaryContainer)
                                        .border(BorderStroke(1.dp, FieldDarkBorder), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = result.icon,
                                        contentDescription = null,
                                        tint = FieldOnPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = result.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = FieldOnSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = result.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FieldOnSurfaceVariant,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(result.badgeColor.copy(alpha = 0.18f))
                                    .border(BorderStroke(1.dp, result.badgeColor), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = result.badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = result.badgeColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chip interactivo con animación de selección fluida
 */
@Composable
fun DynamicFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    badgeCount: Int? = null
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) FieldPrimaryContainer else FieldSurfaceContainerHigh,
        animationSpec = tween(250),
        label = "chipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) FieldPrimary else FieldDarkBorder,
        animationSpec = tween(250),
        label = "chipBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) FieldOnPrimary else FieldOnSurface,
        animationSpec = tween(250),
        label = "chipText"
    )

    Surface(
        modifier = modifier.bounceClick(scaleDown = 0.95f, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = textColor
            )
            if (badgeCount != null && badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) FieldSecondaryFixed else FieldPrimary)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) FieldDarkBorder else FieldSecondaryFixed
                    )
                }
            }
        }
    }
}
