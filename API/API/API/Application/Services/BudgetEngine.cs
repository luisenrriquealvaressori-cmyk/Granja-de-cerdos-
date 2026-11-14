using API.Domain.Entities;

namespace API.Application.Services;

public class BudgetEngine
{
    // Parámetros zootécnicos estándar de porcicultura moderna
    public const decimal ConsumoDiarioMaternidadKg = 6.5m; // Cerdas en lactancia
    public const decimal ConsumoDiarioPreceboKg = 1.2m;    // Lechones en recría/transición
    public const decimal ConsumoDiarioEngordeKg = 2.7m;    // Cerdos en cebo
    public const decimal GananciaMediaDiariaGramos = 780m; // GMD promedio en engorde
    public const decimal PesoPromedioSalidaRastroKg = 115m;
    public const decimal PrecioKiloCerdoEnPie = 38.50m;    // Moneda por Kg en pie

    /// <summary>
    /// Calcula el presupuesto automático proyectado de alimentación para un mes dado
    /// </summary>
    public PresupuestoAutomatico CalcularPresupuestoAlimento(
        int anio,
        int mes,
        IEnumerable<Lote> lotesActivos,
        decimal costoKiloAlimentoPromedio)
    {
        int diasDelMes = DateTime.DaysInMonth(anio, mes);
        decimal totalKilosProyectados = 0m;

        foreach (var lote in lotesActivos.Where(l => l.Estado == "Activo"))
        {
            decimal consumoPorCabeza = lote.TipoEtapa.ToUpperInvariant() switch
            {
                "MATERNIDAD" => ConsumoDiarioMaternidadKg,
                "PRECEBO" => ConsumoDiarioPreceboKg,
                "ENGORDE" => ConsumoDiarioEngordeKg,
                _ => ConsumoDiarioEngordeKg
            };

            totalKilosProyectados += lote.CantidadActual * consumoPorCabeza * diasDelMes;
        }

        decimal montoEstimado = Math.Round(totalKilosProyectados * costoKiloAlimentoPromedio, 2);

        return new PresupuestoAutomatico
        {
            PeriodoAnio = anio,
            PeriodoMes = mes,
            Categoria = "Alimento",
            MontoEstimado = montoEstimado,
            MontoEjecutado = 0m,
            Estado = "Proyectado"
        };
    }

    /// <summary>
    /// Proyecta los ingresos brutos estimados por venta de lotes próximos a finalizar engorde
    /// </summary>
    public decimal ProyectarIngresosVenta(IEnumerable<Lote> lotesListosParaVenta)
    {
        decimal totalIngresoProyectado = 0m;
        foreach (var lote in lotesListosParaVenta)
        {
            decimal kilosLote = lote.CantidadActual * PesoPromedioSalidaRastroKg;
            totalIngresoProyectado += kilosLote * PrecioKiloCerdoEnPie;
        }
        return Math.Round(totalIngresoProyectado, 2);
    }

    /// <summary>
    /// Actualiza la ejecución presupuestaria al ingresar un gasto/ingreso y evalúa la desviación > 10%
    /// </summary>
    public void ActualizarEjecucion(PresupuestoAutomatico presupuesto, decimal nuevoGasto)
    {
        presupuesto.MontoEjecutado += nuevoGasto;

        if (presupuesto.MontoEstimado > 0)
        {
            decimal desviacion = ((presupuesto.MontoEjecutado - presupuesto.MontoEstimado) / presupuesto.MontoEstimado) * 100m;
            if (desviacion > 10.0m)
            {
                presupuesto.Estado = "Desviado";
            }
            else
            {
                presupuesto.Estado = "En Curso";
            }
        }
    }
}
