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

