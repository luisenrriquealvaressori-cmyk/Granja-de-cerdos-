using API.Domain.Entities;
using Microsoft.EntityFrameworkCore;

namespace API.Infrastructure.Data;

public class GranjaPorcinaDbContext : DbContext
{
    public GranjaPorcinaDbContext(DbContextOptions<GranjaPorcinaDbContext> options)
        : base(options)
    {
    }

    public DbSet<Lote> Lotes => Set<Lote>();
    public DbSet<Cerdo> Cerdos => Set<Cerdo>();
    public DbSet<CicloReproductivo> CiclosReproductivos => Set<CicloReproductivo>();
    public DbSet<Inventario> Inventarios => Set<Inventario>();
    public DbSet<Finanza> Finanzas => Set<Finanza>();
    public DbSet<PresupuestoAutomatico> PresupuestosAutomaticos => Set<PresupuestoAutomatico>();
    public DbSet<TratamientoSanitario> TratamientosSanitarios => Set<TratamientoSanitario>();
    public DbSet<ConsumoAlimento> ConsumosAlimento => Set<ConsumoAlimento>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        // Configuración Lotes
        modelBuilder.Entity<Lote>(entity =>
        {
            entity.ToTable("lotes");
            entity.HasKey(e => e.Id);
            entity.HasIndex(e => e.Codigo).IsUnique();
            entity.Property(e => e.Codigo).HasMaxLength(50).IsRequired();
            entity.Property(e => e.TipoEtapa).HasMaxLength(30).IsRequired();
            entity.Property(e => e.Estado).HasMaxLength(20).IsRequired();
        });

        // Configuración Cerdos
        modelBuilder.Entity<Cerdo>(entity =>
        {
            entity.ToTable("cerdos");
            entity.HasKey(e => e.Id);
            entity.HasIndex(e => e.CodigoCrotal).IsUnique();
            entity.Property(e => e.CodigoCrotal).HasMaxLength(50).IsRequired();
            entity.Property(e => e.Sexo).HasMaxLength(20).IsRequired();
            entity.Property(e => e.Estado).HasMaxLength(30).IsRequired();
        });

        // Configuración Ciclos Reproductivos
        modelBuilder.Entity<CicloReproductivo>(entity =>
        {
            entity.ToTable("ciclos_reproductivos");
            entity.HasKey(e => e.Id);
            entity.HasOne(e => e.Hembra)
                  .WithMany(c => c.CiclosReproductivos)
                  .HasForeignKey(e => e.HembraId)
                  .OnDelete(DeleteBehavior.Cascade);
            entity.Property(e => e.EstadoCiclo).HasMaxLength(30).IsRequired();
            entity.Property(e => e.PesoCamadaKg).HasPrecision(10, 2);
        });

        // Configuración Inventario
        modelBuilder.Entity<Inventario>(entity =>
        {
            entity.ToTable("inventario");
            entity.HasKey(e => e.Id);
            entity.HasIndex(e => e.CodigoSku).IsUnique();
            entity.Property(e => e.CodigoSku).HasMaxLength(50).IsRequired();
            entity.Property(e => e.Nombre).HasMaxLength(100).IsRequired();
            entity.Property(e => e.StockActual).HasPrecision(12, 2);
            entity.Property(e => e.StockMinimo).HasPrecision(12, 2);
            entity.Property(e => e.CostoPromedioUnitario).HasPrecision(12, 2);
        });

        // Configuración Finanzas
        modelBuilder.Entity<Finanza>(entity =>
        {
            entity.ToTable("finanzas");
            entity.HasKey(e => e.Id);
            entity.Property(e => e.TipoTransaccion).HasMaxLength(20).IsRequired();
            entity.Property(e => e.Categoria).HasMaxLength(50).IsRequired();
            entity.Property(e => e.Monto).HasPrecision(14, 2);
            entity.HasOne(e => e.Lote)
                  .WithMany(l => l.Finanzas)
                  .HasForeignKey(e => e.LoteId)
                  .OnDelete(DeleteBehavior.SetNull);
            entity.HasOne(e => e.Presupuesto)
                  .WithMany()
                  .HasForeignKey(e => e.PresupuestoId)
                  .OnDelete(DeleteBehavior.SetNull);
        });

        // Configuración Presupuestos Automáticos
        modelBuilder.Entity<PresupuestoAutomatico>(entity =>
        {
            entity.ToTable("presupuestos_automaticos");
            entity.HasKey(e => e.Id);
            entity.Property(e => e.Categoria).HasMaxLength(50).IsRequired();
            entity.Property(e => e.Estado).HasMaxLength(20).IsRequired();
            entity.Property(e => e.MontoEstimado).HasPrecision(14, 2);
            entity.Property(e => e.MontoEjecutado).HasPrecision(14, 2);
        });

        // Datos iniciales de demostración
        SeedInitialData(modelBuilder);
    }

    private void SeedInitialData(ModelBuilder modelBuilder)
    {
        var loteId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        modelBuilder.Entity<Lote>().HasData(
            new Lote
            {
                Id = loteId,
                Codigo = "LOT-2024-01",
                TipoEtapa = "Engorde",
                CantidadInicial = 200,
                CantidadActual = 180,
                FechaInicio = new DateOnly(2024, 8, 1),
                FechaCierreEstimada = new DateOnly(2024, 11, 20),
                Estado = "Activo"
            }
        );

        modelBuilder.Entity<Inventario>().HasData(
            new Inventario
            {
                Id = Guid.Parse("22222222-2222-2222-2222-222222222222"),
                CodigoSku = "ALIM-CREC-01",
                Nombre = "Crecimiento Premium - Pellet 3mm",
                Categoria = "Alimento",
                StockActual = 3400m,
                StockMinimo = 1000m,
                UnidadMedida = "KG",
                CostoPromedioUnitario = 9.80m
            },
            new Inventario
            {
                Id = Guid.Parse("33333333-3333-3333-3333-333333333333"),
                CodigoSku = "MED-HIERRO-01",
                Nombre = "Hierro Dextrano 200mg/ml",
                Categoria = "Medicamento",
                StockActual = 14m,
                StockMinimo = 5m,
                UnidadMedida = "Dosis",
                CostoPromedioUnitario = 45.00m
            }
        );

        modelBuilder.Entity<PresupuestoAutomatico>().HasData(
            new PresupuestoAutomatico
            {
                Id = Guid.Parse("44444444-4444-4444-4444-444444444444"),
                PeriodoAnio = 2024,
                PeriodoMes = 10,
                Categoria = "Alimento",
                MontoEstimado = 142560.00m,
                MontoEjecutado = 159800.00m, // +12.1% Desviación -> Supera umbral del 10%
                Estado = "Desviado"
            },
            new PresupuestoAutomatico
            {
                Id = Guid.Parse("55555555-5555-5555-5555-555555555555"),
                PeriodoAnio = 2024,
                PeriodoMes = 10,
                Categoria = "Medicación y Vacunas",
                MontoEstimado = 28000.00m,
                MontoEjecutado = 22400.00m,
                Estado = "En Curso"
            }
        );
    }
}
