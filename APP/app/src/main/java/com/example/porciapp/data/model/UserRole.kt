package com.example.porciapp.data.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Roles del sistema porcino móvil. El rol lo define el servidor al iniciar sesión (JWT):
 * la app no permite cambiarlo.
 * - Administrador: acceso completo.
 * - Veterinario: partos en maternidad, tratamientos médicos y reportes sanitarios.
 * - Operario: alimentación, silos y tareas de galpón. No prescribe ni atiende partos.
 */
enum class UserRole(
    val titulo: String,
    val etiqueta: String,
    val descripcionCorta: String
) {
    ADMINISTRADOR(
        titulo = "Administrador",
        etiqueta = "Control Total",
        descripcionCorta = "Supervisión global y acceso a todos los módulos de campo."
    ),
    VETERINARIO(
        titulo = "Veterinario",
        etiqueta = "Clínica & Maternidad",
        descripcionCorta = "Registro de partos y tratamientos sanitarios."
    ),
    OPERARIO(
        titulo = "Operario",
        etiqueta = "Galpón & Alimentación",
        descripcionCorta = "Reporte de raciones, silos y tareas operativas."
    );

    val icon: ImageVector
        get() = when (this) {
            ADMINISTRADOR -> Icons.Default.AdminPanelSettings
            VETERINARIO -> Icons.Default.MedicalServices
            OPERARIO -> Icons.Default.Engineering
        }

    val puedeRegistrarPartos: Boolean
        get() = this == ADMINISTRADOR || this == VETERINARIO

    val puedeAplicarTratamientos: Boolean
        get() = this == ADMINISTRADOR || this == VETERINARIO

    val puedeSuministrarAlimento: Boolean
        get() = true

    /** Solo el Administrador puede ver costos, precios monetarios (USD/C$) y liquidaciones */
    val puedeVerCostos: Boolean
        get() = this == ADMINISTRADOR

    val puedeGestionarPresupuestos: Boolean
        get() = this == ADMINISTRADOR

    /** Solo el Administrador gestiona gastos de campo y liquidación de jornales */
    val puedeGestionarOperacionesGastos: Boolean
        get() = this == ADMINISTRADOR

    val puedeVerSanidad: Boolean
        get() = this == ADMINISTRADOR || this == VETERINARIO

    val puedeVerReproduccion: Boolean
        get() = this == ADMINISTRADOR || this == VETERINARIO

    val puedeVerAlimentacion: Boolean
        get() = true

    val puedeVerTareas: Boolean
        get() = true

    val puedeProgramarTareas: Boolean
        get() = this == ADMINISTRADOR || this == VETERINARIO

    val puedeRegistrarBajas: Boolean
        get() = this == ADMINISTRADOR || this == VETERINARIO

    companion object {
        /** Traduce el rol que entrega el servidor. Devuelve null si no se reconoce (se deniega el acceso). */
        fun fromServer(raw: String?): UserRole? = when (raw?.trim()?.lowercase()) {
            "administrador" -> ADMINISTRADOR
            "veterinario" -> VETERINARIO
            "operario" -> OPERARIO
            else -> null
        }
    }
}

/**
 * Perfil del usuario autenticado en el dispositivo (sale siempre de la sesión del servidor).
 */
data class UserProfile(
    val id: String,
    val nombre: String,
    val cargo: String,
    val rol: UserRole,
    val identificacion: String,
    val galponAsignado: String = ""
) {
    val nombreCompleto: String get() = nombre

    companion object {
        fun defaultFor(role: UserRole): UserProfile = when (role) {
            UserRole.ADMINISTRADOR -> UserProfile(
                id = "admin-default",
                nombre = "Administrador AgroPig",
                cargo = "Supervisor General",
                rol = UserRole.ADMINISTRADOR,
                identificacion = "ADM-001",
                galponAsignado = "Toda la Granja"
            )
            UserRole.VETERINARIO -> UserProfile(
                id = "vet-default",
                nombre = "Dra. Sanitaria",
                cargo = "Veterinaria Titular",
                rol = UserRole.VETERINARIO,
                identificacion = "VET-COL-9402",
                galponAsignado = "Maternidad y Sanidad"
            )
            UserRole.OPERARIO -> UserProfile(
                id = "oper-default",
                nombre = "Operario de Campo",
                cargo = "Técnico de Galpón",
                rol = UserRole.OPERARIO,
                identificacion = "OP-104",
                galponAsignado = "Galpón 1 - Ceba y Silos"
            )
        }

        /** Alias de compatibilidad */
        fun obtenerPerfilPorRol(role: UserRole): UserProfile = defaultFor(role)
    }
}

/**
 * Preferencias de interfaz personalizable según rol (sincronizadas con PostgreSQL y persistidas en SQLite).
 */
data class WorkspaceUiPreferences(
    val rolBase: UserRole = UserRole.OPERARIO,
    val modulosVisibles: List<String> = emptyList(),
    val accionesRapidas: List<String> = emptyList(),
    val navInferior: List<String> = emptyList(),
    val galponPredeterminado: String = "",
    val turnoPredeterminado: String = "TUR-MAN",
    val monedaPreferida: String = "NIO",
    val modoCompacto: Boolean = false,
    val updatedAtMs: Long = 0L
) {
    fun hasModule(key: String): Boolean = modulosVisibles.isEmpty() || modulosVisibles.contains(key)

    fun sanitizedFor(role: UserRole): WorkspaceUiPreferences {
        val def = defaultFor(role)
        val allowedModulos = allowedModulesFor(role)
        val allowedActs = allowedActionsFor(role)
        val allowedTabs = allowedNavTabsFor(role)

        val mods = modulosVisibles.filter { it in allowedModulos }.distinct().ifEmpty { def.modulosVisibles }
        val acts = accionesRapidas.filter { it in allowedActs }.distinct().take(4).toMutableList()
        for (fb in def.accionesRapidas) {
            if (acts.size < 4 && fb !in acts) acts.add(fb)
        }
        val nav = navInferior.filter { it in allowedTabs }.distinct().take(4).toMutableList()
        if ("DASHBOARD" !in nav) {
            nav.add(0, "DASHBOARD")
            while (nav.size > 4) nav.removeAt(nav.lastIndex)
        }
        for (fb in def.navInferior) {
            if (nav.size < 4 && fb !in nav) nav.add(fb)
        }

        val moneda = if (role == UserRole.ADMINISTRADOR && monedaPreferida.equals("USD", ignoreCase = true)) "USD" else "NIO"

        return copy(
            rolBase = role,
            modulosVisibles = mods,
            accionesRapidas = acts,
            navInferior = nav,
            galponPredeterminado = galponPredeterminado.trim(),
            turnoPredeterminado = turnoPredeterminado.ifBlank { def.turnoPredeterminado },
            monedaPreferida = moneda
        )
    }

    companion object {
        fun allowedModulesFor(role: UserRole): List<String> = if (role == UserRole.ADMINISTRADOR) {
            listOf("ACCIONES_RAPIDAS", "TAREAS_HOY", "METRICAS_VIVO", "SILOS_NIVEL", "BUSCADOR_CAMPO", "RESUMEN_FINANCIERO")
        } else {
            listOf("ACCIONES_RAPIDAS", "TAREAS_HOY", "METRICAS_VIVO", "SILOS_NIVEL", "BUSCADOR_CAMPO")
        }

        fun allowedActionsFor(role: UserRole): List<String> = when (role) {
            UserRole.ADMINISTRADOR -> listOf("FEED_SUPPLY", "REPRODUCTIVE_EVENT", "TREATMENT", "FIELD_OPERATIONS", "ANIMALS", "TASKS_SCHEDULE", "MORTALITY")
            UserRole.VETERINARIO -> listOf("TREATMENT", "REPRODUCTIVE_EVENT", "MORTALITY", "TASKS_SCHEDULE", "ANIMALS", "FIELD_OPERATIONS")
            UserRole.OPERARIO -> listOf("FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE", "ANIMALS")
        }

        fun allowedNavTabsFor(role: UserRole): List<String> = when (role) {
            UserRole.ADMINISTRADOR -> listOf("DASHBOARD", "FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE", "TREATMENT", "ANIMALS", "REPRODUCTIVE_EVENT")
            UserRole.VETERINARIO -> listOf("DASHBOARD", "TREATMENT", "REPRODUCTIVE_EVENT", "TASKS_SCHEDULE", "ANIMALS", "MORTALITY")
            UserRole.OPERARIO -> listOf("DASHBOARD", "FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE", "ANIMALS")
        }

        fun defaultFor(role: UserRole): WorkspaceUiPreferences = when (role) {
            UserRole.ADMINISTRADOR -> WorkspaceUiPreferences(
                rolBase = UserRole.ADMINISTRADOR,
                modulosVisibles = listOf("ACCIONES_RAPIDAS", "TAREAS_HOY", "METRICAS_VIVO", "SILOS_NIVEL", "BUSCADOR_CAMPO", "RESUMEN_FINANCIERO"),
                accionesRapidas = listOf("FEED_SUPPLY", "REPRODUCTIVE_EVENT", "TREATMENT", "FIELD_OPERATIONS"),
                navInferior = listOf("DASHBOARD", "FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE"),
                galponPredeterminado = "",
                turnoPredeterminado = "TUR-COMP",
                monedaPreferida = "NIO",
                modoCompacto = false
            )
            UserRole.VETERINARIO -> WorkspaceUiPreferences(
                rolBase = UserRole.VETERINARIO,
                modulosVisibles = listOf("ACCIONES_RAPIDAS", "TAREAS_HOY", "METRICAS_VIVO", "SILOS_NIVEL", "BUSCADOR_CAMPO"),
                accionesRapidas = listOf("TREATMENT", "REPRODUCTIVE_EVENT", "MORTALITY", "TASKS_SCHEDULE"),
                navInferior = listOf("DASHBOARD", "TREATMENT", "REPRODUCTIVE_EVENT", "TASKS_SCHEDULE"),
                galponPredeterminado = "GALP-01",
                turnoPredeterminado = "TUR-COMP",
                monedaPreferida = "NIO",
                modoCompacto = false
            )
            UserRole.OPERARIO -> WorkspaceUiPreferences(
                rolBase = UserRole.OPERARIO,
                modulosVisibles = listOf("ACCIONES_RAPIDAS", "TAREAS_HOY", "METRICAS_VIVO", "SILOS_NIVEL", "BUSCADOR_CAMPO"),
                accionesRapidas = listOf("FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE", "ANIMALS"),
                navInferior = listOf("DASHBOARD", "FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE"),
                galponPredeterminado = "",
                turnoPredeterminado = "TUR-MAN",
                monedaPreferida = "NIO",
                modoCompacto = false
            )
        }
    }
}

