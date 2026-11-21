package com.example.porciapp

import com.example.porciapp.data.auth.SessionManager
import com.example.porciapp.data.model.UserRole
import com.example.porciapp.data.model.WorkspaceUiPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias locales para normalización de URLs de servidor, permisos por rol y sanitización UI.
 */
class ExampleUnitTest {
    @Test
    fun normalizeUrl_renderAndLocalHosts_resolveExpectedScheme() {
        assertEquals("https://agropig-web.onrender.com", SessionManager.normalizeUrl("agropig-web.onrender.com"))
        assertEquals("https://agropig-web.onrender.com", SessionManager.normalizeUrl("http://agropig-web.onrender.com/Login"))
        assertEquals("http://192.168.0.208:5153", SessionManager.normalizeUrl("192.168.0.208:5153"))
        assertEquals("http://10.0.2.2:5153", SessionManager.normalizeUrl("10.0.2.2:5153"))
        assertEquals("https://agropig-web.onrender.com", SessionManager.normalizeUrl(""))
        assertEquals("https://agropig-web.onrender.com", SessionManager.normalizeUrl("   "))
    }

    @Test
    fun userRolePermissions_enforceRoleBoundaries() {
        assertFalse(UserRole.OPERARIO.puedeVerCostos)
        assertFalse(UserRole.OPERARIO.puedeAplicarTratamientos)
        assertFalse(UserRole.OPERARIO.puedeRegistrarPartos)
        assertFalse(UserRole.OPERARIO.puedeProgramarTareas)
        assertFalse(UserRole.OPERARIO.puedeRegistrarBajas)
        assertTrue(UserRole.OPERARIO.puedeSuministrarAlimento)

        assertFalse(UserRole.VETERINARIO.puedeVerCostos)
        assertTrue(UserRole.VETERINARIO.puedeAplicarTratamientos)
        assertTrue(UserRole.VETERINARIO.puedeRegistrarPartos)
        assertTrue(UserRole.VETERINARIO.puedeProgramarTareas)
        assertTrue(UserRole.VETERINARIO.puedeRegistrarBajas)

        assertTrue(UserRole.ADMINISTRADOR.puedeVerCostos)
        assertTrue(UserRole.ADMINISTRADOR.puedeGestionarPresupuestos)
        assertTrue(UserRole.ADMINISTRADOR.puedeProgramarTareas)
    }

    @Test
    fun workspaceUiPreferences_sanitizedForOperarioAndVeterinario_stripsRestrictedModulesAndActions() {
        val maliciousPrefs = WorkspaceUiPreferences(
            rolBase = UserRole.OPERARIO,
            modulosVisibles = listOf("RESUMEN_FINANCIERO", "TAREAS_HOY", "METRICAS_VIVO"),
            accionesRapidas = listOf("TREATMENT", "REPRODUCTIVE_EVENT", "MORTALITY", "FEED_SUPPLY"),
            navInferior = listOf("TREATMENT", "DASHBOARD", "FEED_SUPPLY", "ANIMALS"),
            galponPredeterminado = "  GALP-02  ",
            turnoPredeterminado = "",
            monedaPreferida = "USD",
            modoCompacto = true
        )

        val cleanOp = maliciousPrefs.sanitizedFor(UserRole.OPERARIO)
        assertFalse(cleanOp.modulosVisibles.contains("RESUMEN_FINANCIERO"))
        assertEquals(listOf("TAREAS_HOY", "METRICAS_VIVO"), cleanOp.modulosVisibles)
        assertFalse(cleanOp.accionesRapidas.contains("TREATMENT"))
        assertFalse(cleanOp.accionesRapidas.contains("REPRODUCTIVE_EVENT"))
        assertFalse(cleanOp.accionesRapidas.contains("MORTALITY"))
        assertEquals(listOf("FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE", "ANIMALS"), cleanOp.accionesRapidas)
        assertFalse(cleanOp.navInferior.contains("TREATMENT"))
        assertEquals("NIO", cleanOp.monedaPreferida)
        assertEquals("GALP-02", cleanOp.galponPredeterminado)
        assertEquals("TUR-MAN", cleanOp.turnoPredeterminado)

        val cleanVet = maliciousPrefs.sanitizedFor(UserRole.VETERINARIO)
        assertFalse(cleanVet.modulosVisibles.contains("RESUMEN_FINANCIERO"))
        assertFalse(cleanVet.accionesRapidas.contains("FEED_SUPPLY"))
        assertEquals("NIO", cleanVet.monedaPreferida)

        val cleanAdmin = maliciousPrefs.sanitizedFor(UserRole.ADMINISTRADOR)
        assertTrue(cleanAdmin.modulosVisibles.contains("RESUMEN_FINANCIERO"))
        assertEquals("USD", cleanAdmin.monedaPreferida)
    }
}