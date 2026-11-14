using API.Application.Services;
using API.Domain.Entities;
using API.Infrastructure.Data;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace API.Controllers;

[ApiController]
[Route("api/v1/[controller]")]
public class PresupuestosController : ControllerBase
{
    private readonly GranjaPorcinaDbContext _context;
    private readonly BudgetEngine _budgetEngine;

    public PresupuestosController(GranjaPorcinaDbContext context, BudgetEngine budgetEngine)
    {
        _context = context;
        _budgetEngine = budgetEngine;
    }

    [HttpGet]
    public async Task<ActionResult<IEnumerable<PresupuestoAutomatico>>> GetPresupuestos()
    {
        return await _context.PresupuestosAutomaticos
            .OrderByDescending(p => p.PeriodoAnio)
            .ThenByDescending(p => p.PeriodoMes)
            .ToListAsync();
    }

    /// <summary>
    /// Recalcula el presupuesto automático en base a los lotes activos y el costo actual de insumos
    /// </summary>
    [HttpPost("recalcular")]
    public async Task<ActionResult<PresupuestoAutomatico>> RecalcularPresupuesto([FromQuery] int anio, [FromQuery] int mes)
    {
        var lotesActivos = await _context.Lotes.Where(l => l.Estado == "Activo").ToListAsync();
        
        // Obtener costo promedio ponderado del alimento en inventario
        var costoAlimento = await _context.Inventarios
            .Where(i => i.Categoria == "Alimento")
            .Select(i => (decimal?)i.CostoPromedioUnitario)
            .AverageAsync() ?? 9.80m;

        var nuevoPresupuesto = _budgetEngine.CalcularPresupuestoAlimento(anio, mes, lotesActivos, costoAlimento);

        var existing = await _context.PresupuestosAutomaticos
            .FirstOrDefaultAsync(p => p.PeriodoAnio == anio && p.PeriodoMes == mes && p.Categoria == "Alimento");

        if (existing != null)
        {
            existing.MontoEstimado = nuevoPresupuesto.MontoEstimado;
            await _context.SaveChangesAsync();
            return Ok(existing);
        }

        _context.PresupuestosAutomaticos.Add(nuevoPresupuesto);
        await _context.SaveChangesAsync();
        return Ok(nuevoPresupuesto);
    }

    /// <summary>
    /// Registrar un gasto ejecutado directamente desde el panel Offcanvas
    /// </summary>
    [HttpPost("registrar-gasto")]
    public async Task<ActionResult<PresupuestoAutomatico>> RegistrarGasto([FromBody] RegistrarGastoDto dto)
    {
        var presupuesto = await _context.PresupuestosAutomaticos.FindAsync(dto.PresupuestoId);
        if (presupuesto == null) return NotFound("Presupuesto no encontrado.");

        _budgetEngine.ActualizarEjecucion(presupuesto, dto.Monto);

        _context.Finanzas.Add(new Finanza
        {
            TipoTransaccion = "Gasto",
            Categoria = presupuesto.Categoria,
            Monto = dto.Monto,
            Fecha = DateOnly.FromDateTime(DateTime.UtcNow),
            PresupuestoId = presupuesto.Id,
            Descripcion = dto.Concepto
        });

        await _context.SaveChangesAsync();

        return Ok(presupuesto);
    }
}

public class RegistrarGastoDto
{
    public Guid PresupuestoId { get; set; }
    public string Concepto { get; set; } = string.Empty;
    public decimal Monto { get; set; }
}
