package com.example.porciapp.data.local

import com.example.porciapp.data.model.*

object MockData {
    val sampleSows = listOf(
        Sow(
            crotal = "CR-7492",
            galpon = "Galpón 2",
            jaula = "Jaula 14",
            estado = "Cerda Gestante",
            raza = "Landrace",
            gestacionDias = 114,
            partoHistorico = "Parto #3"
        ),
        Sow(
            crotal = "CR-5821",
            galpon = "Galpón Maternidad A",
            jaula = "Jaula 08",
            estado = "Parto Inminente",
            raza = "Large White",
            gestacionDias = 114,
            partoHistorico = "Parto #4"
        ),
        Sow(
            crotal = "CR-8842",
            galpon = "Galpón Maternidad A",
            jaula = "Jaula 11",
            estado = "Lactante",
            raza = "Pietrain x Duroc",
            gestacionDias = 114,
            partoHistorico = "Parto #2"
        )
    )

    val sampleMedicines = listOf(
        MedicineItem(
            id = "MED-01",
            nombre = "Hierro Dextrano 200mg/ml",
            especificacion = "Solución Inyectable",
            laboratorio = "Laboratorio AgroVet",
            periodoRetiroDias = 0,
            stockViales = 14
        ),
        MedicineItem(
            id = "MED-02",
            nombre = "Gleptoferrón 200mg/ml",
            especificacion = "Inyectable Antianémico",
            laboratorio = "VetPharma",
            periodoRetiroDias = 0,
            stockViales = 28
        ),
        MedicineItem(
            id = "MED-03",
            nombre = "Oxitetraciclina L.A. 20%",
            especificacion = "Antibiótico de amplio espectro",
            laboratorio = "Zoovet",
            periodoRetiroDias = 21,
            stockViales = 8
        )
    )

    val sampleLots = listOf(
        BarnLot(
            id = "LOT-01",
            galponNombre = "Galpón 3 — Engorde / Ceba",
            loteNombre = "Lote 22",
            animalesCount = 180,
            etapa = "ENGORDE"
        ),
        BarnLot(
            id = "LOT-02",
            galponNombre = "Galpón 1 — Maternidad Nave Norte",
            loteNombre = "Lote 08",
            animalesCount = 32,
            etapa = "MATERNIDAD"
        ),
        BarnLot(
            id = "LOT-03",
            galponNombre = "Galpón 4 — Pre-Ceba / Destete",
            loteNombre = "Lote 29",
            animalesCount = 240,
            etapa = "PRE-CEBA"
        )
    )

    val defaultFeed = FeedType(
        id = "FEED-01",
        nombre = "Crecimiento Premium - Pellet 3mm",
        etiqueta = "FÓRMULA BALANCEADA ACTIVA",
        proteinaCruda = "16.5%",
        stockSiloKg = 3400
    )

    val sampleTasks = listOf(
        FarmTask(
            id = "TASK-01",
            titulo = "Tratamiento Hierro - Camada Lote 14 (Galpón 2)",
            descripcion = "Aplicación de 2ml Gleptoferrón por lechón de 3 días (14 camadas pendientes)",
            prioridad = "ALTA",
            galponEtapa = "Galpón 2 • Maternidad",
            completada = false
        ),
        FarmTask(
            id = "TASK-02",
            titulo = "Revisión Parto Cerda #CR-8842 (Sala 3)",
            descripcion = "Monitorear expulsión placentaria y calostrado en camada de 12 lechones nacidos",
            prioridad = "OBSTETRICA",
            galponEtapa = "Sala 3 • Jaula 11",
            completada = false
        ),
        FarmTask(
            id = "TASK-03",
            titulo = "Suministro Iniciador Pre-cebo - Galpón 4",
            descripcion = "450 Kg servidos en tolvas automáticas sin incidentes registrados",
            prioridad = "NORMAL",
            galponEtapa = "Galpón 4 • Transición",
            completada = true,
            horaCompletada = "08:30 AM",
            verificadoPor = "Supervisor"
        )
    )

    val defaultTelemetry = BarnTelemetry(
        temperatura = 23.8,
        humedad = 62.0,
        amoniacoPpm = 11
    )
}
