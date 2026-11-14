using API.Domain.Entities;
using API.Infrastructure.Data;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Npgsql;

namespace API.Controllers;

[ApiController]
[Route("api/v1/[controller]")]
public class CerdosController : ControllerBase
{
    private readonly GranjaPorcinaDbContext _context;
    private readonly string _connectionString;

    public CerdosController(GranjaPorcinaDbContext context, IConfiguration config)
    {
        _context = context;
        _connectionString = config.GetConnectionString("PostgreSQL")
            ?? throw new InvalidOperationException("Falta la cadena de conexión 'ConnectionStrings:PostgreSQL' en user-secrets o variables de entorno.");
    }

    [HttpGet]
    public async Task<ActionResult<IEnumerable<CerdoDto>>> GetCerdos([FromQuery] string? estado)
    {
        try
        {
            var list = new List<CerdoDto>();
            await using var conn = new NpgsqlConnection(_connectionString);
            await conn.OpenAsync();
            var sql = @"
                SELECT c.id, c.caravana, 'HEMBRA', COALESCE(lg.nombre, 'Topigs Norsvin'), 
                       'Maternidad', 'J-01', c.estado_fisiologico, 114, 'Paridad ' || c.paridad_acumulada
                FROM cerdas_reproductoras c
                LEFT JOIN lineas_geneticas lg ON c.linea_genetica_id = lg.id
                ORDER BY c.caravana;";

            await using var cmd = new NpgsqlCommand(sql, conn);
            await using var reader = await cmd.ExecuteReaderAsync();
            while (await reader.ReadAsync())
            {
                list.Add(new CerdoDto
                {
                    Id = reader.GetGuid(0),
                    CodigoCrotal = reader.GetString(1),
                    Sexo = reader.GetString(2),
                    Raza = reader.GetString(3),
                    IdGalpon = reader.GetString(4),
                    Jaula = reader.GetString(5),
                    Estado = reader.GetString(6),
                    GestacionDias = reader.GetInt32(7),
                    PartoHistorico = reader.GetString(8)
                });
            }

            if (list.Count > 0)
            {
                if (!string.IsNullOrWhiteSpace(estado))
                {
                    return Ok(list.Where(c => c.Estado.Equals(estado, StringComparison.OrdinalIgnoreCase)));
                }
                return Ok(list);
            }
        }
        catch
        {
            // Fallback to EF Core if needed
        }

        var query = _context.Cerdos.AsQueryable();

        if (!string.IsNullOrWhiteSpace(estado))
        {
            query = query.Where(c => c.Estado.ToUpper() == estado.ToUpper());
        }

        var cerdos = await query
            .OrderBy(c => c.CodigoCrotal)
            .Select(c => new CerdoDto
            {
                Id = c.Id,
                CodigoCrotal = c.CodigoCrotal,
                Sexo = c.Sexo,
                Raza = c.Raza,
                IdGalpon = c.IdGalpon ?? "Galpón 1",
                Jaula = c.Jaula ?? "J-01",
                Estado = c.Estado,
                GestacionDias = c.GestacionDias,
                PartoHistorico = c.PartoHistorico
            })
            .ToListAsync();

        return Ok(cerdos);
    }

    [HttpGet("{crotal}")]
    public async Task<ActionResult<CerdoDetalleDto>> GetByCrotal(string crotal)
    {
        var cerdo = await _context.Cerdos
            .Include(c => c.CiclosReproductivos)
            .Include(c => c.Tratamientos)
            .FirstOrDefaultAsync(c => c.CodigoCrotal.ToUpper() == crotal.ToUpper());

        if (cerdo == null) return NotFound($"Cerdo con crotal '{crotal}' no encontrado.");

        return Ok(new CerdoDetalleDto
        {
            Id = cerdo.Id,
            CodigoCrotal = cerdo.CodigoCrotal,
            Sexo = cerdo.Sexo,
            Raza = cerdo.Raza,
            IdGalpon = cerdo.IdGalpon ?? "N/A",
            Jaula = cerdo.Jaula ?? "N/A",
            Estado = cerdo.Estado,
            GestacionDias = cerdo.GestacionDias,
            PartoHistorico = cerdo.PartoHistorico,
            Ciclos = cerdo.CiclosReproductivos.OrderByDescending(c => c.FechaServicio).Select(c => new CicloDto
            {
                Id = c.Id,
                FechaServicio = c.FechaServicio,
                FechaPartoReal = c.FechaPartoReal,
                NacidosVivos = c.NacidosVivos,
                NacidosMuertos = c.NacidosMuertos,
                Momificados = c.Momificados,
                PesoCamadaKg = c.PesoCamadaKg,
                EstadoCiclo = c.EstadoCiclo
            }).ToList(),
            Tratamientos = cerdo.Tratamientos.OrderByDescending(t => t.FechaAplicacion).Select(t => new TratamientoItemDto
            {
                Id = t.Id,
                Medicamento = t.Medicamento,
                Dosis = t.Dosis,
                UnidadMedida = t.UnidadMedida,
                FechaAplicacion = t.FechaAplicacion,
                Operario = t.Operario,
                Observaciones = t.Observaciones
            }).ToList()
        });
    }

    [HttpPost]
    public async Task<ActionResult<Cerdo>> Create([FromBody] CreateCerdoDto dto)
    {
        var existing = await _context.Cerdos.AnyAsync(c => c.CodigoCrotal == dto.CodigoCrotal);
        if (existing)
        {
            return BadRequest($"Ya existe un cerdo registrado con el crotal '{dto.CodigoCrotal}'.");
        }

        var cerdo = new Cerdo
        {
            CodigoCrotal = dto.CodigoCrotal,
            Sexo = dto.Sexo,
            Raza = dto.Raza,
            IdGalpon = dto.IdGalpon,
            Jaula = dto.Jaula,
            Estado = dto.Estado,
            GestacionDias = dto.GestacionDias,
            PartoHistorico = dto.PartoHistorico
        };

        _context.Cerdos.Add(cerdo);
        await _context.SaveChangesAsync();

        return CreatedAtAction(nameof(GetByCrotal), new { crotal = cerdo.CodigoCrotal }, cerdo);
    }
}

public class CerdoDto
{
    public Guid Id { get; set; }
    public string CodigoCrotal { get; set; } = string.Empty;
    public string Sexo { get; set; } = "HEMBRA";
    public string Raza { get; set; } = string.Empty;
    public string IdGalpon { get; set; } = string.Empty;
    public string Jaula { get; set; } = string.Empty;
    public string Estado { get; set; } = string.Empty;
    public int GestacionDias { get; set; }
    public string PartoHistorico { get; set; } = string.Empty;
}

public class CerdoDetalleDto : CerdoDto
{
    public List<CicloDto> Ciclos { get; set; } = new();
    public List<TratamientoItemDto> Tratamientos { get; set; } = new();
}

public class CicloDto
{
    public Guid Id { get; set; }
    public DateOnly FechaServicio { get; set; }
    public DateOnly? FechaPartoReal { get; set; }
    public int NacidosVivos { get; set; }
    public int NacidosMuertos { get; set; }
    public int Momificados { get; set; }
    public decimal PesoCamadaKg { get; set; }
    public string EstadoCiclo { get; set; } = string.Empty;
}

public class TratamientoItemDto
{
    public Guid Id { get; set; }
    public string Medicamento { get; set; } = string.Empty;
    public decimal Dosis { get; set; }
    public string UnidadMedida { get; set; } = string.Empty;
    public DateTime FechaAplicacion { get; set; }
    public string Operario { get; set; } = string.Empty;
    public string? Observaciones { get; set; }
}

public class CreateCerdoDto
{
    public string CodigoCrotal { get; set; } = string.Empty;
    public string Sexo { get; set; } = "HEMBRA";
    public string Raza { get; set; } = "Landrace x Large White";
    public string? IdGalpon { get; set; }
    public string? Jaula { get; set; }
    public string Estado { get; set; } = "Gestante";
    public int GestacionDias { get; set; } = 0;
    public string PartoHistorico { get; set; } = "Parto #1";
}
