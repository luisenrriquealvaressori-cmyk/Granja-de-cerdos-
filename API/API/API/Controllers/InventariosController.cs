using API.Domain.Entities;
using API.Infrastructure.Data;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace API.Controllers;

[ApiController]
[Route("api/v1/[controller]")]
public class InventariosController : ControllerBase
{
    private readonly GranjaPorcinaDbContext _context;

    public InventariosController(GranjaPorcinaDbContext context)
    {
        _context = context;
    }

    [HttpGet]
    public async Task<ActionResult<IEnumerable<InventarioItemDto>>> GetInventario()
    {
        var items = await _context.Inventarios
            .OrderBy(i => i.Categoria)
            .ThenBy(i => i.Nombre)
            .Select(i => new InventarioItemDto
            {
                Id = i.Id,
                CodigoSku = i.CodigoSku,
                Nombre = i.Nombre,
                Categoria = i.Categoria,
                StockActual = i.StockActual,
                StockMinimo = i.StockMinimo,
                UnidadMedida = i.UnidadMedida,
                CostoPromedioUnitario = i.CostoPromedioUnitario,
                ValorTotalStock = Math.Round(i.StockActual * i.CostoPromedioUnitario, 2),
                AlertaStockBajo = i.StockActual <= i.StockMinimo
            })
            .ToListAsync();

        return Ok(items);
    }

    [HttpPost("movimiento")]
    public async Task<ActionResult<Inventario>> RegistrarMovimiento([FromBody] RegistrarMovimientoInventarioDto dto)
    {
        var item = await _context.Inventarios.FindAsync(dto.InventarioId);
        if (item == null) return NotFound("Ítem de inventario no encontrado.");

        if (dto.TipoMovimiento.ToUpper() == "ENTRADA")
        {
            // Recalcular costo promedio ponderado
            decimal totalValorExistente = item.StockActual * item.CostoPromedioUnitario;
            decimal totalValorNuevo = dto.Cantidad * (dto.CostoUnitarioCompra ?? item.CostoPromedioUnitario);
            decimal nuevoStock = item.StockActual + dto.Cantidad;

            item.CostoPromedioUnitario = nuevoStock > 0 ? Math.Round((totalValorExistente + totalValorNuevo) / nuevoStock, 2) : item.CostoPromedioUnitario;
            item.StockActual = nuevoStock;

            // Registrar en finanzas si aplica costo
            if (dto.CostoUnitarioCompra.HasValue && dto.CostoUnitarioCompra.Value > 0)
            {
                _context.Finanzas.Add(new Finanza
                {
                    TipoTransaccion = "Gasto",
                    Categoria = item.Categoria,
                    Monto = dto.Cantidad * dto.CostoUnitarioCompra.Value,
                    Fecha = DateOnly.FromDateTime(DateTime.UtcNow),
                    Descripcion = $"Compra insumo: {item.Nombre} ({dto.Cantidad} {item.UnidadMedida})"
                });
            }
        }
        else if (dto.TipoMovimiento.ToUpper() == "SALIDA")
        {
            if (item.StockActual < dto.Cantidad)
            {
                return BadRequest($"Stock insuficiente ({item.StockActual} {item.UnidadMedida} disponibles).");
            }
            item.StockActual -= dto.Cantidad;
        }

        await _context.SaveChangesAsync();
        return Ok(item);
    }
}

public class InventarioItemDto
{
    public Guid Id { get; set; }
    public string CodigoSku { get; set; } = string.Empty;
    public string Nombre { get; set; } = string.Empty;
    public string Categoria { get; set; } = string.Empty;
    public decimal StockActual { get; set; }
    public decimal StockMinimo { get; set; }
    public string UnidadMedida { get; set; } = string.Empty;
    public decimal CostoPromedioUnitario { get; set; }
    public decimal ValorTotalStock { get; set; }
    public bool AlertaStockBajo { get; set; }
}

public class RegistrarMovimientoInventarioDto
{
    public Guid InventarioId { get; set; }
    public string TipoMovimiento { get; set; } = "Entrada"; // Entrada, Salida
    public decimal Cantidad { get; set; }
    public decimal? CostoUnitarioCompra { get; set; }
    public string? Motivo { get; set; }
}
