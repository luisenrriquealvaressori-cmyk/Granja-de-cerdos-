package com.example.porciapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val FieldLightColorScheme = lightColorScheme(
    primary = FieldPrimary,
    onPrimary = FieldOnPrimary,
    primaryContainer = FieldPrimaryContainer,
    onPrimaryContainer = FieldOnPrimaryContainer,
    secondary = FieldSecondary,
    onSecondary = FieldOnPrimary,
    secondaryContainer = FieldSecondaryContainer,
    onSecondaryContainer = FieldOnSecondaryContainer,
    background = FieldBackground,
    onBackground = FieldOnSurface,
    surface = FieldSurface,
    onSurface = FieldOnSurface,
    surfaceVariant = FieldSurfaceContainerHighest,
    onSurfaceVariant = FieldOnSurfaceVariant,
    surfaceContainer = FieldSurfaceContainer,
    surfaceContainerHigh = FieldSurfaceContainerHigh,
    surfaceContainerHighest = FieldSurfaceContainerHighest,
    surfaceContainerLowest = FieldSurfaceContainerLowest,
    outline = FieldOutline,
    outlineVariant = FieldOutlineVariant,
    error = FieldError,
    onError = FieldOnError,
    errorContainer = FieldErrorContainer,
    onErrorContainer = FieldOnErrorContainer
)

private val FieldDarkColorScheme = darkColorScheme(
    primary = FieldOliveCTA,
    onPrimary = FieldDarkBorder,
    primaryContainer = FieldPrimaryContainer,
    onPrimaryContainer = FieldOnPrimaryContainer,
    secondary = FieldSecondaryFixed,
    onSecondary = FieldOnSecondaryFixed,
    background = FieldOnSurface,
    surface = FieldPrimary,
    onSurface = FieldBackground
)

@Composable
fun PORCIAPPTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) FieldDarkColorScheme else FieldLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}