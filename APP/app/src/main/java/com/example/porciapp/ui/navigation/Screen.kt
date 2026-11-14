package com.example.porciapp.ui.navigation

sealed class Screen(val title: String) {
    data object Login : Screen("Iniciar Sesión")
    data object Dashboard : Screen("Dashboard")
    data object Treatment : Screen("Nuevo Tratamiento")
    data object ReproductiveEvent : Screen("Evento Reproductivo")
    data object FeedSupply : Screen("Suministro de Alimento")
    data object Animals : Screen("Animales")
    data object Health : Screen("Sanidad")
    data object FeedList : Screen("Alimento")
    data object FieldOperations : Screen("Mano de Obra & Gastos")
    data object TasksSchedule : Screen("Agenda & Tareas")
    data object Mortality : Screen("Bajas & Mortalidad")
}
