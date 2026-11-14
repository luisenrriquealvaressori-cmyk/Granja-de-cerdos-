using API.Domain.Entities;
using API.Infrastructure.Data;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace API.Controllers;

[ApiController]
[Route("api/v1/[controller]")]
public class LotesController : ControllerBase
{
    private readonly GranjaPorcinaDbContext _context;

    public LotesController(GranjaPorcinaDbContext context)
    {
        _context = context;
    }

    [HttpGet]
    public async Task<ActionResult<IEnumerable<Lote>>> GetLotes()
    {
        return await _context.Lotes.OrderByDescending(l => l.FechaInicio).ToListAsync();
    }

    [HttpGet("{id:guid}")]
    public async Task<ActionResult<Lote>> GetLote(Guid id)
    {
        var lote = await _context.Lotes.FindAsync(id);
        if (lote == null) return NotFound();
        return lote;
    }

    [HttpPost]
    public async Task<ActionResult<Lote>> CreateLote([FromBody] CreateLoteDto dto)
    {
        // Validar formato estandarizado LOT-AAAA-MM-XX
        var regex = new System.Text.RegularExpressions.Regex(@"^LOT-\d{4}-\d{2}-\d{2}$");
        var codigo = dto.Codigo?.Trim();
        if (string.IsNullOrWhiteSpace(codigo) || !regex.IsMatch(codigo))
        {
            // Autogenerar código estandarizado si viene vacío o no cumple
            var now = DateTime.UtcNow;
            var countThisMonth = await _context.Lotes.CountAsync(l => l.FechaInicio.Year == now.Year && l.FechaInicio.Month == now.Month) + 1;
            codigo = $"LOT-{now.Year}-{now.Month:D2}-{countThisMonth:D2}";
        }

        var lote = new Lote
        {
            Codigo = codigo,
            TipoEtapa = dto.TipoEtapa,
            CantidadInicial = dto.CantidadInicial,
            CantidadActual = dto.CantidadInicial,
            FechaInicio = dto.FechaInicio,
            FechaCierreEstimada = dto.FechaCierreEstimada,
            Estado = "Activo"
        };

        _context.Lotes.Add(lote);
        await _context.SaveChangesAsync();

        return CreatedAtAction(nameof(GetLote), new { id = lote.Id }, lote);
    }
}

public class CreateLoteDto
{
    public string? Codigo { get; set; }
    public string TipoEtapa { get; set; } = "Maternidad"; // Maternidad, Precebo, Engorde
    public int CantidadInicial { get; set; }
    public DateOnly FechaInicio { get; set; } = DateOnly.FromDateTime(DateTime.UtcNow);
    public DateOnly? FechaCierreEstimada { get; set; }
}
