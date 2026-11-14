namespace API.Domain.Entities;

public class Lote
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Codigo { get; set; } = string.Empty; // e.g. LOT-2024-01
    public string TipoEtapa { get; set; } = "Maternidad"; // Maternidad, Precebo, Engorde
    public int CantidadInicial { get; set; }
    public int CantidadActual { get; set; }
    public DateOnly FechaInicio { get; set; } = DateOnly.FromDateTime(DateTime.UtcNow);
    public DateOnly? FechaCierreEstimada { get; set; }
    public string Estado { get; set; } = "Activo"; // Activo, Cerrado, Vendido
    
    // Relaciones
    public ICollection<Finanza> Finanzas { get; set; } = new List<Finanza>();
    public ICollection<ConsumoAlimento> Consumos { get; set; } = new List<ConsumoAlimento>();
}

public class Cerdo
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string CodigoCrotal { get; set; } = string.Empty; // Único / RFID
    public string Sexo { get; set; } = "Hembra"; // Hembra, Macho
    public string Raza { get; set; } = "Landrace";
    public string? IdGalpon { get; set; }
    public string? Jaula { get; set; }
    public string Estado { get; set; } = "Activo"; // Gestante, Lactante, Activo, Baja, Vendido
    public DateOnly FechaNacimiento { get; set; }
    public int GestacionDias { get; set; } = 0;
    public string PartoHistorico { get; set; } = "Parto #1";

    public ICollection<CicloReproductivo> CiclosReproductivos { get; set; } = new List<CicloReproductivo>();
    public ICollection<TratamientoSanitario> Tratamientos { get; set; } = new List<TratamientoSanitario>();
}

public class CicloReproductivo
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid HembraId { get; set; }
    public Cerdo? Hembra { get; set; }
    public DateOnly FechaServicio { get; set; }
    public DateOnly FechaPartoProbable { get; set; } // +114 días calculados automáticamente
    public DateOnly? FechaPartoReal { get; set; }
    public int NacidosVivos { get; set; } = 0;
    public int NacidosMuertos { get; set; } = 0;
    public int Momificados { get; set; } = 0;
    public int LechonesDestetados { get; set; } = 0;
    public decimal PesoCamadaKg { get; set; } = 0.0m;
    public string TipoAsistencia { get; set; } = "Natural"; // Natural, Asistido Leve, Distócico
    public string EstadoCiclo { get; set; } = "Gestante"; // Gestante, Lactante, Destetada, Vacía

    public int TotalCamada => NacidosVivos + NacidosMuertos + Momificados;
}

public class Inventario
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string CodigoSku { get; set; } = string.Empty;
    public string Nombre { get; set; } = string.Empty;
    public string Categoria { get; set; } = "Alimento"; // Alimento, Medicamento, Insumo
    public decimal StockActual { get; set; }
    public decimal StockMinimo { get; set; }
    public string UnidadMedida { get; set; } = "KG"; // KG, Litros, Unidades, Dosis
    public decimal CostoPromedioUnitario { get; set; }
}

public class Finanza
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string TipoTransaccion { get; set; } = "Gasto"; // Ingreso, Gasto
    public string Categoria { get; set; } = "Alimento"; // Venta de Lote, Alimento, Sanidad, Operativos
    public decimal Monto { get; set; }
    public DateOnly Fecha { get; set; } = DateOnly.FromDateTime(DateTime.UtcNow);
    public Guid? LoteId { get; set; }
    public Lote? Lote { get; set; }
    public Guid? PresupuestoId { get; set; }
    public PresupuestoAutomatico? Presupuesto { get; set; }
    public string? Descripcion { get; set; }
}

public class PresupuestoAutomatico
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public int PeriodoAnio { get; set; }
    public int PeriodoMes { get; set; }
    public string Categoria { get; set; } = string.Empty; // Alimento, Medicación, Mantenimiento, Nómina
    public decimal MontoEstimado { get; set; }
    public decimal MontoEjecutado { get; set; } = 0.0m;
    public string Estado { get; set; } = "Proyectado"; // Proyectado, En Curso, Desviado, Cerrado

    public decimal DesviacionPorcentaje => MontoEstimado > 0 
        ? Math.Round(((MontoEjecutado - MontoEstimado) / MontoEstimado) * 100m, 2)
        : 0.0m;

    public bool SuperaUmbralAlerta => DesviacionPorcentaje > 10.0m;
}

public class TratamientoSanitario
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid? CerdoId { get; set; }
    public Cerdo? Cerdo { get; set; }
    public string Crotal { get; set; } = string.Empty;
    public string Medicamento { get; set; } = string.Empty;
    public decimal Dosis { get; set; }
    public string UnidadMedida { get; set; } = "ml";
    public DateTime FechaAplicacion { get; set; } = DateTime.UtcNow;
    public string Operario { get; set; } = string.Empty;
    public string Observaciones { get; set; } = string.Empty;
}

public class ConsumoAlimento
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid? LoteId { get; set; }
    public Lote? Lote { get; set; }
    public string GalponNombre { get; set; } = string.Empty;
    public string LoteNombre { get; set; } = string.Empty;
    public string TipoAlimento { get; set; } = string.Empty;
    public string SiloOrigen { get; set; } = string.Empty;
    public decimal CantidadKg { get; set; }
    public DateTime FechaRegistro { get; set; } = DateTime.UtcNow;
    public string Operario { get; set; } = string.Empty;
    public string NotasConsumo { get; set; } = string.Empty;
    public string ObservacionAdicional { get; set; } = string.Empty;
}
