using API.Application.DTOs;
using API.Domain.Entities;
using API.Infrastructure.Data;
using Microsoft.AspNetCore.Mvc;

namespace API.Controllers;

[ApiController]
[Route("api/v1/[controller]")]
public class SyncController : ControllerBase
{
    private readonly GranjaPorcinaDbContext _context;
    private readonly ILogger<SyncController> _logger;

    public SyncController(GranjaPorcinaDbContext context, ILogger<SyncController> logger)
    {
        _context = context;
        _logger = logger;
    }

    /// <summary>
    /// Sincronización por lotes desde la aplicación móvil de campo (Outbox Engine)
    /// Garantiza Zero-Loss devolviendo exactamente los IDs confirmados en el servidor
    /// </summary>
    [HttpPost("batch")]
    public async Task<ActionResult<SyncBatchResponseDto>> ProcessBatch([FromBody] SyncBatchRequestDto request)
    {
        _logger.LogInformation("Recibido lote de sincronización desde dispositivo {DeviceId} con {Tratamientos} tratamientos, {Eventos} eventos y {Consumos} consumos.",
            request.DeviceId, request.Tratamientos.Count, request.EventosReproductivos.Count, request.ConsumosAlimento.Count);

        var savedIds = new List<string>();

        // 1. Procesar Tratamientos
        foreach (var item in request.Tratamientos)
        {
            if (Guid.TryParse(item.Id, out var id))
            {
                var existing = await _context.TratamientosSanitarios.FindAsync(id);
                if (existing == null)
                {
                    _context.TratamientosSanitarios.Add(new TratamientoSanitario
                    {
                        Id = id,
                        Crotal = item.Crotal,
                        Medicamento = item.Medicamento,
                        Dosis = item.Dosis,
                        UnidadMedida = item.UnidadMedida,
                        FechaAplicacion = DateTime.UtcNow,
                        Operario = item.Operario,
                        Observaciones = item.Observaciones
                    });
                }
                savedIds.Add(item.Id);
            }
        }

        // 2. Procesar Eventos Reproductivos (Partos y Destetes)
        foreach (var item in request.EventosReproductivos)
        {
            if (Guid.TryParse(item.Id, out var id))
            {
                var existing = await _context.CiclosReproductivos.FindAsync(id);
                if (existing == null)
                {
                    _context.CiclosReproductivos.Add(new CicloReproductivo
                    {
                        Id = id,
                        FechaServicio = DateOnly.FromDateTime(DateTime.UtcNow.AddDays(-114)),
                        FechaPartoProbable = DateOnly.FromDateTime(DateTime.UtcNow),
                        FechaPartoReal = DateOnly.FromDateTime(DateTime.UtcNow),
                        NacidosVivos = item.NacidosVivos,
                        NacidosMuertos = item.NacidosMuertos,
                        Momificados = item.Momificados,
                        LechonesDestetados = item.LechonesDestetados,
                        PesoCamadaKg = item.PesoCamadaKg,
                        TipoAsistencia = item.TipoAsistencia,
                        EstadoCiclo = item.TipoEvento.Equals("PARTO", StringComparison.OrdinalIgnoreCase) ? "Lactante" : "Destetada"
                    });
                }
                savedIds.Add(item.Id);
            }
        }

        // 3. Procesar Consumos de Alimento
        foreach (var item in request.ConsumosAlimento)
        {
            if (Guid.TryParse(item.Id, out var id))
            {
                var existing = await _context.ConsumosAlimento.FindAsync(id);
                if (existing == null)
                {
                    _context.ConsumosAlimento.Add(new ConsumoAlimento
                    {
                        Id = id,
                        GalponNombre = item.GalponNombre,
                        LoteNombre = item.LoteNombre,
                        TipoAlimento = item.TipoAlimento,
                        SiloOrigen = item.SiloOrigen,
                        CantidadKg = item.CantidadKg,
                        FechaRegistro = DateTime.UtcNow,
                        Operario = item.Operario,
                        NotasConsumo = item.NotasConsumo,
                        ObservacionAdicional = item.ObservacionAdicional
                    });
                }
                savedIds.Add(item.Id);
            }
        }

        await _context.SaveChangesAsync();

        return Ok(new SyncBatchResponseDto
        {
            Success = true,
            Message = $"Lote procesado exitosamente. {savedIds.Count} registros guardados.",
            SavedIds = savedIds,
            ServerTimestamp = DateTime.UtcNow
        });
    }
}
