using System.Text.Json.Serialization;

namespace API.Application.DTOs;

public class SyncBatchRequestDto
{
    [JsonPropertyName("deviceId")]
    public string DeviceId { get; set; } = string.Empty;

    [JsonPropertyName("clientTimestamp")]
    public long ClientTimestamp { get; set; }

    [JsonPropertyName("tratamientos")]
    public List<SyncTratamientoDto> Tratamientos { get; set; } = new();

    [JsonPropertyName("eventosReproductivos")]
    public List<SyncEventoReproductivoDto> EventosReproductivos { get; set; } = new();

    [JsonPropertyName("consumosAlimento")]
    public List<SyncConsumoAlimentoDto> ConsumosAlimento { get; set; } = new();
}

public class SyncTratamientoDto
{
    public string Id { get; set; } = string.Empty;
    public string Crotal { get; set; } = string.Empty;
    public string Medicamento { get; set; } = string.Empty;
    public decimal Dosis { get; set; }
    public string UnidadMedida { get; set; } = "ml";
    public string FechaHoraDisplay { get; set; } = string.Empty;
    public string Operario { get; set; } = string.Empty;
    public string Observaciones { get; set; } = string.Empty;
}

public class SyncEventoReproductivoDto
{
    public string Id { get; set; } = string.Empty;
    public string CrotalCerda { get; set; } = string.Empty;
    public string TipoEvento { get; set; } = "PARTO";
    public string FechaHoraDisplay { get; set; } = string.Empty;
    public int NacidosVivos { get; set; }
    public int NacidosMuertos { get; set; }
    public int Momificados { get; set; }
    public int LechonesDestetados { get; set; }
    public decimal PesoCamadaKg { get; set; }
    public string TipoAsistencia { get; set; } = "Natural";
    public bool OxitocinaAplicada { get; set; }
    public bool MadreEnObservacion { get; set; }
}

public class SyncConsumoAlimentoDto
{
    public string Id { get; set; } = string.Empty;
    public string GalponNombre { get; set; } = string.Empty;
    public string LoteNombre { get; set; } = string.Empty;
    public int AnimalesCount { get; set; }
    public string TipoAlimento { get; set; } = string.Empty;
    public string SiloOrigen { get; set; } = string.Empty;
    public decimal CantidadKg { get; set; }
    public string FechaHoraDisplay { get; set; } = string.Empty;
    public string Operario { get; set; } = string.Empty;
    public string NotasConsumo { get; set; } = string.Empty;
    public string ObservacionAdicional { get; set; } = string.Empty;
}

public class SyncBatchResponseDto
{
    public bool Success { get; set; } = true;
    public string Message { get; set; } = "Sincronización procesada correctamente.";
    public List<string> SavedIds { get; set; } = new();
    public int TotalProcessed => SavedIds.Count;
    public DateTime ServerTimestamp { get; set; } = DateTime.UtcNow;
}
