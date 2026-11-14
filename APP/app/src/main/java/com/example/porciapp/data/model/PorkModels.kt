package com.example.porciapp.data.model

import java.util.UUID

data class Sow(
    val crotal: String,
    val galpon: String,
    val jaula: String,
    val estado: String,
    val raza: String,
    val gestacionDias: Int,
    val partoHistorico: String
)

data class MedicalTreatmentRecord(
    val id: String = UUID.randomUUID().toString(),
    val crotal: String,
    val medicamento: String,
    val dosis: Double,
    val unidad: String,
    val volumenFrascoInicial: Double = 100.0,
    val volumenFrascoRestante: Double = 95.0,
    val insumosUtilizados: List<String> = listOf("1 Jeringa descartable 5 ml", "1 Aguja hipodérmica 16G x 1\"", "5 ml Yodo Povidona 10%"),
    val viaAdministracion: String = "Intramuscular (IM)",
    val diagnostico: String = "Preventivo / Deficiencia neonatal",
    val fechaHora: String,
    val operario: String,
    val veterinarioResponsable: String = "",
    val matriculaVeterinario: String = "",
    val galponJaula: String = "Galpón 2 / Jaula 14",
    val costoTotalEstimadoUsd: Double = 0.69,
    val observaciones: String = "",
    val sincronizado: Boolean = false
)

enum class ReproductiveEventType(val label: String) {
    SERVICIO_IA("Monta / IA"),
    PARTO("Parto"),
    DESTETE("Destete")
}

data class ReproductiveEventRecord(
    val id: String = UUID.randomUUID().toString(),
    val crotalCerda: String,
    val tipoEvento: ReproductiveEventType,
    val fechaEvento: String,
    val lechonesVivos: Int = 0,
    val lechonesMuertos: Int = 0,
    val momificados: Int = 0,
    val lechonesDestetados: Int = 0,
    val pesoCamadaKg: Double = 0.0,
    val tipoAsistencia: String = "Natural",
    val oxitocinaAplicada: Boolean = false,
    val madreEnObservacion: Boolean = false,
    val verracoCodigo: String = "",
    val tecnicaIa: String = "IA Tradicional",
    val turnoIa: String = "AM",
    val condicionCorporal: Double = 3.0,
    val sincronizado: Boolean = false
) {
    val totalCamada: Int get() = lechonesVivos + lechonesMuertos + momificados
    val promedioPesoVivo: Double get() = if (lechonesVivos > 0) pesoCamadaKg / lechonesVivos else 0.0
}

data class FeedSupplyRecord(
    val id: String = UUID.randomUUID().toString(),
    val galponLote: String,
    val loteNumero: String,
    val animalesCount: Int,
    val tipoAlimento: String,
    val siloOrigen: String,
    val cantidadKg: Double,
    val fechaHora: String,
    val operario: String,
    val notasConsumo: List<String>,
    val observacionAdicional: String,
    val sincronizado: Boolean = false
) {
    val racionPorAnimal: Double get() = if (animalesCount > 0) cantidadKg / animalesCount else 0.0
}

data class FarmTask(
    val id: String,
    val titulo: String,
    val descripcion: String,
    val prioridad: String, // "ALTA", "NORMAL", "COMPLETADA"
    val galponEtapa: String,
    val completada: Boolean = false,
    val horaCompletada: String? = null,
    val verificadoPor: String? = null
)

data class BarnTelemetry(
    val temperatura: Double,
    val humedad: Double,
    val amoniacoPpm: Int
)

data class MedicineItem(
    val id: String,
    val nombre: String,
    val especificacion: String,
    val laboratorio: String,
    val periodoRetiroDias: Int,
    val stockViales: Int
)

data class BarnLot(
    val id: String,
    val galponNombre: String,
    val loteNombre: String,
    val animalesCount: Int,
    val etapa: String
)

data class FeedType(
    val id: String,
    val nombre: String,
    val etiqueta: String,
    val proteinaCruda: String,
    val stockSiloKg: Int
)
