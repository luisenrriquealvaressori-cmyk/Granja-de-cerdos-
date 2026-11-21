using Microsoft.Extensions.Caching.Memory;
using Npgsql;
using System.Data;
using System.Security.Claims;
using System.Text.Json;
using WEB.Security;

namespace WEB.Services;

public class AgroPigDataService
{
    private readonly IConfiguration _configuration;
    private readonly ILogger<AgroPigDataService> _logger;
    private readonly IHttpContextAccessor _http;
    private readonly IMemoryCache _cache;

    // La estructura base solo se verifica una vez por proceso (antes se ejecutaba en cada petición)
    private static volatile bool _baseEntitiesListas;
    private static readonly SemaphoreSlim _baseLock = new(1, 1);
    private static long _cacheVersion = 1;

    public AgroPigDataService(
        IConfiguration configuration,
        ILogger<AgroPigDataService> logger,
        IHttpContextAccessor httpContextAccessor,
        IMemoryCache cache)
    {
        // Sin credenciales por defecto en código: configure ConnectionStrings:PostgreSQL o DATABASE_URL en user-secrets/variables de entorno
        _configuration = configuration;
        _logger = logger;
        _http = httpContextAccessor;
        _cache = cache;
    }

    public static void InvalidarCachesOperativos()
    {
        Interlocked.Increment(ref _cacheVersion);
    }

    private string CacheKey(string prefix, params object?[] parts)
    {
        var ver = Volatile.Read(ref _cacheVersion);
        return parts.Length == 0
            ? $"agropig:v{ver}:{prefix}"
            : $"agropig:v{ver}:{prefix}:{string.Join("|", parts.Select(p => p?.ToString() ?? ""))}";
    }

    private string ResolveRole(string? rolRaw)
        => AppRoles.Normalizar(rolRaw ?? _http.HttpContext?.User?.FindFirstValue(ClaimTypes.Role)) ?? AppRoles.Operario;

    private string ResolveUserName(string? nombreUsuario)
        => !string.IsNullOrWhiteSpace(nombreUsuario)
            ? nombreUsuario.Trim()
            : (_http.HttpContext?.User?.FindFirstValue(ClaimTypes.Name)?.Trim() ?? "");

    private string _connectionString => WEB.Security.PostgresConnectionResolver.Resolve(_configuration);

    /// <summary>Id del usuario autenticado (para trazabilidad responsable_id / registrado_por_id). Null si no hay sesión (p.ej. tareas internas).</summary>
    private Guid? CurrentUserId => WEB.Security.ClaimsExtensions.GetUserId(_http.HttpContext?.User ?? new System.Security.Claims.ClaimsPrincipal());
    private object CurrentUserIdDb => (object?)CurrentUserId ?? DBNull.Value;

    /// <summary>Fecha real del registro en campo (epoch ms enviado por la app). Si falta o es futura (&gt;5 min) se usa la hora del servidor.</summary>
    private static DateTime FechaDeCliente(long epochMs)
    {
        if (epochMs <= 0) return DateTime.UtcNow;
        var f = DateTimeOffset.FromUnixTimeMilliseconds(epochMs).UtcDateTime;
        return f > DateTime.UtcNow.AddMinutes(5) ? DateTime.UtcNow : f;
    }

    /// <summary>Centro de costo (código) donde se asienta un gasto de campo según su categoría.</summary>
    private static string CentroCostoPorCategoria(string? categoria) => (categoria ?? "").Trim().ToLowerInvariant() switch
    {
        "mano de obra" => "CC-NOM",
        "sanitario" => "CC-SAN",
        "mantenimiento" => "CC-MANT",
        "combustible" or "servicios" => "CC-SERV",
        "alimentación" or "alimentacion" => "CC-ALIM",
        _ => "CC-MANT"
    };

    private NpgsqlConnection CreateConnection() => new NpgsqlConnection(_connectionString);

    // =========================================================================
    // 0. AUTO-VERIFICACIÓN ESTRUCTURAL DE ENTIDADES BASE (ROLES Y CENTROS DE COSTO)
    // =========================================================================
    public async Task EnsureBaseEntitiesAsync()
    {
        if (_baseEntitiesListas) return;
        await _baseLock.WaitAsync();
        try
        {
            if (_baseEntitiesListas) return;
            await using var conn = CreateConnection();
            await conn.OpenAsync();

            Guid granjaId = Guid.Parse("a0000000-0000-0000-0000-000000000001");

            // 1. Asegurar Centros de Costo completos (Mano de Obra, Servicios, Alimentos, Sanidad, etc.)
            string sqlCentros = @"
                INSERT INTO centros_costo (granja_id, codigo, nombre, porcentaje_peso_presupuestal)
                VALUES 
                    (@granja, 'CC-ALIM', 'Alimento Balanceado & Nutrición', 62.0),
                    (@granja, 'CC-NOM', 'Mano de Obra & Operarios Galpón', 18.0),
                    (@granja, 'CC-SERV', 'Servicios Generales (Energía, Agua, Gas)', 8.0),
                    (@granja, 'CC-SAN', 'Sanidad & Plan Vacunal', 6.0),
                    (@granja, 'CC-VET', 'Servicios Veterinarios & Asistencia Externa', 4.0),
                    (@granja, 'CC-MANT', 'Mantenimiento & Reparaciones', 2.0)
                ON CONFLICT (codigo) DO UPDATE 
                SET nombre = EXCLUDED.nombre, porcentaje_peso_presupuestal = EXCLUDED.porcentaje_peso_presupuestal;";
            await using (var cmd = new NpgsqlCommand(sqlCentros, conn))
            {
                cmd.Parameters.AddWithValue("granja", granjaId);
                await cmd.ExecuteNonQueryAsync();
            }

            // 2. Asegurar Categorías de Insumos
            string sqlCategorias = @"
                INSERT INTO categorias_insumos (codigo, nombre, descripcion)
                VALUES 
                    ('ALIM', 'Alimento Balanceado', 'Fórmulas terminadas precebo, engorde y cerdas'),
                    ('MP', 'Materias Primas / Granos', 'Maíz, torta de soya, harina de pescado'),
                    ('PRE', 'Preiniciadores & Aditivos', 'Preiniciadores de alta digestibilidad y acidificantes'),
                    ('MED', 'Medicamentos & Vacunas', 'Biológicos, antibióticos, antiparasitarios'),
                    ('BIO', 'Desinfectantes & Bioseguridad', 'Detergentes, cal viva, amonios cuaternarios'),
                    ('KIT', 'Instrumental & Kit Clínico', 'Jeringas descartables, agujas hipodérmicas, antisépticos tópicos, torundas y guantes')
                ON CONFLICT (codigo) DO UPDATE 
                SET nombre = EXCLUDED.nombre, descripcion = EXCLUDED.descripcion;";
            await using (var cmd = new NpgsqlCommand(sqlCategorias, conn))
            {
                await cmd.ExecuteNonQueryAsync();
            }

            // 3. Asegurar columnas de seguridad en Usuarios Operadores
            string sqlUsuarios = @"
                ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS debe_cambiar_password BOOLEAN NOT NULL DEFAULT FALSE;";
            await using (var cmd = new NpgsqlCommand(sqlUsuarios, conn))
            {
                await cmd.ExecuteNonQueryAsync();
            }

            // 3.1 Asegurar integridad de columnas en lotes_produccion (fase, etapa, cabezas, pesos, fechas)
            string sqlLotesCols = @"
                ALTER TABLE lotes_produccion 
                    ADD COLUMN IF NOT EXISTS etapa_zootecnica VARCHAR(50) DEFAULT 'engorde_intensivo',
                    ADD COLUMN IF NOT EXISTS fase_actual VARCHAR(50) DEFAULT 'engorde_intensivo',
                    ADD COLUMN IF NOT EXISTS fecha_inicio DATE DEFAULT CURRENT_DATE,
                    ADD COLUMN IF NOT EXISTS fecha_ingreso_fase DATE DEFAULT CURRENT_DATE,
                    ADD COLUMN IF NOT EXISTS fecha_cierre_estimada DATE,
                    ADD COLUMN IF NOT EXISTS fecha_estimada_salida DATE,
                    ADD COLUMN IF NOT EXISTS fecha_cierre_real DATE,
                    ADD COLUMN IF NOT EXISTS cantidad_inicial_cabezas INT DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS cantidad_inicial INT DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS cantidad_actual_cabezas INT DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS cantidad_actual INT DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS bajas_acumuladas INT DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS mortalidad_acumulada_cabezas INT DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS peso_promedio_entrada_kg NUMERIC(6,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS peso_promedio_actual_kg NUMERIC(6,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS peso_ingreso_total_kg NUMERIC(10,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS peso_actual_total_kg NUMERIC(10,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS peso_objetivo_salida_kg NUMERIC(6,2) DEFAULT 115.0,
                    ADD COLUMN IF NOT EXISTS linea_genetica_id UUID REFERENCES lineas_geneticas(id),
                    ADD COLUMN IF NOT EXISTS procedencia_camada VARCHAR(150),
                    ADD COLUMN IF NOT EXISTS alimento_acumulado_kg NUMERIC(12,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS fcr_actual NUMERIC(4,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS gmd_actual_g_dia NUMERIC(6,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS presupuesto_asignado_usd NUMERIC(12,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS costo_acumulado_usd NUMERIC(12,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS estado VARCHAR(30) DEFAULT 'activo',
                    ADD COLUMN IF NOT EXISTS responsable_id UUID REFERENCES usuarios_operadores(id);";
            await using (var cmdLotes = new NpgsqlCommand(sqlLotesCols, conn))
            {
                await cmdLotes.ExecuteNonQueryAsync();
            }

            // 3.2 Asegurar columnas en insumos_catalogo y movimientos_kardex
            string sqlInsumosCols = @"
                ALTER TABLE insumos_catalogo 
                    ADD COLUMN IF NOT EXISTS silo_deposito_id UUID REFERENCES silos_depositos(id),
                    ADD COLUMN IF NOT EXISTS estado_stock VARCHAR(50) DEFAULT 'optimo',
                    ADD COLUMN IF NOT EXISTS descripcion TEXT;

                ALTER TABLE movimientos_kardex 
                    ADD COLUMN IF NOT EXISTS documento_referencia VARCHAR(120),
                    ADD COLUMN IF NOT EXISTS numero_documento VARCHAR(80),
                    ADD COLUMN IF NOT EXISTS proveedor VARCHAR(150),
                    ADD COLUMN IF NOT EXISTS saldo_stock_resultante NUMERIC(12,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS valor_total_usd NUMERIC(12,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS observaciones TEXT;";
            await using (var cmdInsumosCols = new NpgsqlCommand(sqlInsumosCols, conn))
            {
                await cmdInsumosCols.ExecuteNonQueryAsync();
            }

            // 3.3 Asegurar columnas en gastos_operativos_transacciones y liquidaciones_ventas
            string sqlGastosCols = @"
                ALTER TABLE gastos_operativos_transacciones 
                    ADD COLUMN IF NOT EXISTS tipo_movimiento VARCHAR(40) DEFAULT 'gasto_operativo',
                    ADD COLUMN IF NOT EXISTS monto_usd NUMERIC(12,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS monto_total_usd NUMERIC(12,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS concepto_detalle TEXT DEFAULT '',
                    ADD COLUMN IF NOT EXISTS concepto VARCHAR(200) DEFAULT '',
                    ADD COLUMN IF NOT EXISTS proveedor_factura VARCHAR(150),
                    ADD COLUMN IF NOT EXISTS proveedor_beneficiario VARCHAR(150),
                    ADD COLUMN IF NOT EXISTS impacto_desviacion_pct NUMERIC(5,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS registrado_por_id UUID REFERENCES usuarios_operadores(id);

                ALTER TABLE gastos_operativos_transacciones ALTER COLUMN concepto DROP NOT NULL;
                ALTER TABLE gastos_operativos_transacciones ALTER COLUMN monto_total_usd DROP NOT NULL;

                ALTER TABLE liquidaciones_ventas
                    ADD COLUMN IF NOT EXISTS fecha_venta DATE DEFAULT CURRENT_DATE,
                    ADD COLUMN IF NOT EXISTS cliente_comprador VARCHAR(150),
                    ADD COLUMN IF NOT EXISTS monto_bruto_usd NUMERIC(12,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS ingreso_neto_usd NUMERIC(12,2) DEFAULT 0;

                ALTER TABLE presupuestos_lote 
                    ADD COLUMN IF NOT EXISTS rubro_nombre VARCHAR(120) DEFAULT 'Rubro Operativo',
                    ADD COLUMN IF NOT EXISTS monto_estimado_usd NUMERIC(12,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS monto_ejecutado_usd NUMERIC(12,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS porcentaje_desviacion NUMERIC(5,2) DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS estado_bi VARCHAR(40) DEFAULT 'optimo',
                    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

                ALTER TABLE presupuestos_lote ALTER COLUMN monto_presupuestado_usd DROP NOT NULL;";
            await using (var cmdGCols = new NpgsqlCommand(sqlGastosCols, conn))
            {
                await cmdGCols.ExecuteNonQueryAsync();
            }

            // 4. Crear Tabla de Auditoría Clínica Precisa de Tratamientos y Deducción de Kit
            string sqlTablaTratamientos = @"
                CREATE TABLE IF NOT EXISTS registro_tratamientos_clinicos (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
                    lote_id UUID REFERENCES lotes_produccion(id),
                    crotal_cerda VARCHAR(50) NOT NULL,
                    galpon_jaula VARCHAR(80),
                    insumo_medicamento_id UUID NOT NULL REFERENCES insumos_catalogo(id),
                    nombre_medicamento VARCHAR(150) NOT NULL,
                    dosis_aplicada NUMERIC(10,2) NOT NULL,
                    unidad_medida VARCHAR(30) NOT NULL DEFAULT 'ml',
                    frasco_volumen_inicial NUMERIC(10,2) NOT NULL DEFAULT 100.0,
                    frasco_volumen_restante NUMERIC(10,2) NOT NULL DEFAULT 95.0,
                    insumos_kit_detalle TEXT NOT NULL,
                    via_administracion VARCHAR(60) NOT NULL,
                    diagnostico VARCHAR(200) NOT NULL,
                    veterinario_responsable VARCHAR(150) NOT NULL,
                    matricula_veterinario VARCHAR(50),
                    costo_total_usd NUMERIC(10,2) NOT NULL,
                    fecha_aplicacion TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );";
            await using (var cmdT = new NpgsqlCommand(sqlTablaTratamientos, conn))
            {
                await cmdT.ExecuteNonQueryAsync();
            }

            // 4.1 Crear Tabla de Cola y Auditoría Outbox Móvil (sync_outbox_logs)
            string sqlTablaOutbox = @"
                CREATE TABLE IF NOT EXISTS sync_outbox_logs (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    client_sync_id VARCHAR(100) NOT NULL UNIQUE,
                    dispositivo_id VARCHAR(100),
                    operario_id UUID REFERENCES usuarios_operadores(id),
                    entidad_tipo VARCHAR(60) NOT NULL,
                    operacion VARCHAR(30) NOT NULL DEFAULT 'INSERT',
                    payload_json JSONB NOT NULL,
                    fecha_cliente TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    fecha_servidor TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    estado_procesamiento VARCHAR(30) NOT NULL DEFAULT 'procesado',
                    mensaje_error TEXT
                );";
            await using (var cmdOutbox = new NpgsqlCommand(sqlTablaOutbox, conn))
            {
                await cmdOutbox.ExecuteNonQueryAsync();
            }

            // 4.2 Armonizar Vista de Radar de Partos Inminentes
            try
            {
                string sqlVistaRadar = @"
                    ALTER TABLE servicios_inseminacion ADD COLUMN IF NOT EXISTS verraco_id UUID REFERENCES verracos_semen(id);
                    ALTER TABLE servicios_inseminacion ADD COLUMN IF NOT EXISTS verraco_semen_id UUID REFERENCES verracos_semen(id);

                    DROP VIEW IF EXISTS v_radar_partos_inminentes CASCADE;

                    CREATE VIEW v_radar_partos_inminentes AS
                    SELECT 
                        c.id AS cerda_id,
                        s.id AS servicio_id,
                        c.caravana,
                        c.caravana AS crotal_cerda,
                        c.paridad_acumulada,
                        c.jaula_numero,
                        COALESCE(g.nombre, 'Maternidad Nave 1') AS galpon_actual,
                        GREATEST(0, (CURRENT_DATE - s.fecha_servicio::date)) AS dias_gestacion_actual,
                        (s.fecha_parto_estimada - CURRENT_DATE) AS dias_para_parto,
                        ROUND(LEAST(100.0, GREATEST(0.0, ((CURRENT_DATE - s.fecha_servicio::date)::numeric / 114.0) * 100.0)), 1) AS pct_avance_gestacional,
                        CASE 
                            WHEN (s.fecha_parto_estimada - CURRENT_DATE) < 0 THEN 'Parto Retrasado / Crítico'
                            WHEN (s.fecha_parto_estimada - CURRENT_DATE) <= 2 THEN 'Parto Inminente (48h)'
                            WHEN (s.fecha_parto_estimada - CURRENT_DATE) <= 7 THEN 'Semana de Parición'
                            ELSE 'Gestación Regular'
                        END AS aviso_temporal,
                        CASE 
                            WHEN (s.fecha_parto_estimada - CURRENT_DATE) < 0 THEN 'Parto Retrasado / Crítico'
                            WHEN (s.fecha_parto_estimada - CURRENT_DATE) <= 2 THEN 'Parto Inminente (48h)'
                            WHEN (s.fecha_parto_estimada - CURRENT_DATE) <= 7 THEN 'Semana de Parición'
                            ELSE 'Gestación Regular'
                        END AS estado_alerta,
                        COALESCE(vs.nombre, vs.codigo, 'Pietrain Master') AS verraco_utilizado,
                        COALESCE(c.estatus_sanitario, 'Óptimo / Sanitizado') AS estatus_sanitario
                    FROM servicios_inseminacion s
                    JOIN cerdas_reproductoras c ON c.id = s.cerda_id
                    LEFT JOIN verracos_semen vs ON vs.id = COALESCE(s.verraco_id, s.verraco_semen_id)
                    LEFT JOIN galpones_naves g ON g.id = c.galpon_id
                    WHERE s.resultado_concepcion IN ('confirmada_positiva', 'pendiente_diagnostico', 'pendiente')
                    ORDER BY s.fecha_parto_estimada ASC;";
                await using var cmdVR = new NpgsqlCommand(sqlVistaRadar, conn);
                await cmdVR.ExecuteNonQueryAsync();
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[AgroPig] Error actualizando v_radar_partos_inminentes: {ex.Message}");
            }

            // 5. El catálogo de insumos y alimentos lo crea y administra el usuario desde cero (sin auto-siembra demo)

            // 6. Tabla de registro_tratamientos_clinicos (sin siembra de datos ficticios)

            // 7. Asegurar Estructura de Jaulas / Corrales y Padrón de Cerdos Individuales
            string sqlJaulasCorrales = @"
                CREATE TABLE IF NOT EXISTS jaulas_corrales (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
                    galpon_id UUID REFERENCES galpones_naves(id) ON DELETE SET NULL,
                    codigo VARCHAR(50) NOT NULL UNIQUE,
                    nombre VARCHAR(100) NOT NULL,
                    etapa_zootecnica VARCHAR(60) NOT NULL,
                    capacidad_maxima INT NOT NULL DEFAULT 20,
                    ocupacion_actual INT NOT NULL DEFAULT 0,
                    tipo_instalacion VARCHAR(100) NOT NULL DEFAULT 'Corral tecnificado',
                    activo BOOLEAN NOT NULL DEFAULT true,
                    observaciones TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                CREATE TABLE IF NOT EXISTS cerdos_individuales (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
                    crotal VARCHAR(50) NOT NULL UNIQUE,
                    rfid VARCHAR(80),
                    sexo VARCHAR(20) NOT NULL DEFAULT 'Hembra',
                    fecha_nacimiento DATE NOT NULL,
                    peso_actual_kg NUMERIC(6,2) NOT NULL DEFAULT 15.00,
                    etapa_actual VARCHAR(60) NOT NULL,
                    jaula_corral_id UUID REFERENCES jaulas_corrales(id) ON DELETE SET NULL,
                    galpon_id UUID REFERENCES galpones_naves(id) ON DELETE SET NULL,
                    lote_id UUID REFERENCES lotes_produccion(id) ON DELETE SET NULL,
                    linea_genetica VARCHAR(100) NOT NULL DEFAULT 'Topigs Norsvin',
                    estatus_sanitario VARCHAR(60) NOT NULL DEFAULT 'Saludable',
                    activo BOOLEAN NOT NULL DEFAULT true,
                    observaciones TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );";
            await using (var cmdJC = new NpgsqlCommand(sqlJaulasCorrales, conn))
            {
                await cmdJC.ExecuteNonQueryAsync();
            }

            // 7.1 Sembrar Jaulas y Corrales por Etapa Zootécnica
            string sqlSeedJaulas = @"
                INSERT INTO jaulas_corrales (granja_id, codigo, nombre, etapa_zootecnica, capacidad_maxima, tipo_instalacion)
                VALUES 
                    (@granja, 'J-MAT-01', 'Maternidad A - Jaula 01', 'Maternidad', 15, 'Jaula paridera con piso térmico y lechonera infrarroja'),
                    (@granja, 'J-MAT-02', 'Maternidad A - Jaula 02', 'Maternidad', 15, 'Jaula paridera con piso térmico y lechonera infrarroja'),
                    (@granja, 'J-MAT-03', 'Maternidad B - Jaula 03', 'Maternidad', 15, 'Jaula paridera con piso térmico y lechonera infrarroja'),
                    (@granja, 'C-PREC-01', 'Precebo Nave 1 - Corral 01', 'Precebo', 30, 'Corral slat plástico térmico con tolva seco-húmedo'),
                    (@granja, 'C-PREC-02', 'Precebo Nave 1 - Corral 02', 'Precebo', 30, 'Corral slat plástico térmico con tolva seco-húmedo'),
                    (@granja, 'C-PREC-03', 'Precebo Nave 2 - Corral 03', 'Precebo', 30, 'Corral slat plástico térmico con comedero regulable'),
                    (@granja, 'C-CREC-01', 'Crecimiento Nave 1 - Corral 01', 'Crecimiento', 25, 'Corral semieslatado con tolva redonda y doble bebedero'),
                    (@granja, 'C-CREC-02', 'Crecimiento Nave 1 - Corral 02', 'Crecimiento', 25, 'Corral semieslatado con tolva redonda y doble bebedero'),
                    (@granja, 'C-CREC-03', 'Crecimiento Nave 2 - Corral 03', 'Crecimiento', 25, 'Corral semieslatado con tolva redonda y doble bebedero'),
                    (@granja, 'C-ENG-01', 'Engorde Nave 1 - Corral 01', 'Engorde', 20, 'Corral terminación intensiva tolva inox y aspersores'),
                    (@granja, 'C-ENG-02', 'Engorde Nave 1 - Corral 02', 'Engorde', 20, 'Corral terminación intensiva tolva inox y aspersores'),
                    (@granja, 'C-ENG-03', 'Engorde Nave 2 - Corral 03', 'Engorde', 20, 'Corral terminación intensiva tolva inox y aspersores'),
                    (@granja, 'J-GEST-01', 'Gestación Nave 1 - Jaula 01', 'Gestación', 1, 'Jaula individual de inseminación y control gestacional'),
                    (@granja, 'J-GEST-02', 'Gestación Nave 1 - Jaula 02', 'Gestación', 1, 'Jaula individual de inseminación y control gestacional'),
                    (@granja, 'C-GEST-01', 'Gestación Colectiva - Corral 01', 'Gestación', 8, 'Corral dinámico con estación de alimentación electrónica')
                ON CONFLICT (codigo) DO UPDATE 
                SET nombre = EXCLUDED.nombre, etapa_zootecnica = EXCLUDED.etapa_zootecnica, 
                    capacidad_maxima = EXCLUDED.capacidad_maxima, tipo_instalacion = EXCLUDED.tipo_instalacion;";
            await using (var cmdSJ = new NpgsqlCommand(sqlSeedJaulas, conn))
            {
                cmdSJ.Parameters.AddWithValue("granja", granjaId);
                await cmdSJ.ExecuteNonQueryAsync();
            }

            // 7.2 Padrón de cerdos_individuales limpio (sin siembra de datos ficticios)

            // 8. Asegurar Tabla de Tareas y Horario de Actividades de Granja
            string sqlTareas = @"
                CREATE TABLE IF NOT EXISTS tareas_actividades_granja (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
                    titulo VARCHAR(150) NOT NULL,
                    descripcion TEXT,
                    tipo_actividad VARCHAR(50) NOT NULL DEFAULT 'Sanidad',
                    prioridad VARCHAR(20) NOT NULL DEFAULT 'media',
                    estado VARCHAR(30) NOT NULL DEFAULT 'pendiente',
                    fecha_programada DATE NOT NULL DEFAULT CURRENT_DATE,
                    hora_programada VARCHAR(10) NOT NULL DEFAULT '08:00',
                    galpon_nombre VARCHAR(100),
                    lote_codigo VARCHAR(50),
                    cerda_caravana VARCHAR(50),
                    asignado_a_nombre VARCHAR(120),
                    asignado_a_rol VARCHAR(50) DEFAULT 'Operario',
                    completado_por VARCHAR(120),
                    fecha_completado TIMESTAMPTZ,
                    observaciones TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                CREATE TABLE IF NOT EXISTS registro_bajas_mortalidad (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
                    crotal VARCHAR(50) NOT NULL,
                    lote_codigo VARCHAR(50),
                    galpon_nombre VARCHAR(100),
                    causa_muerte VARCHAR(100) NOT NULL,
                    peso_kg NUMERIC(6,2) DEFAULT 0,
                    etapa_zootecnica VARCHAR(60) DEFAULT 'Maternidad',
                    necropsia_realizada BOOLEAN DEFAULT FALSE,
                    hallazgos_necropsia TEXT,
                    fecha_baja TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    registrado_por VARCHAR(120) NOT NULL,
                    observaciones TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );";

            await using (var cmdT = new NpgsqlCommand(sqlTareas, conn))
            {
                await cmdT.ExecuteNonQueryAsync();
            }

            // 10. Armonizar tabla partos para evitar errores en Reproducción
            try
            {
                string sqlPartosFix = @"
                    ALTER TABLE partos 
                        ADD COLUMN IF NOT EXISTS cerda_id UUID REFERENCES cerdas_reproductoras(id),
                        ADD COLUMN IF NOT EXISTS total_nacidos INT DEFAULT 0,
                        ADD COLUMN IF NOT EXISTS jaula_maternidad VARCHAR(50),
                        ADD COLUMN IF NOT EXISTS peso_total_camada_kg NUMERIC(6,2) DEFAULT 0,
                        ADD COLUMN IF NOT EXISTS peso_promedio_lechon_kg NUMERIC(6,2) DEFAULT 0,
                        ADD COLUMN IF NOT EXISTS condicion_corporal_cerda NUMERIC(3,1) DEFAULT 3.0,
                        ADD COLUMN IF NOT EXISTS asistencia_obstetrica BOOLEAN DEFAULT false,
                        ADD COLUMN IF NOT EXISTS responsable_id UUID REFERENCES usuarios_operadores(id);

                    UPDATE partos SET jaula_maternidad = jaula_paridera WHERE jaula_maternidad IS NULL AND jaula_paridera IS NOT NULL;
                    UPDATE partos SET peso_total_camada_kg = peso_camada_total_kg WHERE peso_total_camada_kg = 0 AND peso_camada_total_kg > 0;
                    UPDATE partos SET total_nacidos = (nacidos_vivos + nacidos_muertos + momias) WHERE total_nacidos = 0;

                    ALTER TABLE destetes
                        ADD COLUMN IF NOT EXISTS condicion_corporal_cerda NUMERIC(3,1) DEFAULT 3.0;";
                await using var cmdPF = new NpgsqlCommand(sqlPartosFix, conn);
                await cmdPF.ExecuteNonQueryAsync();
            }
            catch { /* Resiliente */ }

            // 11. Asegurar esquema de Usuarios y crear únicamente cuenta inicial admin si no existe ningún administrador
            try
            {
                string sqlAdminInicial = @"
                    ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS nombre_usuario VARCHAR(60);
                    ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS matricula_profesional VARCHAR(60);
                    ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS telefono VARCHAR(50);
                    ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS debe_cambiar_password BOOLEAN NOT NULL DEFAULT FALSE;
                    ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS ultimo_login TIMESTAMPTZ;
                    ALTER TABLE usuarios_operadores ALTER COLUMN email DROP NOT NULL;

                    INSERT INTO usuarios_operadores (id, granja_id, nombre_usuario, nombre, password_hash, rol, activo, debe_cambiar_password)
                    SELECT 'd0000000-0000-0000-0000-0000000000a1', @granja, 'admin', 'Luis (Administrador General)', crypt('Prueba#2026', gen_salt('bf', 10)), 'administrador_financiero', TRUE, FALSE
                    WHERE NOT EXISTS (
                        SELECT 1 FROM usuarios_operadores
                        WHERE lower(nombre_usuario) = 'admin' OR rol = 'administrador_financiero'
                    )
                    ON CONFLICT (id) DO NOTHING;";

                await using var cmdUsers = new NpgsqlCommand(sqlAdminInicial, conn);
                cmdUsers.Parameters.AddWithValue("granja", granjaId);
                await cmdUsers.ExecuteNonQueryAsync();
            }
            catch { /* Resiliente */ }

            // 12. Tablas de Proveedores, Catálogo de Productos para Compras y Reglas de Descuento
            try
            {
                string sqlCompras = @"
                    CREATE TABLE IF NOT EXISTS proveedores (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
                        codigo VARCHAR(50) NOT NULL UNIQUE,
                        nombre_comercial VARCHAR(150) NOT NULL,
                        razon_social VARCHAR(150),
                        ruc VARCHAR(50),
                        contacto_nombre VARCHAR(100),
                        telefono VARCHAR(50),
                        email VARCHAR(100),
                        direccion TEXT,
                        condiciones_pago VARCHAR(50) DEFAULT 'Contado',
                        activo BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );

                    CREATE TABLE IF NOT EXISTS catalogo_proveedores_productos (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        proveedor_id UUID NOT NULL REFERENCES proveedores(id) ON DELETE CASCADE,
                        insumo_id UUID REFERENCES insumos_catalogo(id),
                        codigo_producto VARCHAR(50) NOT NULL,
                        nombre_producto VARCHAR(150) NOT NULL,
                        unidad_medida VARCHAR(30) NOT NULL DEFAULT 'qq (100 lbs)',
                        precio_unitario_usd NUMERIC(10,2) NOT NULL DEFAULT 0,
                        precio_unitario_nio NUMERIC(10,2) NOT NULL DEFAULT 0,
                        aplica_descuento BOOLEAN NOT NULL DEFAULT FALSE,
                        unidades_minimas_descuento INT NOT NULL DEFAULT 10,
                        tipo_descuento VARCHAR(20) NOT NULL DEFAULT 'porcentaje', -- 'porcentaje' o 'monetario'
                        descuento_porcentaje NUMERIC(5,2) NOT NULL DEFAULT 0,
                        descuento_monetario_usd NUMERIC(10,2) NOT NULL DEFAULT 0,
                        descuento_monetario_nio NUMERIC(10,2) NOT NULL DEFAULT 0,
                        notas_promocion VARCHAR(200),
                        activo BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );

                    CREATE TABLE IF NOT EXISTS ordenes_compra (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
                        numero_orden VARCHAR(50) NOT NULL UNIQUE,
                        proveedor_id UUID NOT NULL REFERENCES proveedores(id),
                        fecha_emision DATE NOT NULL DEFAULT CURRENT_DATE,
                        fecha_entrega_esperada DATE,
                        moneda VARCHAR(10) NOT NULL DEFAULT 'USD',
                        tasa_cambio NUMERIC(6,2) NOT NULL DEFAULT 36.80,
                        estado VARCHAR(30) NOT NULL DEFAULT 'borrador',
                        subtotal_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
                        descuento_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
                        total_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
                        subtotal_nio NUMERIC(12,2) NOT NULL DEFAULT 0,
                        descuento_nio NUMERIC(12,2) NOT NULL DEFAULT 0,
                        total_nio NUMERIC(12,2) NOT NULL DEFAULT 0,
                        solicitado_por_id UUID REFERENCES usuarios_operadores(id),
                        aprobado_por_id UUID REFERENCES usuarios_operadores(id),
                        observaciones TEXT,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );

                    ALTER TABLE ordenes_compra ADD COLUMN IF NOT EXISTS recibido_por VARCHAR(120);
                    ALTER TABLE ordenes_compra ADD COLUMN IF NOT EXISTS fecha_recepcion TIMESTAMPTZ;
                    ALTER TABLE ordenes_compra ADD COLUMN IF NOT EXISTS lote_fabricante VARCHAR(80);
                    ALTER TABLE ordenes_compra ADD COLUMN IF NOT EXISTS fecha_vencimiento DATE;
                    ALTER TABLE ordenes_compra ADD COLUMN IF NOT EXISTS bodega_destino VARCHAR(100);

                    CREATE TABLE IF NOT EXISTS ordenes_compra_detalles (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        orden_compra_id UUID NOT NULL REFERENCES ordenes_compra(id) ON DELETE CASCADE,
                        catalogo_producto_id UUID REFERENCES catalogo_proveedores_productos(id),
                        insumo_id UUID REFERENCES insumos_catalogo(id),
                        descripcion VARCHAR(150) NOT NULL,
                        cantidad NUMERIC(10,2) NOT NULL,
                        unidad_medida VARCHAR(30) NOT NULL,
                        precio_unitario_usd NUMERIC(10,2) NOT NULL,
                        precio_unitario_nio NUMERIC(10,2) NOT NULL,
                        aplico_descuento BOOLEAN NOT NULL DEFAULT FALSE,
                        tipo_descuento VARCHAR(20) DEFAULT 'ninguno',
                        monto_descuento_unitario_usd NUMERIC(10,2) DEFAULT 0,
                        monto_descuento_linea_usd NUMERIC(12,2) DEFAULT 0,
                        subtotal_linea_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
                        total_linea_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
                        monto_descuento_linea_nio NUMERIC(12,2) DEFAULT 0,
                        total_linea_nio NUMERIC(12,2) NOT NULL DEFAULT 0,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );";

                await using var cmdCompras = new NpgsqlCommand(sqlCompras, conn);
                await cmdCompras.ExecuteNonQueryAsync();
            }
            catch { /* Resiliente */ }

            // 13. Estructura de Galpones y Jaulas: 6 Jaulas por Galpón (5m fondo x 3m ancho = 15m²)
            try
            {
                string sqlJaulasMedidas = @"
                    ALTER TABLE jaulas_corrales 
                        ADD COLUMN IF NOT EXISTS largo_fondo_metros NUMERIC(4,2) DEFAULT 5.0,
                        ADD COLUMN IF NOT EXISTS ancho_metros NUMERIC(4,2) DEFAULT 3.0,
                        ADD COLUMN IF NOT EXISTS area_m2 NUMERIC(6,2) DEFAULT 15.0,
                        ADD COLUMN IF NOT EXISTS capacidad_lechones INT DEFAULT 20,
                        ADD COLUMN IF NOT EXISTS capacidad_adultos INT DEFAULT 6,
                        ADD COLUMN IF NOT EXISTS numero_jaula INT DEFAULT 1;

                    -- Asegurar que las jaulas tengan las medidas exactas
                    UPDATE jaulas_corrales 
                    SET largo_fondo_metros = 5.0, ancho_metros = 3.0, area_m2 = 15.0, 
                        capacidad_lechones = 20, capacidad_adultos = 6
                    WHERE largo_fondo_metros IS NULL OR largo_fondo_metros = 0;

                    -- Asegurar 6 Jaulas en Galpón de Maternidad y reparar vínculos huérfanos
                    DO $$
                    DECLARE
                        v_galpon_id UUID;
                    BEGIN
                        SELECT id INTO v_galpon_id FROM galpones_naves WHERE codigo = 'GALP-01' LIMIT 1;
                        IF v_galpon_id IS NOT NULL THEN
                            FOR i IN 1..6 LOOP
                                INSERT INTO jaulas_corrales (granja_id, galpon_id, codigo, nombre, etapa_zootecnica, capacidad_maxima, capacidad_lechones, capacidad_adultos, largo_fondo_metros, ancho_metros, area_m2, numero_jaula, tipo_instalacion)
                                VALUES ('a0000000-0000-0000-0000-000000000001', v_galpon_id, 'G1-J' || lpad(i::text, 2, '0'), 'Galpón 1 - Jaula ' || i, 'Maternidad', 20, 20, 6, 5.0, 3.0, 15.0, i, 'Jaula técnica 5x3m con comedero inox seco-húmedo')
                                ON CONFLICT (codigo) DO UPDATE 
                                SET etapa_zootecnica = 'Maternidad', largo_fondo_metros = 5.0, ancho_metros = 3.0, area_m2 = 15.0, capacidad_lechones = 20, capacidad_adultos = 6, numero_jaula = i;
                            END LOOP;
                        END IF;
                    END $$;

                    UPDATE jaulas_corrales j
                    SET galpon_id = CASE
                        WHEN j.codigo LIKE 'J-MAT-%' OR j.codigo LIKE 'G1-J%' THEN (SELECT id FROM galpones_naves WHERE codigo = 'GALP-01' LIMIT 1)
                        WHEN j.codigo LIKE '%GEST%'                          THEN (SELECT id FROM galpones_naves WHERE codigo = 'GALP-02' LIMIT 1)
                        WHEN j.codigo LIKE 'C-PREC-%'                        THEN (SELECT id FROM galpones_naves WHERE codigo = 'GALP-03' LIMIT 1)
                        ELSE (SELECT id FROM galpones_naves WHERE codigo = 'GALP-04' LIMIT 1)
                    END
                    WHERE j.galpon_id IS NULL;";

                await using var cmdJM = new NpgsqlCommand(sqlJaulasMedidas, conn);
                await cmdJM.ExecuteNonQueryAsync();
            }
            catch { /* Resiliente */ }

            // 14. Catálogos Maestros Operativos (Labores, Horarios/Turnos, Clínico y Preferencias UI por Rol)
            try
            {
                string sqlCatalogosMaestros = @"
                    CREATE TABLE IF NOT EXISTS catalogo_labores_campo (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
                        codigo VARCHAR(40) NOT NULL UNIQUE,
                        nombre VARCHAR(120) NOT NULL,
                        tipo_registro VARCHAR(30) NOT NULL,
                        categoria VARCHAR(50) NOT NULL,
                        etapa_sugerida VARCHAR(60),
                        galpon_sugerido VARCHAR(30),
                        duracion_sugerida VARCHAR(40),
                        tarifa_referencia_nio NUMERIC(12,2) NOT NULL DEFAULT 0,
                        centro_costo_codigo VARCHAR(20),
                        rol_sugerido VARCHAR(40),
                        requiere_insumo BOOLEAN NOT NULL DEFAULT FALSE,
                        activo BOOLEAN NOT NULL DEFAULT TRUE,
                        orden INT NOT NULL DEFAULT 100,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );

                    CREATE TABLE IF NOT EXISTS catalogo_horarios_turnos (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
                        codigo VARCHAR(40) NOT NULL UNIQUE,
                        nombre VARCHAR(120) NOT NULL,
                        tipo_horario VARCHAR(30) NOT NULL,
                        hora_inicio VARCHAR(10) NOT NULL,
                        hora_fin VARCHAR(10),
                        categoria_actividad VARCHAR(40),
                        etapa_aplicable VARCHAR(60),
                        rol_responsable VARCHAR(40),
                        prioridad_default VARCHAR(20) NOT NULL DEFAULT 'Media',
                        descripcion VARCHAR(250),
                        activo BOOLEAN NOT NULL DEFAULT TRUE,
                        orden INT NOT NULL DEFAULT 100
                    );

                    CREATE TABLE IF NOT EXISTS catalogo_clinico_sanitario (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
                        codigo VARCHAR(40) NOT NULL UNIQUE,
                        tipo_catalogo VARCHAR(35) NOT NULL,
                        nombre VARCHAR(120) NOT NULL,
                        subcategoria VARCHAR(60),
                        dias_retiro_referencia INT NOT NULL DEFAULT 0,
                        activo BOOLEAN NOT NULL DEFAULT TRUE,
                        orden INT NOT NULL DEFAULT 100
                    );

                    CREATE TABLE IF NOT EXISTS preferencias_ui_usuario (
                        usuario_id UUID PRIMARY KEY REFERENCES usuarios_operadores(id) ON DELETE CASCADE,
                        rol_base VARCHAR(40) NOT NULL,
                        modulos_visibles_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                        acciones_rapidas_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                        nav_inferior_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                        galpon_predeterminado VARCHAR(30),
                        turno_predeterminado VARCHAR(40),
                        moneda_preferida VARCHAR(10) NOT NULL DEFAULT 'NIO',
                        modo_compacto BOOLEAN NOT NULL DEFAULT FALSE,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );

                    INSERT INTO catalogo_labores_campo (granja_id, codigo, nombre, tipo_registro, categoria, etapa_sugerida, galpon_sugerido, duracion_sugerida, tarifa_referencia_nio, centro_costo_codigo, rol_sugerido, requiere_insumo, orden)
                    SELECT @granja, d.codigo, d.nombre, d.tipo_registro, d.categoria, d.etapa_sugerida, d.galpon_sugerido, d.duracion_sugerida, d.tarifa_nio, d.cc_codigo, d.rol_sugerido, d.req_insumo, d.orden
                    FROM (VALUES
                        ('LAB-LIMP-GALP',   'Limpieza y lavado a presión de corrales',        'LABOR_CAMPO',     'Bioseguridad',     'Todas',       'GALP-04', 'Medio día (4h)',   160.00, 'CC-NOM',  'Operario',    FALSE, 10),
                        ('LAB-DESINF-PED',  'Desinfección, calado y recarga de pediluvios',   'LABOR_CAMPO',     'Bioseguridad',     'Todas',       'GALP-01', '2 horas (0.25j)',  80.00,  'CC-SAN',  'Operario',    TRUE,  20),
                        ('LAB-ALIM-MAN',    'Distribución y calibración de tolvas de comida', 'LABOR_CAMPO',     'Alimentación',     'Precebo',     'GALP-03', '2 horas (0.25j)',  80.00,  'CC-ALIM', 'Operario',    FALSE, 30),
                        ('LAB-PESAJE-LOT',  'Pesaje muestral y control GMD de lote',          'LABOR_CAMPO',     'Manejo',           'Crecimiento', 'GALP-04', 'Medio día (4h)',   160.00, 'CC-NOM',  'Operario',    FALSE, 40),
                        ('LAB-ASIST-PARTO', 'Vigilancia de partos y secado de lechones',      'LABOR_CAMPO',     'Maternidad',       'Maternidad',  'GALP-01', 'Jornal completo',  320.00, 'CC-NOM',  'Veterinario', FALSE, 50),
                        ('LAB-DETEC-CELO',  'Detección de celo y recela con verraco',         'LABOR_CAMPO',     'Reproducción',     'Gestación',   'GALP-02', '2 horas (0.25j)',  80.00,  'CC-NOM',  'Veterinario', FALSE, 60),
                        ('LAB-VAC-LOTE',    'Jornada de vacunación y desparasitación grupal', 'LABOR_CAMPO',     'Sanidad',          'Precebo',     'GALP-03', 'Medio día (4h)',   160.00, 'CC-SAN',  'Veterinario', TRUE,  70),
                        ('LAB-MANEJO-LECH', 'Descolmillado, corte de cola y muesqueo',        'LABOR_CAMPO',     'Maternidad',       'Maternidad',  'GALP-01', '2 horas (0.25j)',  80.00,  'CC-NOM',  'Veterinario', TRUE,  80),
                        ('LAB-MANT-HIDR',   'Revisión de chupones, bebederos y cortinas',     'LABOR_CAMPO',     'Mantenimiento',    'Todas',       'GALP-04', '2 horas (0.25j)',  80.00,  'CC-MANT', 'Operario',    FALSE, 90),
                        ('LAB-MOV-CORRAL',  'Traslado y reagrupación de cerdos entre corrales','LABOR_CAMPO',    'Manejo',           'Crecimiento', 'GALP-03', 'Medio día (4h)',   160.00, 'CC-NOM',  'Operario',    FALSE, 100),
                        ('GAS-JORNAL-EXT',  'Pago de jornal temporal adicional de campo',      'GASTO_OPERATIVO', 'Mano de Obra',     'Todas',       'GALP-04', 'Jornal completo',  320.00, 'CC-NOM',  'Administrador', FALSE, 110),
                        ('GAS-CAL-DESINF',  'Compra local de cal viva, cloro o jabón yodado', 'GASTO_OPERATIVO', 'Sanidad/Limpieza', 'Todas',       'GALP-01', NULL,               450.00, 'CC-SAN',  'Administrador', FALSE, 120),
                        ('GAS-REP-CHUPETE', 'Repuestos menores: chupones inox, manguera, llaves','GASTO_OPERATIVO','Mantenimiento',   'Todas',       'GALP-03', NULL,               380.00, 'CC-MANT', 'Administrador', FALSE, 130),
                        ('GAS-BOMB-TERM',   'Bombillos infrarrojos / calefacción maternidad', 'GASTO_OPERATIVO', 'Mantenimiento',    'Maternidad',  'GALP-01', NULL,               550.00, 'CC-MANT', 'Administrador', FALSE, 140),
                        ('GAS-COMBUST-GEN', 'Combustible motobomba lavado y planta eléctrica','GASTO_OPERATIVO', 'Combustible/Agua', 'Todas',       'GALP-04', NULL,               750.00, 'CC-SERV', 'Administrador', FALSE, 150),
                        ('GAS-FLETE-INS',   'Flete o acarreo de sacos de alimento / insumos', 'GASTO_OPERATIVO', 'Transporte',       'Todas',       'GALP-04', NULL,               600.00, 'CC-ALIM', 'Administrador', FALSE, 160),
                        ('GAS-ENERG-AGUA',  'Recibo energía eléctrica / bombeo de agua',      'GASTO_OPERATIVO', 'Servicios',        'Todas',       'GALP-01', NULL,               1850.00,'CC-SERV', 'Administrador', FALSE, 170),
                        ('GAS-HONOR-VET',   'Asistencia clínica / diagnóstico laboratorio ext.','GASTO_OPERATIVO','Otros',           'Todas',       'GALP-01', NULL,               1200.00,'CC-VET',  'Administrador', FALSE, 180)
                    ) AS d(codigo, nombre, tipo_registro, categoria, etapa_sugerida, galpon_sugerido, duracion_sugerida, tarifa_nio, cc_codigo, rol_sugerido, req_insumo, orden)
                    ON CONFLICT (codigo) DO NOTHING;

                    INSERT INTO catalogo_horarios_turnos (granja_id, codigo, nombre, tipo_horario, hora_inicio, hora_fin, categoria_actividad, etapa_aplicable, rol_responsable, prioridad_default, descripcion, orden)
                    SELECT @granja, d.codigo, d.nombre, d.tipo_horario, d.h_ini, d.h_fin, d.cat, d.etapa, d.rol, d.prio, d.descr, d.orden
                    FROM (VALUES
                        ('TUR-MAN',       'Turno Mañana (06:00 - 14:00)',                'TURNO_JORNADA',    '06:00', '14:00', 'Manejo',       'Todas',       'Operario',    'Media',   'Primera jornada operativa.', 10),
                        ('TUR-TAR',       'Turno Tarde (14:00 - 20:00)',                 'TURNO_JORNADA',    '14:00', '20:00', 'Manejo',       'Todas',       'Operario',    'Media',   'Segunda jornada operativa.', 20),
                        ('TUR-NOC',       'Turno Guardia Nocturna (20:00 - 06:00)',      'TURNO_JORNADA',    '20:00', '06:00', 'Reproduccion', 'Maternidad',  'Operario',    'Alta',    'Vigilancia de partos inminentes.', 30),
                        ('TUR-COMP',      'Jornada Técnica Diurna (07:00 - 16:00)',      'TURNO_JORNADA',    '07:00', '16:00', 'Sanidad',      'Todas',       'Veterinario', 'Media',   'Horario administrativo y clínica.', 40),
                        ('ALIM-0630',     'Toma 1 · Mañana Temprano (06:30)',            'TOMA_ALIMENTO',    '06:30', '07:30', 'Alimentacion', 'Todas',       'Operario',    'Alta',    'Primera ración del día.', 50),
                        ('ALIM-1030',     'Toma 2 · Media Mañana Lechones (10:30)',      'TOMA_ALIMENTO',    '10:30', '11:00', 'Alimentacion', 'Precebo',     'Operario',    'Media',   'Refuerzo de preiniciador.', 60),
                        ('ALIM-1430',     'Toma 3 · Tarde Lechones / Nodrizas (14:30)',  'TOMA_ALIMENTO',    '14:30', '15:00', 'Alimentacion', 'Maternidad',  'Operario',    'Media',   'Tercera ración lactancia.', 70),
                        ('ALIM-1630',     'Toma 4 · Cierre Vespertino (16:30)',          'TOMA_ALIMENTO',    '16:30', '17:30', 'Alimentacion', 'Engorde',     'Operario',    'Alta',    'Segunda ración principal.', 80),
                        ('ALIM-1900',     'Toma 5 · Nocturna Maternidad (19:00)',        'TOMA_ALIMENTO',    '19:00', '19:30', 'Alimentacion', 'Maternidad',  'Operario',    'Media',   'Estímulo nocturno maternidad.', 90),
                        ('IA-1-MAN',      '1ra Dosis Inseminación · Mañana (07:00)',     'DOSIS_IA',         '07:00', '09:00', 'Reproduccion', 'Gestación',   'Veterinario', 'Alta',    'Primera aplicación seminal.', 100),
                        ('IA-2-TAR',      '2da Dosis Inseminación · Tarde +12h (17:00)', 'DOSIS_IA',         '17:00', '19:00', 'Reproduccion', 'Gestación',   'Veterinario', 'Alta',    'Segunda dosis de refuerzo.', 110),
                        ('IA-3-REF',      '3ra Dosis Refuerzo · Mañana Día +1 (07:00)',  'DOSIS_IA',         '07:00', '08:30', 'Reproduccion', 'Gestación',   'Veterinario', 'Media',   'Refuerzo tardío.', 120),
                        ('AGD-RACION-AM', 'Suministro Ración AM y Chequeo Bebederos',    'PLANTILLA_AGENDA', '06:30', '08:00', 'Alimentacion', 'Todas',       'Operario',    'Alta',    'Dispensar alimento según curva.', 130),
                        ('AGD-RONDA-MAT', 'Ronda de Maternidad, Calostro y Neonatos',    'PLANTILLA_AGENDA', '07:00', '08:30', 'Reproduccion', 'Maternidad',  'Veterinario', 'Urgente', 'Control lechoneras y nodrizas.', 140),
                        ('AGD-VAC-HIERRO','Aplicación de Hierro Dextrano + Toltrazuril', 'PLANTILLA_AGENDA', '08:30', '10:00', 'Sanidad',      'Maternidad',  'Veterinario', 'Alta',    'Protocolo Día 3 de vida.', 150),
                        ('AGD-ASEO-NAVE', 'Lavado, Desinfección y Recarga de Pediluvios','PLANTILLA_AGENDA', '09:30', '11:30', 'Bioseguridad', 'Todas',       'Operario',    'Media',   'Higienización de pasillos.', 160),
                        ('AGD-ECO-28D',   'Ecografía de Diagnóstico de Gestación (D+28)','PLANTILLA_AGENDA', '10:30', '12:00', 'Reproduccion', 'Gestación',   'Veterinario', 'Alta',    'Confirmación ultrasónica.', 170),
                        ('AGD-PESAJE-SEM','Pesaje Muestral Semanal y Calibración GMD',   'PLANTILLA_AGENDA', '13:30', '15:30', 'Manejo',       'Engorde',     'Operario',    'Media',   'Muestreo de peso por corral.', 180),
                        ('AGD-RACION-PM', 'Suministro Ración PM y Control Ventilación',  'PLANTILLA_AGENDA', '16:30', '17:30', 'Alimentacion', 'Todas',       'Operario',    'Alta',    'Segunda entrega diaria.', 190),
                        ('AGD-AUD-SILOS', 'Lectura de Nivel de Silos y Stock Kardex',    'PLANTILLA_AGENDA', '17:30', '18:30', 'Mantenimiento','Todas',       'Administrador','Media',  'Conciliación diaria de kilos.', 200)
                    ) AS d(codigo, nombre, tipo_horario, h_ini, h_fin, cat, etapa, rol, prio, descr, orden)
                    ON CONFLICT (codigo) DO NOTHING;

                    INSERT INTO catalogo_clinico_sanitario (granja_id, codigo, tipo_catalogo, nombre, subcategoria, dias_retiro_referencia, orden)
                    SELECT @granja, d.codigo, d.tipo, d.nombre, d.subcat, d.retiro, d.orden
                    FROM (VALUES
                        ('MORT-APLAST',  'CAUSA_MORTALIDAD',    'Aplastamiento perinatal por madre',      'Maternidad', 0, 10),
                        ('MORT-INANIC',  'CAUSA_MORTALIDAD',    'Inanición / Bajo peso al nacer (<0.9kg)','Maternidad', 0, 20),
                        ('MORT-NEUMON',  'CAUSA_MORTALIDAD',    'Complejo respiratorio / Neumonía aguda', 'Precebo',    0, 30),
                        ('MORT-DIARR',   'CAUSA_MORTALIDAD',    'Colibacilosis / Diarrea severa neonatal','Maternidad', 0, 40),
                        ('MORT-INFEC',   'CAUSA_MORTALIDAD',    'Septicemia / Infección estreptocócica',  'Todas',      0, 50),
                        ('MORT-PROLAP',  'CAUSA_MORTALIDAD',    'Prolapso o torsión gástrica aguda',      'Engorde',    0, 60),
                        ('MORT-CARDIO',  'CAUSA_MORTALIDAD',    'Muerte súbita / Estrés cardiovascular',  'Engorde',    0, 70),
                        ('MORT-OTRA',    'CAUSA_MORTALIDAD',    'Accidente traumático / Sacrificio hum.', 'Todas',      0, 80),
                        ('DIAG-ANEMIA',  'DIAGNOSTICO_CLINICO', 'Profilaxis férrica / Anemia neonatal',   'Preventivo', 0, 10),
                        ('DIAG-RESP',    'DIAGNOSTICO_CLINICO', 'Infección respiratoria (Mycoplasma/APP)','Respiratorio',14, 20),
                        ('DIAG-DIGEST',  'DIAGNOSTICO_CLINICO', 'Enteritis bacteriana / Diarrea post-dest.','Digestivo',10, 30),
                        ('DIAG-MASTIT',  'DIAGNOSTICO_CLINICO', 'Síndrome MMA (Metritis-Mastitis-Agalactia)','Maternidad',7,40),
                        ('DIAG-PODAL',   'DIAGNOSTICO_CLINICO', 'Cojera / Inflamación articular y podal', 'Locomotor',  5, 50),
                        ('DIAG-PARASIT', 'DIAGNOSTICO_CLINICO', 'Control de endoparásitos y sarna',       'Preventivo', 18,60),
                        ('DIAG-VACUNA',  'DIAGNOSTICO_CLINICO', 'Inmunización programada calendario oficial','Biológico',0, 70),
                        ('DIAG-PARTO',   'DIAGNOSTICO_CLINICO', 'Asistencia obstétrica / Inducción oxitócica','Maternidad',0,80),
                        ('VIA-IM',       'VIA_ADMINISTRACION',  'Intramuscular profunda (IM)',             'Parenteral', 0, 10),
                        ('VIA-SC',       'VIA_ADMINISTRACION',  'Subcutánea (SC retroauricular)',         'Parenteral', 0, 20),
                        ('VIA-VO',       'VIA_ADMINISTRACION',  'Vía Oral (VO / Agua de bebida)',         'Oral',       0, 30),
                        ('VIA-TOP',      'VIA_ADMINISTRACION',  'Tópica / Desinfección cutánea',          'Externa',    0, 40),
                        ('VIA-IV',       'VIA_ADMINISTRACION',  'Intravenosa auricular (IV)',             'Parenteral', 0, 50),
                        ('IA-PCAI',      'TECNICA_IA',          'Post-Cervical (PCAI)',                   'inseminacion_post_cervical', 0, 10),
                        ('IA-TRAD',      'TECNICA_IA',          'Cervical Clásica Tradicional',           'inseminacion_cervical',      0, 20),
                        ('IA-MONTA',     'TECNICA_IA',          'Monta Natural Dirigida',                 'monta_natural',              0, 30),
                        ('ETP-MAT',      'ETAPA_ZOOTECNICA',    'Maternidad',                             '0 - 21 días',    0, 10),
                        ('ETP-PREC',     'ETAPA_ZOOTECNICA',    'Precebo',                                '22 - 70 días',   0, 20),
                        ('ETP-CREC',     'ETAPA_ZOOTECNICA',    'Crecimiento',                            '71 - 115 días',  0, 30),
                        ('ETP-ENG',      'ETAPA_ZOOTECNICA',    'Engorde',                                '116 - 165 días', 0, 40),
                        ('ETP-GEST',     'ETAPA_ZOOTECNICA',    'Gestación',                              'Reproductoras',  0, 50),
                        ('ETP-REEMP',    'ETAPA_ZOOTECNICA',    'Reemplazo',                              'Primerizas',     0, 60)
                    ) AS d(codigo, tipo, nombre, subcat, retiro, orden)
                    ON CONFLICT (codigo) DO NOTHING;";

                await using var cmdCatM = new NpgsqlCommand(sqlCatalogosMaestros, conn);
                cmdCatM.Parameters.AddWithValue("granja", granjaId);
                await cmdCatM.ExecuteNonQueryAsync();
            }
            catch { /* Resiliente */ }

            // Paso 2.2: Índices compuestos idempotentes para consultas por rol, fecha, catálogos y Sync
            try
            {
                const string sqlIndicesOptimizados = @"
                    CREATE INDEX IF NOT EXISTS idx_tareas_fecha_rol_estado
                        ON tareas_actividades_granja (fecha_programada, asignado_a_rol, estado);
                    CREATE INDEX IF NOT EXISTS idx_tareas_asignado_nombre
                        ON tareas_actividades_granja (asignado_a_nombre, fecha_programada);
                    CREATE INDEX IF NOT EXISTS idx_kardex_fecha_tipo
                        ON movimientos_kardex (fecha_hora DESC, tipo_movimiento);
                    CREATE INDEX IF NOT EXISTS idx_tratamientos_fecha
                        ON registro_tratamientos_clinicos (fecha_aplicacion DESC);
                    CREATE INDEX IF NOT EXISTS idx_cerdos_activo_etapa
                        ON cerdos_individuales (activo, etapa_actual);
                    CREATE INDEX IF NOT EXISTS idx_jaulas_galpon_activo
                        ON jaulas_corrales (galpon_id, activo);
                    CREATE INDEX IF NOT EXISTS idx_labores_tipo_activo
                        ON catalogo_labores_campo (tipo_registro, activo, orden);
                    CREATE INDEX IF NOT EXISTS idx_horarios_tipo_activo
                        ON catalogo_horarios_turnos (tipo_horario, activo, orden);
                    CREATE INDEX IF NOT EXISTS idx_clinico_tipo_activo
                        ON catalogo_clinico_sanitario (tipo_catalogo, activo, orden);
                    CREATE INDEX IF NOT EXISTS idx_outbox_client_estado
                        ON sync_outbox_logs (client_sync_id, estado_procesamiento);
                    CREATE INDEX IF NOT EXISTS idx_alertas_estado_tipo_fecha
                        ON alertas_operativas_desviaciones (estado, tipo_alerta, fecha_deteccion DESC);
                    CREATE INDEX IF NOT EXISTS idx_usuarios_activo_rol
                        ON usuarios_operadores (activo, rol);
                    CREATE INDEX IF NOT EXISTS idx_usuarios_login_lower
                        ON usuarios_operadores (lower(nombre_usuario));";
                await using var cmdIdx = new NpgsqlCommand(sqlIndicesOptimizados, conn);
                await cmdIdx.ExecuteNonQueryAsync();
            }
            catch { /* Resiliente ante entornos con índices ya aplicados o permisos restringidos */ }

            _baseEntitiesListas = true;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Nota al verificar entidades base en PostgreSQL (continuando de forma resiliente)");
        }
        finally
        {
            _baseLock.Release();
        }
    }

    // =========================================================================
    // 1. DASHBOARD PRINCIPAL (BI)
    // =========================================================================
    public async Task<(List<CatalogoGalponMovilDto> Galpones, List<CatalogoHorarioMovilDto> Turnos)> GetPreferenciasSelectorCatalogAsync()
    {
        await EnsureBaseEntitiesAsync();
        return await _cache.GetOrCreateAsync(CacheKey("sel_prefs"), async entry =>
        {
            entry.AbsoluteExpirationRelativeToNow = TimeSpan.FromMinutes(2);
            var galpones = new List<CatalogoGalponMovilDto>();
            var turnos = new List<CatalogoHorarioMovilDto>();

            await using var conn = CreateConnection();
            await conn.OpenAsync();

            try
            {
                await using var cmdG = new NpgsqlCommand(@"
                    SELECT codigo, nombre, tipo_sistema, capacidad_cabezas,
                           COALESCE(temperatura_ambiente_celsius, 22.0), COALESCE(humedad_relativa_pct, 60.0)
                    FROM galpones_naves
                    WHERE activo = TRUE
                    ORDER BY codigo;", conn);
                await using var rG = await cmdG.ExecuteReaderAsync();
                while (await rG.ReadAsync())
                    galpones.Add(new CatalogoGalponMovilDto(rG.GetString(0), rG.GetString(1), rG.GetString(2), rG.GetInt32(3), rG.GetDecimal(4), rG.GetDecimal(5)));
            }
            catch { /* Resiliente */ }

            try
            {
                await using var cmdH = new NpgsqlCommand(@"
                    SELECT codigo, nombre, tipo_horario, hora_inicio, COALESCE(hora_fin, ''),
                           COALESCE(categoria_actividad, ''), COALESCE(etapa_aplicable, ''),
                           COALESCE(rol_responsable, 'Operario'), prioridad_default, COALESCE(descripcion, '')
                    FROM catalogo_horarios_turnos
                    WHERE activo = TRUE AND tipo_horario = 'TURNO_JORNADA'
                    ORDER BY orden, hora_inicio;", conn);
                await using var rH = await cmdH.ExecuteReaderAsync();
                while (await rH.ReadAsync())
                    turnos.Add(new CatalogoHorarioMovilDto(
                        rH.GetString(0), rH.GetString(1), rH.GetString(2), rH.GetString(3), rH.GetString(4),
                        rH.GetString(5), rH.GetString(6), rH.GetString(7), rH.GetString(8), rH.GetString(9)));
            }
            catch { /* Resiliente */ }

            return (galpones, turnos);
        })!;
    }

    public async Task<DashboardBiViewModel> GetDashboardBiDataAsync(
        string? rolRaw = null,
        string? nombreUsuario = null,
        string? galponCodigo = null,
        IEnumerable<string>? modulosVisibles = null)
    {
        await EnsureBaseEntitiesAsync();
        var rol = ResolveRole(rolRaw);
        var usuarioNom = ResolveUserName(nombreUsuario);
        var galponClean = string.IsNullOrWhiteSpace(galponCodigo) ? "" : galponCodigo.Trim();
        var visSet = modulosVisibles?
            .Where(m => !string.IsNullOrWhiteSpace(m))
            .Select(m => m.Trim().ToUpperInvariant())
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var visKey = visSet != null && visSet.Count > 0 ? string.Join(",", visSet.OrderBy(x => x)) : "ALL";

        bool Ver(string modulo) => visSet == null || visSet.Count == 0 || visSet.Contains(modulo);

        return (await _cache.GetOrCreateAsync(CacheKey("dash", rol, usuarioNom, galponClean, visKey), async entry =>
        {
            entry.AbsoluteExpirationRelativeToNow = TimeSpan.FromSeconds(15);

            var model = new DashboardBiViewModel();
            bool esAdmin = rol == AppRoles.Administrador;
            bool esVet = rol == AppRoles.Veterinario;
            bool esOp = rol == AppRoles.Operario;

            await using var conn = CreateConnection();
            await conn.OpenAsync();

            // Resolver galpón si hay filtro activo
            Guid? galponIdFiltro = null;
            string galponNombreFiltro = "";
            if (!string.IsNullOrWhiteSpace(galponClean))
            {
                try
                {
                    await using var cmdGf = new NpgsqlCommand(@"
                        SELECT id, nombre
                        FROM galpones_naves
                        WHERE activo = TRUE AND (upper(codigo) = upper(@g) OR upper(nombre) = upper(@g))
                        LIMIT 1;", conn);
                    cmdGf.Parameters.AddWithValue("g", galponClean);
                    await using var rGf = await cmdGf.ExecuteReaderAsync();
                    if (await rGf.ReadAsync())
                    {
                        galponIdFiltro = rGf.GetGuid(0);
                        galponNombreFiltro = rGf.GetString(1);
                    }
                    else
                    {
                        galponNombreFiltro = galponClean;
                    }
                }
                catch { galponNombreFiltro = galponClean; }
            }

            // 1.1 KPI Financiero (Solo Administrador)
            if (esAdmin)
            {
                try
                {
                    await using var cmd = new NpgsqlCommand("SELECT costo_por_kg_producido_usd, variacion_vs_target_usd FROM v_analisis_costo_kg_producido LIMIT 1;", conn);
                    await using var reader = await cmd.ExecuteReaderAsync();
                    if (await reader.ReadAsync())
                    {
                        model.CostoKgProducido = reader.IsDBNull(0) ? 0.00m : reader.GetDecimal(0);
                        model.VariacionCostoTarget = reader.IsDBNull(1) ? 0.00m : reader.GetDecimal(1);
                    }
                }
                catch { /* Resiliente */ }
            }

            // 1.2 Escalares consolidados compartidos (1 solo roundtrip)
            try
            {
                await using var cmdCore = new NpgsqlCommand(@"
                    SELECT
                        COALESCE((SELECT AVG(gmd_actual_g_dia) FROM lotes_produccion WHERE estado = 'activo' AND (@gid::uuid IS NULL OR galpon_id = @gid::uuid)), 0),
                        (SELECT COUNT(*) FROM lotes_produccion WHERE estado = 'activo' AND (@gid::uuid IS NULL OR galpon_id = @gid::uuid)),
                        COALESCE((SELECT SUM(ocupacion_actual) FROM jaulas_corrales WHERE activo = true AND (@gid::uuid IS NULL OR galpon_id = @gid::uuid)), 0),
                        (SELECT COUNT(*) FROM jaulas_corrales WHERE activo = true AND (@gid::uuid IS NULL OR galpon_id = @gid::uuid)),
                        COALESCE((SELECT SUM(cantidad) FROM movimientos_kardex WHERE tipo_movimiento LIKE 'salida%' AND fecha_hora::date = CURRENT_DATE), 0);", conn);
                cmdCore.Parameters.Add(new NpgsqlParameter("gid", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)galponIdFiltro ?? DBNull.Value });
                await using var rCore = await cmdCore.ExecuteReaderAsync();
                if (await rCore.ReadAsync())
                {
                    model.GmdPromedio = Convert.ToInt32(rCore.GetDecimal(0));
                    model.LotesActivosCount = Convert.ToInt32(rCore.GetInt64(1));
                    model.JaulasOcupadas = Convert.ToInt32(rCore.GetInt64(2));
                    model.JaulasTotales = Convert.ToInt32(rCore.GetInt64(3));
                    model.AlimentoSuministradoHoyKg = rCore.IsDBNull(4) ? 0m : Convert.ToDecimal(rCore.GetDecimal(4));
                }
            }
            catch { /* Resiliente */ }

            // 1.3 KPIs Reproductivos y Sanitarios consolidados (Solo Administrador o Veterinario)
            if (esAdmin || esVet)
            {
                try
                {
                    await using var cmdClin = new NpgsqlCommand(@"
                        SELECT
                            COALESCE((SELECT ROUND((COUNT(CASE WHEN resultado_concepcion = 'confirmada_positiva' THEN 1 END)::numeric / NULLIF(COUNT(*), 0)) * 100, 1) FROM servicios_inseminacion), 0),
                            (SELECT COUNT(*) FROM servicios_inseminacion),
                            (SELECT COUNT(CASE WHEN resultado_concepcion = 'confirmada_positiva' THEN 1 END) FROM servicios_inseminacion),
                            COALESCE((SELECT AVG(nacidos_vivos) FROM partos), 0),
                            (SELECT COUNT(*) FROM registro_tratamientos_clinicos WHERE fecha_aplicacion >= date_trunc('month', CURRENT_DATE)),
                            COALESCE((SELECT SUM(dosis_aplicada) FROM registro_tratamientos_clinicos WHERE fecha_aplicacion >= date_trunc('month', CURRENT_DATE)), 0),
                            (SELECT COUNT(DISTINCT crotal_cerda) FROM registro_tratamientos_clinicos WHERE fecha_aplicacion >= date_trunc('month', CURRENT_DATE));", conn);
                    await using var rClin = await cmdClin.ExecuteReaderAsync();
                    if (await rClin.ReadAsync())
                    {
                        model.TasaFertilidad = rClin.IsDBNull(0) ? 0m : rClin.GetDecimal(0);
                        model.ServiciosMes = Convert.ToInt32(rClin.GetInt64(1));
                        model.ConfirmadasEco = Convert.ToInt32(rClin.GetInt64(2));
                        model.PromedioNacidosVivos = rClin.IsDBNull(3) ? 0m : Math.Round(Convert.ToDecimal(rClin.GetDecimal(3)), 1);
                        model.TratamientosAplicadosMes = Convert.ToInt32(rClin.GetInt64(4));
                        model.DosisMedicamentosMlMes = rClin.IsDBNull(5) ? 0m : rClin.GetDecimal(5);
                        model.AnimalesBajoTratamiento = Convert.ToInt32(rClin.GetInt64(6));
                    }
                }
                catch { /* Resiliente */ }
            }

            // 1.4 Silos individuales y Capacidad Total
            if (Ver("SILOS_NIVEL") || Ver("METRICAS_VIVO"))
            {
                await using var cmd = new NpgsqlCommand("SELECT id, codigo, nombre, capacidad_maxima_kg, nivel_actual_kg, COALESCE(tipo_insumo_principal, 'Concentrado') FROM silos_depositos ORDER BY codigo;", conn);
                await using var reader = await cmd.ExecuteReaderAsync();
                decimal totNivel = 0;
                decimal totCap = 0;
                while (await reader.ReadAsync())
                {
                    var cap = reader.GetDecimal(3);
                    var niv = reader.GetDecimal(4);
                    totNivel += niv;
                    totCap += cap;
                    model.Silos.Add(new SiloDetalleItem
                    {
                        Id = reader.GetGuid(0),
                        Codigo = reader.GetString(1),
                        Nombre = reader.GetString(2),
                        CapacidadMaximaKg = cap,
                        NivelActualKg = niv,
                        InsumoPrincipal = reader.GetString(5)
                    });
                }
                model.ToneladasSilo = Math.Round(totNivel / 1000.0m, 2);
                model.PorcentajeCapacidadSilos = totCap > 0 ? (int)Math.Round((totNivel / totCap) * 100m) : 0;
            }

            // 1.6 Tareas de Hoy filtradas por rol y galpón
            if (Ver("TAREAS_HOY") || Ver("METRICAS_VIVO"))
            {
                try
                {
                    var roleFilterSql = esOp
                        ? " AND (COALESCE(asignado_a_rol, '') IN ('Operario', 'operario_galpon', 'Ambos', '') OR (@uPat <> '' AND asignado_a_nombre ILIKE @uPat)) "
                        : (esVet
                            ? " AND (COALESCE(asignado_a_rol, '') IN ('Veterinario', 'director_veterinario', 'Ambos', '') OR (@uPat <> '' AND asignado_a_nombre ILIKE @uPat)) "
                            : "");
                    var galponFilterSql = !string.IsNullOrWhiteSpace(galponClean)
                        ? " AND (galpon_nombre ILIKE @gCodPat OR (@gNomPat <> '' AND galpon_nombre ILIKE @gNomPat)) "
                        : "";

                    await using var cmd = new NpgsqlCommand($@"
                        SELECT id, titulo, descripcion, tipo_actividad, prioridad, estado,
                               fecha_programada, hora_programada, COALESCE(galpon_nombre, ''),
                               COALESCE(lote_codigo, ''), COALESCE(cerda_caravana, ''),
                               COALESCE(asignado_a_nombre, ''), COALESCE(asignado_a_rol, '')
                        FROM tareas_actividades_granja
                        WHERE fecha_programada = CURRENT_DATE
                        {roleFilterSql}
                        {galponFilterSql}
                        ORDER BY CASE lower(prioridad) WHEN 'urgente' THEN 1 WHEN 'alta' THEN 2 WHEN 'media' THEN 3 ELSE 4 END, hora_programada ASC;", conn);
                    if (esOp || esVet)
                        cmd.Parameters.AddWithValue("uPat", string.IsNullOrWhiteSpace(usuarioNom) ? "" : $"%{usuarioNom}%");
                    if (!string.IsNullOrWhiteSpace(galponClean))
                    {
                        cmd.Parameters.AddWithValue("gCodPat", $"%{galponClean}%");
                        cmd.Parameters.AddWithValue("gNomPat", string.IsNullOrWhiteSpace(galponNombreFiltro) ? "" : $"%{galponNombreFiltro}%");
                    }

                    await using var reader = await cmd.ExecuteReaderAsync();
                    while (await reader.ReadAsync())
                    {
                        var tarea = new TareaActividadItem
                        {
                            Id = reader.GetGuid(0),
                            Titulo = reader.GetString(1),
                            Descripcion = reader.IsDBNull(2) ? "" : reader.GetString(2),
                            TipoActividad = reader.GetString(3),
                            Prioridad = reader.GetString(4),
                            Estado = reader.GetString(5),
                            FechaProgramada = reader.GetDateTime(6),
                            HoraProgramada = reader.GetString(7),
                            GalponNombre = reader.GetString(8),
                            LoteCodigo = reader.GetString(9),
                            CerdaCaravana = reader.GetString(10),
                            AsignadoANombre = reader.GetString(11),
                            AsignadoARol = reader.GetString(12)
                        };
                        model.TareasHoy.Add(tarea);
                        if (tarea.EstaCompletada) model.TareasCompletadasHoy++;
                        else model.TareasPendientesHoy++;
                    }
                }
                catch { /* Resiliente */ }
            }

            // 1.9 Radar de Partos Inminentes (Exclusivo Administrador / Veterinario)
            if ((esAdmin || esVet) && (Ver("ACCIONES_RAPIDAS") || Ver("RADAR_PARTOS")))
            {
                try
                {
                    await using var cmd = new NpgsqlCommand("SELECT crotal_cerda, COALESCE(jaula_numero, 'Por asignar'), COALESCE(galpon_actual, 'Maternidad'), dias_para_parto, estado_alerta FROM v_radar_partos_inminentes LIMIT 6;", conn);
                    await using var reader = await cmd.ExecuteReaderAsync();
                    while (await reader.ReadAsync())
                    {
                        model.RadarPartos.Add(new PartoInminenteItem
                        {
                            Caravana = reader.GetString(0),
                            Jaula = reader.GetString(1),
                            DiasParaParto = reader.GetInt32(3),
                            AvisoTemporal = reader.GetString(4)
                        });
                    }
                }
                catch { /* Resiliente */ }
            }

            // 1.10 Lotes Activos DataGrid (filtrado por galpón si aplica)
            await using (var cmd = new NpgsqlCommand(@"
                SELECT l.id, l.codigo_lote,
                       COALESCE(NULLIF(l.fase_actual, ''), l.etapa_zootecnica),
                       COALESCE(NULLIF(l.cantidad_actual_cabezas, 0), l.cantidad_actual, 0) AS cabezas,
                       COALESCE(NULLIF(l.peso_promedio_actual_kg, 0),
                                ROUND(l.peso_actual_total_kg / NULLIF(COALESCE(NULLIF(l.cantidad_actual_cabezas, 0), l.cantidad_actual), 0), 2),
                                0) AS peso_prom,
                       COALESCE(l.gmd_actual_g_dia, 0),
                       COALESCE(g.nombre, 'Nave General') AS galpon_nombre,
                       COALESCE(l.fcr_actual, 0)
                FROM lotes_produccion l
                LEFT JOIN galpones_naves g ON l.galpon_id = g.id
                WHERE l.estado = 'activo'
                  AND (@gid::uuid IS NULL OR l.galpon_id = @gid::uuid)
                ORDER BY l.codigo_lote;", conn))
            {
                cmd.Parameters.Add(new NpgsqlParameter("gid", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)galponIdFiltro ?? DBNull.Value });
                await using var reader = await cmd.ExecuteReaderAsync();
                while (await reader.ReadAsync())
                {
                    model.Lotes.Add(new LoteItemBi
                    {
                        Id = reader.GetGuid(0),
                        Codigo = reader.GetString(1),
                        Etapa = reader.GetString(2),
                        Cabezas = reader.GetInt32(3),
                        PesoPromedioKg = reader.IsDBNull(4) ? 0m : reader.GetDecimal(4),
                        Gmd = reader.IsDBNull(5) ? 0m : reader.GetDecimal(5),
                        Galpon = reader.IsDBNull(6) ? "Nave General" : reader.GetString(6),
                        Fcr = reader.IsDBNull(7) ? 0m : reader.GetDecimal(7)
                    });
                }
            }

            // 1.11 Alertas Operativas filtradas por rol en SQL
            var alertaSqlFilter = esOp
                ? " AND tipo_alerta NOT IN ('desviacion_financiera', 'protocolo_sanitario', 'mortalidad_anormal') "
                : (esVet ? " AND tipo_alerta <> 'desviacion_financiera' " : "");
            await using (var cmd = new NpgsqlCommand($@"
                SELECT tipo_alerta, nivel_severidad, descripcion, valor_detectado, fecha_deteccion
                FROM alertas_operativas_desviaciones
                WHERE estado = 'abierta'
                {alertaSqlFilter}
                ORDER BY fecha_deteccion DESC LIMIT 4;", conn))
            await using (var reader = await cmd.ExecuteReaderAsync())
            {
                while (await reader.ReadAsync())
                {
                    model.Alertas.Add(new AlertaBiItem
                    {
                        Tipo = reader.GetString(0),
                        Severidad = reader.GetString(1),
                        Descripcion = reader.GetString(2),
                        ValorDetectado = reader.IsDBNull(3) ? "" : reader.GetString(3),
                        Fecha = reader.GetDateTime(4)
                    });
                }
            }

            // 1.12 Gráficos Financieros (Solo Administrador y cuando el módulo esté visible)
            if (esAdmin && Ver("RESUMEN_FINANCIERO"))
            {
                await CargarGraficosFinancierosAsync(conn, model.BarrasCentroCosto, model.BarrasMensuales, (ingresos, gastos, _) =>
                {
                    model.TotalIngresosVentasUsd = ingresos;
                    model.TotalGastosOperativosUsd = gastos;
                });
            }

            return model;
        }))!;
    }

    // =========================================================================
    // 2. FINANZAS & PRESUPUESTOS AUTOMÁTICOS (CRUD COMPLETO)
    // =========================================================================
    public async Task<PresupuestosViewModel> GetPresupuestosDataAsync(string? loteCodigoFiltro = null)
    {
        await EnsureBaseEntitiesAsync();

        var model = new PresupuestosViewModel();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 2.1 Lista de Lotes disponibles
        await using (var cmd = new NpgsqlCommand("SELECT id, codigo_lote, etapa_zootecnica FROM lotes_produccion ORDER BY codigo_lote;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.ListaLotes.Add(new LoteSelectorItem
                {
                    Id = reader.GetGuid(0),
                    Codigo = reader.GetString(1),
                    Etapa = reader.GetString(2)
                });
            }
        }

        // 2.2 Lista de Centros de Costo (Alimento, Mano de Obra, Servicios, etc.)
        await using (var cmd = new NpgsqlCommand("SELECT id, codigo, nombre FROM centros_costo ORDER BY codigo;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.ListaCentrosCosto.Add(new CentroCostoSelectorItem
                {
                    Id = reader.GetGuid(0),
                    Codigo = reader.GetString(1),
                    Nombre = reader.GetString(2)
                });
            }
        }

        // 2.3 Matriz Presupuestaria de Presupuestos_lote
        string sql = @"
            SELECT p.id, p.rubro_nombre, l.codigo_lote, p.monto_estimado_usd, 
                   p.monto_ejecutado_usd, p.porcentaje_desviacion, p.estado_bi, 
                   cc.nombre as centro_costo, p.lote_id, p.centro_costo_id
            FROM presupuestos_lote p
            JOIN lotes_produccion l ON p.lote_id = l.id
            JOIN centros_costo cc ON p.centro_costo_id = cc.id";

        bool tieneFiltro = !string.IsNullOrEmpty(loteCodigoFiltro) && loteCodigoFiltro != "TODOS";
        if (tieneFiltro)
        {
            sql += " WHERE l.codigo_lote = @lote";
        }
        sql += " ORDER BY p.porcentaje_desviacion DESC;";

        await using (var cmd = new NpgsqlCommand(sql, conn))
        {
            if (tieneFiltro)
            {
                cmd.Parameters.AddWithValue("lote", loteCodigoFiltro!);
            }
            await using var reader = await cmd.ExecuteReaderAsync();
            while (await reader.ReadAsync())
            {
                model.Presupuestos.Add(new PresupuestoRubroItem
                {
                    Id = reader.GetGuid(0),
                    Rubro = reader.GetString(1),
                    LoteCodigo = reader.GetString(2),
                    MontoEstimado = reader.GetDecimal(3),
                    MontoEjecutado = reader.GetDecimal(4),
                    PorcentajeDesviacion = reader.GetDecimal(5),
                    EstadoBi = reader.GetString(6),
                    CentroCosto = reader.GetString(7),
                    LoteId = reader.GetGuid(8),
                    CentroCostoId = reader.GetGuid(9)
                });
            }
        }

        // 2.4 Historial Detallado de Gastos Reales Transaccionados
        string sqlGastos = @"
            SELECT g.id, g.lote_id, l.codigo_lote, cc.nombre as centro_costo,
                   g.concepto_detalle, COALESCE(g.proveedor_factura, 'Sin Proveedor'),
                   g.monto_usd, g.fecha_contable, g.tipo_movimiento,
                   COALESCE(u.nombre, 'Administrador') as registrado_por
            FROM gastos_operativos_transacciones g
            JOIN lotes_produccion l ON g.lote_id = l.id
            JOIN centros_costo cc ON g.centro_costo_id = cc.id
            LEFT JOIN usuarios_operadores u ON g.registrado_por_id = u.id";

        if (tieneFiltro)
        {
            sqlGastos += " WHERE l.codigo_lote = @lote";
        }
        sqlGastos += " ORDER BY g.fecha_contable DESC, g.created_at DESC LIMIT 30;";

        await using (var cmdG = new NpgsqlCommand(sqlGastos, conn))
        {
            if (tieneFiltro)
            {
                cmdG.Parameters.AddWithValue("lote", loteCodigoFiltro!);
            }
            await using var readerG = await cmdG.ExecuteReaderAsync();
            while (await readerG.ReadAsync())
            {
                model.GastosRecientes.Add(new GastoOperativoItem
                {
                    Id = readerG.GetGuid(0),
                    LoteId = readerG.GetGuid(1),
                    LoteCodigo = readerG.GetString(2),
                    CentroCosto = readerG.GetString(3),
                    Concepto = readerG.GetString(4),
                    Proveedor = readerG.GetString(5),
                    MontoUsd = readerG.GetDecimal(6),
                    Fecha = readerG.GetDateTime(7),
                    TipoMovimiento = readerG.GetString(8),
                    RegistradoPor = readerG.GetString(9)
                });
            }
        }

        // 2.5 Insumos de Alimento para reporte de Operario
        await using (var cmdA = new NpgsqlCommand(@"
            SELECT i.id, i.codigo_sku as sku, i.nombre, c.nombre as categoria, i.unidad_medida as unidad_medida_base, i.costo_unitario_promedio_usd, i.stock_actual
            FROM insumos_catalogo i
            JOIN categorias_insumos c ON i.categoria_id = c.id
            WHERE c.codigo IN ('ALIM', 'MP', 'PRE') AND i.activo = true
            ORDER BY i.nombre;", conn))
        await using (var readerA = await cmdA.ExecuteReaderAsync())
        {
            while (await readerA.ReadAsync())
            {
                model.InsumosAlimentos.Add(new InsumoDbItem
                {
                    Id = readerA.GetGuid(0),
                    Sku = readerA.GetString(1),
                    Nombre = readerA.GetString(2),
                    Categoria = readerA.GetString(3),
                    Unidad = readerA.GetString(4),
                    CostoUnitario = readerA.GetDecimal(5),
                    StockActual = readerA.GetDecimal(6)
                });
            }
        }

        // 2.6 Medicamentos y Biológicos para reporte de Veterinario (excluye descartables KIT)
        await using (var cmdM = new NpgsqlCommand(@"
            SELECT i.id, i.codigo_sku as sku, i.nombre, c.nombre as categoria, i.unidad_medida as unidad_medida_base, i.costo_unitario_promedio_usd, i.stock_actual
            FROM insumos_catalogo i
            JOIN categorias_insumos c ON i.categoria_id = c.id
            WHERE c.codigo = 'MED' AND i.activo = true
            ORDER BY i.nombre;", conn))
        await using (var readerM = await cmdM.ExecuteReaderAsync())
        {
            while (await readerM.ReadAsync())
            {
                model.InsumosMedicamentos.Add(new InsumoDbItem
                {
                    Id = readerM.GetGuid(0),
                    Sku = readerM.GetString(1),
                    Nombre = readerM.GetString(2),
                    Categoria = readerM.GetString(3),
                    Unidad = readerM.GetString(4),
                    CostoUnitario = readerM.GetDecimal(5),
                    StockActual = readerM.GetDecimal(6)
                });
            }
        }

        // 2.7 Auditoría de Tratamientos Clínicos e Insumos Consumidos
        try
        {
            string sqlTratamientos = @"
                SELECT id, fecha_aplicacion, crotal_cerda, COALESCE(galpon_jaula, 'Maternidad A'),
                       nombre_medicamento, dosis_aplicada, unidad_medida, 
                       frasco_volumen_inicial, frasco_volumen_restante,
                       insumos_kit_detalle, via_administracion, diagnostico,
                       veterinario_responsable, COALESCE(matricula_veterinario, 'MVZ-9402'),
                       costo_total_usd
                FROM registro_tratamientos_clinicos
                ORDER BY fecha_aplicacion DESC
                LIMIT 40;";
            await using (var cmdT = new NpgsqlCommand(sqlTratamientos, conn))
            await using (var readerT = await cmdT.ExecuteReaderAsync())
            {
                while (await readerT.ReadAsync())
                {
                    model.TratamientosAuditoria.Add(new TratamientoAuditoriaItem
                    {
                        Id = readerT.GetGuid(0),
                        FechaHora = readerT.GetDateTime(1),
                        CrotalCerda = readerT.GetString(2),
                        GalponJaula = readerT.GetString(3),
                        Medicamento = readerT.GetString(4),
                        DosisAplicada = readerT.GetDecimal(5),
                        UnidadMedida = readerT.GetString(6),
                        FrascoInicialMl = readerT.GetDecimal(7),
                        FrascoRestanteMl = readerT.GetDecimal(8),
                        InsumosKitUsados = readerT.GetString(9),
                        ViaAdministracion = readerT.GetString(10),
                        Diagnostico = readerT.GetString(11),
                        VeterinarioResponsable = readerT.GetString(12),
                        Matricula = readerT.GetString(13),
                        CostoTotalUsd = readerT.GetDecimal(14)
                    });
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Nota al consultar tabla de auditoría clínica en PostgreSQL");
        }

        // Totales calculados
        model.TotalEstimado = model.Presupuestos.Sum(p => p.MontoEstimado);
        model.TotalEjecutado = model.Presupuestos.Sum(p => p.MontoEjecutado);
        model.DesviacionNetaGlobal = model.TotalEstimado > 0 
            ? Math.Round(((model.TotalEjecutado - model.TotalEstimado) / model.TotalEstimado) * 100, 2)
            : 0;

        // 2.8 Consolidación de Gráficos de Barras Financieros
        await CargarGraficosFinancierosAsync(conn, model.BarrasCentroCosto, model.BarrasMensuales, (ingresos, gastos, tieneDatos) =>
        {
            model.TotalIngresosVentasUsd = ingresos;
            model.TotalGastosOperativosUsd = gastos;
            model.TieneDatosTransaccionales = tieneDatos;
        }, loteCodigoFiltro);

        return model;
    }

    /// <summary>
    /// Consolida datos para los diagramas de barras interactivos:
    /// 1. Flujo Mensual (Ingresos vs Gastos vs Utilidad Neta)
    /// 2. Presupuestado vs Ejecutado por Centro de Costo
    /// </summary>
    public async Task CargarGraficosFinancierosAsync(
        NpgsqlConnection conn,
        List<BarraCentroCostoItem> barrasCC,
        List<BarraMesFlujoItem> barrasMeses,
        Action<decimal, decimal, bool> callbackTotales,
        string? loteCodigoFiltro = null)
    {
        // 1. Centros de Costo estándar con colores visuales de alta gama (alineados con CC-ALIM, CC-NOM, CC-SAN, CC-SERV, CC-MANT, CC-VET)
        var dictCC = new Dictionary<string, BarraCentroCostoItem>(StringComparer.OrdinalIgnoreCase)
        {
            ["CC-ALIM"] = new() { Codigo = "CC-ALIM", Nombre = "Alimento Balanceado", ColorHex = "#D97706" },
            ["CC-NOM"]  = new() { Codigo = "CC-NOM",  Nombre = "Mano de Obra & Labores", ColorHex = "#4F46E5" },
            ["CC-SAN"]  = new() { Codigo = "CC-SAN",  Nombre = "Sanidad & Plan Vacunal", ColorHex = "#0D9488" },
            ["CC-SERV"] = new() { Codigo = "CC-SERV", Nombre = "Servicios & Energía", ColorHex = "#64748B" },
            ["CC-MANT"] = new() { Codigo = "CC-MANT", Nombre = "Mantenimiento Naves", ColorHex = "#EA580C" },
            ["CC-VET"]  = new() { Codigo = "CC-VET",  Nombre = "Asesoría Veterinaria", ColorHex = "#0284C7" }
        };

        // 2. Inicializar los últimos 6 meses en orden cronológico
        var dictMeses = new Dictionary<string, BarraMesFlujoItem>();
        var fechaActual = DateTime.Today;
        for (int i = 5; i >= 0; i--)
        {
            var dt = fechaActual.AddMonths(-i);
            var clave = $"{dt.Year}-{dt.Month:D2}";
            var nombreMes = dt.ToString("MMM yyyy", new System.Globalization.CultureInfo("es-ES"));
            nombreMes = char.ToUpper(nombreMes[0]) + nombreMes.Substring(1);
            dictMeses[clave] = new BarraMesFlujoItem
            {
                MesNombre = nombreMes,
                MesNumero = dt.Month,
                Anio = dt.Year,
                IngresosUsd = 0m,
                GastosUsd = 0m
            };
        }

        decimal totalIngresosGlobal = 0m;
        decimal totalGastosGlobal = 0m;
        bool tieneDatos = false;

        try
        {
            bool conFiltroLote = !string.IsNullOrEmpty(loteCodigoFiltro) && loteCodigoFiltro != "TODOS";
            string filtroPres = conFiltroLote ? "WHERE lp.codigo_lote = @filtroLote" : "";
            string filtroGast = conFiltroLote ? "WHERE lg.codigo_lote = @filtroLote" : "";

            // Consultar Centros de Costo agregando por separado para evitar producto cartesiano p x g
            string sqlCC = $@"
                WITH p_agg AS (
                    SELECT p.centro_costo_id, COALESCE(SUM(p.monto_estimado_usd), 0) AS presupuestado
                    FROM presupuestos_lote p
                    LEFT JOIN lotes_produccion lp ON p.lote_id = lp.id
                    {filtroPres}
                    GROUP BY p.centro_costo_id
                ),
                g_agg AS (
                    SELECT g.centro_costo_id, COALESCE(SUM(g.monto_usd), 0) AS ejecutado
                    FROM gastos_operativos_transacciones g
                    LEFT JOIN lotes_produccion lg ON g.lote_id = lg.id
                    {filtroGast}
                    GROUP BY g.centro_costo_id
                )
                SELECT cc.codigo, cc.nombre,
                       COALESCE(p_agg.presupuestado, 0) AS presupuestado,
                       COALESCE(g_agg.ejecutado, 0) AS ejecutado
                FROM centros_costo cc
                LEFT JOIN p_agg ON p_agg.centro_costo_id = cc.id
                LEFT JOIN g_agg ON g_agg.centro_costo_id = cc.id
                ORDER BY cc.codigo;";

            await using (var cmdCC = new NpgsqlCommand(sqlCC, conn))
            {
                if (!string.IsNullOrEmpty(loteCodigoFiltro) && loteCodigoFiltro != "TODOS")
                {
                    cmdCC.Parameters.AddWithValue("filtroLote", loteCodigoFiltro);
                }
                await using var rCC = await cmdCC.ExecuteReaderAsync();
                while (await rCC.ReadAsync())
                {
                    var cod = rCC.GetString(0);
                    var nom = rCC.GetString(1);
                    var pres = rCC.GetDecimal(2);
                    var ejec = rCC.GetDecimal(3);

                    if (dictCC.TryGetValue(cod, out var item))
                    {
                        item.PresupuestadoUsd = pres;
                        item.EjecutadoUsd = ejec;
                        item.DesviacionPct = pres > 0 ? Math.Round(((ejec - pres) / pres) * 100m, 1) : 0m;
                    }
                    else
                    {
                        dictCC[cod] = new BarraCentroCostoItem
                        {
                            Codigo = cod,
                            Nombre = nom,
                            PresupuestadoUsd = pres,
                            EjecutadoUsd = ejec,
                            DesviacionPct = pres > 0 ? Math.Round(((ejec - pres) / pres) * 100m, 1) : 0m,
                            ColorHex = "#566342"
                        };
                    }
                    if (pres > 0 || ejec > 0) tieneDatos = true;
                }
            }

            // Consultar Ventas por mes
            string sqlVentas = @"
                SELECT TO_CHAR(COALESCE(lv.fecha_venta, lv.fecha_liquidacion, CURRENT_DATE), 'YYYY-MM') as mes_clave,
                       COALESCE(SUM(COALESCE(lv.ingreso_neto_usd, lv.monto_bruto_usd, lv.ingreso_bruto_total_usd, 0)), 0) as total_ventas
                FROM liquidaciones_ventas lv
                LEFT JOIN lotes_produccion l ON lv.lote_id = l.id " +
                (!string.IsNullOrEmpty(loteCodigoFiltro) && loteCodigoFiltro != "TODOS" ? " WHERE l.codigo_lote = @filtroLote " : "") + @"
                GROUP BY mes_clave;";

            await using (var cmdV = new NpgsqlCommand(sqlVentas, conn))
            {
                if (!string.IsNullOrEmpty(loteCodigoFiltro) && loteCodigoFiltro != "TODOS")
                {
                    cmdV.Parameters.AddWithValue("filtroLote", loteCodigoFiltro);
                }
                await using var rV = await cmdV.ExecuteReaderAsync();
                while (await rV.ReadAsync())
                {
                    var clave = rV.GetString(0);
                    var monto = rV.GetDecimal(1);
                    totalIngresosGlobal += monto;
                    if (dictMeses.TryGetValue(clave, out var itemMes))
                    {
                        itemMes.IngresosUsd += monto;
                    }
                    if (monto > 0) tieneDatos = true;
                }
            }

            // Consultar Gastos por mes
            string sqlGastos = @"
                SELECT TO_CHAR(gt.fecha_contable, 'YYYY-MM') as mes_clave,
                       COALESCE(SUM(gt.monto_usd), 0) as total_gastos
                FROM gastos_operativos_transacciones gt
                LEFT JOIN lotes_produccion l ON gt.lote_id = l.id " +
                (!string.IsNullOrEmpty(loteCodigoFiltro) && loteCodigoFiltro != "TODOS" ? " WHERE l.codigo_lote = @filtroLote " : "") + @"
                GROUP BY mes_clave;";

            await using (var cmdG = new NpgsqlCommand(sqlGastos, conn))
            {
                if (!string.IsNullOrEmpty(loteCodigoFiltro) && loteCodigoFiltro != "TODOS")
                {
                    cmdG.Parameters.AddWithValue("filtroLote", loteCodigoFiltro);
                }
                await using var rG = await cmdG.ExecuteReaderAsync();
                while (await rG.ReadAsync())
                {
                    var clave = rG.GetString(0);
                    var monto = rG.GetDecimal(1);
                    totalGastosGlobal += monto;
                    if (dictMeses.TryGetValue(clave, out var itemMes))
                    {
                        itemMes.GastosUsd += monto;
                    }
                    if (monto > 0) tieneDatos = true;
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Nota al consolidar diagramas de barras financieros desde PostgreSQL");
        }

        barrasCC.AddRange(dictCC.Values);
        barrasMeses.AddRange(dictMeses.Values);
        callbackTotales(totalIngresosGlobal, totalGastosGlobal, tieneDatos);
    }

    // CRUD: Crear o Presupuestar Rubro en Lote
    public async Task<bool> CrearPresupuestoRubroAsync(Guid loteId, Guid centroCostoId, string rubroNombre, decimal montoEstimado)
    {
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Insertar o actualizar presupuesto_lote (uq_lote_centro_costo)
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO presupuestos_lote (lote_id, centro_costo_id, rubro_nombre, monto_estimado_usd, monto_ejecutado_usd, porcentaje_desviacion, estado_bi)
            VALUES (@lote, @cc, @nombre, @monto, 0, 0, 'optimo')
            ON CONFLICT (lote_id, centro_costo_id) DO UPDATE 
            SET rubro_nombre = EXCLUDED.rubro_nombre,
                monto_estimado_usd = EXCLUDED.monto_estimado_usd,
                porcentaje_desviacion = CASE WHEN EXCLUDED.monto_estimado_usd > 0 THEN ROUND(((presupuestos_lote.monto_ejecutado_usd - EXCLUDED.monto_estimado_usd) / EXCLUDED.monto_estimado_usd) * 100, 2) ELSE 0 END,
                updated_at = NOW();", conn))
        {
            cmd.Parameters.AddWithValue("lote", loteId);
            cmd.Parameters.AddWithValue("cc", centroCostoId);
            cmd.Parameters.AddWithValue("nombre", rubroNombre);
            cmd.Parameters.AddWithValue("monto", montoEstimado);
            await cmd.ExecuteNonQueryAsync();
        }

        // 2. Actualizar presupuesto total asignado en lotes_produccion
        await using (var cmd = new NpgsqlCommand(@"
            UPDATE lotes_produccion 
            SET presupuesto_asignado_usd = (SELECT COALESCE(SUM(monto_estimado_usd), 0) FROM presupuestos_lote WHERE lote_id = @lote)
            WHERE id = @lote;", conn))
        {
            cmd.Parameters.AddWithValue("lote", loteId);
            await cmd.ExecuteNonQueryAsync();
        }

        InvalidarCachesOperativos();
        return true;
    }

    // CRUD: Actualizar Rubro Presupuestario
    public async Task<bool> ActualizarPresupuestoRubroAsync(Guid id, string rubroNombre, decimal montoEstimado)
    {
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        Guid loteId;
        await using (var cmd = new NpgsqlCommand(@"
            UPDATE presupuestos_lote 
            SET rubro_nombre = @nombre,
                monto_estimado_usd = @monto,
                porcentaje_desviacion = CASE WHEN @monto > 0 THEN ROUND(((monto_ejecutado_usd - @monto) / @monto) * 100, 2) ELSE 0 END,
                updated_at = NOW()
            WHERE id = @id
            RETURNING lote_id;", conn))
        {
            cmd.Parameters.AddWithValue("id", id);
            cmd.Parameters.AddWithValue("nombre", rubroNombre);
            cmd.Parameters.AddWithValue("monto", montoEstimado);
            var res = await cmd.ExecuteScalarAsync();
            if (res == null) return false;
            loteId = (Guid)res;
        }

        // Recalcular lote
        await using (var cmd = new NpgsqlCommand(@"
            UPDATE lotes_produccion 
            SET presupuesto_asignado_usd = (SELECT COALESCE(SUM(monto_estimado_usd), 0) FROM presupuestos_lote WHERE lote_id = @lote)
            WHERE id = @lote;", conn))
        {
            cmd.Parameters.AddWithValue("lote", loteId);
            await cmd.ExecuteNonQueryAsync();
        }

        InvalidarCachesOperativos();
        return true;
    }

    // CRUD: Eliminar Rubro Presupuestario
    public async Task<bool> EliminarPresupuestoRubroAsync(Guid id)
    {
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        Guid loteId;
        await using (var cmd = new NpgsqlCommand("DELETE FROM presupuestos_lote WHERE id = @id RETURNING lote_id;", conn))
        {
            cmd.Parameters.AddWithValue("id", id);
            var res = await cmd.ExecuteScalarAsync();
            if (res == null) return false;
            loteId = (Guid)res;
        }

        await using (var cmd = new NpgsqlCommand(@"
            UPDATE lotes_produccion 
            SET presupuesto_asignado_usd = (SELECT COALESCE(SUM(monto_estimado_usd), 0) FROM presupuestos_lote WHERE lote_id = @lote)
            WHERE id = @lote;", conn))
        {
            cmd.Parameters.AddWithValue("lote", loteId);
            await cmd.ExecuteNonQueryAsync();
        }

        InvalidarCachesOperativos();
        return true;
    }

    // CRUD: Registrar Gasto Operativo Real (Mano de Obra, Servicios, Alimentos, etc.)
    public async Task<bool> RegistrarGastoOperativoAsync(Guid loteId, Guid centroCostoId, string tipoMovimiento, decimal monto, string concepto, string proveedor, DateTime fecha)
    {
        // Todo el flujo (gasto + presupuesto + costo del lote) es atómico: si algo falla se revierte completo
        using var tx = new System.Transactions.TransactionScope(System.Transactions.TransactionScopeAsyncFlowOption.Enabled);
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Obtener granja_id del lote
        Guid granjaId;
        await using (var cmd = new NpgsqlCommand("SELECT granja_id FROM lotes_produccion WHERE id = @id;", conn))
        {
            cmd.Parameters.AddWithValue("id", loteId);
            var res = await cmd.ExecuteScalarAsync();
            if (res == null) return false;
            granjaId = (Guid)res;
        }

        // 2. Insertar en gastos_operativos_transacciones
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO gastos_operativos_transacciones (granja_id, lote_id, centro_costo_id, tipo_movimiento, fecha_contable, monto_usd, concepto_detalle, proveedor_factura, registrado_por_id)
            VALUES (@granja, @lote, @cc, @tipo, @fecha, @monto, @concepto, @prov, @uid);", conn))
        {
            cmd.Parameters.AddWithValue("granja", granjaId);
            cmd.Parameters.AddWithValue("lote", loteId);
            cmd.Parameters.AddWithValue("cc", centroCostoId);
            cmd.Parameters.AddWithValue("tipo", tipoMovimiento);
            cmd.Parameters.AddWithValue("fecha", fecha.Date);
            cmd.Parameters.AddWithValue("monto", monto);
            cmd.Parameters.AddWithValue("concepto", concepto);
            cmd.Parameters.AddWithValue("prov", proveedor ?? "");
            cmd.Parameters.AddWithValue("uid", CurrentUserIdDb);
            await cmd.ExecuteNonQueryAsync();
        }

        // 3. Upsert en presupuestos_lote: si no existía el rubro para este centro de costo, se inicializa automáticamente
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO presupuestos_lote (lote_id, centro_costo_id, rubro_nombre, monto_estimado_usd, monto_ejecutado_usd, porcentaje_desviacion, estado_bi)
            VALUES (
                @lote, @cc, 
                (SELECT nombre FROM centros_costo WHERE id = @cc LIMIT 1), 
                @monto, @monto, 0, 'optimo'
            )
            ON CONFLICT (lote_id, centro_costo_id) DO UPDATE 
            SET monto_ejecutado_usd = presupuestos_lote.monto_ejecutado_usd + @monto,
                porcentaje_desviacion = CASE WHEN presupuestos_lote.monto_estimado_usd > 0 
                    THEN ROUND(((presupuestos_lote.monto_ejecutado_usd + @monto - presupuestos_lote.monto_estimado_usd) / presupuestos_lote.monto_estimado_usd) * 100, 2)
                    ELSE 0 END,
                updated_at = NOW();", conn))
        {
            cmd.Parameters.AddWithValue("lote", loteId);
            cmd.Parameters.AddWithValue("cc", centroCostoId);
            cmd.Parameters.AddWithValue("monto", monto);
            await cmd.ExecuteNonQueryAsync();
        }

        // 4. Actualizar costo_acumulado_usd en lotes_produccion
        await using (var cmd = new NpgsqlCommand(@"
            UPDATE lotes_produccion 
            SET costo_acumulado_usd = costo_acumulado_usd + @monto
            WHERE id = @lote;", conn))
        {
            cmd.Parameters.AddWithValue("monto", monto);
            cmd.Parameters.AddWithValue("lote", loteId);
            await cmd.ExecuteNonQueryAsync();
        }

        tx.Complete();
        InvalidarCachesOperativos();
        return true;
    }

    // CRUD: Eliminar o Anular Gasto Operativo
    public async Task<bool> EliminarGastoOperativoAsync(Guid transaccionId)
    {
        using var tx = new System.Transactions.TransactionScope(System.Transactions.TransactionScopeAsyncFlowOption.Enabled);
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        Guid? loteId;
        Guid centroCostoId;
        decimal monto;

        await using (var cmd = new NpgsqlCommand(@"
            SELECT lote_id, centro_costo_id, monto_usd 
            FROM gastos_operativos_transacciones 
            WHERE id = @id;", conn))
        {
            cmd.Parameters.AddWithValue("id", transaccionId);
            await using var reader = await cmd.ExecuteReaderAsync();
            if (!await reader.ReadAsync()) return false;
            loteId = reader.IsDBNull(0) ? null : reader.GetGuid(0);
            centroCostoId = reader.GetGuid(1);
            monto = reader.GetDecimal(2);
        }

        // Evidencia del registro anulado (el DELETE es físico; el detalle queda en audit_log)
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO audit_log (usuario_id, accion, entidad, detalle)
            VALUES (@uid, 'GASTO_ANULADO', 'gastos_operativos_transacciones', @detalle);", conn))
        {
            cmd.Parameters.AddWithValue("uid", CurrentUserIdDb);
            cmd.Parameters.AddWithValue("detalle", $"id={transaccionId}; lote={loteId?.ToString() ?? "general"}; centro_costo={centroCostoId}; monto_usd={monto}");
            await cmd.ExecuteNonQueryAsync();
        }

        // Eliminar transacción
        await using (var cmd = new NpgsqlCommand("DELETE FROM gastos_operativos_transacciones WHERE id = @id;", conn))
        {
            cmd.Parameters.AddWithValue("id", transaccionId);
            await cmd.ExecuteNonQueryAsync();
        }

        if (loteId.HasValue)
        {
            // Descontar de presupuestos_lote
            await using (var cmd = new NpgsqlCommand(@"
                UPDATE presupuestos_lote 
                SET monto_ejecutado_usd = GREATEST(0, monto_ejecutado_usd - @monto),
                    porcentaje_desviacion = CASE WHEN monto_estimado_usd > 0 
                        THEN ROUND(((GREATEST(0, monto_ejecutado_usd - @monto) - monto_estimado_usd) / monto_estimado_usd) * 100, 2)
                        ELSE 0 END,
                    updated_at = NOW()
                WHERE lote_id = @lote AND centro_costo_id = @cc;", conn))
            {
                cmd.Parameters.AddWithValue("monto", monto);
                cmd.Parameters.AddWithValue("lote", loteId.Value);
                cmd.Parameters.AddWithValue("cc", centroCostoId);
                await cmd.ExecuteNonQueryAsync();
            }

            // Descontar de lotes_produccion
            await using (var cmd = new NpgsqlCommand(@"
                UPDATE lotes_produccion 
                SET costo_acumulado_usd = GREATEST(0, costo_acumulado_usd - @monto)
                WHERE id = @lote;", conn))
            {
                cmd.Parameters.AddWithValue("monto", monto);
                cmd.Parameters.AddWithValue("lote", loteId.Value);
                await cmd.ExecuteNonQueryAsync();
            }
        }

        tx.Complete();
        InvalidarCachesOperativos();
        return true;
    }

    // =========================================================================
    // 3. PLANIFICACIÓN Y APERTURA DE LOTES
    // =========================================================================
    public async Task<LotesPageViewModel> GetLotesPlanningDataAsync(string? rolRaw = null)
    {
        var rol = ResolveRole(rolRaw);
        var model = new LotesPageViewModel();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 3.1 Naves disponibles
        await using (var cmd = new NpgsqlCommand("SELECT id, codigo, nombre, capacidad_cabezas, temperatura_ambiente_celsius, humedad_relativa_pct FROM galpones_naves WHERE activo = true ORDER BY codigo;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.Galpones.Add(new GalponItem
                {
                    Id = reader.GetGuid(0),
                    Codigo = reader.GetString(1),
                    Nombre = reader.GetString(2),
                    Capacidad = reader.GetInt32(3),
                    Temperatura = reader.GetDecimal(4),
                    Humedad = reader.GetDecimal(5)
                });
            }
        }

        // 3.2 Líneas genéticas
        await using (var cmd = new NpgsqlCommand("SELECT id, nombre, casa_genetica FROM lineas_geneticas ORDER BY nombre;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.LineasGeneticas.Add(new LineaGeneticaItem
                {
                    Id = reader.GetGuid(0),
                    Nombre = reader.GetString(1),
                    Casa = reader.GetString(2)
                });
            }
        }

        // 3.3 Silos
        await using (var cmd = new NpgsqlCommand("SELECT COALESCE(SUM(nivel_actual_kg), 0) / 1000.0 FROM silos_depositos;", conn))
        {
            var res = await cmd.ExecuteScalarAsync();
            model.ToneladasAlimentoSilo = res != null ? Convert.ToDecimal(res) : 42.5m;
        }

        // 3.4 Lotes coexistentes y disponibles para el simulador de fecha tope
        await using (var cmd = new NpgsqlCommand(@"
            SELECT l.id, l.codigo_lote, l.etapa_zootecnica, l.cantidad_actual_cabezas, 
                   COALESCE(l.peso_promedio_actual_kg, 43.1), g.nombre, 
                   (CURRENT_DATE - l.fecha_inicio) as dias,
                   l.fecha_inicio, l.fecha_cierre_estimada
            FROM lotes_produccion l
            LEFT JOIN galpones_naves g ON l.galpon_id = g.id
            WHERE l.estado = 'activo'
            ORDER BY l.fecha_inicio DESC;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                var id = reader.GetGuid(0);
                var codigo = reader.GetString(1);
                var etapa = reader.GetString(2);
                var cabezas = reader.GetInt32(3);
                var pesoKg = reader.GetDecimal(4);
                var galpon = reader.IsDBNull(5) ? "" : reader.GetString(5);
                var dias = reader.GetInt32(6);
                var fechaIni = reader.GetDateTime(7);
                DateTime? fechaCierre = reader.IsDBNull(8) ? null : reader.GetDateTime(8);

                if (model.LotesCoexistentes.Count < 5)
                {
                    model.LotesCoexistentes.Add(new LoteCoexistenteItem
                    {
                        Codigo = codigo,
                        Etapa = etapa,
                        Cabezas = cabezas,
                        PesoPromedioKg = pesoKg,
                        Galpon = galpon,
                        DiasCiclo = dias
                    });
                }

                model.LotesDisponibles.Add(new LoteSelectorItem
                {
                    Id = id,
                    Codigo = codigo,
                    Etapa = etapa,
                    Cabezas = cabezas,
                    PesoPromedioKg = pesoKg,
                    Galpon = galpon,
                    FechaInicio = fechaIni,
                    FechaCierreEstimada = fechaCierre
                });
            }
        }

        // Si no existen lotes activos en BD, suministrar lote canónico de engorde
        if (model.LotesDisponibles.Count == 0)
        {
            model.LotesDisponibles.Add(new LoteSelectorItem
            {
                Id = Guid.NewGuid(),
                Codigo = "LOT-ENG-2026-01",
                Etapa = "engorde_intensivo",
                Cabezas = 150,
                PesoPromedioKg = 43.1m, // ~95 lbs
                Galpon = "Galpón 2 (Engorde)",
                FechaInicio = DateTime.Today.AddDays(-30),
                FechaCierreEstimada = DateTime.Today.AddDays(60)
            });
        }

        var loteDefecto = model.LotesDisponibles.FirstOrDefault(l => l.Etapa.Contains("engorde")) 
                          ?? model.LotesDisponibles.First();

        model.ProyeccionDefecto = await CalcularProyeccionLoteFechaTopeAsync(
            loteId: loteDefecto.Id,
            cabezas: loteDefecto.Cabezas,
            pesoActualLbs: loteDefecto.PesoPromedioLbs,
            fechaTope: loteDefecto.FechaCierreEstimada ?? DateTime.Today.AddDays(60)
        );
        if (rol != AppRoles.Administrador)
            model.ProyeccionDefecto?.LimpiarDatosFinancieros();

        model.GalponesTecnicos = await GetGalponesConJaulasAsync();

        return model;
    }

    public async Task<ProyeccionLoteFechaTopeResult> CalcularProyeccionLoteFechaTopeAsync(
        Guid? loteId = null,
        int? cabezas = null,
        decimal? pesoActualLbs = null,
        DateTime? fechaTope = null,
        decimal? gmdLbs = null,
        decimal? consumoDiarioLbs = null,
        decimal? precioAlimentoQuintalNio = null,
        decimal? precioVentaLibraPieNio = null,
        decimal? costoManoObraPorCerdoNio = null,
        decimal? costoSanitarioPorCerdoNio = null,
        decimal? costosIndirectosPorCerdoNio = null,
        decimal? tipoCambioNioUsd = null)
    {
        string codigoLote = "LOT-ENGORDE-SIM";
        string etapa = "engorde_intensivo";
        DateTime fechaInicio = DateTime.Today.AddDays(-30);
        DateTime targetDate = fechaTope ?? DateTime.Today.AddDays(60);
        int finalCabezas = cabezas ?? 150;
        decimal finalPesoLbs = pesoActualLbs ?? 95.0m;

        if (loteId.HasValue && loteId.Value != Guid.Empty)
        {
            try
            {
                await using var conn = CreateConnection();
                await conn.OpenAsync();
                await using var cmd = new NpgsqlCommand(
                    "SELECT codigo_lote, etapa_zootecnica, cantidad_actual_cabezas, peso_promedio_actual_kg, fecha_inicio, fecha_cierre_estimada FROM lotes_produccion WHERE id = @id LIMIT 1;", conn);
                cmd.Parameters.AddWithValue("id", loteId.Value);
                await using var reader = await cmd.ExecuteReaderAsync();
                if (await reader.ReadAsync())
                {
                    codigoLote = reader.GetString(0);
                    etapa = reader.GetString(1);
                    if (!cabezas.HasValue) finalCabezas = reader.GetInt32(2);
                    if (!pesoActualLbs.HasValue)
                    {
                        var kg = reader.IsDBNull(3) ? 43.1m : reader.GetDecimal(3);
                        finalPesoLbs = Math.Round(kg * 2.20462m, 1);
                    }
                    fechaInicio = reader.GetDateTime(4);
                    if (!fechaTope.HasValue && !reader.IsDBNull(5))
                    {
                        targetDate = reader.GetDateTime(5);
                    }
                }
            }
            catch { /* Continuar con fallback */ }
        }

        int diasRestantes = Math.Max(1, (int)(targetDate.Date - DateTime.Today).TotalDays);

        var result = new ProyeccionLoteFechaTopeResult
        {
            LoteId = loteId,
            CodigoLote = codigoLote,
            Etapa = etapa,
            Cabezas = Math.Max(1, finalCabezas),
            FechaInicio = fechaInicio,
            FechaTope = targetDate,
            DiasRestantes = diasRestantes,
            PesoActualLbs = Math.Max(5.0m, finalPesoLbs),
            GmdLbsDia = gmdLbs ?? 1.80m,
            ConsumoDiarioCerdoLbs = consumoDiarioLbs ?? 5.80m,
            PrecioAlimentoQuintalNio = precioAlimentoQuintalNio ?? 1400.00m,
            PrecioVentaLibraPieNio = precioVentaLibraPieNio ?? 45.00m,
            CostoManoObraPorCerdoNio = costoManoObraPorCerdoNio ?? 180.00m,
            CostoSanitarioPorCerdoNio = costoSanitarioPorCerdoNio ?? 120.00m,
            CostosIndirectosPorCerdoNio = costosIndirectosPorCerdoNio ?? 80.00m,
            TipoCambioNioUsd = tipoCambioNioUsd ?? 36.80m
        };

        return result;
    }

    public async Task<string> CrearLoteProduccionAsync(string etapa, Guid galponId, int cantidad, decimal pesoEntrada, DateTime fechaIngreso, Guid lineaGeneticaId, string procedencia)
    {
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        Guid granjaId;
        await using (var cmd = new NpgsqlCommand("SELECT granja_id FROM galpones_naves WHERE id = @id;", conn))
        {
            cmd.Parameters.AddWithValue("id", galponId);
            var res = await cmd.ExecuteScalarAsync();
            granjaId = res != null ? (Guid)res : Guid.Parse("a0000000-0000-0000-0000-000000000001");
        }

        string codigoLote = $"LOT-{fechaIngreso:yyyy-MM-dd}-{new Random().Next(10, 99)}";
        decimal presupuestoTotal = Math.Round(cantidad * 108.30m, 2);

        Guid loteId = Guid.NewGuid();
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO lotes_produccion (
                id, granja_id, galpon_id, codigo_lote, etapa_zootecnica, fase_actual,
                fecha_inicio, fecha_ingreso_fase, fecha_cierre_estimada, fecha_estimada_salida,
                cantidad_inicial_cabezas, cantidad_inicial, 
                cantidad_actual_cabezas, cantidad_actual, 
                peso_promedio_entrada_kg, peso_promedio_actual_kg,
                peso_ingreso_total_kg, peso_actual_total_kg,
                linea_genetica_id, procedencia_camada, presupuesto_asignado_usd, gmd_actual_g_dia, fcr_actual, estado
            ) VALUES (
                @id, @granja, @galpon, @codigo, @etapa, @etapa,
                @fecha, @fecha, @fechaCierre, @fechaCierre, 
                @cant, @cant,
                @cant, @cant, 
                @peso, @peso,
                ROUND(@cant * @peso, 2), ROUND(@cant * @peso, 2),
                @linea, @procedencia, @ppto, 750.0, 2.50, 'activo'
            );", conn))
        {
            cmd.Parameters.AddWithValue("id", loteId);
            cmd.Parameters.AddWithValue("granja", granjaId);
            cmd.Parameters.AddWithValue("galpon", galponId);
            cmd.Parameters.AddWithValue("codigo", codigoLote);
            cmd.Parameters.AddWithValue("etapa", etapa);
            cmd.Parameters.AddWithValue("fecha", fechaIngreso.Date);
            cmd.Parameters.AddWithValue("fechaCierre", fechaIngreso.Date.AddDays(116));
            cmd.Parameters.AddWithValue("cant", cantidad);
            cmd.Parameters.AddWithValue("peso", pesoEntrada);
            cmd.Parameters.AddWithValue("linea", lineaGeneticaId);
            cmd.Parameters.AddWithValue("procedencia", procedencia ?? "");
            cmd.Parameters.AddWithValue("ppto", presupuestoTotal);
            await cmd.ExecuteNonQueryAsync();
        }

        // Crear rubros de presupuesto automático: Alimento, Sanidad, Mano de Obra
        var rubros = new[]
        {
            (Codigo: "CC-ALIM", Nombre: "Alimento Balanceado", Pct: 0.72m),
            (Codigo: "CC-NOM", Nombre: "Mano de Obra & Operarios", Pct: 0.18m),
            (Codigo: "CC-SAN", Nombre: "Plan Vacunal & Sanidad", Pct: 0.10m)
        };

        foreach (var r in rubros)
        {
            await using var cmd = new NpgsqlCommand(@"
                INSERT INTO presupuestos_lote (lote_id, centro_costo_id, rubro_nombre, monto_estimado_usd, monto_ejecutado_usd, porcentaje_desviacion, estado_bi)
                SELECT @lote, id, @nombre, @monto, 0, 0, 'optimo'
                FROM centros_costo 
                WHERE granja_id = @granja AND codigo = @ccCodigo
                LIMIT 1;", conn);
            cmd.Parameters.AddWithValue("lote", loteId);
            cmd.Parameters.AddWithValue("nombre", r.Nombre);
            cmd.Parameters.AddWithValue("monto", Math.Round(presupuestoTotal * r.Pct, 2));
            cmd.Parameters.AddWithValue("granja", granjaId);
            cmd.Parameters.AddWithValue("ccCodigo", r.Codigo);
            await cmd.ExecuteNonQueryAsync();
        }

        InvalidarCachesOperativos();
        return codigoLote;
    }

    // =========================================================================
    // 4. CONTROL CENTRAL DE INVENTARIOS & INSUMOS (CRUD COMPLETO)
    // =========================================================================
    public async Task<decimal> GetCostoPromedioInsumoPorSkuAsync(string sku)
    {
        if (string.IsNullOrWhiteSpace(sku)) return 0m;
        await using var conn = CreateConnection();
        await conn.OpenAsync();
        await using var cmd = new NpgsqlCommand("SELECT COALESCE(costo_unitario_promedio_usd, 0) FROM insumos_catalogo WHERE codigo_sku = @sku LIMIT 1;", conn);
        cmd.Parameters.AddWithValue("sku", sku.Trim());
        var res = await cmd.ExecuteScalarAsync();
        return res != null && res != DBNull.Value ? Convert.ToDecimal(res) : 0m;
    }

    public async Task<InventarioPageViewModel> GetInventarioDataAsync(string? rolRaw = null)
    {
        await EnsureBaseEntitiesAsync();
        var rol = ResolveRole(rolRaw);
        bool esAdmin = rol == AppRoles.Administrador;
        bool esOp = rol == AppRoles.Operario;

        var model = new InventarioPageViewModel();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 4.1 Categorías disponibles
        await using (var cmd = new NpgsqlCommand("SELECT id, codigo, nombre FROM categorias_insumos ORDER BY nombre;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.CategoriasDisponibles.Add(new CategoriaSelectorItem
                {
                    Id = reader.GetGuid(0),
                    Codigo = reader.GetString(1),
                    Nombre = reader.GetString(2)
                });
            }
        }

        // 4.2 Silos y depósitos disponibles
        await using (var cmd = new NpgsqlCommand("SELECT id, codigo, nombre FROM silos_depositos ORDER BY nombre;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.SilosDisponibles.Add(new SiloSelectorItem
                {
                    Id = reader.GetGuid(0),
                    Codigo = reader.GetString(1),
                    Nombre = reader.GetString(2)
                });
            }
        }

        // 4.3 Catálogo de Insumos Activos (Operario excluye medicamentos restringidos; solo Admin recibe costos)
        var catFilterSql = esOp ? " AND c.codigo <> 'MED' " : "";
        await using (var cmd = new NpgsqlCommand($@"
            SELECT i.id, i.codigo_sku, i.nombre, c.nombre as categoria, 
                   i.unidad_medida, i.stock_actual, i.stock_minimo_seguridad, 
                   i.costo_unitario_promedio_usd, s.nombre as silo_nombre, i.estado_stock,
                   i.categoria_id, i.silo_deposito_id, COALESCE(i.descripcion, ''), c.codigo
            FROM insumos_catalogo i
            JOIN categorias_insumos c ON i.categoria_id = c.id
            LEFT JOIN silos_depositos s ON i.silo_deposito_id = s.id
            WHERE i.activo = true {catFilterSql}
            ORDER BY (i.stock_actual <= i.stock_minimo_seguridad) DESC, i.nombre;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                var item = new InsumoDbItem
                {
                    Id = reader.GetGuid(0),
                    Sku = reader.GetString(1),
                    Nombre = reader.GetString(2),
                    Categoria = reader.GetString(3),
                    CategoriaCodigo = reader.GetString(13),
                    Unidad = reader.GetString(4),
                    StockActual = reader.GetDecimal(5),
                    StockMinimo = reader.GetDecimal(6),
                    CostoUnitario = esAdmin ? reader.GetDecimal(7) : 0m,
                    Ubicacion = reader.IsDBNull(8) ? "Bodega Central" : reader.GetString(8),
                    Estado = reader.GetString(9),
                    CategoriaId = reader.GetGuid(10),
                    SiloId = reader.IsDBNull(11) ? null : reader.GetGuid(11),
                    Descripcion = reader.GetString(12)
                };
                model.Insumos.Add(item);
            }
        }

        // Totales calculados (valorización exclusivamente para Administrador)
        model.ValorTotalInventario = esAdmin ? model.Insumos.Sum(i => i.StockActual * i.CostoUnitario) : 0m;
        model.ItemsCriticosCount = model.Insumos.Count(i => i.StockActual <= i.StockMinimo || i.Estado == "reabastecer_urgente");

        // 4.4 Silos capacidad
        await using (var cmd = new NpgsqlCommand("SELECT COALESCE(SUM(nivel_actual_kg), 0) / 1000.0, COALESCE(SUM(capacidad_maxima_kg), 1) / 1000.0 FROM silos_depositos;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            if (await reader.ReadAsync())
            {
                model.ToneladasSilos = reader.GetDecimal(0);
                var cap = reader.GetDecimal(1);
                model.PorcentajeOcupacionSilos = cap > 0 ? (int)Math.Round((model.ToneladasSilos / cap) * 100) : 0;
            }
        }

        // 4.5 Últimos Movimientos Kardex (ocultar costos/valores para no-Admin)
        var kardexCatFilterSql = esOp ? " JOIN categorias_insumos c ON i.categoria_id = c.id WHERE c.codigo <> 'MED' " : "";
        await using (var cmd = new NpgsqlCommand($@"
            SELECT k.tipo_movimiento, k.cantidad, k.costo_unitario_usd, k.valor_total_usd,
                   k.fecha_hora, i.nombre, k.documento_referencia, l.codigo_lote
            FROM movimientos_kardex k
            JOIN insumos_catalogo i ON k.insumo_id = i.id
            {kardexCatFilterSql}
            LEFT JOIN lotes_produccion l ON k.lote_id = l.id
            ORDER BY k.fecha_hora DESC LIMIT 12;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.Movimientos.Add(new KardexMovimientoItem
                {
                    Tipo = reader.GetString(0),
                    Cantidad = reader.GetDecimal(1),
                    CostoUnitario = esAdmin ? reader.GetDecimal(2) : 0m,
                    ValorTotal = esAdmin ? reader.GetDecimal(3) : 0m,
                    Fecha = reader.GetDateTime(4),
                    InsumoNombre = reader.GetString(5),
                    Documento = reader.IsDBNull(6) ? "" : reader.GetString(6),
                    LoteCodigo = reader.IsDBNull(7) ? "" : reader.GetString(7)
                });
            }
        }

        return model;
    }

    // CRUD: Crear Insumo / Alimento en Catálogo
    public async Task<bool> CrearInsumoAsync(string sku, string nombre, string categoriaNombre, string unidad, decimal stockMinimo, decimal costoUnitario, string? siloNombre, decimal stockInicial, string? descripcion)
    {
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        Guid granjaId = Guid.Parse("a0000000-0000-0000-0000-000000000001");

        // 1. Obtener o crear categoría
        Guid catId;
        await using (var cmd = new NpgsqlCommand("SELECT id FROM categorias_insumos WHERE nombre ILIKE @cat OR codigo ILIKE @cat LIMIT 1;", conn))
        {
            cmd.Parameters.AddWithValue("cat", $"%{categoriaNombre}%");
            var res = await cmd.ExecuteScalarAsync();
            if (res != null)
            {
                catId = (Guid)res;
            }
            else
            {
                catId = Guid.NewGuid();
                string codCat = (categoriaNombre.Length >= 4 ? categoriaNombre[..4] : categoriaNombre).ToUpper();
                await using var insCatCmd = new NpgsqlCommand("INSERT INTO categorias_insumos (id, codigo, nombre) VALUES (@id, @cod, @nom);", conn);
                insCatCmd.Parameters.AddWithValue("id", catId);
                insCatCmd.Parameters.AddWithValue("cod", codCat);
                insCatCmd.Parameters.AddWithValue("nom", categoriaNombre);
                await insCatCmd.ExecuteNonQueryAsync();
            }
        }

        // 2. Obtener Silo
        Guid? siloId = null;
        if (!string.IsNullOrEmpty(siloNombre) && siloNombre != "Sin Silo" && siloNombre != "Bodega Central")
        {
            await using var cmd = new NpgsqlCommand("SELECT id FROM silos_depositos WHERE nombre ILIKE @silo LIMIT 1;", conn);
            cmd.Parameters.AddWithValue("silo", $"%{siloNombre}%");
            var res = await cmd.ExecuteScalarAsync();
            if (res != null) siloId = (Guid)res;
        }

        // 3. Insertar Insumo
        Guid insumoId = Guid.NewGuid();
        string estadoStock = stockInicial <= stockMinimo ? "reabastecer_urgente" : "optimo";

        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO insumos_catalogo (
                id, granja_id, categoria_id, codigo_sku, nombre, unidad_medida, 
                stock_actual, stock_minimo_seguridad, costo_unitario_promedio_usd, 
                silo_deposito_id, estado_stock, descripcion, activo
            ) VALUES (
                @id, @granja, @cat, @sku, @nombre, @unidad, 
                0, @stockMin, @costo, 
                @silo, @estado, @desc, true
            );", conn))
        {
            cmd.Parameters.AddWithValue("id", insumoId);
            cmd.Parameters.AddWithValue("granja", granjaId);
            cmd.Parameters.AddWithValue("cat", catId);
            cmd.Parameters.AddWithValue("sku", sku);
            cmd.Parameters.AddWithValue("nombre", nombre);
            cmd.Parameters.AddWithValue("unidad", unidad);
            cmd.Parameters.AddWithValue("stockMin", stockMinimo);
            cmd.Parameters.AddWithValue("costo", costoUnitario);
            cmd.Parameters.Add(new NpgsqlParameter("silo", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)siloId ?? DBNull.Value });
            cmd.Parameters.AddWithValue("estado", estadoStock);
            cmd.Parameters.AddWithValue("desc", descripcion ?? "");
            await cmd.ExecuteNonQueryAsync();
        }

        // 4. Si tiene stock inicial, registrar entrada en Kardex (el trigger 'trg_actualizar_stock_kardex' sumará a stock_actual)
        if (stockInicial > 0)
        {
            await using var cmd = new NpgsqlCommand(@"
                INSERT INTO movimientos_kardex (granja_id, insumo_id, silo_id, tipo_movimiento, fecha_hora, cantidad, costo_unitario_usd, documento_referencia, proveedor, responsable_id)
                VALUES (@granja, @insumo, @silo, 'entrada_compra', NOW(), @cant, @costo, 'Apertura de Inventario Inicial', 'Ingreso Catálogo', @uid);", conn);
            cmd.Parameters.AddWithValue("uid", CurrentUserIdDb);
            cmd.Parameters.AddWithValue("granja", granjaId);
            cmd.Parameters.AddWithValue("insumo", insumoId);
            cmd.Parameters.Add(new NpgsqlParameter("silo", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)siloId ?? DBNull.Value });
            cmd.Parameters.AddWithValue("cant", stockInicial);
            cmd.Parameters.AddWithValue("costo", costoUnitario);
            await cmd.ExecuteNonQueryAsync();
        }

        InvalidarCachesOperativos();
        return true;
    }

    // CRUD: Actualizar Insumo
    public async Task<bool> ActualizarInsumoAsync(Guid id, string nombre, string categoriaNombre, string unidad, decimal stockMinimo, decimal costoUnitario, string? siloNombre, decimal stockActual)
    {
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Obtener Silo
        Guid? siloId = null;
        if (!string.IsNullOrEmpty(siloNombre) && siloNombre != "Sin Silo" && siloNombre != "Bodega Central")
        {
            await using var cmd = new NpgsqlCommand("SELECT id FROM silos_depositos WHERE nombre ILIKE @silo LIMIT 1;", conn);
            cmd.Parameters.AddWithValue("silo", $"%{siloNombre}%");
            var res = await cmd.ExecuteScalarAsync();
            if (res != null) siloId = (Guid)res;
        }

        string estadoStock = stockActual <= stockMinimo ? "reabastecer_urgente" : "optimo";

        await using (var cmd = new NpgsqlCommand(@"
            UPDATE insumos_catalogo 
            SET nombre = @nom,
                unidad_medida = @unidad,
                stock_actual = @stockActual,
                stock_minimo_seguridad = @stockMin,
                costo_unitario_promedio_usd = @costo,
                silo_deposito_id = @silo,
                estado_stock = @estado
            WHERE id = @id;", conn))
        {
            cmd.Parameters.AddWithValue("id", id);
            cmd.Parameters.AddWithValue("nom", nombre);
            cmd.Parameters.AddWithValue("unidad", unidad);
            cmd.Parameters.AddWithValue("stockActual", stockActual);
            cmd.Parameters.AddWithValue("stockMin", stockMinimo);
            cmd.Parameters.AddWithValue("costo", costoUnitario);
            cmd.Parameters.Add(new NpgsqlParameter("silo", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)siloId ?? DBNull.Value });
            cmd.Parameters.AddWithValue("estado", estadoStock);
            await cmd.ExecuteNonQueryAsync();
        }

        InvalidarCachesOperativos();
        return true;
    }

    // CRUD: Eliminar / Desactivar Insumo
    public async Task<bool> EliminarInsumoAsync(Guid id)
    {
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // Intentar soft-delete para conservar integridad de movimientos históricos de Kardex
        await using (var cmd = new NpgsqlCommand("UPDATE insumos_catalogo SET activo = false WHERE id = @id;", conn))
        {
            cmd.Parameters.AddWithValue("id", id);
            var affected = await cmd.ExecuteNonQueryAsync();
            if (affected > 0) InvalidarCachesOperativos();
            return affected > 0;
        }
    }

    // Registro de entrada a Kardex
    public async Task<bool> RegistrarEntradaKardexAsync(string sku, string nombre, string categoriaNombre, string unidad, decimal cantidad, decimal costoUnitario, string destinoNombre)
    {
        if (cantidad <= 0) throw new InvalidOperationException("La cantidad de entrada debe ser mayor a cero.");
        using var tx = new System.Transactions.TransactionScope(System.Transactions.TransactionScopeAsyncFlowOption.Enabled);
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        Guid granjaId = Guid.Parse("a0000000-0000-0000-0000-000000000001");

        // 1. Buscar o crear insumo
        Guid insumoId;
        await using (var cmd = new NpgsqlCommand("SELECT id FROM insumos_catalogo WHERE codigo_sku = @sku;", conn))
        {
            cmd.Parameters.AddWithValue("sku", sku);
            var res = await cmd.ExecuteScalarAsync();
            if (res != null)
            {
                insumoId = (Guid)res;
            }
            else
            {
                Guid catId;
                await using (var catCmd = new NpgsqlCommand("SELECT id FROM categorias_insumos LIMIT 1;", conn))
                {
                    catId = (Guid)(await catCmd.ExecuteScalarAsync())!;
                }

                insumoId = Guid.NewGuid();
                await using var insCmd = new NpgsqlCommand(@"
                    INSERT INTO insumos_catalogo (id, granja_id, categoria_id, codigo_sku, nombre, unidad_medida, stock_actual, stock_minimo_seguridad, costo_unitario_promedio_usd)
                    VALUES (@id, @granja, @cat, @sku, @nombre, @unidad, 0, 1000, @costo);", conn);
                insCmd.Parameters.AddWithValue("id", insumoId);
                insCmd.Parameters.AddWithValue("granja", granjaId);
                insCmd.Parameters.AddWithValue("cat", catId);
                insCmd.Parameters.AddWithValue("sku", sku);
                insCmd.Parameters.AddWithValue("nombre", nombre);
                insCmd.Parameters.AddWithValue("unidad", unidad);
                insCmd.Parameters.AddWithValue("costo", costoUnitario);
                await insCmd.ExecuteNonQueryAsync();
            }
        }

        // 2. Buscar Silo
        Guid? siloId = null;
        await using (var cmd = new NpgsqlCommand("SELECT id FROM silos_depositos WHERE nombre ILIKE @nombre LIMIT 1;", conn))
        {
            cmd.Parameters.AddWithValue("nombre", $"%{destinoNombre}%");
            var res = await cmd.ExecuteScalarAsync();
            if (res != null) siloId = (Guid)res;
        }

        // 3. Insertar movimiento en Kardex (el trigger 'trg_actualizar_stock_kardex' sumará automáticamente al stock)
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO movimientos_kardex (granja_id, insumo_id, silo_id, tipo_movimiento, fecha_hora, cantidad, costo_unitario_usd, documento_referencia, proveedor, responsable_id)
            VALUES (@granja, @insumo, @silo, 'entrada_compra', NOW(), @cant, @costo, 'Recepción Directa Operador', 'Proveedor Homologado', @uid);", conn))
        {
            cmd.Parameters.AddWithValue("uid", CurrentUserIdDb);
            cmd.Parameters.AddWithValue("granja", granjaId);
            cmd.Parameters.AddWithValue("insumo", insumoId);
            cmd.Parameters.Add(new NpgsqlParameter("silo", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)siloId ?? DBNull.Value });
            cmd.Parameters.AddWithValue("cant", cantidad);
            cmd.Parameters.AddWithValue("costo", costoUnitario);
            await cmd.ExecuteNonQueryAsync();
        }

        tx.Complete();
        InvalidarCachesOperativos();
        return true;
    }

    // =========================================================================
    // 5. CONTROL REPRODUCTIVO & MANEJO SANITARIO (VETERINARIO)
    // =========================================================================
    public async Task<ReproduccionPageViewModel> GetReproduccionDataAsync()
    {
        var model = new ReproduccionPageViewModel();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 5.1 Cerdas Reproductoras
        await using (var cmd = new NpgsqlCommand(@"
            SELECT c.id, c.caravana, c.rfid, c.paridad_acumulada, c.estado_fisiologico,
                   c.estatus_sanitario, c.prolificidad_historica_media, lg.nombre as genetica
            FROM cerdas_reproductoras c
            LEFT JOIN lineas_geneticas lg ON c.linea_genetica_id = lg.id
            ORDER BY c.paridad_acumulada DESC, c.caravana;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                var cerda = new CerdaDbItem
                {
                    Id = reader.GetGuid(0),
                    Caravana = reader.GetString(1),
                    Rfid = reader.IsDBNull(2) ? "" : reader.GetString(2),
                    Paridad = reader.GetInt32(3),
                    Estado = reader.GetString(4),
                    EstatusSanitario = reader.IsDBNull(5) ? "Normal" : reader.GetString(5),
                    Prolificidad = reader.GetDecimal(6),
                    Genetica = reader.IsDBNull(7) ? "Topigs Norsvin" : reader.GetString(7)
                };
                model.Cerdas.Add(cerda);
            }
        }

        model.TotalPlantelActivo = model.Cerdas.Count;
        model.CerdasGestantes = model.Cerdas.Count(c => c.Estado.Contains("gestante", StringComparison.OrdinalIgnoreCase));
        model.CerdasLactantes = model.Cerdas.Count(c => c.Estado.Contains("lactante", StringComparison.OrdinalIgnoreCase));
        model.CerdasVacias = model.Cerdas.Count(c => c.Estado.Contains("vacia", StringComparison.OrdinalIgnoreCase));
        model.CerdasReemplazo = model.Cerdas.Count(c => c.Paridad == 0);

        // 5.2 Radar de Partos Inminentes (Vista PostgreSQL)
        await using (var cmd = new NpgsqlCommand(@"
            SELECT cerda_id, caravana, paridad_acumulada, jaula_numero, dias_gestacion_actual, 
                   dias_para_parto, pct_avance_gestacional, aviso_temporal, verraco_utilizado, estatus_sanitario
            FROM v_radar_partos_inminentes;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.RadarPartos.Add(new PartoInminenteItem
                {
                    CerdaId = reader.GetGuid(0),
                    Caravana = reader.GetString(1),
                    Paridad = reader.GetInt32(2),
                    Jaula = reader.IsDBNull(3) ? "M-04" : reader.GetString(3),
                    DiasGestacion = reader.GetInt32(4),
                    DiasParaParto = reader.GetInt32(5),
                    PctAvance = reader.GetDecimal(6),
                    AvisoTemporal = reader.GetString(7),
                    Verraco = reader.IsDBNull(8) ? "Pietrain Master" : reader.GetString(8),
                    EstatusSanitario = reader.IsDBNull(9) ? "Pre-parto OK" : reader.GetString(9)
                });
            }
        }

        // 5.3 Verracos y Dosis disponibles
        await using (var cmd = new NpgsqlCommand("SELECT id, codigo, nombre FROM verracos_semen WHERE activo = true ORDER BY nombre;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.Verracos.Add(new VerracoSelectorItem
                {
                    Id = reader.GetGuid(0),
                    Codigo = reader.GetString(1),
                    Nombre = reader.GetString(2)
                });
            }
        }

        // 5.4 Historial Reciente de Partos Registrados (PostgreSQL partos)
        await using (var cmd = new NpgsqlCommand(@"
            SELECT p.id, c.caravana, p.jaula_maternidad, p.fecha_hora_parto,
                   p.total_nacidos, p.nacidos_vivos, p.nacidos_muertos, p.momias,
                   COALESCE(p.peso_total_camada_kg, 0), COALESCE(p.peso_promedio_lechon_kg, 0),
                   COALESCE(p.condicion_corporal_cerda, 3.0), COALESCE(p.asistencia_obstetrica, false),
                   COALESCE(p.observaciones, ''), COALESCE(u.nombre, 'Equipo Veterinario')
            FROM partos p
            JOIN cerdas_reproductoras c ON p.cerda_id = c.id
            LEFT JOIN usuarios_operadores u ON p.responsable_id = u.id
            ORDER BY p.fecha_hora_parto DESC LIMIT 15;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.HistorialPartos.Add(new PartoHistoricoItem
                {
                    Id = reader.GetGuid(0),
                    Caravana = reader.GetString(1),
                    Jaula = reader.GetString(2),
                    FechaHora = reader.GetDateTime(3),
                    TotalNacidos = reader.GetInt32(4),
                    NacidosVivos = reader.GetInt32(5),
                    NacidosMuertos = reader.GetInt32(6),
                    Momias = reader.GetInt32(7),
                    PesoTotalCamadaKg = reader.GetDecimal(8),
                    PesoPromedioLechonKg = reader.GetDecimal(9),
                    CondicionCorporal = reader.GetDecimal(10),
                    AsistenciaObstetrica = reader.GetBoolean(11),
                    Observaciones = reader.GetString(12),
                    AtendidoPor = reader.GetString(13)
                });
            }
        }

        // 5.5 Lotes de destino para lechones
        await using (var cmd = new NpgsqlCommand("SELECT id, codigo_lote, etapa_zootecnica FROM lotes_produccion WHERE estado = 'activo' ORDER BY codigo_lote;", conn))
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            while (await reader.ReadAsync())
            {
                model.LotesDestino.Add(new LoteSelectorItem
                {
                    Id = reader.GetGuid(0),
                    Codigo = reader.GetString(1),
                    Etapa = reader.GetString(2)
                });
            }
        }

        return model;
    }

    public async Task<bool> RegistrarServicioIaAsync(string caravana, Guid? verracoId, string tecnica, string turno, DateTime fecha)
    {
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Buscar cerda_id por caravana
        Guid cerdaId;
        await using (var cmd = new NpgsqlCommand("SELECT id FROM cerdas_reproductoras WHERE caravana ILIKE @c LIMIT 1;", conn))
        {
            cmd.Parameters.AddWithValue("c", caravana.Split(' ')[0]);
            var res = await cmd.ExecuteScalarAsync();
            if (res == null) return false;
            cerdaId = (Guid)res;
        }

        // 2. Si verracoId es nulo, tomar el primer verraco disponible
        if (!verracoId.HasValue)
        {
            await using var cmd = new NpgsqlCommand("SELECT id FROM verracos_semen LIMIT 1;", conn);
            var res = await cmd.ExecuteScalarAsync();
            if (res != null) verracoId = (Guid)res;
        }

        // 3. Insertar servicio IA
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO servicios_inseminacion (cerda_id, verraco_id, fecha_servicio, turno, tecnica_ia, resultado_concepcion, responsable_id)
            VALUES (@cerda, @verraco, @fecha, @turno, @tecnica, 'pendiente', @uid);", conn))
        {
            cmd.Parameters.AddWithValue("uid", CurrentUserIdDb);
            cmd.Parameters.AddWithValue("cerda", cerdaId);
            cmd.Parameters.Add(new NpgsqlParameter("verraco", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)verracoId ?? DBNull.Value });
            cmd.Parameters.AddWithValue("fecha", fecha.Date);
            cmd.Parameters.AddWithValue("turno", turno ?? "1_dosis_manana");
            cmd.Parameters.AddWithValue("tecnica", tecnica ?? "inseminacion_post_cervical");
            await cmd.ExecuteNonQueryAsync();
        }

        // 4. Programar automáticamente tareas clave de seguimiento gestacional (+21d celo/eco y +107d traslado a maternidad)
        var caravanaLimpia = caravana.Split(' ')[0].Trim();
        var granjaId = Guid.Parse("a0000000-0000-0000-0000-000000000001");
        await using (var cmdT1 = new NpgsqlCommand(@"
            INSERT INTO tareas_actividades_granja (
                granja_id, titulo, descripcion, tipo_actividad, prioridad, estado,
                fecha_programada, hora_programada, galpon_nombre, cerda_caravana, asignado_a_rol
            ) VALUES (
                @granja, @t1, @d1, 'Reproduccion', 'Alta', 'pendiente', @f1, '08:00', 'Nave Gestación', @cerda, 'Veterinario'
            ), (
                @granja, @t2, @d2, 'Bioseguridad', 'Alta', 'pendiente', @f2, '09:00', 'Maternidad', @cerda, 'Operario'
            );", conn))
        {
            cmdT1.Parameters.AddWithValue("granja", granjaId);
            cmdT1.Parameters.AddWithValue("cerda", caravanaLimpia);
            cmdT1.Parameters.AddWithValue("t1", $"Revisión retorno a celo / Ecografía ({caravanaLimpia})");
            cmdT1.Parameters.AddWithValue("d1", $"Control reproductivo día 21 post-servicio para cerda {caravanaLimpia}.");
            cmdT1.Parameters.AddWithValue("f1", fecha.Date.AddDays(21));
            cmdT1.Parameters.AddWithValue("t2", $"Lavado y traslado a Maternidad ({caravanaLimpia})");
            cmdT1.Parameters.AddWithValue("d2", $"Preparación pre-parto (día 107 de gestación) para cerda {caravanaLimpia}.");
            cmdT1.Parameters.AddWithValue("f2", fecha.Date.AddDays(107));
            await cmdT1.ExecuteNonQueryAsync();
        }

        InvalidarCachesOperativos();
        return true;
    }

    // =========================================================================
    // 6. REGISTRO DE PARTOS (SECUENCIA REPRODUCTIVA COMPLETA)
    // =========================================================================
    public async Task<bool> RegistrarPartoAsync(
        Guid cerdaId,
        int totalNacidos,
        int nacidosVivos,
        int nacidosMuertos,
        int momias,
        decimal pesoTotalCamadaKg,
        string jaulaMaternidad,
        bool asistenciaObstetrica,
        decimal condicionCorporal,
        int duracionMinutos,
        string observaciones,
        DateTime fechaHora)
    {
        if (nacidosVivos < 0 || nacidosMuertos < 0 || momias < 0 || pesoTotalCamadaKg < 0)
            throw new InvalidOperationException("Los valores del parto no pueden ser negativos.");
        if (totalNacidos != nacidosVivos + nacidosMuertos + momias)
            throw new InvalidOperationException("El total de nacidos debe ser igual a vivos + muertos + momias.");

        using var tx = new System.Transactions.TransactionScope(System.Transactions.TransactionScopeAsyncFlowOption.Enabled);
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Obtener servicio IA activo, galpon_id y caravana de la cerda
        Guid? servicioIaId = null;
        Guid? galponId = null;
        string caravanaCerda = "Cerda";

        await using (var cmd = new NpgsqlCommand(@"
            SELECT s.id, c.galpon_id, c.caravana
            FROM cerdas_reproductoras c
            LEFT JOIN servicios_inseminacion s ON s.cerda_id = c.id AND s.resultado_concepcion IN ('pendiente', 'confirmada_positiva')
            WHERE c.id = @id
            ORDER BY s.fecha_servicio DESC LIMIT 1;", conn))
        {
            cmd.Parameters.AddWithValue("id", cerdaId);
            await using var reader = await cmd.ExecuteReaderAsync();
            if (await reader.ReadAsync())
            {
                if (!reader.IsDBNull(0)) servicioIaId = reader.GetGuid(0);
                if (!reader.IsDBNull(1)) galponId = reader.GetGuid(1);
                if (!reader.IsDBNull(2)) caravanaCerda = reader.GetString(2);
            }
        }

        decimal pesoPromedioLechon = nacidosVivos > 0 
            ? Math.Round(pesoTotalCamadaKg / nacidosVivos, 2) 
            : 0;

        // 2. Insertar en tabla partos
        Guid partoId = Guid.NewGuid();
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO partos (
                id, cerda_id, servicio_ia_id, galpon_id, jaula_maternidad, 
                fecha_hora_parto, duracion_minutos, total_nacidos, nacidos_vivos, 
                nacidos_muertos, momias, peso_total_camada_kg, peso_promedio_lechon_kg, 
                condicion_corporal_cerda, asistencia_obstetrica, observaciones, responsable_id
            ) VALUES (
                @id, @cerda, @servicio, @galpon, @jaula, 
                @fecha, @duracion, @total, @vivos, 
                @muertos, @momias, @pesoCamada, @pesoPromedio, 
                @condicion, @asistencia, @obs, @uid
            );", conn))
        {
            cmd.Parameters.AddWithValue("uid", CurrentUserIdDb);
            cmd.Parameters.AddWithValue("id", partoId);
            cmd.Parameters.AddWithValue("cerda", cerdaId);
            cmd.Parameters.Add(new NpgsqlParameter("servicio", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)servicioIaId ?? DBNull.Value });
            cmd.Parameters.Add(new NpgsqlParameter("galpon", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)galponId ?? DBNull.Value });
            cmd.Parameters.AddWithValue("jaula", string.IsNullOrWhiteSpace(jaulaMaternidad) ? "M-01" : jaulaMaternidad);
            cmd.Parameters.AddWithValue("fecha", fechaHora);
            cmd.Parameters.AddWithValue("duracion", duracionMinutos > 0 ? duracionMinutos : 180);
            cmd.Parameters.AddWithValue("total", totalNacidos);
            cmd.Parameters.AddWithValue("vivos", nacidosVivos);
            cmd.Parameters.AddWithValue("muertos", nacidosMuertos);
            cmd.Parameters.AddWithValue("momias", momias);
            cmd.Parameters.AddWithValue("pesoCamada", pesoTotalCamadaKg);
            cmd.Parameters.AddWithValue("pesoPromedio", pesoPromedioLechon);
            cmd.Parameters.AddWithValue("condicion", condicionCorporal > 0 ? condicionCorporal : 3.0m);
            cmd.Parameters.AddWithValue("asistencia", asistenciaObstetrica);
            cmd.Parameters.AddWithValue("obs", observaciones ?? "");
            await cmd.ExecuteNonQueryAsync();
        }

        // 3. Actualizar la cerda reproductora:
        //    Pasa a estado 'lactante_maternidad', se incrementa su paridad_acumulada y se recalcula su prolificidad media
        await using (var cmd = new NpgsqlCommand(@"
            UPDATE cerdas_reproductoras 
            SET estado_fisiologico = 'lactante_maternidad',
                paridad_acumulada = paridad_acumulada + 1,
                jaula_numero = @jaula,
                prolificidad_historica_media = (
                    SELECT ROUND(AVG(nacidos_vivos), 1) 
                    FROM partos 
                    WHERE cerda_id = @cerda
                ),
                updated_at = NOW()
            WHERE id = @cerda;", conn))
        {
            cmd.Parameters.AddWithValue("cerda", cerdaId);
            cmd.Parameters.AddWithValue("jaula", string.IsNullOrWhiteSpace(jaulaMaternidad) ? "M-01" : jaulaMaternidad);
            await cmd.ExecuteNonQueryAsync();
        }

        // 4. Si había un servicio IA activo, actualizar resultado a 'parto_efectivo' (sale del radar de partos)
        if (servicioIaId.HasValue)
        {
            await using var cmd = new NpgsqlCommand(@"
                UPDATE servicios_inseminacion 
                SET resultado_concepcion = 'parto_efectivo' 
                WHERE id = @servicio;", conn);
            cmd.Parameters.AddWithValue("servicio", servicioIaId.Value);
            await cmd.ExecuteNonQueryAsync();
        }

        // 5. Programar automáticamente tareas neonatales y destete (+3d Hierro Dextrano y +21d Destete)
        var granjaParto = Guid.Parse("a0000000-0000-0000-0000-000000000001");
        var jaulaEfectiva = string.IsNullOrWhiteSpace(jaulaMaternidad) ? "M-01" : jaulaMaternidad;
        await using (var cmdTareasParto = new NpgsqlCommand(@"
            INSERT INTO tareas_actividades_granja (
                granja_id, titulo, descripcion, tipo_actividad, prioridad, estado,
                fecha_programada, hora_programada, galpon_nombre, cerda_caravana, asignado_a_rol
            ) VALUES (
                @granja, @t1, @d1, 'Sanidad', 'Urgente', 'pendiente', @f1, '07:30', @jaula, @cerda, 'Veterinario'
            ), (
                @granja, @t2, @d2, 'Reproduccion', 'Alta', 'pendiente', @f2, '08:00', @jaula, @cerda, 'Veterinario'
            );", conn))
        {
            cmdTareasParto.Parameters.AddWithValue("granja", granjaParto);
            cmdTareasParto.Parameters.AddWithValue("jaula", $"Maternidad ({jaulaEfectiva})");
            cmdTareasParto.Parameters.AddWithValue("cerda", caravanaCerda);
            cmdTareasParto.Parameters.AddWithValue("t1", $"Aplicar Hierro Dextrano + Anticoccidial ({caravanaCerda})");
            cmdTareasParto.Parameters.AddWithValue("d1", $"Protocolo neonatal día 3 para {nacidosVivos} lechones vivos en jaula {jaulaEfectiva}.");
            cmdTareasParto.Parameters.AddWithValue("f1", fechaHora.Date.AddDays(3));
            cmdTareasParto.Parameters.AddWithValue("t2", $"Destete programado y pesaje de camada ({caravanaCerda})");
            cmdTareasParto.Parameters.AddWithValue("d2", $"Cierre de lactancia (día 21) de cerda {caravanaCerda} en jaula {jaulaEfectiva}.");
            cmdTareasParto.Parameters.AddWithValue("f2", fechaHora.Date.AddDays(21));
            await cmdTareasParto.ExecuteNonQueryAsync();
        }

        tx.Complete();
        InvalidarCachesOperativos();
        return true;
    }

    // =========================================================================
    // 7. GASTOS ESPECÍFICOS SEGÚN ROL: ALIMENTACIÓN (OPERARIO) Y SANITARIOS (VETERINARIO)
    // =========================================================================
    
    // Especial Operario: Reportar consumo de alimento (imputa gasto a CC-ALIM y descuenta stock en silo/kardex)
    public async Task<bool> RegistrarConsumoAlimentoGastoAsync(
        Guid loteId,
        Guid insumoId,
        decimal cantidad,
        string unidadMedida,
        DateTime fecha,
        string observaciones,
        Guid? siloOverrideId = null)
    {
        // Kardex + gasto + presupuesto se confirman juntos o no se confirma nada
        using var tx = new System.Transactions.TransactionScope(System.Transactions.TransactionScopeAsyncFlowOption.Enabled);
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Factores de conversión a Kg según la unidad reportada
        static decimal FactorAKg(string? u)
        {
            var norm = (u ?? "kg").Trim().ToLowerInvariant();
            if (norm == "tn" || norm.Contains("ton")) return 1000m;
            if (norm == "qq" || norm.Contains("quintal") || norm.Contains("100 lb")) return 45.36m;
            if (norm.Contains("40")) return 40m;
            if (norm.Contains("25")) return 25m;
            if (norm == "lb" || norm == "lbs" || norm.Contains("libra")) return 0.453592m;
            return 1.0m;
        }

        decimal factorEntradaKg = FactorAKg(unidadMedida);
        decimal cantidadKg = Math.Round(cantidad * factorEntradaKg, 2);

        // 2. Obtener insumo, silo, unidad de catálogo y costo unitario
        decimal costoUnitario;
        decimal stockDisponible;
        Guid granjaId;
        Guid? siloId = null;
        string nombreInsumo;
        string unidadCatalogo;

        await using (var cmd = new NpgsqlCommand(@"
            SELECT costo_unitario_promedio_usd, granja_id, silo_deposito_id, nombre, stock_actual, COALESCE(unidad_medida, 'Kg')
            FROM insumos_catalogo
            WHERE id = @id;", conn))
        {
            cmd.Parameters.AddWithValue("id", insumoId);
            await using var reader = await cmd.ExecuteReaderAsync();
            if (!await reader.ReadAsync()) return false;
            costoUnitario = reader.GetDecimal(0);
            granjaId = reader.GetGuid(1);
            if (!reader.IsDBNull(2)) siloId = reader.GetGuid(2);
            nombreInsumo = reader.GetString(3);
            stockDisponible = reader.GetDecimal(4);
            unidadCatalogo = reader.GetString(5);
        }

        if (siloOverrideId.HasValue) siloId = siloOverrideId.Value;

        decimal factorCatalogoKg = FactorAKg(unidadCatalogo);
        decimal cantidadEnUnidadCatalogo = Math.Round(cantidadKg / factorCatalogoKg, 2);

        if (cantidadEnUnidadCatalogo > stockDisponible)
        {
            throw new InvalidOperationException($"Stock insuficiente de '{nombreInsumo}': solicitado {cantidadEnUnidadCatalogo:N2} {unidadCatalogo} ({cantidadKg:N2} kg), disponible {stockDisponible:N2} {unidadCatalogo}.");
        }

        decimal montoTotalUsd = Math.Round(cantidadEnUnidadCatalogo * costoUnitario, 2);

        // 3. Obtener id de Centro de Costo de Alimentación
        Guid centroCostoAlimId;
        await using (var cmd = new NpgsqlCommand("SELECT id FROM centros_costo WHERE granja_id = @granja AND codigo = 'CC-ALIM' LIMIT 1;", conn))
        {
            cmd.Parameters.AddWithValue("granja", granjaId);
            var res = await cmd.ExecuteScalarAsync();
            if (res == null) return false;
            centroCostoAlimId = (Guid)res;
        }

        // 4. Registrar salida en Kardex (en la unidad del catálogo para que el trigger descuente la magnitud correcta)
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO movimientos_kardex (granja_id, insumo_id, lote_id, silo_id, tipo_movimiento, fecha_hora, cantidad, costo_unitario_usd, documento_referencia, proveedor, responsable_id)
            VALUES (@granja, @insumo, @lote, @silo, 'salida_alimentacion_automatica', @fecha, @cant, @costo, 'Dispensación Ración de Campo', 'Operario Granja', @uid);", conn))
        {
            cmd.Parameters.AddWithValue("granja", granjaId);
            cmd.Parameters.AddWithValue("insumo", insumoId);
            cmd.Parameters.AddWithValue("lote", loteId);
            cmd.Parameters.Add(new NpgsqlParameter("silo", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)siloId ?? DBNull.Value });
            cmd.Parameters.AddWithValue("fecha", fecha);
            cmd.Parameters.AddWithValue("cant", cantidadEnUnidadCatalogo);
            cmd.Parameters.AddWithValue("costo", costoUnitario);
            cmd.Parameters.AddWithValue("uid", CurrentUserIdDb);
            await cmd.ExecuteNonQueryAsync();
        }

        // 5. Asentar el gasto operativo en gastos_operativos_transacciones
        string detalle = $"Alimentación Lote: {cantidad} {unidadMedida} ({cantidadKg:N0} Kg) de '{nombreInsumo}'. {observaciones}".Trim();
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO gastos_operativos_transacciones (granja_id, lote_id, centro_costo_id, tipo_movimiento, fecha_contable, monto_usd, concepto_detalle, proveedor_factura, registrado_por_id)
            VALUES (@granja, @lote, @cc, 'gasto_operativo', @fecha, @monto, @concepto, 'Consumo Interno Silo', @uid);", conn))
        {
            cmd.Parameters.AddWithValue("granja", granjaId);
            cmd.Parameters.AddWithValue("lote", loteId);
            cmd.Parameters.AddWithValue("cc", centroCostoAlimId);
            cmd.Parameters.AddWithValue("fecha", fecha.Date);
            cmd.Parameters.AddWithValue("monto", montoTotalUsd);
            cmd.Parameters.AddWithValue("concepto", detalle);
            cmd.Parameters.AddWithValue("uid", CurrentUserIdDb);
            await cmd.ExecuteNonQueryAsync();
        }

        // 6. Actualizar presupuestos_lote
        await using (var cmd = new NpgsqlCommand(@"
            INSERT INTO presupuestos_lote (lote_id, centro_costo_id, rubro_nombre, monto_estimado_usd, monto_ejecutado_usd, porcentaje_desviacion, estado_bi)
            VALUES (@lote, @cc, 'Alimento Balanceado', @monto, @monto, 0, 'optimo')
            ON CONFLICT (lote_id, centro_costo_id) DO UPDATE 
            SET monto_ejecutado_usd = presupuestos_lote.monto_ejecutado_usd + @monto,
                updated_at = NOW();", conn))
        {
            cmd.Parameters.AddWithValue("lote", loteId);
            cmd.Parameters.AddWithValue("cc", centroCostoAlimId);
            cmd.Parameters.AddWithValue("monto", montoTotalUsd);
            await cmd.ExecuteNonQueryAsync();
        }

        tx.Complete();
        InvalidarCachesOperativos();
        return true;
    }

    // Helpers para deducción de insumos descartables en Kardex
    private async Task<Guid?> GetInsumoIdBySkuAsync(NpgsqlConnection conn, string sku)
    {
        await using var cmd = new NpgsqlCommand("SELECT id FROM insumos_catalogo WHERE codigo_sku = @sku LIMIT 1;", conn);
        cmd.Parameters.AddWithValue("sku", sku);
        var res = await cmd.ExecuteScalarAsync();
        return res != null ? (Guid)res : null;
    }

    private async Task InsertKardexSalidaAsync(
        NpgsqlConnection conn,
        Guid granjaId,
        Guid insumoId,
        Guid? loteId,
        decimal cantidad,
        decimal costoUnitarioUsd,
        DateTime fecha,
        string docReferencia,
        bool validarStock = false)
    {
        if (validarStock)
        {
            await using var stockCmd = new NpgsqlCommand("SELECT stock_actual FROM insumos_catalogo WHERE id = @id FOR UPDATE;", conn);
            stockCmd.Parameters.AddWithValue("id", insumoId);
            var stockObj = await stockCmd.ExecuteScalarAsync();
            var stock = stockObj == null || stockObj is DBNull ? 0m : Convert.ToDecimal(stockObj);
            if (cantidad > stock)
                throw new InvalidOperationException($"Stock insuficiente: se requieren {cantidad:0.##} y solo hay {stock:0.##} disponibles.");
        }

        await using var cmd = new NpgsqlCommand(@"
            INSERT INTO movimientos_kardex (granja_id, insumo_id, lote_id, tipo_movimiento, fecha_hora, cantidad, costo_unitario_usd, documento_referencia, proveedor, responsable_id)
            VALUES (@granja, @insumo, @lote, 'salida_tratamiento_sanitario', @fecha, @cant, @costo, @doc, 'Farmacia Sanitaria Granja', @uid);", conn);
        cmd.Parameters.AddWithValue("uid", CurrentUserIdDb);
        cmd.Parameters.AddWithValue("granja", granjaId);
        cmd.Parameters.AddWithValue("insumo", insumoId);
        cmd.Parameters.Add(new NpgsqlParameter("lote", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)loteId ?? DBNull.Value });
        cmd.Parameters.AddWithValue("fecha", fecha);
        cmd.Parameters.AddWithValue("cant", cantidad);
        cmd.Parameters.AddWithValue("costo", costoUnitarioUsd);
        cmd.Parameters.AddWithValue("doc", docReferencia);
        await cmd.ExecuteNonQueryAsync();
    }

    // Especial Veterinario: Aplicar medicamento con kit completo de insumos (jeringa, aguja, yodo, torunda)
    // Descuenta en Kardex (trigger resta inventario), registra auditoría clínica e imputa gasto a CC-SAN
    public async Task<bool> RegistrarAplicacionMedicaGastoAsync(
        Guid? loteId,
        string crotalCerda,
        string galponJaula,
        Guid insumoId,
        decimal dosis,
        string unidadMedida,
        decimal volumenFrascoInicial,
        DateTime fecha,
        string diagnostico,
        string viaAdministracion,
        bool incluyeJeringa,
        string tipoJeringa,
        bool incluyeAguja,
        string tipoAguja,
        bool incluyeYodo,
        decimal volumenYodoMl,
        bool incluyeTorunda,
        string veterinarioResponsable,
        string matriculaVeterinario)
    {
        using var tx = new System.Transactions.TransactionScope(System.Transactions.TransactionScopeAsyncFlowOption.Enabled);
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Obtener datos del medicamento
        decimal costoUnitarioMed;
        Guid granjaId;
        string nombreMedicamento;

        await using (var cmd = new NpgsqlCommand(@"
            SELECT costo_unitario_promedio_usd, granja_id, nombre
            FROM insumos_catalogo
            WHERE id = @id;", conn))
        {
            cmd.Parameters.AddWithValue("id", insumoId);
            await using var reader = await cmd.ExecuteReaderAsync();
            if (!await reader.ReadAsync()) return false;
            costoUnitarioMed = reader.GetDecimal(0);
            granjaId = reader.GetGuid(1);
            nombreMedicamento = reader.GetString(2);
        }

        // Costo del fármaco
        decimal costoMedicinaUsd = Math.Round(dosis * costoUnitarioMed, 2);
        if (costoMedicinaUsd <= 0) costoMedicinaUsd = costoUnitarioMed;

        // Cálculo de saldo exacto del frasco
        decimal volInicial = volumenFrascoInicial > 0 ? volumenFrascoInicial : 100.0m;
        decimal volRestante = Math.Max(0m, volInicial - dosis);

        // 2. Obtener Centro de Costo de Sanidad (CC-SAN)
        Guid centroCostoSanId;
        await using (var cmd = new NpgsqlCommand("SELECT id FROM centros_costo WHERE granja_id = @granja AND codigo = 'CC-SAN' LIMIT 1;", conn))
        {
            cmd.Parameters.AddWithValue("granja", granjaId);
            var res = await cmd.ExecuteScalarAsync();
            if (res == null) return false;
            centroCostoSanId = (Guid)res;
        }

        // Resolver lote si es nulo
        if (!loteId.HasValue)
        {
            await using var cmd = new NpgsqlCommand("SELECT id FROM lotes_produccion WHERE estado = 'activo' LIMIT 1;", conn);
            var res = await cmd.ExecuteScalarAsync();
            if (res != null) loteId = (Guid)res;
        }

        // 3. Registrar salida del medicamento en Kardex (el trigger 'trg_actualizar_stock_kardex' restará el stock de farmacia)
        string docMed = $"Tratamiento Clínico [{crotalCerda}] Frasco {volInicial:0}ml -> {volRestante:0}ml (-{dosis:0.#}{unidadMedida})";
        await InsertKardexSalidaAsync(conn, granjaId, insumoId, loteId, dosis, costoUnitarioMed, fecha, docMed, validarStock: true);

        // 4. Procesar y Descontar el Kit Clínico Descartable (Jeringa, Aguja, Yodo, Torunda)
        var kitItems = new List<string>();
        decimal costoKitTotal = 0m;

        // 4.1 Jeringa descartable
        if (incluyeJeringa)
        {
            var jeringaId = await GetInsumoIdBySkuAsync(conn, "MED-JER-05ML");
            if (jeringaId.HasValue)
            {
                await InsertKardexSalidaAsync(conn, granjaId, jeringaId.Value, loteId, 1m, 0.12m, fecha, $"Kit Descartable [{crotalCerda}] Jeringa {tipoJeringa}");
            }
            kitItems.Add($"Jeringa {tipoJeringa} (1 ud)");
            costoKitTotal += 0.12m;
        }

        // 4.2 Aguja hipodérmica
        if (incluyeAguja)
        {
            var agujaId = await GetInsumoIdBySkuAsync(conn, "MED-AGU-16G");
            if (agujaId.HasValue)
            {
                await InsertKardexSalidaAsync(conn, granjaId, agujaId.Value, loteId, 1m, 0.06m, fecha, $"Kit Descartable [{crotalCerda}] Aguja {tipoAguja}");
            }
            kitItems.Add($"Aguja {tipoAguja} (1 ud)");
            costoKitTotal += 0.06m;
        }

        // 4.3 Antiséptico Yodo Povidona
        if (incluyeYodo)
        {
            var yodoId = await GetInsumoIdBySkuAsync(conn, "MED-YOD-10");
            decimal cantYodo = volumenYodoMl > 0 ? volumenYodoMl : 5.0m;
            if (yodoId.HasValue)
            {
                await InsertKardexSalidaAsync(conn, granjaId, yodoId.Value, loteId, cantYodo, 0.015m, fecha, $"Asepsia Cutánea [{crotalCerda}] Yodo Povidona 10%");
            }
            kitItems.Add($"Yodo Povidona 10% ({cantYodo:0} ml)");
            costoKitTotal += Math.Round(cantYodo * 0.015m, 2);
        }

        // 4.4 Torunda de algodón / gasa
        if (incluyeTorunda)
        {
            var torundaId = await GetInsumoIdBySkuAsync(conn, "MED-TOR-ALG");
            if (torundaId.HasValue)
            {
                await InsertKardexSalidaAsync(conn, granjaId, torundaId.Value, loteId, 1m, 0.03m, fecha, $"Asepsia Inyección [{crotalCerda}] Torunda Algodón");
            }
            kitItems.Add("Torunda de Algodón (1 ud)");
            costoKitTotal += 0.03m;
        }

        decimal costoTotalTratamiento = costoMedicinaUsd + costoKitTotal;
        string insumosKitDetalle = kitItems.Count > 0 ? string.Join(", ", kitItems) : "Solo medicamento";

        // 5. Asentar Registro en Tabla de Auditoría Clínica Precisa
        // Si falla, toda la transacción se revierte (no se descuenta stock sin trazabilidad clínica)
        {
            string sqlInsertAudit = @"
                INSERT INTO registro_tratamientos_clinicos (
                    granja_id, lote_id, crotal_cerda, galpon_jaula, insumo_medicamento_id,
                    nombre_medicamento, dosis_aplicada, unidad_medida, frasco_volumen_inicial,
                    frasco_volumen_restante, insumos_kit_detalle, via_administracion,
                    diagnostico, veterinario_responsable, matricula_veterinario, costo_total_usd,
                    fecha_aplicacion
                ) VALUES (
                    @granja, @lote, @crotal, @galpon, @insumo,
                    @nombreMed, @dosis, @unidad, @volInicial,
                    @volRestante, @kitDetalle, @via,
                    @diag, @vet, @mat, @costoTotal,
                    @fecha
                );";
            await using var cmdAudit = new NpgsqlCommand(sqlInsertAudit, conn);
            cmdAudit.Parameters.AddWithValue("granja", granjaId);
            cmdAudit.Parameters.Add(new NpgsqlParameter("lote", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)loteId ?? DBNull.Value });
            cmdAudit.Parameters.AddWithValue("crotal", crotalCerda);
            cmdAudit.Parameters.AddWithValue("galpon", galponJaula);
            cmdAudit.Parameters.AddWithValue("insumo", insumoId);
            cmdAudit.Parameters.AddWithValue("nombreMed", nombreMedicamento);
            cmdAudit.Parameters.AddWithValue("dosis", dosis);
            cmdAudit.Parameters.AddWithValue("unidad", unidadMedida);
            cmdAudit.Parameters.AddWithValue("volInicial", volInicial);
            cmdAudit.Parameters.AddWithValue("volRestante", volRestante);
            cmdAudit.Parameters.AddWithValue("kitDetalle", insumosKitDetalle);
            cmdAudit.Parameters.AddWithValue("via", viaAdministracion);
            cmdAudit.Parameters.AddWithValue("diag", diagnostico);
            cmdAudit.Parameters.AddWithValue("vet", veterinarioResponsable);
            cmdAudit.Parameters.AddWithValue("mat", matriculaVeterinario);
            cmdAudit.Parameters.AddWithValue("costoTotal", costoTotalTratamiento);
            cmdAudit.Parameters.AddWithValue("fecha", fecha);
            await cmdAudit.ExecuteNonQueryAsync();
        }

        // 6. Asentar gasto unificado en gastos_operativos_transacciones
        string conceptoGasto = $"Tratamiento [{crotalCerda}]: {nombreMedicamento} ({dosis}{unidadMedida}, Frasco: {volInicial:0}ml -> {volRestante:0}ml) + Kit ({insumosKitDetalle}). MVZ: {veterinarioResponsable} ({matriculaVeterinario})".Trim();
        await using (var cmdGasto = new NpgsqlCommand(@"
            INSERT INTO gastos_operativos_transacciones (granja_id, lote_id, centro_costo_id, tipo_movimiento, fecha_contable, monto_usd, concepto_detalle, proveedor_factura, registrado_por_id)
            VALUES (@granja, @lote, @cc, 'gasto_operativo', @fecha, @monto, @concepto, 'Farmacia & Sanidad Veterinaria', @uid);", conn))
        {
            cmdGasto.Parameters.AddWithValue("uid", CurrentUserIdDb);
            cmdGasto.Parameters.AddWithValue("granja", granjaId);
            cmdGasto.Parameters.Add(new NpgsqlParameter("lote", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)loteId ?? DBNull.Value });
            cmdGasto.Parameters.AddWithValue("cc", centroCostoSanId);
            cmdGasto.Parameters.AddWithValue("fecha", fecha.Date);
            cmdGasto.Parameters.AddWithValue("monto", costoTotalTratamiento);
            cmdGasto.Parameters.AddWithValue("concepto", conceptoGasto);
            await cmdGasto.ExecuteNonQueryAsync();
        }

        // 7. Actualizar presupuestos_lote si hay lote asignado
        if (loteId.HasValue)
        {
            await using var cmdPres = new NpgsqlCommand(@"
                INSERT INTO presupuestos_lote (lote_id, centro_costo_id, rubro_nombre, monto_estimado_usd, monto_ejecutado_usd, porcentaje_desviacion, estado_bi)
                VALUES (@lote, @cc, 'Plan Vacunal & Sanidad', @monto, @monto, 0, 'optimo')
                ON CONFLICT (lote_id, centro_costo_id) DO UPDATE 
                SET monto_ejecutado_usd = presupuestos_lote.monto_ejecutado_usd + @monto,
                    updated_at = NOW();", conn);
            cmdPres.Parameters.AddWithValue("lote", loteId.Value);
            cmdPres.Parameters.AddWithValue("cc", centroCostoSanId);
            await cmdPres.ExecuteNonQueryAsync();
        }

        tx.Complete();
        InvalidarCachesOperativos();
        return true;
    }

    // Sobrecarga de compatibilidad anterior
    public Task<bool> RegistrarAplicacionMedicaGastoAsync(
        Guid? loteId,
        Guid? cerdaId,
        Guid insumoId,
        decimal dosis,
        string unidadMedida,
        DateTime fecha,
        string diagnostico,
        string viaAdministracion)
    {
        return RegistrarAplicacionMedicaGastoAsync(
            loteId,
            crotalCerda: "C-104",
            galponJaula: "Maternidad A - Jaula 12",
            insumoId: insumoId,
            dosis: dosis,
            unidadMedida: unidadMedida,
            volumenFrascoInicial: 100.0m,
            fecha: fecha,
            diagnostico: diagnostico,
            viaAdministracion: viaAdministracion,
            incluyeJeringa: true,
            tipoJeringa: "5 ml Descartable",
            incluyeAguja: true,
            tipoAguja: "16G x 1.5\"",
            incluyeYodo: true,
            volumenYodoMl: 5.0m,
            incluyeTorunda: true,
            veterinarioResponsable: "Dra. Sofía Valenzuela",
            matriculaVeterinario: "MVZ-9402");
    }

    // =========================================================================
    // 6. MOTOR DE SINCRONIZACIÓN DE CAMPO OUTBOX MÓVIL (ANDROID -> POSTGRESQL)
    //    - Idempotente por client_sync_id: un reenvío no duplica gastos ni descuentos de stock.
    //    - Nunca se confirma (savedIds) algo que no se registró de verdad; lo que no se pueda
    //      aplicar se devuelve en 'rechazados' con el motivo, para que la app lo muestre.
    //    - Los responsables salen del usuario autenticado (JWT), no de valores enviados por el cliente.
    // =========================================================================
    public async Task<List<string>> ProcesarBatchSyncMovilAsync(
        SyncBatchRequest batch,
        List<SyncRechazo>? rechazados = null,
        string? usuarioNombre = null,
        string? usuarioMatricula = null,
        string? usuarioRol = null)
    {
        await EnsureBaseEntitiesAsync();
        var savedIds = new List<string>();
        rechazados ??= new List<SyncRechazo>();
        if (batch == null) return savedIds;

        var rolEfectivo = ResolveRole(usuarioRol);
        bool esAdmin = rolEfectivo == AppRoles.Administrador;
        bool puedeVet = rolEfectivo is AppRoles.Administrador or AppRoles.Veterinario;
        bool puedeOp = rolEfectivo is AppRoles.Administrador or AppRoles.Operario;

        await using var conn = CreateConnection();
        await conn.OpenAsync();

        var fechaCliente = batch.ClientTimestamp > 0
            ? DateTimeOffset.FromUnixTimeMilliseconds(batch.ClientTimestamp)
            : DateTimeOffset.UtcNow;

        async Task<bool> YaProcesadoAsync(string id)
        {
            await using var cmd = new NpgsqlCommand(
                "SELECT 1 FROM sync_outbox_logs WHERE client_sync_id = @id AND estado_procesamiento = 'procesado' LIMIT 1;", conn);
            cmd.Parameters.AddWithValue("id", id);
            return await cmd.ExecuteScalarAsync() != null;
        }

        async Task AsentarAsync(string id, string entidad, object payload, string? error)
        {
            try
            {
                await using var cmd = new NpgsqlCommand(@"
                    INSERT INTO sync_outbox_logs (
                        client_sync_id, dispositivo_id, operario_id, entidad_tipo, operacion,
                        payload_json, fecha_cliente, estado_procesamiento, mensaje_error
                    ) VALUES (
                        @id, @disp, @op, @ent, 'INSERT', @payload::jsonb, @fc, @estado, @err
                    ) ON CONFLICT (client_sync_id) DO UPDATE
                      SET fecha_servidor = NOW(),
                          estado_procesamiento = EXCLUDED.estado_procesamiento,
                          mensaje_error = EXCLUDED.mensaje_error;", conn);
                cmd.Parameters.AddWithValue("id", id);
                cmd.Parameters.AddWithValue("disp", (object?)batch.DeviceId ?? DBNull.Value);
                cmd.Parameters.AddWithValue("op", CurrentUserIdDb);
                cmd.Parameters.AddWithValue("ent", entidad);
                cmd.Parameters.AddWithValue("payload", System.Text.Json.JsonSerializer.Serialize(payload));
                cmd.Parameters.AddWithValue("fc", fechaCliente);
                cmd.Parameters.AddWithValue("estado", error == null ? "procesado" : "rechazado");
                cmd.Parameters.AddWithValue("err", (object?)error ?? DBNull.Value);
                await cmd.ExecuteNonQueryAsync();
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "No se pudo asentar el log de sincronización {Id}", id);
            }
        }

        // Ejecuta una operación de negocio y traduce el resultado a confirmado/rechazado
        async Task ProcesarAsync(string id, string entidad, object payload, Func<Task<string?>> operacion)
        {
            if (string.IsNullOrWhiteSpace(id))
            {
                rechazados.Add(new SyncRechazo(id ?? "", entidad, "Registro sin identificador."));
                return;
            }
            try
            {
                if (await YaProcesadoAsync(id)) { savedIds.Add(id); return; }

                var error = await operacion();
                await AsentarAsync(id, entidad, payload, error);
                if (error == null) savedIds.Add(id);
                else rechazados.Add(new SyncRechazo(id, entidad, error));
            }
            catch (InvalidOperationException ex)
            {
                // Regla de negocio (p. ej. stock insuficiente): no tiene sentido reintentar tal cual
                await AsentarAsync(id, entidad, payload, ex.Message);
                rechazados.Add(new SyncRechazo(id, entidad, ex.Message));
            }
            catch (Exception ex)
            {
                // Falla técnica: no se confirma ni se rechaza; la app reintentará
                _logger.LogWarning(ex, "Error técnico sincronizando {Entidad} {Id} desde la app", entidad, id);
            }
        }

        // 1. Tratamientos médicos
        foreach (var t in batch.Tratamientos)
        {
            await ProcesarAsync(t.Id, "TRATAMIENTO", t, async () =>
            {
                if (!puedeVet) return "Acceso denegado: solo el Veterinario o Administrador puede registrar tratamientos clínicos.";
                if (string.IsNullOrWhiteSpace(t.Crotal)) return "Falta el crotal del animal tratado.";
                if (string.IsNullOrWhiteSpace(t.Medicamento)) return "Falta el medicamento.";
                if (t.Dosis <= 0) return "La dosis debe ser mayor que cero.";

                Guid? insumoId = null;
                await using (var cmdFind = new NpgsqlCommand(@"
                    SELECT id FROM insumos_catalogo
                    WHERE (nombre ILIKE @nombre OR codigo_sku ILIKE @sku) AND activo = true
                    ORDER BY (lower(nombre) = lower(@exacto)) DESC, nombre
                    LIMIT 1;", conn))
                {
                    cmdFind.Parameters.AddWithValue("nombre", $"%{t.Medicamento.Trim()}%");
                    cmdFind.Parameters.AddWithValue("sku", $"%{t.Medicamento.Trim()}%");
                    cmdFind.Parameters.AddWithValue("exacto", t.Medicamento.Trim());
                    var res = await cmdFind.ExecuteScalarAsync();
                    if (res != null) insumoId = (Guid)res;
                }
                if (!insumoId.HasValue) return $"El medicamento '{t.Medicamento}' no existe en el catálogo de insumos.";

                bool sinDetalle = t.InsumosUtilizados.Count == 0;
                bool Usa(params string[] claves) => sinDetalle || t.InsumosUtilizados.Any(i => claves.Any(c => i.Contains(c, StringComparison.OrdinalIgnoreCase)));

                var ok = await RegistrarAplicacionMedicaGastoAsync(
                    loteId: null,
                    crotalCerda: t.Crotal.Trim(),
                    galponJaula: t.GalponJaula ?? "",
                    insumoId: insumoId.Value,
                    dosis: t.Dosis,
                    unidadMedida: string.IsNullOrWhiteSpace(t.Unidad) ? "ml" : t.Unidad,
                    volumenFrascoInicial: t.VolumenFrascoInicial,
                    fecha: FechaDeCliente(t.FechaAplicacion),
                    diagnostico: string.IsNullOrWhiteSpace(t.Diagnostico) ? "Tratamiento sanitario de campo" : t.Diagnostico,
                    viaAdministracion: string.IsNullOrWhiteSpace(t.ViaAdministracion) ? "Intramuscular (IM)" : t.ViaAdministracion,
                    incluyeJeringa: Usa("Jeringa"),
                    tipoJeringa: "5 ml Descartable",
                    incluyeAguja: Usa("Aguja"),
                    tipoAguja: "16G x 1.5\"",
                    incluyeYodo: Usa("Yodo"),
                    volumenYodoMl: 5.0m,
                    incluyeTorunda: Usa("Torunda", "Algodón"),
                    veterinarioResponsable: !string.IsNullOrWhiteSpace(usuarioNombre) ? usuarioNombre! : (t.VeterinarioResponsable ?? ""),
                    matriculaVeterinario: !string.IsNullOrWhiteSpace(usuarioMatricula) ? usuarioMatricula! : (t.MatriculaVeterinario ?? ""));
                return ok ? null : "No se pudo registrar la aplicación (centro de costo o insumo no disponible).";
            });
        }

        // 2. Eventos reproductivos (SERVICIO_IA / MONTA, PARTO y DESTETE)
        foreach (var ev in batch.EventosReproductivos)
        {
            await ProcesarAsync(ev.Id, "EVENTO_REPRODUCTIVO", ev, async () =>
            {
                if (!puedeVet) return "Acceso denegado: los eventos reproductivos (IA, Parto, Destete) requieren perfil Veterinario o Administrador.";
                if (string.IsNullOrWhiteSpace(ev.CrotalCerda)) return "Falta la caravana de la cerda.";

                var tipoUpper = (ev.TipoEvento ?? "").Trim().ToUpperInvariant();
                if (tipoUpper is "SERVICIO_IA" or "INSEMINACION" or "MONTA")
                {
                    Guid? verracoId = null;
                    if (!string.IsNullOrWhiteSpace(ev.VerracoCodigo))
                    {
                        await using var cmdV = new NpgsqlCommand(
                            "SELECT id FROM verracos_semen WHERE activo = true AND (codigo ILIKE @v OR nombre ILIKE @v) LIMIT 1;", conn);
                        cmdV.Parameters.AddWithValue("v", $"%{ev.VerracoCodigo.Trim()}%");
                        var rV = await cmdV.ExecuteScalarAsync();
                        if (rV != null) verracoId = (Guid)rV;
                    }

                    var tecnica = string.IsNullOrWhiteSpace(ev.TecnicaIa) ? "Post-cervical" : ev.TecnicaIa.Trim();
                    var turno = string.IsNullOrWhiteSpace(ev.TurnoIa) ? "AM" : ev.TurnoIa.Trim();
                    var okIa = await RegistrarServicioIaAsync(
                        caravana: ev.CrotalCerda.Trim(),
                        verracoId: verracoId,
                        tecnica: tecnica,
                        turno: turno,
                        fecha: FechaDeCliente(ev.FechaEvento));
                    return okIa ? null : $"La cerda '{ev.CrotalCerda}' no existe en el plantel activo.";
                }

                Guid? cerdaId = null;
                string jaula = "";
                await using (var cmdCerda = new NpgsqlCommand(
                    "SELECT id, COALESCE(jaula_numero, '') FROM cerdas_reproductoras WHERE caravana = @caravana LIMIT 1;", conn))
                {
                    cmdCerda.Parameters.AddWithValue("caravana", ev.CrotalCerda.Trim());
                    await using var r = await cmdCerda.ExecuteReaderAsync();
                    if (await r.ReadAsync()) { cerdaId = r.GetGuid(0); jaula = r.GetString(1); }
                }
                if (!cerdaId.HasValue) return $"La cerda '{ev.CrotalCerda}' no existe en el servidor.";

                decimal bcs = ev.CondicionCorporal > 0 ? Math.Clamp(ev.CondicionCorporal, 1.0m, 5.0m) : 3.0m;

                if (tipoUpper == "DESTETE")
                {
                    int destetados = ev.LechonesDestetados > 0 ? ev.LechonesDestetados : ev.LechonesVivos;
                    if (destetados <= 0 || ev.PesoCamadaKg < 0)
                        return "El destete debe registrar al menos un lechón destetado y peso válido.";

                    var fechaDestete = FechaDeCliente(ev.FechaEvento);

                    // Buscar último parto de la cerda para vincular el destete si existe
                    Guid? partoId = null;
                    DateTime? fechaParto = null;
                    await using (var cmdP = new NpgsqlCommand(
                        "SELECT id, fecha_hora_parto FROM partos WHERE cerda_id = @cerda ORDER BY fecha_hora_parto DESC LIMIT 1;", conn))
                    {
                        cmdP.Parameters.AddWithValue("cerda", cerdaId.Value);
                        await using var rP = await cmdP.ExecuteReaderAsync();
                        if (await rP.ReadAsync())
                        {
                            partoId = rP.GetGuid(0);
                            if (!rP.IsDBNull(1)) fechaParto = rP.GetDateTime(1);
                        }
                    }

                    if (partoId.HasValue)
                    {
                        int diasLactancia = fechaParto.HasValue
                            ? Math.Max(1, (int)(fechaDestete.Date - fechaParto.Value.Date).TotalDays)
                            : 21;
                        decimal pesoProm = destetados > 0 ? Math.Round(ev.PesoCamadaKg / destetados, 2) : 0m;

                        await using var cmdDest = new NpgsqlCommand(@"
                            INSERT INTO destetes (parto_id, cerda_id, fecha_destete, dias_lactancia, lechones_destetados, peso_total_destete_kg, peso_promedio_destete_kg, condicion_corporal_cerda)
                            VALUES (@parto, @cerda, @fecha, @dias, @destetados, @peso, @pesoProm, @bcs);", conn);
                        cmdDest.Parameters.AddWithValue("parto", partoId.Value);
                        cmdDest.Parameters.AddWithValue("cerda", cerdaId.Value);
                        cmdDest.Parameters.AddWithValue("fecha", fechaDestete.Date);
                        cmdDest.Parameters.AddWithValue("dias", diasLactancia);
                        cmdDest.Parameters.AddWithValue("destetados", destetados);
                        cmdDest.Parameters.AddWithValue("peso", ev.PesoCamadaKg);
                        cmdDest.Parameters.AddWithValue("pesoProm", pesoProm);
                        cmdDest.Parameters.AddWithValue("bcs", bcs);
                        await cmdDest.ExecuteNonQueryAsync();
                    }

                    await using (var cmdUpdCerda = new NpgsqlCommand(@"
                        UPDATE cerdas_reproductoras
                        SET estado_fisiologico = 'vacia_espera_celo',
                            estatus_sanitario = CASE WHEN @obs THEN 'En observación post-destete' ELSE COALESCE(estatus_sanitario, 'optimo') END,
                            updated_at = NOW()
                        WHERE id = @cerda;", conn))
                    {
                        cmdUpdCerda.Parameters.AddWithValue("cerda", cerdaId.Value);
                        cmdUpdCerda.Parameters.AddWithValue("obs", ev.MadreEnObservacion);
                        await cmdUpdCerda.ExecuteNonQueryAsync();
                    }

                    return null;
                }

                if (tipoUpper != "PARTO")
                    return $"El servidor aún no registra eventos de tipo '{ev.TipoEvento}'.";

                int total = ev.LechonesVivos + ev.LechonesMuertos + ev.Momificados;
                if (ev.LechonesVivos < 0 || ev.LechonesMuertos < 0 || ev.Momificados < 0 || ev.PesoCamadaKg < 0)
                    return "Los valores del parto no pueden ser negativos.";
                if (total == 0) return "El parto debe registrar al menos un nacido.";

                await RegistrarPartoAsync(
                    cerdaId: cerdaId.Value,
                    totalNacidos: total,
                    nacidosVivos: ev.LechonesVivos,
                    nacidosMuertos: ev.LechonesMuertos,
                    momias: ev.Momificados,
                    pesoTotalCamadaKg: ev.PesoCamadaKg,
                    jaulaMaternidad: jaula,
                    asistenciaObstetrica: (ev.TipoAsistencia ?? "").Contains("Asistido", StringComparison.OrdinalIgnoreCase)
                                          || (ev.TipoAsistencia ?? "").Contains("Distocico", StringComparison.OrdinalIgnoreCase),
                    condicionCorporal: bcs,
                    duracionMinutos: 0,
                    observaciones: $"Registrado desde la app móvil. Asistencia: {ev.TipoAsistencia}. Oxitocina: {(ev.OxitocinaAplicada ? "sí" : "no")}.",
                    fechaHora: FechaDeCliente(ev.FechaEvento));
                return null;
            });
        }

        // 3. Consumos de alimento
        foreach (var c in batch.ConsumosAlimento)
        {
            await ProcesarAsync(c.Id, "CONSUMO_ALIMENTO", c, async () =>
            {
                if (!puedeOp) return "Acceso denegado: solo el Operario o Administrador puede registrar suministros de alimento.";
                // La app envía cantidadKg / loteNombre / tipoAlimento; se aceptan ambos nombres
                var cant = c.Cantidad > 0 ? c.Cantidad : c.CantidadKg;
                var loteCod = !string.IsNullOrWhiteSpace(c.LoteCodigo) ? c.LoteCodigo : c.LoteNombre;
                var insumoNom = !string.IsNullOrWhiteSpace(c.Insumo) ? c.Insumo : c.TipoAlimento;
                if (cant <= 0) return "La cantidad debe ser mayor que cero.";
                if (string.IsNullOrWhiteSpace(loteCod)) return "Falta el lote.";
                if (string.IsNullOrWhiteSpace(insumoNom)) return "Falta el alimento.";

                Guid? loteId = null;
                await using (var cmdLote = new NpgsqlCommand("SELECT id FROM lotes_produccion WHERE codigo_lote = @cod LIMIT 1;", conn))
                {
                    cmdLote.Parameters.AddWithValue("cod", loteCod.Trim());
                    var res = await cmdLote.ExecuteScalarAsync();
                    if (res != null) loteId = (Guid)res;
                }
                if (!loteId.HasValue) return $"El lote '{loteCod}' no existe en el servidor.";

                Guid? insumoId = null;
                await using (var cmdInsumo = new NpgsqlCommand(@"
                    SELECT i.id FROM insumos_catalogo i
                    JOIN categorias_insumos cat ON i.categoria_id = cat.id
                    WHERE (i.nombre ILIKE @nombre OR i.codigo_sku ILIKE @sku) AND i.activo = true
                      AND cat.codigo IN ('ALIM', 'MP', 'PRE')
                    ORDER BY (lower(i.nombre) = lower(@exacto)) DESC, i.nombre
                    LIMIT 1;", conn))
                {
                    cmdInsumo.Parameters.AddWithValue("nombre", $"%{insumoNom.Trim()}%");
                    cmdInsumo.Parameters.AddWithValue("sku", $"%{insumoNom.Trim()}%");
                    cmdInsumo.Parameters.AddWithValue("exacto", insumoNom.Trim());
                    var res = await cmdInsumo.ExecuteScalarAsync();
                    if (res != null) insumoId = (Guid)res;
                }
                if (!insumoId.HasValue) return $"El alimento '{insumoNom}' no existe en el catálogo de insumos.";

                Guid? siloOverrideId = null;
                if (!string.IsNullOrWhiteSpace(c.SiloCodigo))
                {
                    await using var cmdSilo = new NpgsqlCommand("SELECT id FROM silos_depositos WHERE codigo ILIKE @cod OR nombre ILIKE @nom LIMIT 1;", conn);
                    cmdSilo.Parameters.AddWithValue("cod", c.SiloCodigo.Trim());
                    cmdSilo.Parameters.AddWithValue("nom", $"%{c.SiloCodigo.Trim()}%");
                    var rs = await cmdSilo.ExecuteScalarAsync();
                    if (rs != null) siloOverrideId = (Guid)rs;
                }

                var horarioInfo = string.IsNullOrWhiteSpace(c.HorarioToma) ? "" : $" [Toma: {c.HorarioToma.Trim()}]";
                var ok = await RegistrarConsumoAlimentoGastoAsync(
                    loteId: loteId.Value,
                    insumoId: insumoId.Value,
                    cantidad: cant,
                    unidadMedida: string.IsNullOrWhiteSpace(c.Unidad) ? "Kg" : c.Unidad,
                    fecha: FechaDeCliente(c.FechaRegistro),
                    observaciones: $"Reporte móvil de {(!string.IsNullOrWhiteSpace(usuarioNombre) ? usuarioNombre : c.Operario)}{horarioInfo}",
                    siloOverrideId: siloOverrideId);
                return ok ? null : "No se pudo registrar el consumo (insumo o centro de costo no disponible).";
            });
        }

        // 4. Bajas / Mortalidad de campo reportadas desde móvil
        if (batch.BajasMortalidad != null)
        {
            foreach (var b in batch.BajasMortalidad)
            {
                await ProcesarAsync(b.Id, "BAJA_MORTALIDAD", b, async () =>
                {
                    if (!puedeVet) return "Acceso denegado: solo el Veterinario o Administrador puede registrar bajas o mortalidad.";
                    if (string.IsNullOrWhiteSpace(b.Crotal)) return "Falta la caravana/crotal del animal.";
                    if (string.IsNullOrWhiteSpace(b.CausaMuerte)) return "Falta la causa clínica de muerte.";

                    await RegistrarBajaMortalidadAsync(new BajaMortalidadInputModel
                    {
                        Crotal = b.Crotal.Trim(),
                        LoteCodigo = b.LoteCodigo,
                        GalponNombre = !string.IsNullOrWhiteSpace(b.Galpon) ? b.Galpon : b.GalponLote,
                        CausaMuerte = b.CausaMuerte,
                        PesoKg = b.PesoKg,
                        EtapaZootecnica = !string.IsNullOrWhiteSpace(b.EtapaZootecnica) ? b.EtapaZootecnica : (string.IsNullOrWhiteSpace(b.Etapa) ? "Maternidad" : b.Etapa),
                        NecropsiaRealizada = b.Necropsia || b.NecropsiaRealizada == true,
                        HallazgosNecropsia = !string.IsNullOrWhiteSpace(b.Hallazgos) ? b.Hallazgos : b.HallazgosNecropsia,
                        RegistradoPor = !string.IsNullOrWhiteSpace(usuarioNombre) ? usuarioNombre : (b.RegistradoPor ?? "Operario Móvil"),
                        Observaciones = b.Observaciones,
                        FechaBaja = b.FechaBaja > 0 ? FechaDeCliente(b.FechaBaja) : null
                    });
                    return null;
                });
            }
        }

        // 5. Estado de Tareas cumplidas en campo
        if (batch.TareasEstado != null)
        {
            foreach (var te in batch.TareasEstado)
            {
                await ProcesarAsync(string.IsNullOrWhiteSpace(te.SyncId) ? te.Id : te.SyncId, "TAREA_ESTADO", te, async () =>
                {
                    if (Guid.TryParse(te.Id, out var tid))
                    {
                        if (rolEfectivo == AppRoles.Operario)
                        {
                            await using var cmdChkT = new NpgsqlCommand(
                                "SELECT COALESCE(asignado_a_rol, ''), COALESCE(asignado_a_nombre, '') FROM tareas_actividades_granja WHERE id = @tid LIMIT 1;", conn);
                            cmdChkT.Parameters.AddWithValue("tid", tid);
                            await using var rChkT = await cmdChkT.ExecuteReaderAsync();
                            if (await rChkT.ReadAsync())
                            {
                                var aRol = rChkT.GetString(0);
                                var aNom = rChkT.GetString(1);
                                bool asignadaAUser = !string.IsNullOrWhiteSpace(usuarioNombre)
                                    && aNom.Contains(usuarioNombre.Trim(), StringComparison.OrdinalIgnoreCase);
                                bool esTareaOtroRol = !asignadaAUser && (
                                    aRol.Contains("Veterinario", StringComparison.OrdinalIgnoreCase)
                                    || aRol.Contains("Administrador", StringComparison.OrdinalIgnoreCase));
                                if (esTareaOtroRol)
                                    return "Acceso denegado: esta tarea corresponde al perfil clínico o administrativo.";
                            }
                        }

                        var ok = await ActualizarEstadoTareaAsync(
                            tid,
                            te.Completada ? "completada" : (string.IsNullOrWhiteSpace(te.Estado) ? "completada" : te.Estado),
                            te.Observaciones,
                            !string.IsNullOrWhiteSpace(usuarioNombre) ? usuarioNombre : te.CompletadoPor);
                        return ok ? null : "No se encontró la tarea en el servidor.";
                    }
                    return "Identificador de tarea no válido.";
                });
            }
        }

        // 6. Labores de campo (sin monto explícito -> imputación estándar CC-NOM + bitácora) y gastos operativos
        if (batch.OperacionesGasto != null)
        {
            const decimal TarifaJornalEstandarNio = 320.00m; // C$ 320 / jornal 8h en granja pequeña
            const decimal TasaCambioOficial = 36.80m;

            foreach (var g in batch.OperacionesGasto)
            {
                await ProcesarAsync(g.Id, "OPERACION_GASTO", g, async () =>
                {
                    if (string.IsNullOrWhiteSpace(g.Concepto)) return "Falta el concepto de la labor o gasto.";

                    bool esLabor = string.Equals(g.TipoRegistro, "LABOR_CAMPO", StringComparison.OrdinalIgnoreCase)
                        || string.Equals(g.TipoRegistro, "MANO_OBRA", StringComparison.OrdinalIgnoreCase)
                        || string.Equals(g.Categoria, "Mano de Obra", StringComparison.OrdinalIgnoreCase);

                    if (!esLabor && !esAdmin)
                        return "Acceso denegado: el registro de gastos financieros directos es exclusivo del Administrador.";

                    decimal tarifaLaborNio = TarifaJornalEstandarNio;
                    if (esLabor && !esAdmin)
                    {
                        await using var cmdTar = new NpgsqlCommand(@"
                            SELECT tarifa_referencia_nio, COALESCE(rol_sugerido, 'Operario')
                            FROM catalogo_labores_campo
                            WHERE activo = TRUE AND lower(nombre) = lower(@nom)
                            LIMIT 1;", conn);
                        cmdTar.Parameters.AddWithValue("nom", g.Concepto.Trim());
                        await using var rTar = await cmdTar.ExecuteReaderAsync();
                        if (await rTar.ReadAsync())
                        {
                            var rolSug = rTar.GetString(1);
                            if (rolEfectivo == AppRoles.Operario && rolSug.Contains("Veterinario", StringComparison.OrdinalIgnoreCase))
                                return "Acceso denegado: esta labor está asignada exclusivamente al perfil Veterinario.";
                            var tDb = rTar.GetDecimal(0);
                            if (tDb > 0) tarifaLaborNio = tDb;
                        }
                    }

                    decimal factorJornal = g.HorasOJornal > 0 ? g.HorasOJornal : 1.0m;
                    decimal montoCordobasInput = esAdmin ? g.MontoCordobas : 0m;
                    decimal montoDolaresInput = esAdmin ? g.MontoDolares : 0m;
                    decimal montoNioEfectivo = montoCordobasInput > 0
                        ? montoCordobasInput
                        : (montoDolaresInput > 0
                            ? Math.Round(montoDolaresInput * TasaCambioOficial, 2)
                            : (esLabor ? Math.Round(tarifaLaborNio * factorJornal, 2) : 0m));

                    decimal montoUsdEfectivo = montoDolaresInput > 0
                        ? montoDolaresInput
                        : (montoNioEfectivo > 0 ? Math.Round(montoNioEfectivo / TasaCambioOficial, 2) : 0m);

                    if (!esLabor && montoUsdEfectivo <= 0)
                        return "El monto en dólares o córdobas debe ser mayor que cero.";

                    var granjaGasto = Guid.Parse("a0000000-0000-0000-0000-000000000001");
                    var categoriaEfectiva = esLabor && string.IsNullOrWhiteSpace(g.Categoria) ? "Mano de Obra" : g.Categoria;
                    var codigoCc = esLabor ? "CC-NOM" : CentroCostoPorCategoria(categoriaEfectiva);
                    Guid? ccId = null;
                    await using (var cmdCc = new NpgsqlCommand("SELECT id FROM centros_costo WHERE granja_id = @granja AND codigo = @cod LIMIT 1;", conn))
                    {
                        cmdCc.Parameters.AddWithValue("granja", granjaGasto);
                        cmdCc.Parameters.AddWithValue("cod", codigoCc);
                        var res = await cmdCc.ExecuteScalarAsync();
                        if (res != null) ccId = (Guid)res;
                    }
                    if (!ccId.HasValue) return $"El centro de costo '{codigoCc}' no existe en el servidor.";

                    // Resolver lote_id si GalponLote trae el código de lote o el nombre del galpón con lote activo
                    Guid? loteId = null;
                    string? loteCodigoResuelto = null;
                    if (!string.IsNullOrWhiteSpace(g.GalponLote))
                    {
                        var gl = g.GalponLote.Trim();
                        await using (var cmdL = new NpgsqlCommand(@"
                            SELECT id, codigo_lote
                            FROM lotes_produccion
                            WHERE estado = 'activo'
                              AND (codigo_lote ILIKE @exact OR @full ILIKE '%' || codigo_lote || '%')
                            ORDER BY fecha_inicio DESC
                            LIMIT 1;", conn))
                        {
                            cmdL.Parameters.AddWithValue("exact", gl);
                            cmdL.Parameters.AddWithValue("full", gl);
                            await using var rL = await cmdL.ExecuteReaderAsync();
                            if (await rL.ReadAsync())
                            {
                                loteId = rL.GetGuid(0);
                                loteCodigoResuelto = rL.GetString(1);
                            }
                        }

                        if (!loteId.HasValue)
                        {
                            await using var cmdLG = new NpgsqlCommand(@"
                                SELECT l.id, l.codigo_lote
                                FROM lotes_produccion l
                                JOIN galpones_naves gn ON gn.id = l.galpon_id
                                WHERE l.estado = 'activo'
                                  AND (gn.nombre ILIKE @gl OR gn.codigo ILIKE @gl)
                                ORDER BY l.fecha_inicio DESC
                                LIMIT 1;", conn);
                            cmdLG.Parameters.AddWithValue("gl", $"%{gl}%");
                            await using var rLG = await cmdLG.ExecuteReaderAsync();
                            if (await rLG.ReadAsync())
                            {
                                loteId = rLG.GetGuid(0);
                                loteCodigoResuelto = rLG.GetString(1);
                            }
                        }
                    }

                    var responsableEfectivo = !string.IsNullOrWhiteSpace(usuarioNombre)
                        ? usuarioNombre!
                        : (string.IsNullOrWhiteSpace(g.ResponsableNombre) ? "Operario Móvil" : g.ResponsableNombre.Trim());
                    var fechaOp = FechaDeCliente(g.FechaRegistro);

                    var detalleGasto = $"[{(esLabor ? "Labor de Campo" : categoriaEfectiva)}] {g.Concepto.Trim()}"
                        + (string.IsNullOrWhiteSpace(g.GalponLote) ? "" : $" · {g.GalponLote.Trim()}")
                        + (factorJornal > 0 && esLabor ? $" · {factorJornal:0.##} jornal(es)" : (g.HorasOJornal > 0 ? $" · {g.HorasOJornal:0.##} h/jornal" : ""))
                        + (montoNioEfectivo > 0 ? $" · C$ {montoNioEfectivo:N2}" : "")
                        + (string.IsNullOrWhiteSpace(g.Observaciones) ? "" : $". {g.Observaciones.Trim()}")
                        + $" (Resp.: {responsableEfectivo})";

                    await using (var cmdGasto = new NpgsqlCommand(@"
                        INSERT INTO gastos_operativos_transacciones (granja_id, lote_id, centro_costo_id, tipo_movimiento, fecha_contable, monto_usd, concepto_detalle, proveedor_factura, registrado_por_id)
                        VALUES (@granja, @lote, @cc, 'gasto_operativo', @fecha, @monto, @concepto, @prov, @uid);", conn))
                    {
                        cmdGasto.Parameters.AddWithValue("granja", granjaGasto);
                        cmdGasto.Parameters.Add(new NpgsqlParameter("lote", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)loteId ?? DBNull.Value });
                        cmdGasto.Parameters.AddWithValue("cc", ccId.Value);
                        cmdGasto.Parameters.AddWithValue("fecha", fechaOp.Date);
                        cmdGasto.Parameters.AddWithValue("monto", montoUsdEfectivo);
                        cmdGasto.Parameters.AddWithValue("concepto", detalleGasto);
                        cmdGasto.Parameters.AddWithValue("prov", string.IsNullOrWhiteSpace(g.NumeroComprobante)
                            ? (esLabor ? "Jornada Móvil" : "Gasto de campo")
                            : g.NumeroComprobante.Trim());
                        cmdGasto.Parameters.AddWithValue("uid", CurrentUserIdDb);
                        await cmdGasto.ExecuteNonQueryAsync();
                    }

                    // Si el gasto o labor quedó asociado a un lote, actualizar su presupuesto y costo acumulado
                    if (loteId.HasValue && montoUsdEfectivo > 0)
                    {
                        await using (var cmdPres = new NpgsqlCommand(@"
                            INSERT INTO presupuestos_lote (lote_id, centro_costo_id, rubro_nombre, monto_estimado_usd, monto_ejecutado_usd, porcentaje_desviacion, estado_bi)
                            VALUES (
                                @lote, @cc,
                                (SELECT nombre FROM centros_costo WHERE id = @cc LIMIT 1),
                                @monto, @monto, 0, 'optimo'
                            )
                            ON CONFLICT (lote_id, centro_costo_id) DO UPDATE
                            SET monto_ejecutado_usd = presupuestos_lote.monto_ejecutado_usd + @monto,
                                porcentaje_desviacion = CASE WHEN presupuestos_lote.monto_estimado_usd > 0
                                    THEN ROUND(((presupuestos_lote.monto_ejecutado_usd + @monto - presupuestos_lote.monto_estimado_usd) / presupuestos_lote.monto_estimado_usd) * 100, 2)
                                    ELSE 0 END,
                                updated_at = NOW();", conn))
                        {
                            cmdPres.Parameters.AddWithValue("lote", loteId.Value);
                            cmdPres.Parameters.AddWithValue("cc", ccId.Value);
                            cmdPres.Parameters.AddWithValue("monto", montoUsdEfectivo);
                            await cmdPres.ExecuteNonQueryAsync();
                        }

                        await using (var cmdUpdLote = new NpgsqlCommand(@"
                            UPDATE lotes_produccion
                            SET costo_acumulado_usd = COALESCE(costo_acumulado_usd, 0) + @monto,
                                updated_at = NOW()
                            WHERE id = @lote;", conn))
                        {
                            cmdUpdLote.Parameters.AddWithValue("monto", montoUsdEfectivo);
                            cmdUpdLote.Parameters.AddWithValue("lote", loteId.Value);
                            await cmdUpdLote.ExecuteNonQueryAsync();
                        }
                    }

                    // Registrar también la labor de campo completada en tareas_actividades_granja para visibilidad operativa Web
                    if (esLabor)
                    {
                        var laborTareaId = Guid.TryParse(g.Id, out var parsedLabId) ? parsedLabId : Guid.NewGuid();
                        await using var cmdLabTarea = new NpgsqlCommand(@"
                            INSERT INTO tareas_actividades_granja (
                                id, granja_id, titulo, descripcion, tipo_actividad, prioridad, estado,
                                fecha_programada, hora_programada, galpon_nombre, lote_codigo,
                                asignado_a_nombre, completado_por, fecha_completado, observaciones
                            ) VALUES (
                                @id, @granja, @titulo, @desc, 'Manejo', 'media', 'completada',
                                @fecha, @hora, @galpon, @loteCod,
                                @asig, @comp, @fComp, @obs
                            ) ON CONFLICT (id) DO NOTHING;", conn);
                        cmdLabTarea.Parameters.AddWithValue("id", laborTareaId);
                        cmdLabTarea.Parameters.AddWithValue("granja", granjaGasto);
                        cmdLabTarea.Parameters.AddWithValue("titulo", $"Labor: {g.Concepto.Trim()}");
                        cmdLabTarea.Parameters.AddWithValue("desc", detalleGasto);
                        cmdLabTarea.Parameters.AddWithValue("fecha", fechaOp.Date);
                        cmdLabTarea.Parameters.AddWithValue("hora", fechaOp.ToString("HH:mm"));
                        cmdLabTarea.Parameters.AddWithValue("galpon", string.IsNullOrWhiteSpace(g.GalponLote) ? (object)DBNull.Value : g.GalponLote.Trim());
                        cmdLabTarea.Parameters.AddWithValue("loteCod", string.IsNullOrWhiteSpace(loteCodigoResuelto) ? (object)DBNull.Value : loteCodigoResuelto);
                        cmdLabTarea.Parameters.AddWithValue("asig", responsableEfectivo);
                        cmdLabTarea.Parameters.AddWithValue("comp", responsableEfectivo);
                        cmdLabTarea.Parameters.AddWithValue("fComp", fechaOp);
                        cmdLabTarea.Parameters.AddWithValue("obs", string.IsNullOrWhiteSpace(g.Observaciones) ? (object)DBNull.Value : g.Observaciones.Trim());
                        await cmdLabTarea.ExecuteNonQueryAsync();
                    }

                    return null;
                });
            }
        }

        // 7. Cerdos registrados desde la app móvil
        if (batch.Cerdos != null)
        {
            foreach (var c in batch.Cerdos)
            {
                await ProcesarAsync(c.Id, "CERDO", c, async () =>
                {
                    var crotal = c.CrotalEfectivo;
                    if (string.IsNullOrWhiteSpace(crotal)) return "Falta el crotal del animal.";

                    Guid? jaulaId = null;
                    if (!string.IsNullOrWhiteSpace(c.Jaula))
                    {
                        await using var cmdJ = new NpgsqlCommand("SELECT id FROM jaulas_corrales WHERE codigo ILIKE @j OR nombre ILIKE @j LIMIT 1;", conn);
                        cmdJ.Parameters.AddWithValue("j", c.Jaula.Trim());
                        var rJ = await cmdJ.ExecuteScalarAsync();
                        if (rJ != null) jaulaId = (Guid)rJ;
                    }

                    var input = new CerdoInputModel
                    {
                        Crotal = crotal,
                        Rfid = c.Rfid,
                        Sexo = string.IsNullOrWhiteSpace(c.Sexo) ? "Hembra" : c.Sexo,
                        FechaNacimiento = c.FechaNacimiento > 0 
                            ? DateTimeOffset.FromUnixTimeMilliseconds(c.FechaNacimiento).Date 
                            : DateTime.Today.AddDays(-30),
                        PesoActualKg = c.PesoKg > 0 ? c.PesoKg : 15m,
                        JaulaCorralId = jaulaId,
                        LineaGenetica = string.IsNullOrWhiteSpace(c.Raza) ? "Topigs Norsvin 70" : c.Raza,
                        EstatusSanitario = "Saludable",
                        Observaciones = c.Observaciones
                    };

                    var ok = await GuardarCerdoAsync(input);
                    return ok ? null : "No se pudo registrar el cerdo en el servidor.";
                });
            }
        }

        // 8. Nuevas tareas programadas desde la app móvil
        if (batch.TareasNuevas != null)
        {
            foreach (var tn in batch.TareasNuevas)
            {
                await ProcesarAsync(tn.Id, "TAREA", tn, async () =>
                {
                    if (!puedeVet) return "Acceso denegado: solo el Veterinario o Administrador puede programar nuevas tareas.";
                    if (string.IsNullOrWhiteSpace(tn.Titulo)) return "Falta el título de la tarea.";
                    var granjaTarea = Guid.Parse("a0000000-0000-0000-0000-000000000001");
                    var tareaGuid = Guid.TryParse(tn.Id, out var parsedGuid) ? parsedGuid : Guid.NewGuid();

                    await using var cmdTn = new NpgsqlCommand(@"
                        INSERT INTO tareas_actividades_granja (
                            id, granja_id, titulo, descripcion, tipo_actividad, prioridad, estado,
                            fecha_programada, hora_programada, galpon_nombre, lote_codigo, asignado_a_nombre, observaciones
                        ) VALUES (
                            @id, @granja, @titulo, @desc, @tipo, @prio, @estado,
                            CURRENT_DATE, @hora, @galpon, @lote, @asig, @obs
                        ) ON CONFLICT (id) DO NOTHING;", conn);
                    cmdTn.Parameters.AddWithValue("id", tareaGuid);
                    cmdTn.Parameters.AddWithValue("granja", granjaTarea);
                    cmdTn.Parameters.AddWithValue("titulo", tn.Titulo.Trim());
                    cmdTn.Parameters.AddWithValue("desc", string.IsNullOrWhiteSpace(tn.Descripcion) ? (object)DBNull.Value : tn.Descripcion.Trim());
                    cmdTn.Parameters.AddWithValue("tipo", string.IsNullOrWhiteSpace(tn.TipoActividad) ? "Manejo" : tn.TipoActividad.Trim());
                    cmdTn.Parameters.AddWithValue("prio", string.IsNullOrWhiteSpace(tn.Prioridad) ? "Media" : tn.Prioridad.Trim());
                    cmdTn.Parameters.AddWithValue("estado", tn.Completada ? "completada" : "pendiente");
                    cmdTn.Parameters.AddWithValue("hora", string.IsNullOrWhiteSpace(tn.HoraProgramada) ? "08:00" : tn.HoraProgramada.Trim());
                    cmdTn.Parameters.AddWithValue("galpon", string.IsNullOrWhiteSpace(tn.GalponEtapa) ? (object)DBNull.Value : tn.GalponEtapa.Trim());
                    cmdTn.Parameters.AddWithValue("lote", string.IsNullOrWhiteSpace(tn.LoteCodigo) ? (object)DBNull.Value : tn.LoteCodigo.Trim());
                    cmdTn.Parameters.AddWithValue("asig", string.IsNullOrWhiteSpace(tn.AsignadoA) ? (object?)usuarioNombre ?? DBNull.Value : tn.AsignadoA.Trim());
                    cmdTn.Parameters.AddWithValue("obs", string.IsNullOrWhiteSpace(tn.Observaciones) ? (object)DBNull.Value : tn.Observaciones.Trim());
                    await cmdTn.ExecuteNonQueryAsync();
                    return null;
                });
            }
        }

        if (savedIds.Count > 0)
            InvalidarCachesOperativos();

        return savedIds;
    }

    // Catálogo completo (todo viene de PostgreSQL para que Web y App trabajen mediante selectores sin texto libre)
    public async Task<MovilCatalogo> ObtenerCatalogoMovilAsync(Guid? usuarioId = null, string? rolUsuario = null, string? nombreUsuario = null)
    {
        await EnsureBaseEntitiesAsync();
        var rol = ResolveRole(rolUsuario);
        var usuarioNom = ResolveUserName(nombreUsuario);
        bool esAdmin = rol == AppRoles.Administrador;
        bool esVet = rol == AppRoles.Veterinario;
        bool esOp = rol == AppRoles.Operario;

        var cat = new MovilCatalogo { GeneradoEn = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() };
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        if (esAdmin || esVet)
        {
            await using var cmd = new NpgsqlCommand(@"
                SELECT c.caravana, c.estado_fisiologico, COALESCE(c.jaula_numero, ''), COALESCE(g.nombre, ''), c.paridad_acumulada
                FROM cerdas_reproductoras c
                LEFT JOIN galpones_naves g ON g.id = c.galpon_id
                ORDER BY c.caravana;", conn);
            await using var r = await cmd.ExecuteReaderAsync();
            while (await r.ReadAsync())
                cat.Cerdas.Add(new MovilCerda(r.GetString(0), r.GetString(1), r.GetString(2), r.GetString(3), r.GetInt32(4)));
        }

        async Task LeerInsumosAsync(string[] categorias, List<MovilInsumo> destino)
        {
            await using var cmd = new NpgsqlCommand(@"
                SELECT i.nombre, i.unidad_medida, i.stock_actual
                FROM insumos_catalogo i
                JOIN categorias_insumos c ON c.id = i.categoria_id
                WHERE i.activo = TRUE AND c.codigo = ANY(@cats)
                ORDER BY i.nombre;", conn);
            cmd.Parameters.AddWithValue("cats", categorias);
            await using var r = await cmd.ExecuteReaderAsync();
            while (await r.ReadAsync())
                destino.Add(new MovilInsumo(r.GetString(0), r.GetString(1), r.GetDecimal(2)));
        }
        if (esAdmin || esVet)
            await LeerInsumosAsync(new[] { "MED" }, cat.Medicamentos);
        await LeerInsumosAsync(new[] { "ALIM", "PRE" }, cat.Alimentos);

        // Detalle de insumos filtrado por rol (Operario: sin MED ni costos; Vet: con MED sin costos; Admin: completo)
        try
        {
            var catSqlFilter = esOp ? " AND c.codigo IN ('ALIM', 'PRE', 'BIO') " : "";
            await using var cmdInsDet = new NpgsqlCommand($@"
                SELECT i.id::text, i.codigo_sku, i.nombre, c.codigo, i.unidad_medida,
                       i.stock_actual, i.stock_minimo_seguridad, i.costo_unitario_promedio_usd, COALESCE(s.codigo, '')
                FROM insumos_catalogo i
                JOIN categorias_insumos c ON c.id = i.categoria_id
                LEFT JOIN silos_depositos s ON s.id = i.silo_deposito_id
                WHERE i.activo = TRUE {catSqlFilter}
                ORDER BY c.codigo, i.nombre;", conn);
            await using var rDet = await cmdInsDet.ExecuteReaderAsync();
            while (await rDet.ReadAsync())
            {
                cat.InsumosDetalle.Add(new CatalogoInsumoDetalleDto(
                    rDet.GetString(0), rDet.GetString(1), rDet.GetString(2), rDet.GetString(3), rDet.GetString(4),
                    rDet.GetDecimal(5), rDet.GetDecimal(6), esAdmin ? rDet.GetDecimal(7) : 0m, rDet.GetString(8)));
            }
        }
        catch { /* Resiliente */ }

        await using (var cmd = new NpgsqlCommand(@"
            SELECT l.codigo_lote, COALESCE(g.nombre, ''), l.etapa_zootecnica, l.cantidad_actual_cabezas
            FROM lotes_produccion l
            LEFT JOIN galpones_naves g ON g.id = l.galpon_id
            WHERE l.estado = 'activo'
            ORDER BY l.codigo_lote;", conn))
        await using (var r = await cmd.ExecuteReaderAsync())
        {
            while (await r.ReadAsync())
                cat.Lotes.Add(new MovilLote(r.GetString(0), r.GetString(1), r.GetString(2), r.GetInt32(3)));
        }

        // Galpones y Naves reales
        try
        {
            await using var cmdGalp = new NpgsqlCommand(@"
                SELECT codigo, nombre, tipo_sistema, capacidad_cabezas, COALESCE(temperatura_ambiente_celsius, 22.0), COALESCE(humedad_relativa_pct, 60.0)
                FROM galpones_naves
                WHERE activo = TRUE
                ORDER BY codigo;", conn);
            await using var rG = await cmdGalp.ExecuteReaderAsync();
            while (await rG.ReadAsync())
                cat.Galpones.Add(new CatalogoGalponMovilDto(rG.GetString(0), rG.GetString(1), rG.GetString(2), rG.GetInt32(3), rG.GetDecimal(4), rG.GetDecimal(5)));
        }
        catch { /* Resiliente */ }

        // Jaulas y Corrales reales vinculados a su galpón
        try
        {
            await using var cmdJau = new NpgsqlCommand(@"
                SELECT jc.codigo, jc.nombre, COALESCE(gn.codigo, ''), COALESCE(gn.nombre, ''), jc.etapa_zootecnica, jc.capacidad_maxima, jc.ocupacion_actual
                FROM jaulas_corrales jc
                LEFT JOIN galpones_naves gn ON gn.id = jc.galpon_id
                WHERE jc.activo = TRUE
                ORDER BY COALESCE(gn.codigo, ''), jc.codigo;", conn);
            await using var rJ = await cmdJau.ExecuteReaderAsync();
            while (await rJ.ReadAsync())
                cat.Jaulas.Add(new CatalogoJaulaMovilDto(rJ.GetString(0), rJ.GetString(1), rJ.GetString(2), rJ.GetString(3), rJ.GetString(4), rJ.GetInt32(5), rJ.GetInt32(6)));
        }
        catch { /* Resiliente */ }

        // Cerdos individuales activos
        try
        {
            await using var cmdCer = new NpgsqlCommand(@"
                SELECT ci.id::text, ci.crotal, COALESCE(ci.rfid, ''), ci.sexo, ci.fecha_nacimiento,
                       ci.peso_actual_kg, ci.etapa_actual, COALESCE(jc.codigo, ''), COALESCE(gn.codigo, ''),
                       ci.linea_genetica, ci.estatus_sanitario, COALESCE(ci.observaciones, '')
                FROM cerdos_individuales ci
                LEFT JOIN jaulas_corrales jc ON jc.id = ci.jaula_corral_id
                LEFT JOIN galpones_naves gn ON gn.id = COALESCE(ci.galpon_id, jc.galpon_id)
                WHERE ci.activo = TRUE
                ORDER BY ci.crotal;", conn);
            await using var rCer = await cmdCer.ExecuteReaderAsync();
            while (await rCer.ReadAsync())
            {
                var fn = rCer.GetDateTime(4);
                var fnMs = new DateTimeOffset(DateTime.SpecifyKind(fn.Date, DateTimeKind.Utc)).ToUnixTimeMilliseconds();
                cat.CerdosIndividuales.Add(new CatalogoCerdoMovilDto(
                    rCer.GetString(0), rCer.GetString(1), rCer.GetString(2), rCer.GetString(3), fnMs,
                    rCer.GetDecimal(5), rCer.GetString(6), rCer.GetString(7), rCer.GetString(8),
                    rCer.GetString(9), rCer.GetString(10), rCer.GetString(11)));
            }
        }
        catch { /* Resiliente */ }

        // 4. Silos de Alimento con Autonomía Calculada
        try
        {
            await using var cmdSilos = new NpgsqlCommand(@"
                SELECT codigo, nombre, capacidad_maxima_kg, nivel_actual_kg, COALESCE(tipo_insumo_principal, 'Concentrado')
                FROM silos_depositos
                ORDER BY codigo;", conn);
            await using var rSilos = await cmdSilos.ExecuteReaderAsync();
            while (await rSilos.ReadAsync())
            {
                var cap = rSilos.GetDecimal(2);
                var act = rSilos.GetDecimal(3);
                var pct = cap > 0 ? Math.Round((act / cap) * 100m, 1) : 0m;
                var dias = act > 0 ? (int)Math.Floor(act / 600m) : 0;
                cat.Silos.Add(new MovilSilo(rSilos.GetString(0), rSilos.GetString(1), cap, act, pct, rSilos.GetString(4), dias));
            }
        }
        catch { /* Resiliente */ }

        // 5. Tareas y Actividades Programadas filtradas por rol (de hoy y próximos 5 días)
        try
        {
            var tareaRoleSql = esOp
                ? " AND (COALESCE(asignado_a_rol, '') IN ('Operario', 'operario_galpon', 'Ambos', '') OR (@uPat <> '' AND asignado_a_nombre ILIKE @uPat)) "
                : (esVet
                    ? " AND (COALESCE(asignado_a_rol, '') IN ('Veterinario', 'director_veterinario', 'Ambos', '') OR (@uPat <> '' AND asignado_a_nombre ILIKE @uPat)) "
                    : "");
            await using var cmdTareas = new NpgsqlCommand($@"
                SELECT id::text, titulo, COALESCE(descripcion, ''), prioridad, tipo_actividad, estado, 
                       fecha_programada::text, hora_programada, COALESCE(galpon_nombre, ''), COALESCE(lote_codigo, ''), COALESCE(asignado_a_nombre, '')
                FROM tareas_actividades_granja
                WHERE fecha_programada BETWEEN CURRENT_DATE - INTERVAL '1 day' AND CURRENT_DATE + INTERVAL '5 days'
                {tareaRoleSql}
                ORDER BY fecha_programada ASC, hora_programada ASC;", conn);
            if (esOp || esVet)
                cmdTareas.Parameters.AddWithValue("uPat", string.IsNullOrWhiteSpace(usuarioNom) ? "" : $"%{usuarioNom}%");
            await using var rTareas = await cmdTareas.ExecuteReaderAsync();
            while (await rTareas.ReadAsync())
            {
                cat.Tareas.Add(new MovilTarea(
                    rTareas.GetString(0), rTareas.GetString(1), rTareas.GetString(2), rTareas.GetString(3), rTareas.GetString(4),
                    rTareas.GetString(5), rTareas.GetString(6), rTareas.GetString(7), rTareas.GetString(8), rTareas.GetString(9), rTareas.GetString(10)
                ));
            }
        }
        catch { /* Resiliente */ }

        // 6. Notificaciones y Alertas Sanitarias / Operativas Activas filtradas por rol
        try
        {
            var notifs = await GetNotificacionesActivasAsync(rol, usuarioNom);
            foreach (var n in notifs)
            {
                var ms = new DateTimeOffset(n.FechaHora).ToUnixTimeMilliseconds();
                cat.Notificaciones.Add(new MovilNotificacion(
                    n.Id.ToString(), n.Titulo, n.Mensaje, n.Tipo, n.Severidad, ms, n.Leida
                ));
            }
        }
        catch { /* Resiliente */ }

        // 7. Verracos y Dosis Seminales Activos (Solo Administrador / Veterinario)
        if (esAdmin || esVet)
        {
            try
            {
                await using var cmdVer = new NpgsqlCommand(@"
                    SELECT codigo, nombre
                    FROM verracos_semen
                    WHERE activo = true
                    ORDER BY codigo;", conn);
                await using var rVer = await cmdVer.ExecuteReaderAsync();
                while (await rVer.ReadAsync())
                    cat.Verracos.Add(new MovilVerraco(rVer.GetString(0), rVer.GetString(1)));
            }
            catch { /* Resiliente */ }
        }

        // 8. Labores de Campo y Conceptos de Gasto Operativo (tarifas solo visibles para Admin; Operario solo LABOR_CAMPO de campo)
        try
        {
            var laborFilterSql = esOp
                ? " AND tipo_registro = 'LABOR_CAMPO' AND COALESCE(rol_sugerido, 'Operario') NOT ILIKE 'Veterinario' "
                : (esVet ? " AND tipo_registro = 'LABOR_CAMPO' " : "");
            await using var cmdLab = new NpgsqlCommand($@"
                SELECT codigo, nombre, tipo_registro, categoria, COALESCE(etapa_sugerida, ''), COALESCE(galpon_sugerido, ''),
                       COALESCE(duracion_sugerida, ''), tarifa_referencia_nio, COALESCE(centro_costo_codigo, ''), COALESCE(rol_sugerido, 'Operario'), requiere_insumo
                FROM catalogo_labores_campo
                WHERE activo = TRUE {laborFilterSql}
                ORDER BY orden, nombre;", conn);
            await using var rLab = await cmdLab.ExecuteReaderAsync();
            while (await rLab.ReadAsync())
            {
                cat.Labores.Add(new CatalogoLaborMovilDto(
                    rLab.GetString(0), rLab.GetString(1), rLab.GetString(2), rLab.GetString(3),
                    rLab.GetString(4), rLab.GetString(5), rLab.GetString(6),
                    esAdmin ? rLab.GetDecimal(7) : 0m,
                    rLab.GetString(8), rLab.GetString(9), rLab.GetBoolean(10)));
            }
        }
        catch { /* Resiliente */ }

        // 9. Horarios, Turnos, Tomas de Alimento, Dosis IA y Plantillas de Agenda
        try
        {
            var horarioFilterSql = esOp ? " AND tipo_horario <> 'DOSIS_IA' " : "";
            await using var cmdHor = new NpgsqlCommand($@"
                SELECT codigo, nombre, tipo_horario, hora_inicio, COALESCE(hora_fin, ''),
                       COALESCE(categoria_actividad, ''), COALESCE(etapa_aplicable, ''),
                       COALESCE(rol_responsable, 'Operario'), prioridad_default, COALESCE(descripcion, '')
                FROM catalogo_horarios_turnos
                WHERE activo = TRUE {horarioFilterSql}
                ORDER BY orden, hora_inicio;", conn);
            await using var rHor = await cmdHor.ExecuteReaderAsync();
            while (await rHor.ReadAsync())
            {
                cat.Horarios.Add(new CatalogoHorarioMovilDto(
                    rHor.GetString(0), rHor.GetString(1), rHor.GetString(2), rHor.GetString(3), rHor.GetString(4),
                    rHor.GetString(5), rHor.GetString(6), rHor.GetString(7), rHor.GetString(8), rHor.GetString(9)));
            }
        }
        catch { /* Resiliente */ }

        // 10. Operarios y Responsables activos
        try
        {
            await using var cmdOp = new NpgsqlCommand(@"
                SELECT id::text, COALESCE(nombre_usuario, ''), nombre, rol, COALESCE(matricula_profesional, '')
                FROM usuarios_operadores
                WHERE activo = TRUE
                ORDER BY rol, nombre;", conn);
            await using var rOp = await cmdOp.ExecuteReaderAsync();
            while (await rOp.ReadAsync())
                cat.Operarios.Add(new CatalogoOperarioMovilDto(rOp.GetString(0), rOp.GetString(1), rOp.GetString(2), AppRoles.Normalizar(rOp.GetString(3)) ?? rOp.GetString(3), rOp.GetString(4)));
        }
        catch { /* Resiliente */ }

        // 11. Catálogos Clínicos / Sanitarios / Etapas / Causas Mortalidad (Operario solo etapas productivas)
        try
        {
            var clinFilterSql = esOp ? " AND tipo_catalogo = 'ETAPA_PRODUCTIVA' " : "";
            await using var cmdCli = new NpgsqlCommand($@"
                SELECT codigo, tipo_catalogo, nombre, COALESCE(subcategoria, ''), dias_retiro_referencia
                FROM catalogo_clinico_sanitario
                WHERE activo = TRUE {clinFilterSql}
                ORDER BY tipo_catalogo, orden, nombre;", conn);
            await using var rCli = await cmdCli.ExecuteReaderAsync();
            while (await rCli.ReadAsync())
                cat.CatalogosClinicos.Add(new CatalogoClinicoMovilDto(rCli.GetString(0), rCli.GetString(1), rCli.GetString(2), rCli.GetString(3), rCli.GetInt32(4)));
        }
        catch { /* Resiliente */ }

        // 12. Líneas Genéticas
        try
        {
            await using var cmdGen = new NpgsqlCommand("SELECT nombre FROM lineas_geneticas ORDER BY nombre;", conn);
            await using var rGen = await cmdGen.ExecuteReaderAsync();
            while (await rGen.ReadAsync())
                cat.LineasGeneticas.Add(rGen.GetString(0));
        }
        catch { /* Resiliente */ }

        // 13. Preferencias de Interfaz Personalizable según Usuario / Rol
        cat.PreferenciasUi = await ObtenerPreferenciasUiUsuarioAsync(usuarioId, rolUsuario);

        return cat;
    }

    public static PreferenciasUiUsuarioDto BuildDefaultUiPreferences(string? rolRaw)
    {
        var rol = AppRoles.Normalizar(rolRaw) ?? AppRoles.Operario;
        return rol switch
        {
            AppRoles.Administrador => new PreferenciasUiUsuarioDto
            {
                RolBase = AppRoles.Administrador,
                ModulosVisibles = new() { "ACCIONES_RAPIDAS", "TAREAS_HOY", "METRICAS_VIVO", "SILOS_NIVEL", "BUSCADOR_CAMPO", "RESUMEN_FINANCIERO" },
                AccionesRapidas = new() { "FEED_SUPPLY", "REPRODUCTIVE_EVENT", "TREATMENT", "FIELD_OPERATIONS" },
                NavInferior = new() { "DASHBOARD", "FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE" },
                GalponPredeterminado = "",
                TurnoPredeterminado = "TUR-COMP",
                MonedaPreferida = "NIO",
                ModoCompacto = false
            },
            AppRoles.Veterinario => new PreferenciasUiUsuarioDto
            {
                RolBase = AppRoles.Veterinario,
                ModulosVisibles = new() { "ACCIONES_RAPIDAS", "TAREAS_HOY", "METRICAS_VIVO", "SILOS_NIVEL", "BUSCADOR_CAMPO" },
                AccionesRapidas = new() { "TREATMENT", "REPRODUCTIVE_EVENT", "MORTALITY", "TASKS_SCHEDULE" },
                NavInferior = new() { "DASHBOARD", "TREATMENT", "REPRODUCTIVE_EVENT", "TASKS_SCHEDULE" },
                GalponPredeterminado = "GALP-01",
                TurnoPredeterminado = "TUR-COMP",
                MonedaPreferida = "NIO",
                ModoCompacto = false
            },
            _ => new PreferenciasUiUsuarioDto
            {
                RolBase = AppRoles.Operario,
                ModulosVisibles = new() { "ACCIONES_RAPIDAS", "TAREAS_HOY", "METRICAS_VIVO", "SILOS_NIVEL", "BUSCADOR_CAMPO" },
                AccionesRapidas = new() { "FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE", "ANIMALS" },
                NavInferior = new() { "DASHBOARD", "FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE" },
                GalponPredeterminado = "",
                TurnoPredeterminado = "TUR-MAN",
                MonedaPreferida = "NIO",
                ModoCompacto = false
            }
        };
    }

    private static PreferenciasUiUsuarioDto SanitizeUiPreferences(string? rolRaw, PreferenciasUiUsuarioDto input)
    {
        var rol = AppRoles.Normalizar(rolRaw) ?? AppRoles.Operario;
        var def = BuildDefaultUiPreferences(rol);

        var allowedModulos = rol == AppRoles.Administrador
            ? new HashSet<string> { "ACCIONES_RAPIDAS", "TAREAS_HOY", "METRICAS_VIVO", "SILOS_NIVEL", "BUSCADOR_CAMPO", "RESUMEN_FINANCIERO" }
            : new HashSet<string> { "ACCIONES_RAPIDAS", "TAREAS_HOY", "METRICAS_VIVO", "SILOS_NIVEL", "BUSCADOR_CAMPO" };

        var allowedActions = rol switch
        {
            AppRoles.Administrador => new HashSet<string> { "FEED_SUPPLY", "REPRODUCTIVE_EVENT", "TREATMENT", "FIELD_OPERATIONS", "ANIMALS", "TASKS_SCHEDULE", "MORTALITY" },
            AppRoles.Veterinario => new HashSet<string> { "TREATMENT", "REPRODUCTIVE_EVENT", "MORTALITY", "TASKS_SCHEDULE", "ANIMALS", "FIELD_OPERATIONS" },
            _ => new HashSet<string> { "FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE", "ANIMALS" }
        };

        var allowedNav = rol switch
        {
            AppRoles.Administrador => new HashSet<string> { "DASHBOARD", "FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE", "TREATMENT", "ANIMALS", "REPRODUCTIVE_EVENT" },
            AppRoles.Veterinario => new HashSet<string> { "DASHBOARD", "TREATMENT", "REPRODUCTIVE_EVENT", "TASKS_SCHEDULE", "ANIMALS", "MORTALITY" },
            _ => new HashSet<string> { "DASHBOARD", "FEED_SUPPLY", "FIELD_OPERATIONS", "TASKS_SCHEDULE", "ANIMALS" }
        };

        var modulos = (input.ModulosVisibles ?? new()).Where(allowedModulos.Contains).Distinct().ToList();
        if (modulos.Count == 0) modulos = def.ModulosVisibles;

        var acciones = (input.AccionesRapidas ?? new()).Where(allowedActions.Contains).Distinct().Take(4).ToList();
        if (acciones.Count < 4)
        {
            foreach (var fallback in def.AccionesRapidas)
                if (!acciones.Contains(fallback) && acciones.Count < 4) acciones.Add(fallback);
        }

        var nav = (input.NavInferior ?? new()).Where(allowedNav.Contains).Distinct().Take(4).ToList();
        if (!nav.Contains("DASHBOARD"))
        {
            nav.Insert(0, "DASHBOARD");
            nav = nav.Take(4).ToList();
        }
        if (nav.Count < 4)
        {
            foreach (var fallback in def.NavInferior)
                if (!nav.Contains(fallback) && nav.Count < 4) nav.Add(fallback);
        }

        var moneda = (rol == AppRoles.Administrador && string.Equals(input.MonedaPreferida, "USD", StringComparison.OrdinalIgnoreCase))
            ? "USD"
            : "NIO";

        return new PreferenciasUiUsuarioDto
        {
            RolBase = rol,
            ModulosVisibles = modulos,
            AccionesRapidas = acciones,
            NavInferior = nav,
            GalponPredeterminado = (input.GalponPredeterminado ?? "").Trim(),
            TurnoPredeterminado = string.IsNullOrWhiteSpace(input.TurnoPredeterminado) ? def.TurnoPredeterminado : input.TurnoPredeterminado.Trim(),
            MonedaPreferida = moneda,
            ModoCompacto = input.ModoCompacto,
            UpdatedAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        };
    }

    public async Task<PreferenciasUiUsuarioDto> ObtenerPreferenciasUiUsuarioAsync(Guid? usuarioId, string? rolRaw)
    {
        await EnsureBaseEntitiesAsync();
        var rol = AppRoles.Normalizar(rolRaw) ?? AppRoles.Operario;
        var fallback = BuildDefaultUiPreferences(rol);
        if (!usuarioId.HasValue || usuarioId.Value == Guid.Empty) return fallback;

        try
        {
            await using var conn = CreateConnection();
            await conn.OpenAsync();
            await using var cmd = new NpgsqlCommand(@"
                SELECT modulos_visibles_json::text, acciones_rapidas_json::text, nav_inferior_json::text,
                       COALESCE(galpon_predeterminado, ''), COALESCE(turno_predeterminado, ''),
                       COALESCE(moneda_preferida, 'NIO'), modo_compacto, updated_at
                FROM preferencias_ui_usuario
                WHERE usuario_id = @uid;", conn);
            cmd.Parameters.AddWithValue("uid", usuarioId.Value);
            await using var r = await cmd.ExecuteReaderAsync();
            if (!await r.ReadAsync()) return fallback;

            var dto = new PreferenciasUiUsuarioDto
            {
                RolBase = rol,
                ModulosVisibles = JsonSerializer.Deserialize<List<string>>(r.GetString(0)) ?? fallback.ModulosVisibles,
                AccionesRapidas = JsonSerializer.Deserialize<List<string>>(r.GetString(1)) ?? fallback.AccionesRapidas,
                NavInferior = JsonSerializer.Deserialize<List<string>>(r.GetString(2)) ?? fallback.NavInferior,
                GalponPredeterminado = r.GetString(3),
                TurnoPredeterminado = r.GetString(4),
                MonedaPreferida = r.GetString(5),
                ModoCompacto = r.GetBoolean(6),
                UpdatedAtMs = new DateTimeOffset(r.GetDateTime(7)).ToUnixTimeMilliseconds()
            };
            return SanitizeUiPreferences(rol, dto);
        }
        catch
        {
            return fallback;
        }
    }

    public async Task<PreferenciasUiUsuarioDto> GuardarPreferenciasUiUsuarioAsync(Guid usuarioId, string? rolRaw, PreferenciasUiUsuarioDto input)
    {
        await EnsureBaseEntitiesAsync();
        var clean = SanitizeUiPreferences(rolRaw, input);

        await using var conn = CreateConnection();
        await conn.OpenAsync();
        await using var cmd = new NpgsqlCommand(@"
            INSERT INTO preferencias_ui_usuario (
                usuario_id, rol_base, modulos_visibles_json, acciones_rapidas_json, nav_inferior_json,
                galpon_predeterminado, turno_predeterminado, moneda_preferida, modo_compacto, updated_at
            ) VALUES (
                @uid, @rol, @modulos::jsonb, @acciones::jsonb, @nav::jsonb,
                @galpon, @turno, @moneda, @compacto, NOW()
            )
            ON CONFLICT (usuario_id) DO UPDATE SET
                rol_base = EXCLUDED.rol_base,
                modulos_visibles_json = EXCLUDED.modulos_visibles_json,
                acciones_rapidas_json = EXCLUDED.acciones_rapidas_json,
                nav_inferior_json = EXCLUDED.nav_inferior_json,
                galpon_predeterminado = EXCLUDED.galpon_predeterminado,
                turno_predeterminado = EXCLUDED.turno_predeterminado,
                moneda_preferida = EXCLUDED.moneda_preferida,
                modo_compacto = EXCLUDED.modo_compacto,
                updated_at = NOW();", conn);

        cmd.Parameters.AddWithValue("uid", usuarioId);
        cmd.Parameters.AddWithValue("rol", clean.RolBase);
        cmd.Parameters.AddWithValue("modulos", JsonSerializer.Serialize(clean.ModulosVisibles));
        cmd.Parameters.AddWithValue("acciones", JsonSerializer.Serialize(clean.AccionesRapidas));
        cmd.Parameters.AddWithValue("nav", JsonSerializer.Serialize(clean.NavInferior));
        cmd.Parameters.AddWithValue("galpon", (object?)clean.GalponPredeterminado ?? "");
        cmd.Parameters.AddWithValue("turno", (object?)clean.TurnoPredeterminado ?? "");
        cmd.Parameters.AddWithValue("moneda", clean.MonedaPreferida);
        cmd.Parameters.AddWithValue("compacto", clean.ModoCompacto);
        await cmd.ExecuteNonQueryAsync();
        InvalidarCachesOperativos();
        return clean;
    }

    public async Task<CatalogosFormularioWebViewModel> GetCatalogosFormularioWebAsync()
    {
        var cat = await ObtenerCatalogoMovilAsync(CurrentUserId, _http.HttpContext?.User?.FindFirst(System.Security.Claims.ClaimTypes.Role)?.Value);
        var vm = new CatalogosFormularioWebViewModel
        {
            Labores = cat.Labores.Where(l => l.TipoRegistro == "LABOR_CAMPO").ToList(),
            GastosConceptos = cat.Labores.Where(l => l.TipoRegistro == "GASTO_OPERATIVO").ToList(),
            Horarios = cat.Horarios,
            PlantillasAgenda = cat.Horarios.Where(h => h.TipoHorario == "PLANTILLA_AGENDA").ToList(),
            TurnosJornada = cat.Horarios.Where(h => h.TipoHorario == "TURNO_JORNADA").ToList(),
            DosisIaHorarios = cat.Horarios.Where(h => h.TipoHorario == "DOSIS_IA").ToList(),
            CausasMortalidad = cat.CatalogosClinicos.Where(c => c.TipoCatalogo == "CAUSA_MORTALIDAD").ToList(),
            Diagnosticos = cat.CatalogosClinicos.Where(c => c.TipoCatalogo == "DIAGNOSTICO_CLINICO").ToList(),
            ViasAdministracion = cat.CatalogosClinicos.Where(c => c.TipoCatalogo == "VIA_ADMINISTRACION").ToList(),
            TecnicasIa = cat.CatalogosClinicos.Where(c => c.TipoCatalogo == "TECNICA_IA").ToList(),
            LineasGeneticas = cat.LineasGeneticas,
            Galpones = cat.Galpones,
            Jaulas = cat.Jaulas,
            Operadores = cat.Operarios,
            Insumos = cat.InsumosDetalle,
            Silos = cat.Silos,
            Lotes = cat.Lotes
        };

        try
        {
            await using var conn = CreateConnection();
            await conn.OpenAsync();
            await using var cmdPrv = new NpgsqlCommand("SELECT nombre_comercial FROM proveedores WHERE activo = TRUE ORDER BY nombre_comercial;", conn);
            await using var rPrv = await cmdPrv.ExecuteReaderAsync();
            while (await rPrv.ReadAsync())
                vm.Proveedores.Add(rPrv.GetString(0));
        }
        catch { /* Resiliente */ }

        return vm;
    }


    // =========================================================================
    // 7. GESTIÓN INTEGRAL DE CERDOS, JAULAS Y CÁLCULO ZOOTÉCNICO / NUTRICIONAL
    // =========================================================================

    public static CalculoZootecnicoResult CalcularPlanZootecnico(DateTime fechaNacimiento, decimal pesoActual)
    {
        var hoy = DateTime.Today;
        var fechaNac = fechaNacimiento.Date;
        int diasVida = Math.Max(0, (int)(hoy - fechaNac).TotalDays);

        string etapa;
        string subEtapa;
        string jaulaTipo;
        string rangoPesoEsperado;
        string alimentoTipo;
        decimal racionDiariaKg;
        int tomasAlDia;
        string horariosSugeridos;
        string recomendacionNutricional;

        if (diasVida <= 28)
        {
            etapa = "Lactancia (Maternidad)";
            subEtapa = "Lechón Lactante";
            jaulaTipo = "Maternidad";
            rangoPesoEsperado = "1.4 - 7.0 kg";
            alimentoTipo = "Preiniciador Fase 1 (Creep Feeding Micro-pellet)";
            racionDiariaKg = 0.10m; // 100 g/día
            tomasAlDia = 5;
            horariosSugeridos = "06:30, 09:30, 12:30, 15:30, 18:30";
            recomendacionNutricional = "Alimentación en platos lamedores limpios para acostumbrar el aparato digestivo previo al destete.";
        }
        else if (diasVida <= 45)
        {
            etapa = "Precebo Fase 1";
            subEtapa = "Destete Inmediato";
            jaulaTipo = "Precebo";
            rangoPesoEsperado = "7.0 - 15.0 kg";
            alimentoTipo = "Preiniciador Fase 2 (Pellet con Plasma Porcino Alta Digestibilidad)";
            racionDiariaKg = 0.50m; // 500 g/día
            tomasAlDia = 4;
            horariosSugeridos = "07:00, 11:00, 15:00, 19:00";
            recomendacionNutricional = "Dividir en 4 tomas pequeñas para prevenir acidosis digestiva y diarreas de transición post-destete.";
        }
        else if (diasVida <= 70)
        {
            etapa = "Precebo Fase 2";
            subEtapa = "Iniciador / Transición";
            jaulaTipo = "Precebo";
            rangoPesoEsperado = "15.0 - 28.0 kg";
            alimentoTipo = "Iniciador Lecherón (20% Proteína Cruda)";
            racionDiariaKg = 1.10m; // 1100 g/día
            tomasAlDia = 4;
            horariosSugeridos = "07:00, 11:00, 15:00, 19:00";
            recomendacionNutricional = "Monitorear Ganancia Media Diaria meta (450 - 520 g/día) y flujo continuo de agua fresca (0.8 L/min).";
        }
        else if (diasVida <= 115)
        {
            etapa = "Crecimiento / Levante";
            subEtapa = "Desarrollo Muscular Magro";
            jaulaTipo = "Crecimiento";
            rangoPesoEsperado = "28.0 - 65.0 kg";
            alimentoTipo = "Crecimiento / Desarrollo (17% Proteína Cruda, FCR ~2.1)";
            racionDiariaKg = 1.95m; // 1950 g/día
            tomasAlDia = 3;
            horariosSugeridos = "07:00, 13:00, 18:00";
            recomendacionNutricional = "3 tomas distribuidas para maximizar absorción de aminoácidos esenciales y depósito de magro.";
        }
        else if (diasVida <= 165)
        {
            etapa = "Engorde / Acabado Final";
            subEtapa = "Terminación Peso Frigorífico";
            jaulaTipo = "Engorde";
            rangoPesoEsperado = "65.0 - 115.0+ kg";
            alimentoTipo = "Finalizador / Engorde Comercial (Alta Energía Neta)";
            racionDiariaKg = 2.80m; // 2800 g/día
            tomasAlDia = 2;
            horariosSugeridos = "07:30, 16:30";
            recomendacionNutricional = "2 raciones abundantes mañana y tarde. Control estricto del periodo de retiro sanitario de promotores.";
        }
        else
        {
            etapa = "Gestación / Reproductor";
            subEtapa = "Vientre Adulto en Mantenimiento";
            jaulaTipo = "Gestación";
            rangoPesoEsperado = "120.0 - 220.0 kg";
            alimentoTipo = "Gestación Controlada (Alta Fibra saciante)";
            racionDiariaKg = 2.20m; // 2200 g/día
            tomasAlDia = 2;
            horariosSugeridos = "07:00, 16:00";
            recomendacionNutricional = "2 tomas exactas para mantener condición corporal entre 3.0 y 3.5. Evitar sobrepeso gestacional.";
        }

        decimal gramosPorToma = Math.Round((racionDiariaKg * 1000m) / tomasAlDia, 0);

        var vacunas = new List<PlanVacunacionItem>();

        void AgregarVacuna(int diaObj, string vacuna, string enfermedad, string dosis, string via)
        {
            var fechaProg = fechaNac.AddDays(diaObj);
            int dif = diasVida - diaObj;
            string estado;
            string badgeClass;
            string icono;

            if (dif > 3)
            {
                estado = "Aplicada / Histórica";
                badgeClass = "bg-emerald-100 text-emerald-800 border border-emerald-300";
                icono = "check_circle";
            }
            else if (Math.Abs(dif) <= 3)
            {
                estado = "URGENTE / Toca Hoy";
                badgeClass = "bg-amber-100 text-amber-900 border border-amber-300 font-bold";
                icono = "notification_important";
            }
            else
            {
                int faltan = diaObj - diasVida;
                estado = $"Programada (en {faltan} d)";
                badgeClass = "bg-blue-100 text-blue-800 border border-blue-200";
                icono = "schedule";
            }

            vacunas.Add(new PlanVacunacionItem
            {
                DiaVidaProgramado = diaObj,
                FechaEstimada = fechaProg,
                VacunaBiológico = vacuna,
                EnfermedadPrevenida = enfermedad,
                Dosis = dosis,
                Via = via,
                Estado = estado,
                BadgeClass = badgeClass,
                Icono = icono
            });
        }

        AgregarVacuna(3, "Hierro Dextrano 200mg + Toltrazuril 5%", "Anemia ferropénica y Coccidiosis neonatal", "2.0 ml IM + 1.0 ml VO", "Intramuscular profunda / Oral");
        AgregarVacuna(7, "Mycoplasma hyopneumoniae Dosis 1", "Neumonía Enzoótica Porcina", "2.0 ml IM", "Intramuscular tabla del cuello");
        AgregarVacuna(21, "Circovirus PCV2 + Mycoplasma Refuerzo", "Desmedro multisistémico y Neumonía", "2.0 ml IM", "Intramuscular tabla del cuello");
        AgregarVacuna(35, "Peste Porcina Clásica (PPC Cepa China)", "Inmunización oficial preventiva", "2.0 ml IM", "Intramuscular profunda");
        AgregarVacuna(50, "Erisipela Porcina + Actinobacillus (APP)", "Mal Rojo y Pleuroneumonía aguda", "2.0 ml IM", "Intramuscular tabla del cuello");
        AgregarVacuna(70, "Desparasitación Interna y Externa", "Ascaris suum, Stephanurus, Sarna", "1.0 ml / 33 kg SC", "Subcutánea retroauricular");
        AgregarVacuna(90, "Refuerzo Complejo Respiratorio PRDC", "Complejo respiratorio porcino", "2.0 ml IM", "Intramuscular");
        AgregarVacuna(150, "Vacuna PLE (Parvo + Lepto + Erisipela)", "Fallas reproductivas en reemplazos", "2.0 ml IM", "Intramuscular profunda");

        return new CalculoZootecnicoResult
        {
            FechaNacimiento = fechaNac,
            DiasVida = diasVida,
            EtapaClasificada = etapa,
            SubEtapa = subEtapa,
            JaulaSugeridaTipo = jaulaTipo,
            RangoPesoEsperado = rangoPesoEsperado,
            PesoActualKg = pesoActual,
            AlimentoTipo = alimentoTipo,
            ConsumoDiarioKg = racionDiariaKg,
            ConsumoDiarioGramos = (int)(racionDiariaKg * 1000m),
            TomasAlDia = tomasAlDia,
            CantidadPorTomaGramos = (int)gramosPorToma,
            CantidadPorTomaKg = Math.Round(racionDiariaKg / tomasAlDia, 3),
            HorariosSugeridos = horariosSugeridos,
            RecomendacionNutricional = recomendacionNutricional,
            PlanVacunacion = vacunas
        };
    }

    public async Task<List<JaulaCorralViewModel>> GetJaulasCorralesAsync(string? etapaFiltro = null)
    {
        var list = new List<JaulaCorralViewModel>();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        string sql = @"
            SELECT 
                j.id, j.codigo, j.nombre, j.etapa_zootecnica, j.capacidad_maxima,
                COALESCE(sub.total_alojados, 0) as ocupacion_real,
                j.tipo_instalacion,
                COALESCE(g.nombre, 'Sector General') as galpon_nombre,
                COALESCE(sub.crotales_list, '') as crotales,
                COALESCE(j.largo_fondo_metros, 5.0) as largo_fondo,
                COALESCE(j.ancho_metros, 3.0) as ancho,
                COALESCE(j.area_m2, 15.0) as area,
                COALESCE(j.capacidad_lechones, 20) as cap_lechones,
                COALESCE(j.capacidad_adultos, 6) as cap_adultos,
                COALESCE(j.numero_jaula, 1) as num_jaula
            FROM jaulas_corrales j
            LEFT JOIN galpones_naves g ON j.galpon_id = g.id
            LEFT JOIN (
                SELECT jaula_corral_id, COUNT(*) as total_alojados, string_agg(crotal, ', ' ORDER BY crotal) as crotales_list
                FROM cerdos_individuales
                WHERE activo = true AND jaula_corral_id IS NOT NULL
                GROUP BY jaula_corral_id
            ) sub ON sub.jaula_corral_id = j.id
            WHERE j.activo = true " +
            (string.IsNullOrWhiteSpace(etapaFiltro) || etapaFiltro == "Todas" ? "" : " AND j.etapa_zootecnica ILIKE @etapa ") +
            " ORDER BY j.etapa_zootecnica, j.codigo;";

        await using var cmd = new NpgsqlCommand(sql, conn);
        if (!string.IsNullOrWhiteSpace(etapaFiltro) && etapaFiltro != "Todas")
        {
            cmd.Parameters.AddWithValue("etapa", $"%{etapaFiltro}%");
        }

        await using var reader = await cmd.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            int capMax = reader.GetInt32(4);
            int ocup = Convert.ToInt32(reader.GetInt64(5));
            int cupo = Math.Max(0, capMax - ocup);
            decimal pct = capMax > 0 ? Math.Round(((decimal)ocup / capMax) * 100m, 1) : 0m;

            string estadoBadge;
            string estadoBadgeClass;
            if (ocup == 0)
            {
                estadoBadge = "Disponible (Vacía)";
                estadoBadgeClass = "bg-emerald-50 text-emerald-700 border-emerald-200";
            }
            else if (ocup < capMax)
            {
                estadoBadge = $"Cupo Disponible ({cupo})";
                estadoBadgeClass = "bg-blue-50 text-blue-700 border-blue-200";
            }
            else if (ocup == capMax)
            {
                estadoBadge = "Capacidad Óptima Llena";
                estadoBadgeClass = "bg-amber-50 text-amber-800 border-amber-300 font-bold";
            }
            else
            {
                estadoBadge = $"Sobrecupo (+{ocup - capMax})";
                estadoBadgeClass = "bg-red-50 text-red-700 border-red-300 font-bold";
            }

            var crotalesStr = reader.IsDBNull(8) ? "" : reader.GetString(8);
            var crotalesList = string.IsNullOrWhiteSpace(crotalesStr) 
                ? new List<string>() 
                : crotalesStr.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries).ToList();

            list.Add(new JaulaCorralViewModel
            {
                Id = reader.GetGuid(0),
                Codigo = reader.GetString(1),
                Nombre = reader.GetString(2),
                EtapaZootecnica = reader.GetString(3),
                CapacidadMaxima = capMax,
                OcupacionActual = ocup,
                CupoDisponible = cupo,
                PorcentajeOcupacion = pct,
                TipoInstalacion = reader.IsDBNull(6) ? "Corral tecnificado" : reader.GetString(6),
                GalponNombre = reader.GetString(7),
                EstadoBadge = estadoBadge,
                EstadoBadgeClass = estadoBadgeClass,
                CerdosAlojados = crotalesList,
                LargoFondoMetros = reader.GetDecimal(9),
                AnchoMetros = reader.GetDecimal(10),
                AreaM2 = reader.GetDecimal(11),
                CapacidadLechones = reader.GetInt32(12),
                CapacidadAdultos = reader.GetInt32(13),
                NumeroJaula = reader.GetInt32(14)
            });
        }

        return list;
    }

    public async Task<CerdosPageViewModel> GetCerdosPageDataAsync(string? etapaFiltro = null, string? busqueda = null)
    {
        await EnsureBaseEntitiesAsync();
        var model = new CerdosPageViewModel();

        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Obtener Jaulas / Corrales
        model.Jaulas = await GetJaulasCorralesAsync();
        model.TotalCapacidadJaulas = model.Jaulas.Sum(j => j.CapacidadMaxima);
        model.TotalOcupacionJaulas = model.Jaulas.Sum(j => j.OcupacionActual);
        model.PctOcupacionGlobal = model.TotalCapacidadJaulas > 0 
            ? Math.Round(((decimal)model.TotalOcupacionJaulas / model.TotalCapacidadJaulas) * 100m, 1) 
            : 0m;

        // 2. Obtener Cerdos Individuales
        string sqlCerdos = @"
            SELECT 
                c.id, c.crotal, c.rfid, c.sexo, c.fecha_nacimiento, c.peso_actual_kg,
                c.etapa_actual, c.jaula_corral_id, j.codigo as jaula_codigo, j.nombre as jaula_nombre,
                COALESCE(g.nombre, 'Sin galpón') as galpon_nombre,
                c.linea_genetica, c.estatus_sanitario, COALESCE(c.observaciones, '') as observaciones
            FROM cerdos_individuales c
            LEFT JOIN jaulas_corrales j ON c.jaula_corral_id = j.id
            LEFT JOIN galpones_naves g ON j.galpon_id = g.id
            WHERE c.activo = true ";

        if (!string.IsNullOrWhiteSpace(etapaFiltro) && etapaFiltro != "Todas")
        {
            sqlCerdos += " AND (c.etapa_actual ILIKE @etapa OR j.etapa_zootecnica ILIKE @etapa) ";
        }
        if (!string.IsNullOrWhiteSpace(busqueda))
        {
            sqlCerdos += " AND (c.crotal ILIKE @busq OR c.rfid ILIKE @busq OR j.codigo ILIKE @busq) ";
        }

        sqlCerdos += " ORDER BY c.fecha_nacimiento DESC, c.crotal;";

        await using (var cmd = new NpgsqlCommand(sqlCerdos, conn))
        {
            if (!string.IsNullOrWhiteSpace(etapaFiltro) && etapaFiltro != "Todas")
            {
                cmd.Parameters.AddWithValue("etapa", $"%{etapaFiltro}%");
            }
            if (!string.IsNullOrWhiteSpace(busqueda))
            {
                cmd.Parameters.AddWithValue("busq", $"%{busqueda}%");
            }

            await using var reader = await cmd.ExecuteReaderAsync();
            while (await reader.ReadAsync())
            {
                var id = reader.GetGuid(0);
                var crotal = reader.GetString(1);
                var rfid = reader.IsDBNull(2) ? "" : reader.GetString(2);
                var sexo = reader.GetString(3);
                var fechaNac = reader.GetDateTime(4);
                var peso = reader.GetDecimal(5);
                var jaulaId = reader.IsDBNull(7) ? (Guid?)null : reader.GetGuid(7);
                var jaulaCod = reader.IsDBNull(8) ? "Sin Jaula" : reader.GetString(8);
                var jaulaNom = reader.IsDBNull(9) ? "No asignada" : reader.GetString(9);
                var galpon = reader.GetString(10);
                var genetica = reader.GetString(11);
                var sanitario = reader.GetString(12);
                var obs = reader.GetString(13);

                // Cálculo zootécnico en tiempo real basado en fecha de nacimiento y peso
                var calculo = CalcularPlanZootecnico(fechaNac, peso);

                // Determinar próxima vacuna
                var proxVacuna = calculo.PlanVacunacion.FirstOrDefault(v => v.DiaVidaProgramado >= calculo.DiasVida) 
                                 ?? calculo.PlanVacunacion.LastOrDefault();

                string proxVacunaNombre = proxVacuna?.VacunaBiológico ?? "Plan Completo";
                DateTime? proxVacunaFecha = proxVacuna?.FechaEstimada;
                int proxVacunaDias = proxVacuna != null ? proxVacuna.DiaVidaProgramado - calculo.DiasVida : 0;
                string proxVacunaEstado = proxVacuna?.Estado ?? "Completado";
                string proxVacunaBadge = proxVacuna?.BadgeClass ?? "bg-gray-100 text-gray-700";

                model.Cerdos.Add(new CerdoViewModel
                {
                    Id = id,
                    Crotal = crotal,
                    Rfid = rfid,
                    Sexo = sexo,
                    FechaNacimiento = fechaNac,
                    DiasVida = calculo.DiasVida,
                    PesoActualKg = peso,
                    EtapaActual = calculo.EtapaClasificada,
                    SubEtapa = calculo.SubEtapa,
                    JaulaCorralId = jaulaId,
                    JaulaCodigo = jaulaCod,
                    JaulaNombre = jaulaNom,
                    GalponNombre = galpon,
                    LineaGenetica = genetica,
                    EstatusSanitario = sanitario,
                    Observaciones = obs,

                    AlimentoTipo = calculo.AlimentoTipo,
                    ConsumoDiarioKg = calculo.ConsumoDiarioKg,
                    ConsumoDiarioGramos = calculo.ConsumoDiarioGramos,
                    TomasAlDia = calculo.TomasAlDia,
                    CantidadPorTomaGramos = calculo.CantidadPorTomaGramos,
                    CantidadPorTomaKg = calculo.CantidadPorTomaKg,
                    HorariosSugeridos = calculo.HorariosSugeridos,
                    RecomendacionNutricional = calculo.RecomendacionNutricional,

                    ProximaVacunaNombre = proxVacunaNombre,
                    ProximaVacunaFecha = proxVacunaFecha,
                    ProximaVacunaDias = proxVacunaDias,
                    ProximaVacunaEstado = proxVacunaEstado,
                    ProximaVacunaBadgeClass = proxVacunaBadge,
                    PlanVacunacion = calculo.PlanVacunacion
                });
            }
        }

        // 3. Métricas Resumen
        model.TotalCerdos = model.Cerdos.Count;
        model.CerdosLactancia = model.Cerdos.Count(c => c.EtapaActual.Contains("Lactancia", StringComparison.OrdinalIgnoreCase));
        model.CerdosPrecebo = model.Cerdos.Count(c => c.EtapaActual.Contains("Precebo", StringComparison.OrdinalIgnoreCase));
        model.CerdosCrecimiento = model.Cerdos.Count(c => c.EtapaActual.Contains("Crecimiento", StringComparison.OrdinalIgnoreCase));
        model.CerdosEngorde = model.Cerdos.Count(c => c.EtapaActual.Contains("Engorde", StringComparison.OrdinalIgnoreCase));
        model.CerdosGestacion = model.Cerdos.Count(c => c.EtapaActual.Contains("Gestación", StringComparison.OrdinalIgnoreCase));
        model.VacunacionesUrgentesHoy = model.Cerdos.Count(c => c.ProximaVacunaEstado.Contains("URGENTE", StringComparison.OrdinalIgnoreCase));

        return model;
    }

    public async Task<CerdoViewModel?> GetCerdoByIdAsync(Guid id)
    {
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        string sql = @"
            SELECT 
                c.id, c.crotal, c.rfid, c.sexo, c.fecha_nacimiento, c.peso_actual_kg,
                c.etapa_actual, c.jaula_corral_id, j.codigo as jaula_codigo, j.nombre as jaula_nombre,
                COALESCE(g.nombre, 'Sin galpón') as galpon_nombre,
                c.linea_genetica, c.estatus_sanitario, COALESCE(c.observaciones, '') as observaciones
            FROM cerdos_individuales c
            LEFT JOIN jaulas_corrales j ON c.jaula_corral_id = j.id
            LEFT JOIN galpones_naves g ON j.galpon_id = g.id
            WHERE c.id = @id AND c.activo = true;";

        await using var cmd = new NpgsqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("id", id);
        await using var reader = await cmd.ExecuteReaderAsync();

        if (await reader.ReadAsync())
        {
            var cid = reader.GetGuid(0);
            var crotal = reader.GetString(1);
            var rfid = reader.IsDBNull(2) ? "" : reader.GetString(2);
            var sexo = reader.GetString(3);
            var fechaNac = reader.GetDateTime(4);
            var peso = reader.GetDecimal(5);
            var jaulaId = reader.IsDBNull(7) ? (Guid?)null : reader.GetGuid(7);
            var jaulaCod = reader.IsDBNull(8) ? "Sin Jaula" : reader.GetString(8);
            var jaulaNom = reader.IsDBNull(9) ? "No asignada" : reader.GetString(9);
            var galpon = reader.GetString(10);
            var genetica = reader.GetString(11);
            var sanitario = reader.GetString(12);
            var obs = reader.GetString(13);

            var calculo = CalcularPlanZootecnico(fechaNac, peso);

            var proxVacuna = calculo.PlanVacunacion.FirstOrDefault(v => v.DiaVidaProgramado >= calculo.DiasVida) 
                             ?? calculo.PlanVacunacion.LastOrDefault();

            return new CerdoViewModel
            {
                Id = cid,
                Crotal = crotal,
                Rfid = rfid,
                Sexo = sexo,
                FechaNacimiento = fechaNac,
                DiasVida = calculo.DiasVida,
                PesoActualKg = peso,
                EtapaActual = calculo.EtapaClasificada,
                SubEtapa = calculo.SubEtapa,
                JaulaCorralId = jaulaId,
                JaulaCodigo = jaulaCod,
                JaulaNombre = jaulaNom,
                GalponNombre = galpon,
                LineaGenetica = genetica,
                EstatusSanitario = sanitario,
                Observaciones = obs,

                AlimentoTipo = calculo.AlimentoTipo,
                ConsumoDiarioKg = calculo.ConsumoDiarioKg,
                ConsumoDiarioGramos = calculo.ConsumoDiarioGramos,
                TomasAlDia = calculo.TomasAlDia,
                CantidadPorTomaGramos = calculo.CantidadPorTomaGramos,
                CantidadPorTomaKg = calculo.CantidadPorTomaKg,
                HorariosSugeridos = calculo.HorariosSugeridos,
                RecomendacionNutricional = calculo.RecomendacionNutricional,

                ProximaVacunaNombre = proxVacuna?.VacunaBiológico ?? "Plan Completo",
                ProximaVacunaFecha = proxVacuna?.FechaEstimada,
                ProximaVacunaDias = proxVacuna != null ? proxVacuna.DiaVidaProgramado - calculo.DiasVida : 0,
                ProximaVacunaEstado = proxVacuna?.Estado ?? "Completado",
                ProximaVacunaBadgeClass = proxVacuna?.BadgeClass ?? "bg-gray-100 text-gray-700",
                PlanVacunacion = calculo.PlanVacunacion
            };
        }

        return null;
    }

    public async Task<bool> GuardarCerdoAsync(CerdoInputModel input)
    {
        if (input == null || string.IsNullOrWhiteSpace(input.Crotal)) return false;
        await EnsureBaseEntitiesAsync();

        await using var conn = CreateConnection();
        await conn.OpenAsync();
        Guid granjaId = Guid.Parse("a0000000-0000-0000-0000-000000000001");

        // Calcular etapa zootécnica real a partir de fecha de nacimiento
        var calc = CalcularPlanZootecnico(input.FechaNacimiento, input.PesoActualKg);

        string sql;
        if (!input.Id.HasValue || input.Id == Guid.Empty)
        {
            sql = @"
                INSERT INTO cerdos_individuales (
                    granja_id, crotal, rfid, sexo, fecha_nacimiento, peso_actual_kg, etapa_actual,
                    jaula_corral_id, linea_genetica, estatus_sanitario, observaciones, activo, created_at, updated_at
                ) VALUES (
                    @granja, @crotal, @rfid, @sexo, @fechaNac, @peso, @etapa,
                    @jaulaId, @linea, @sanitario, @obs, true, NOW(), NOW()
                ) ON CONFLICT (crotal) DO UPDATE 
                SET rfid = EXCLUDED.rfid, sexo = EXCLUDED.sexo, fecha_nacimiento = EXCLUDED.fecha_nacimiento,
                    peso_actual_kg = EXCLUDED.peso_actual_kg, etapa_actual = EXCLUDED.etapa_actual,
                    jaula_corral_id = EXCLUDED.jaula_corral_id, linea_genetica = EXCLUDED.linea_genetica,
                    estatus_sanitario = EXCLUDED.estatus_sanitario, observaciones = EXCLUDED.observaciones,
                    activo = true, updated_at = NOW();";
        }
        else
        {
            sql = @"
                UPDATE cerdos_individuales
                SET crotal = @crotal, rfid = @rfid, sexo = @sexo, fecha_nacimiento = @fechaNac,
                    peso_actual_kg = @peso, etapa_actual = @etapa, jaula_corral_id = @jaulaId,
                    linea_genetica = @linea, estatus_sanitario = @sanitario, observaciones = @obs,
                    activo = true, updated_at = NOW()
                WHERE id = @id;";
        }

        await using (var cmd = new NpgsqlCommand(sql, conn))
        {
            cmd.Parameters.AddWithValue("granja", granjaId);
            cmd.Parameters.AddWithValue("crotal", input.Crotal.Trim().ToUpperInvariant());
            cmd.Parameters.AddWithValue("rfid", string.IsNullOrWhiteSpace(input.Rfid) ? (object)DBNull.Value : input.Rfid.Trim());
            cmd.Parameters.AddWithValue("sexo", string.IsNullOrWhiteSpace(input.Sexo) ? "Hembra" : input.Sexo);
            cmd.Parameters.AddWithValue("fechaNac", input.FechaNacimiento.Date);
            cmd.Parameters.AddWithValue("peso", input.PesoActualKg);
            cmd.Parameters.AddWithValue("etapa", calc.EtapaClasificada);
            cmd.Parameters.AddWithValue("jaulaId", input.JaulaCorralId.HasValue && input.JaulaCorralId != Guid.Empty ? (object)input.JaulaCorralId.Value : DBNull.Value);
            cmd.Parameters.AddWithValue("linea", string.IsNullOrWhiteSpace(input.LineaGenetica) ? "Topigs Norsvin 70" : input.LineaGenetica);
            cmd.Parameters.AddWithValue("sanitario", string.IsNullOrWhiteSpace(input.EstatusSanitario) ? "Saludable" : input.EstatusSanitario);
            cmd.Parameters.AddWithValue("obs", string.IsNullOrWhiteSpace(input.Observaciones) ? "" : input.Observaciones);

            if (input.Id.HasValue && input.Id != Guid.Empty)
            {
                cmd.Parameters.AddWithValue("id", input.Id.Value);
            }

            await cmd.ExecuteNonQueryAsync();
        }

        // Actualizar contador de ocupación en jaulas
        string sqlUpdateOcup = @"
            UPDATE jaulas_corrales j
            SET ocupacion_actual = (
                SELECT COUNT(*) FROM cerdos_individuales c 
                WHERE c.jaula_corral_id = j.id AND c.activo = true
            );";
        await using (var cmdOcup = new NpgsqlCommand(sqlUpdateOcup, conn))
        {
            await cmdOcup.ExecuteNonQueryAsync();
        }

        InvalidarCachesOperativos();
        return true;
    }

    public async Task<bool> EliminarCerdoAsync(Guid id)
    {
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        string sql = "UPDATE cerdos_individuales SET activo = false, updated_at = NOW() WHERE id = @id;";
        await using (var cmd = new NpgsqlCommand(sql, conn))
        {
            cmd.Parameters.AddWithValue("id", id);
            await cmd.ExecuteNonQueryAsync();
        }

        string sqlUpdateOcup = @"
            UPDATE jaulas_corrales j
            SET ocupacion_actual = (
                SELECT COUNT(*) FROM cerdos_individuales c 
                WHERE c.jaula_corral_id = j.id AND c.activo = true
            );";
        await using (var cmdOcup = new NpgsqlCommand(sqlUpdateOcup, conn))
        {
            await cmdOcup.ExecuteNonQueryAsync();
        }

        InvalidarCachesOperativos();
        return true;
    }

    // =========================================================================
    // 8. CRONOGRAMA, TAREAS, HORARIOS Y CENTRO DE NOTIFICACIONES EN VIVO
    // =========================================================================

    public async Task<bool> RegistrarBajaMortalidadAsync(BajaMortalidadInputModel input)
    {
        await EnsureBaseEntitiesAsync();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        Guid granjaId = Guid.Parse("a0000000-0000-0000-0000-000000000001");
        string sql = @"
            INSERT INTO registro_bajas_mortalidad (
                granja_id, crotal, lote_codigo, galpon_nombre, causa_muerte,
                peso_kg, etapa_zootecnica, necropsia_realizada, hallazgos_necropsia,
                registrado_por, observaciones, fecha_baja
            ) VALUES (
                @granja, @crotal, @lote, @galpon, @causa,
                @peso, @etapa, @necropsia, @hallazgos,
                @reg, @obs, COALESCE(@fecha, NOW())
            );

            -- Marcar inactivo en cerdos individuales si existe
            UPDATE cerdos_individuales
            SET activo = false, estatus_sanitario = 'Baja: ' || @causa, updated_at = NOW()
            WHERE crotal = @crotal;

            -- Registrar alerta preventiva
            INSERT INTO alertas_operativas_desviaciones (
                granja_id, tipo_alerta, nivel_severidad, descripcion, valor_detectado, umbral_esperado, estado
            ) VALUES (
                @granja, 'mortalidad_anormal', 'preventiva', 
                'Baja reportada en campo: Animal ' || @crotal || ' por ' || @causa,
                '1 baja (' || @etapa || ')', 'Tasa esperada < 1.5%', 'abierta'
            );";

        await using var cmd = new NpgsqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("granja", granjaId);
        cmd.Parameters.AddWithValue("crotal", input.Crotal.Trim());
        cmd.Parameters.AddWithValue("lote", (object?)input.LoteCodigo ?? DBNull.Value);
        cmd.Parameters.AddWithValue("galpon", (object?)input.GalponNombre ?? DBNull.Value);
        cmd.Parameters.AddWithValue("causa", input.CausaMuerte);
        cmd.Parameters.AddWithValue("peso", input.PesoKg);
        cmd.Parameters.AddWithValue("etapa", input.EtapaZootecnica);
        cmd.Parameters.AddWithValue("necropsia", input.NecropsiaRealizada);
        cmd.Parameters.AddWithValue("hallazgos", (object?)input.HallazgosNecropsia ?? DBNull.Value);
        cmd.Parameters.AddWithValue("reg", input.RegistradoPor);
        cmd.Parameters.AddWithValue("obs", (object?)input.Observaciones ?? DBNull.Value);
        cmd.Parameters.Add(new NpgsqlParameter("fecha", NpgsqlTypes.NpgsqlDbType.TimestampTz) { Value = (object?)input.FechaBaja ?? DBNull.Value });

        await cmd.ExecuteNonQueryAsync();
        InvalidarCachesOperativos();
        return true;
    }

    public async Task<List<NotificacionCentroItem>> GetNotificacionesActivasAsync(string? rolRaw = null, string? nombreUsuario = null)
    {
        await EnsureBaseEntitiesAsync();
        var rol = ResolveRole(rolRaw);
        var usuarioNom = ResolveUserName(nombreUsuario);
        bool esAdmin = rol == AppRoles.Administrador;
        bool esVet = rol == AppRoles.Veterinario;
        bool esOp = rol == AppRoles.Operario;

        // Deduplicación intra-petición (_Layout + PageModel comparten la misma consulta sin tocar BD dos veces)
        var reqKey = $"agropig:req_notif:{rol}:{usuarioNom}";
        if (_http.HttpContext?.Items.TryGetValue(reqKey, out var cachedReq) == true && cachedReq is List<NotificacionCentroItem> reqList)
            return reqList;

        var list = await _cache.GetOrCreateAsync(CacheKey("notif", rol, usuarioNom), async entry =>
        {
            entry.AbsoluteExpirationRelativeToNow = TimeSpan.FromSeconds(30);
            var items = new List<NotificacionCentroItem>();

            await using var conn = CreateConnection();
            await conn.OpenAsync();

            // 1. Alertas operativas abiertas filtradas por rol en SQL
            try
            {
                var alertaSqlFilter = esOp
                    ? " AND tipo_alerta NOT IN ('desviacion_financiera', 'protocolo_sanitario', 'mortalidad_anormal') "
                    : (esVet ? " AND tipo_alerta <> 'desviacion_financiera' " : "");
                string sqlAlertas = $@"
                    SELECT id, tipo_alerta, nivel_severidad, descripcion, fecha_deteccion, estado
                    FROM alertas_operativas_desviaciones
                    WHERE estado = 'abierta'
                    {alertaSqlFilter}
                    ORDER BY fecha_deteccion DESC LIMIT 10;";
                await using var cmd = new NpgsqlCommand(sqlAlertas, conn);
                await using var reader = await cmd.ExecuteReaderAsync();
                while (await reader.ReadAsync())
                {
                    var tipo = reader.GetString(1);
                    var titulo = tipo switch
                    {
                        "desviacion_financiera" => "Desviación Presupuestal",
                        "protocolo_sanitario" => "Protocolo Sanitario Pendiente",
                        "sobreconsumo_alimento" => "Sobreconsumo en Tolvas",
                        "anomalia_ambiental" => "Alerta Ambiental Galpón",
                        "mortalidad_anormal" => "Registro Sanitario de Baja",
                        _ => "Alerta Operativa"
                    };

                    items.Add(new NotificacionCentroItem
                    {
                        Id = reader.GetGuid(0),
                        Titulo = titulo,
                        Mensaje = reader.GetString(3),
                        Tipo = tipo,
                        Severidad = reader.GetString(2),
                        FechaHora = reader.GetDateTime(4),
                        Leida = false,
                        UrlDestino = tipo switch
                        {
                            "protocolo_sanitario" => "/Calendario",
                            "desviacion_financiera" => "/Presupuestos",
                            "mortalidad_anormal" => "/Cerdos",
                            "sobreconsumo_alimento" => "/Inventario",
                            _ => "/"
                        }
                    });
                }
            }
            catch { /* Resiliente */ }

            // 2. Partos inminentes (< 48 hrs o retrasados) — solo Administrador y Veterinario
            if (esAdmin || esVet)
            {
                try
                {
                    string sqlPartos = @"
                        SELECT s.id, c.caravana, s.fecha_parto_estimada, COALESCE(c.jaula_numero, 'Sin jaula')
                        FROM servicios_inseminacion s
                        JOIN cerdas_reproductoras c ON s.cerda_id = c.id
                        WHERE s.resultado_concepcion = 'confirmada_positiva' 
                          AND s.fecha_parto_estimada <= CURRENT_DATE + INTERVAL '2 days'
                          AND NOT EXISTS (SELECT 1 FROM partos p WHERE p.cerda_id = s.cerda_id AND p.fecha_hora >= s.fecha_servicio)
                        ORDER BY s.fecha_parto_estimada ASC LIMIT 5;";
                    await using var cmdPartos = new NpgsqlCommand(sqlPartos, conn);
                    await using var rPartos = await cmdPartos.ExecuteReaderAsync();
                    while (await rPartos.ReadAsync())
                    {
                        var caravana = rPartos.GetString(1);
                        var fep = rPartos.GetDateTime(2);
                        var jaula = rPartos.GetString(3);
                        var diasFaltan = (fep.Date - DateTime.Today).TotalDays;

                        string msg = diasFaltan < 0
                            ? $"Cerda {caravana} en {jaula}: Fecha de parto sobrepasada por {Math.Abs((int)diasFaltan)} días. Requiere monitoreo veterinario."
                            : diasFaltan == 0
                                ? $"Cerda {caravana} en {jaula}: Fecha Estimada de Parto es HOY. Preparar kit de parto y lámpara infrarroja."
                                : $"Cerda {caravana} en {jaula}: Parto programado para mañana ({fep:dd/MM}). Verificar traslado a maternidad.";

                        items.Add(new NotificacionCentroItem
                        {
                            Id = Guid.NewGuid(),
                            Titulo = "Parto Inminente en Maternidad",
                            Mensaje = msg,
                            Tipo = "reproduccion",
                            Severidad = diasFaltan <= 0 ? "critica" : "preventiva",
                            FechaHora = DateTime.Now,
                            Leida = false,
                            UrlDestino = "/Reproduccion"
                        });
                    }
                }
                catch { /* Resiliente */ }
            }

            // 3. Tareas prioritarias pendientes de hoy filtradas por rol en SQL
            try
            {
                var tareaSqlFilter = esOp
                    ? " AND (COALESCE(asignado_a_rol, '') IN ('Operario', 'operario_galpon', 'Ambos', '') OR (@uPat <> '' AND asignado_a_nombre ILIKE @uPat)) "
                    : (esVet
                        ? " AND (COALESCE(asignado_a_rol, '') IN ('Veterinario', 'director_veterinario', 'Ambos', '') OR (@uPat <> '' AND asignado_a_nombre ILIKE @uPat)) "
                        : "");
                string sqlTareasUrg = $@"
                    SELECT id, titulo, hora_programada, tipo_actividad, COALESCE(galpon_nombre, 'Área General')
                    FROM tareas_actividades_granja
                    WHERE fecha_programada = CURRENT_DATE 
                      AND estado IN ('pendiente', 'en_progreso')
                      AND lower(prioridad) IN ('urgente', 'alta')
                    {tareaSqlFilter}
                    ORDER BY hora_programada ASC LIMIT 5;";
                await using var cmdT = new NpgsqlCommand(sqlTareasUrg, conn);
                if (esOp || esVet)
                    cmdT.Parameters.AddWithValue("uPat", string.IsNullOrWhiteSpace(usuarioNom) ? "" : $"%{usuarioNom}%");
                await using var rT = await cmdT.ExecuteReaderAsync();
                while (await rT.ReadAsync())
                {
                    items.Add(new NotificacionCentroItem
                    {
                        Id = rT.GetGuid(0),
                        Titulo = $"Tarea Prioritaria ({rT.GetString(2)})",
                        Mensaje = $"{rT.GetString(1)} en {rT.GetString(4)}",
                        Tipo = "tarea",
                        Severidad = "preventiva",
                        FechaHora = DateTime.Now,
                        Leida = false,
                        UrlDestino = "/Calendario"
                    });
                }
            }
            catch { /* Resiliente */ }

            return items;
        }) ?? new List<NotificacionCentroItem>();

        if (_http.HttpContext != null)
            _http.HttpContext.Items[reqKey] = list;

        return list;
    }

    public async Task<bool> ResolverNotificacionAsync(Guid id, string? rolRaw = null)
    {
        var rol = ResolveRole(rolRaw);
        bool esVet = rol == AppRoles.Veterinario;
        bool esOp = rol == AppRoles.Operario;

        await using var conn = CreateConnection();
        await conn.OpenAsync();

        var alertaSqlFilter = esOp
            ? " AND tipo_alerta NOT IN ('desviacion_financiera', 'protocolo_sanitario', 'mortalidad_anormal') "
            : (esVet ? " AND tipo_alerta <> 'desviacion_financiera' " : "");

        await using var cmd = new NpgsqlCommand($@"
            UPDATE alertas_operativas_desviaciones 
            SET estado = 'resuelta', fecha_resolucion = NOW(), resuelto_por_id = @user
            WHERE id = @id {alertaSqlFilter};", conn);
        cmd.Parameters.AddWithValue("id", id);
        cmd.Parameters.AddWithValue("user", CurrentUserIdDb);
        var ok = await cmd.ExecuteNonQueryAsync() > 0;
        if (ok) InvalidarCachesOperativos();
        return ok;
    }

    public async Task<CalendarioPageViewModel> GetCalendarioPageDataAsync(
        DateTime? fecha = null, 
        int? mes = null, 
        int? anio = null, 
        string? tipo = null, 
        string? estado = null, 
        string? rol = null,
        string? rolUsuarioActual = null,
        string? nombreUsuarioActual = null)
    {
        await EnsureBaseEntitiesAsync();
        var model = new CalendarioPageViewModel();
        var hoy = DateTime.Today;
        model.FechaSeleccionada = fecha ?? hoy;
        model.MesSeleccionado = mes ?? model.FechaSeleccionada.Month;
        model.AnioSeleccionado = anio ?? model.FechaSeleccionada.Year;
        model.FiltroTipo = tipo;
        model.FiltroEstado = estado;

        var callerRol = ResolveRole(rolUsuarioActual);
        var callerName = ResolveUserName(nombreUsuarioActual);
        bool esOperarioActual = callerRol == AppRoles.Operario;

        // El Operario no puede escalar el filtro a otros roles ni consultar agenda ajena
        string? filtroRolEfectivo = esOperarioActual ? "Operario" : rol;
        model.FiltroRol = filtroRolEfectivo;

        var roleSqlFilter = "";
        string? rolAliasAlt = null;
        if (esOperarioActual)
        {
            roleSqlFilter = " AND (COALESCE(asignado_a_rol, '') IN ('Operario', 'operario_galpon', 'Ambos', '') OR (@uPat <> '' AND asignado_a_nombre ILIKE @uPat)) ";
        }
        else if (!string.IsNullOrWhiteSpace(filtroRolEfectivo) && filtroRolEfectivo != "Todos")
        {
            rolAliasAlt = filtroRolEfectivo switch
            {
                "Veterinario" => AppRoles.Veterinario,
                "Operario" => AppRoles.Operario,
                "Administrador" => AppRoles.Administrador,
                _ => filtroRolEfectivo
            };
            roleSqlFilter = " AND (asignado_a_rol ILIKE @rol OR asignado_a_rol ILIKE @rolAlt OR COALESCE(asignado_a_rol, '') IN ('', 'Ambos')) ";
        }

        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Tareas del Horario del Día Seleccionado
        string sqlDia = @"
            SELECT 
                id, titulo, descripcion, tipo_actividad, prioridad, estado,
                fecha_programada, hora_programada, COALESCE(galpon_nombre, ''),
                COALESCE(lote_codigo, ''), COALESCE(cerda_caravana, ''),
                COALESCE(asignado_a_nombre, ''), COALESCE(asignado_a_rol, ''),
                COALESCE(completado_por, ''), fecha_completado, COALESCE(observaciones, '')
            FROM tareas_actividades_granja
            WHERE fecha_programada = @fecha ";

        if (!string.IsNullOrWhiteSpace(tipo) && tipo != "Todos") sqlDia += " AND tipo_actividad ILIKE @tipo ";
        if (!string.IsNullOrWhiteSpace(estado) && estado != "Todos") sqlDia += " AND estado = @estado ";
        sqlDia += roleSqlFilter;
        sqlDia += " ORDER BY hora_programada ASC, prioridad DESC;";

        await using (var cmd = new NpgsqlCommand(sqlDia, conn))
        {
            cmd.Parameters.AddWithValue("fecha", model.FechaSeleccionada.Date);
            if (!string.IsNullOrWhiteSpace(tipo) && tipo != "Todos") cmd.Parameters.AddWithValue("tipo", tipo);
            if (!string.IsNullOrWhiteSpace(estado) && estado != "Todos") cmd.Parameters.AddWithValue("estado", estado);
            if (esOperarioActual)
                cmd.Parameters.AddWithValue("uPat", string.IsNullOrWhiteSpace(callerName) ? "" : $"%{callerName}%");
            else if (!string.IsNullOrWhiteSpace(filtroRolEfectivo) && filtroRolEfectivo != "Todos")
            {
                cmd.Parameters.AddWithValue("rol", filtroRolEfectivo);
                cmd.Parameters.AddWithValue("rolAlt", rolAliasAlt ?? filtroRolEfectivo);
            }

            await using var reader = await cmd.ExecuteReaderAsync();
            while (await reader.ReadAsync())
            {
                model.TareasHorarioDia.Add(new TareaActividadItem
                {
                    Id = reader.GetGuid(0),
                    Titulo = reader.GetString(1),
                    Descripcion = reader.IsDBNull(2) ? "" : reader.GetString(2),
                    TipoActividad = reader.GetString(3),
                    Prioridad = reader.GetString(4),
                    Estado = reader.GetString(5),
                    FechaProgramada = reader.GetDateTime(6),
                    HoraProgramada = reader.GetString(7),
                    GalponNombre = reader.GetString(8),
                    LoteCodigo = reader.GetString(9),
                    CerdaCaravana = reader.GetString(10),
                    AsignadoANombre = reader.GetString(11),
                    AsignadoARol = reader.GetString(12),
                    CompletadoPor = reader.GetString(13),
                    FechaCompletado = reader.IsDBNull(14) ? null : reader.GetDateTime(14),
                    Observaciones = reader.GetString(15)
                });
            }
        }

        // 2. Tareas del Mes para la Rejilla de Calendario (mismos filtros por rol y tipo/estado)
        var inicioMes = new DateTime(model.AnioSeleccionado, model.MesSeleccionado, 1);
        var finMes = inicioMes.AddMonths(1).AddDays(-1);

        string sqlMes = @"
            SELECT 
                id, titulo, tipo_actividad, prioridad, estado, fecha_programada, hora_programada
            FROM tareas_actividades_granja
            WHERE fecha_programada BETWEEN @inicio AND @fin ";
        if (!string.IsNullOrWhiteSpace(tipo) && tipo != "Todos") sqlMes += " AND tipo_actividad ILIKE @tipo ";
        if (!string.IsNullOrWhiteSpace(estado) && estado != "Todos") sqlMes += " AND estado = @estado ";
        sqlMes += roleSqlFilter;
        sqlMes += " ORDER BY fecha_programada ASC, hora_programada ASC;";

        await using (var cmdMes = new NpgsqlCommand(sqlMes, conn))
        {
            cmdMes.Parameters.AddWithValue("inicio", inicioMes);
            cmdMes.Parameters.AddWithValue("fin", finMes);
            if (!string.IsNullOrWhiteSpace(tipo) && tipo != "Todos") cmdMes.Parameters.AddWithValue("tipo", tipo);
            if (!string.IsNullOrWhiteSpace(estado) && estado != "Todos") cmdMes.Parameters.AddWithValue("estado", estado);
            if (esOperarioActual)
                cmdMes.Parameters.AddWithValue("uPat", string.IsNullOrWhiteSpace(callerName) ? "" : $"%{callerName}%");
            else if (!string.IsNullOrWhiteSpace(filtroRolEfectivo) && filtroRolEfectivo != "Todos")
            {
                cmdMes.Parameters.AddWithValue("rol", filtroRolEfectivo);
                cmdMes.Parameters.AddWithValue("rolAlt", rolAliasAlt ?? filtroRolEfectivo);
            }

            await using var rMes = await cmdMes.ExecuteReaderAsync();
            while (await rMes.ReadAsync())
            {
                model.TareasMes.Add(new TareaActividadItem
                {
                    Id = rMes.GetGuid(0),
                    Titulo = rMes.GetString(1),
                    TipoActividad = rMes.GetString(2),
                    Prioridad = rMes.GetString(3),
                    Estado = rMes.GetString(4),
                    FechaProgramada = rMes.GetDateTime(5),
                    HoraProgramada = rMes.GetString(6)
                });
            }
        }

        // 3. Contadores
        model.TareasHoyTotal = model.TareasHorarioDia.Count;
        model.TareasHoyPendientes = model.TareasHorarioDia.Count(t => !t.EstaCompletada);
        model.TareasHoyCompletadas = model.TareasHorarioDia.Count(t => t.EstaCompletada);
        model.TareasUrgentes = model.TareasHorarioDia.Count(t => t.EsUrgente && !t.EstaCompletada);

        // 4. Notificaciones reutilizadas desde caché filtrada por rol
        model.Notificaciones = await GetNotificacionesActivasAsync(callerRol, callerName);

        // 5. Selectores para modal de nueva tarea
        await using (var cmdG = new NpgsqlCommand("SELECT nombre FROM galpones_naves WHERE activo = true ORDER BY nombre;", conn))
        await using (var rG = await cmdG.ExecuteReaderAsync())
        {
            while (await rG.ReadAsync()) model.GalponesDisponibles.Add(rG.GetString(0));
        }

        await using (var cmdL = new NpgsqlCommand("SELECT codigo_lote FROM lotes_produccion WHERE estado = 'activo' ORDER BY codigo_lote;", conn))
        await using (var rL = await cmdL.ExecuteReaderAsync())
        {
            while (await rL.ReadAsync()) model.LotesDisponibles.Add(rL.GetString(0));
        }

        await using (var cmdU = new NpgsqlCommand("SELECT nombre, rol FROM usuarios_operadores WHERE activo = true ORDER BY nombre;", conn))
        await using (var rU = await cmdU.ExecuteReaderAsync())
        {
            while (await rU.ReadAsync()) model.OperadoresDisponibles.Add($"{rU.GetString(0)} ({rU.GetString(1)})");
        }

        return model;
    }

    public async Task<bool> GuardarTareaAsync(TareaActividadInputModel input)
    {
        await EnsureBaseEntitiesAsync();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        Guid granjaId = Guid.Parse("a0000000-0000-0000-0000-000000000001");
        if (input.Id.HasValue && input.Id.Value != Guid.Empty)
        {
            string sqlUp = @"
                UPDATE tareas_actividades_granja
                SET titulo = @titulo,
                    descripcion = @desc,
                    tipo_actividad = @tipo,
                    prioridad = @prio,
                    estado = @estado,
                    fecha_programada = @fecha,
                    hora_programada = @hora,
                    galpon_nombre = @galpon,
                    lote_codigo = @lote,
                    cerda_caravana = @cerda,
                    asignado_a_nombre = @asigNom,
                    asignado_a_rol = @asigRol,
                    observaciones = @obs
                WHERE id = @id;";
            await using var cmd = new NpgsqlCommand(sqlUp, conn);
            cmd.Parameters.AddWithValue("id", input.Id.Value);
            cmd.Parameters.AddWithValue("titulo", input.Titulo.Trim());
            cmd.Parameters.AddWithValue("desc", (object?)input.Descripcion ?? DBNull.Value);
            cmd.Parameters.AddWithValue("tipo", input.TipoActividad);
            cmd.Parameters.AddWithValue("prio", input.Prioridad);
            cmd.Parameters.AddWithValue("estado", input.Estado);
            cmd.Parameters.AddWithValue("fecha", input.FechaProgramada.Date);
            cmd.Parameters.AddWithValue("hora", input.HoraProgramada);
            cmd.Parameters.AddWithValue("galpon", (object?)input.GalponNombre ?? DBNull.Value);
            cmd.Parameters.AddWithValue("lote", (object?)input.LoteCodigo ?? DBNull.Value);
            cmd.Parameters.AddWithValue("cerda", (object?)input.CerdaCaravana ?? DBNull.Value);
            cmd.Parameters.AddWithValue("asigNom", (object?)input.AsignadoANombre ?? DBNull.Value);
            cmd.Parameters.AddWithValue("asigRol", (object?)input.AsignadoARol ?? DBNull.Value);
            cmd.Parameters.AddWithValue("obs", (object?)input.Observaciones ?? DBNull.Value);
            var okUp = await cmd.ExecuteNonQueryAsync() > 0;
            if (okUp) InvalidarCachesOperativos();
            return okUp;
        }
        else
        {
            string sqlIn = @"
                INSERT INTO tareas_actividades_granja (
                    granja_id, titulo, descripcion, tipo_actividad, prioridad, estado,
                    fecha_programada, hora_programada, galpon_nombre, lote_codigo,
                    cerda_caravana, asignado_a_nombre, asignado_a_rol, observaciones
                ) VALUES (
                    @granja, @titulo, @desc, @tipo, @prio, @estado,
                    @fecha, @hora, @galpon, @lote,
                    @cerda, @asigNom, @asigRol, @obs
                );";
            await using var cmd = new NpgsqlCommand(sqlIn, conn);
            cmd.Parameters.AddWithValue("granja", granjaId);
            cmd.Parameters.AddWithValue("titulo", input.Titulo.Trim());
            cmd.Parameters.AddWithValue("desc", (object?)input.Descripcion ?? DBNull.Value);
            cmd.Parameters.AddWithValue("tipo", input.TipoActividad);
            cmd.Parameters.AddWithValue("prio", input.Prioridad);
            cmd.Parameters.AddWithValue("estado", input.Estado);
            cmd.Parameters.AddWithValue("fecha", input.FechaProgramada.Date);
            cmd.Parameters.AddWithValue("hora", input.HoraProgramada);
            cmd.Parameters.AddWithValue("galpon", (object?)input.GalponNombre ?? DBNull.Value);
            cmd.Parameters.AddWithValue("lote", (object?)input.LoteCodigo ?? DBNull.Value);
            cmd.Parameters.AddWithValue("cerda", (object?)input.CerdaCaravana ?? DBNull.Value);
            cmd.Parameters.AddWithValue("asigNom", (object?)input.AsignadoANombre ?? DBNull.Value);
            cmd.Parameters.AddWithValue("asigRol", (object?)input.AsignadoARol ?? DBNull.Value);
            cmd.Parameters.AddWithValue("obs", (object?)input.Observaciones ?? DBNull.Value);
            var okIn = await cmd.ExecuteNonQueryAsync() > 0;
            if (okIn) InvalidarCachesOperativos();
            return okIn;
        }
    }

    public async Task<bool> ActualizarEstadoTareaAsync(Guid id, string nuevoEstado, string? observaciones = null, string? completadoPor = null)
    {
        await EnsureBaseEntitiesAsync();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        string sql = @"
            UPDATE tareas_actividades_granja
            SET estado = @estado,
                completado_por = CASE WHEN @estado = 'completada' THEN COALESCE(@por, completado_por) ELSE completado_por END,
                fecha_completado = CASE WHEN @estado = 'completada' THEN NOW() ELSE fecha_completado END,
                observaciones = CASE WHEN @obs::text IS NOT NULL AND @obs::text != '' THEN @obs::text ELSE observaciones END
            WHERE id = @id;";

        await using var cmd = new NpgsqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("id", id);
        cmd.Parameters.AddWithValue("estado", nuevoEstado);
        cmd.Parameters.AddWithValue("por", (object?)completadoPor ?? DBNull.Value);
        cmd.Parameters.AddWithValue("obs", (object?)observaciones ?? DBNull.Value);
        var ok = await cmd.ExecuteNonQueryAsync() > 0;
        if (ok) InvalidarCachesOperativos();
        return ok;
    }

    public async Task<bool> EliminarTareaAsync(Guid id)
    {
        await EnsureBaseEntitiesAsync();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        await using var cmd = new NpgsqlCommand("DELETE FROM tareas_actividades_granja WHERE id = @id;", conn);
        cmd.Parameters.AddWithValue("id", id);
        var ok = await cmd.ExecuteNonQueryAsync() > 0;
        if (ok) InvalidarCachesOperativos();
        return ok;
    }

    // =========================================================================
    // GESTIÓN DE USUARIOS DEL SISTEMA (EXCLUSIVO ADMINISTRADOR)
    // =========================================================================
    public async Task<UsuariosPageViewModel> GetUsuariosAsync()
    {
        await EnsureBaseEntitiesAsync();
        var vm = new UsuariosPageViewModel();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        string sql = @"
            SELECT id, nombre_usuario, nombre, email, rol, matricula_profesional, telefono, activo, debe_cambiar_password, ultimo_login, created_at
            FROM usuarios_operadores
            ORDER BY 
                CASE rol 
                    WHEN 'administrador_financiero' THEN 1 
                    WHEN 'director_veterinario' THEN 2 
                    ELSE 3 
                END, nombre;";

        await using var cmd = new NpgsqlCommand(sql, conn);
        await using var r = await cmd.ExecuteReaderAsync();
        while (await r.ReadAsync())
        {
            var item = new UsuarioSistemaItem
            {
                Id = r.GetGuid(0),
                NombreUsuario = r.IsDBNull(1) ? "" : r.GetString(1),
                Nombre = r.GetString(2),
                Email = r.IsDBNull(3) ? "" : r.GetString(3),
                Rol = r.GetString(4),
                MatriculaProfesional = r.IsDBNull(5) ? null : r.GetString(5),
                Telefono = r.IsDBNull(6) ? null : r.GetString(6),
                Activo = r.GetBoolean(7),
                DebeCambiarPassword = r.GetBoolean(8),
                UltimoLogin = r.IsDBNull(9) ? null : r.GetDateTime(9),
                FechaCreacion = r.IsDBNull(10) ? DateTime.MinValue : r.GetDateTime(10)
            };
            vm.Usuarios.Add(item);
        }

        vm.TotalUsuarios = vm.Usuarios.Count;
        vm.TotalAdmins = vm.Usuarios.Count(u => u.Rol == "administrador_financiero");
        vm.TotalVeterinarios = vm.Usuarios.Count(u => u.Rol == "director_veterinario");
        vm.TotalOperarios = vm.Usuarios.Count(u => u.Rol == "operario_galpon");
        vm.TotalActivos = vm.Usuarios.Count(u => u.Activo);

        return vm;
    }

    public async Task<(bool Exito, string Mensaje)> CrearUsuarioAsync(CrearUsuarioInput input)
    {
        if (string.IsNullOrWhiteSpace(input.NombreUsuario))
            return (false, "El nombre de usuario es obligatorio.");
        if (string.IsNullOrWhiteSpace(input.Nombre))
            return (false, "El nombre completo del colaborador es obligatorio.");

        var username = input.NombreUsuario.Trim().ToLowerInvariant();
        var password = string.IsNullOrWhiteSpace(input.Password) ? "Prueba#2026" : input.Password.Trim();

        await EnsureBaseEntitiesAsync();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // Validar duplicado
        await using (var checkCmd = new NpgsqlCommand("SELECT COUNT(*) FROM usuarios_operadores WHERE lower(nombre_usuario) = @u OR lower(nombre) = @u;", conn))
        {
            checkCmd.Parameters.AddWithValue("u", username);
            var existe = Convert.ToInt64(await checkCmd.ExecuteScalarAsync());
            if (existe > 0)
            {
                return (false, $"El usuario '{username}' ya existe en el sistema.");
            }
        }

        string granjaId = "a0000000-0000-0000-0000-000000000001";
        string sql = @"
            INSERT INTO usuarios_operadores (granja_id, nombre_usuario, nombre, email, password_hash, rol, matricula_profesional, telefono, activo, debe_cambiar_password)
            VALUES (@granja::uuid, @user, @nombre, @email, crypt(@pass, gen_salt('bf', 10)), @rol, @matricula, @tel, TRUE, FALSE);";

        await using var cmd = new NpgsqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("granja", granjaId);
        cmd.Parameters.AddWithValue("user", username);
        cmd.Parameters.AddWithValue("nombre", input.Nombre.Trim());
        cmd.Parameters.AddWithValue("email", string.IsNullOrWhiteSpace(input.Email) ? (object)DBNull.Value : input.Email.Trim().ToLowerInvariant());
        cmd.Parameters.AddWithValue("pass", password);
        cmd.Parameters.AddWithValue("rol", string.IsNullOrWhiteSpace(input.Rol) ? "operario_galpon" : input.Rol.Trim());
        cmd.Parameters.AddWithValue("matricula", string.IsNullOrWhiteSpace(input.MatriculaProfesional) ? (object)DBNull.Value : input.MatriculaProfesional.Trim());
        cmd.Parameters.AddWithValue("tel", string.IsNullOrWhiteSpace(input.Telefono) ? (object)DBNull.Value : input.Telefono.Trim());

        var rows = await cmd.ExecuteNonQueryAsync();
        if (rows > 0) InvalidarCachesOperativos();
        return (rows > 0, rows > 0 ? $"Usuario '{username}' creado exitosamente con contraseña inicial '{password}'." : "No se pudo crear el usuario.");
    }

    public async Task<bool> ToggleUsuarioActivoAsync(Guid id)
    {
        await EnsureBaseEntitiesAsync();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        string sql = "UPDATE usuarios_operadores SET activo = NOT activo WHERE id = @id;";
        await using var cmd = new NpgsqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("id", id);
        var ok = await cmd.ExecuteNonQueryAsync() > 0;
        if (ok) InvalidarCachesOperativos();
        return ok;
    }

    // =========================================================================
    // MÓDULO DE COMPRAS, PROVEEDORES, CATÁLOGO Y ÓRDENES CON DESCUENTOS
    // =========================================================================
    public async Task<ComprasViewModel> GetComprasDataAsync(bool incluirCostos = true)
    {
        await EnsureBaseEntitiesAsync();
        var vm = new ComprasViewModel();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Proveedores
        string sqlProv = @"
            SELECT p.id, p.codigo, p.nombre_comercial, p.razon_social, p.ruc, p.contacto_nombre, 
                   p.telefono, p.email, p.direccion, p.condiciones_pago, p.activo,
                   COALESCE(COUNT(c.id), 0) AS total_productos
            FROM proveedores p
            LEFT JOIN catalogo_proveedores_productos c ON c.proveedor_id = p.id AND c.activo = true
            GROUP BY p.id, p.codigo, p.nombre_comercial, p.razon_social, p.ruc, p.contacto_nombre, 
                     p.telefono, p.email, p.direccion, p.condiciones_pago, p.activo
            ORDER BY p.nombre_comercial;";

        await using (var cmd = new NpgsqlCommand(sqlProv, conn))
        await using (var r = await cmd.ExecuteReaderAsync())
        {
            while (await r.ReadAsync())
            {
                vm.Proveedores.Add(new ProveedorItem
                {
                    Id = r.GetGuid(0),
                    Codigo = r.GetString(1),
                    NombreComercial = r.GetString(2),
                    RazonSocial = r.IsDBNull(3) ? "" : r.GetString(3),
                    Ruc = r.IsDBNull(4) ? "" : r.GetString(4),
                    ContactoNombre = r.IsDBNull(5) ? "" : r.GetString(5),
                    Telefono = r.IsDBNull(6) ? "" : r.GetString(6),
                    Email = r.IsDBNull(7) ? "" : r.GetString(7),
                    Direccion = r.IsDBNull(8) ? "" : r.GetString(8),
                    CondicionesPago = r.IsDBNull(9) ? "Contado" : r.GetString(9),
                    Activo = r.GetBoolean(10),
                    TotalProductosOfrecidos = Convert.ToInt32(r.GetInt64(11))
                });
            }
        }

        // 2. Catálogo de productos con reglas de descuento
        string sqlProd = @"
            SELECT cp.id, cp.proveedor_id, p.nombre_comercial, cp.codigo_producto, cp.nombre_producto,
                   cp.unidad_medida, cp.precio_unitario_usd, cp.precio_unitario_nio, cp.aplica_descuento,
                   cp.unidades_minimas_descuento, cp.tipo_descuento, cp.descuento_porcentaje,
                   cp.descuento_monetario_usd, cp.descuento_monetario_nio, cp.notas_promocion, cp.activo
            FROM catalogo_proveedores_productos cp
            JOIN proveedores p ON p.id = cp.proveedor_id
            WHERE cp.activo = true
            ORDER BY p.nombre_comercial, cp.nombre_producto;";

        await using (var cmd = new NpgsqlCommand(sqlProd, conn))
        await using (var r = await cmd.ExecuteReaderAsync())
        {
            while (await r.ReadAsync())
            {
                vm.ProductosCatalogo.Add(new ProductoCatalogoCompraItem
                {
                    Id = r.GetGuid(0),
                    ProveedorId = r.GetGuid(1),
                    ProveedorNombre = r.GetString(2),
                    CodigoProducto = r.GetString(3),
                    NombreProducto = r.GetString(4),
                    UnidadMedida = r.GetString(5),
                    PrecioUnitarioUsd = r.GetDecimal(6),
                    PrecioUnitarioNio = r.GetDecimal(7),
                    AplicaDescuento = r.GetBoolean(8),
                    UnidadesMinimasDescuento = r.GetInt32(9),
                    TipoDescuento = r.GetString(10),
                    DescuentoPorcentaje = r.GetDecimal(11),
                    DescuentoMonetarioUsd = r.GetDecimal(12),
                    DescuentoMonetarioNio = r.GetDecimal(13),
                    NotasPromocion = r.IsDBNull(14) ? "" : r.GetString(14),
                    Activo = r.GetBoolean(15)
                });
            }
        }

        // 3. Órdenes de compra emitidas
        string sqlOrd = @"
            SELECT oc.id, oc.numero_orden, oc.proveedor_id, p.nombre_comercial, oc.fecha_emision,
                   oc.fecha_entrega_esperada, oc.moneda, oc.tasa_cambio, oc.estado,
                   oc.subtotal_usd, oc.descuento_usd, oc.total_usd,
                   oc.subtotal_nio, oc.descuento_nio, oc.total_nio,
                   COALESCE(u.nombre, 'Admin'), oc.observaciones,
                   COALESCE(COUNT(d.id), 0) AS cant_items,
                   oc.recibido_por, oc.fecha_recepcion, oc.lote_fabricante, oc.fecha_vencimiento, oc.bodega_destino
            FROM ordenes_compra oc
            JOIN proveedores p ON p.id = oc.proveedor_id
            LEFT JOIN usuarios_operadores u ON u.id = oc.solicitado_por_id
            LEFT JOIN ordenes_compra_detalles d ON d.orden_compra_id = oc.id
            GROUP BY oc.id, oc.numero_orden, oc.proveedor_id, p.nombre_comercial, oc.fecha_emision,
                     oc.fecha_entrega_esperada, oc.moneda, oc.tasa_cambio, oc.estado,
                     oc.subtotal_usd, oc.descuento_usd, oc.total_usd,
                     oc.subtotal_nio, oc.descuento_nio, oc.total_nio,
                     u.nombre, oc.observaciones, oc.recibido_por, oc.fecha_recepcion, oc.lote_fabricante, oc.fecha_vencimiento, oc.bodega_destino
            ORDER BY oc.fecha_emision DESC, oc.created_at DESC;";

        await using (var cmd = new NpgsqlCommand(sqlOrd, conn))
        await using (var r = await cmd.ExecuteReaderAsync())
        {
            while (await r.ReadAsync())
            {
                vm.OrdenesCompra.Add(new OrdenCompraItem
                {
                    Id = r.GetGuid(0),
                    NumeroOrden = r.GetString(1),
                    ProveedorId = r.GetGuid(2),
                    ProveedorNombre = r.GetString(3),
                    FechaEmision = r.GetDateTime(4),
                    FechaEntregaEsperada = r.IsDBNull(5) ? null : r.GetDateTime(5),
                    Moneda = r.GetString(6),
                    TasaCambio = r.GetDecimal(7),
                    Estado = r.GetString(8),
                    SubtotalUsd = r.GetDecimal(9),
                    DescuentoUsd = r.GetDecimal(10),
                    TotalUsd = r.GetDecimal(11),
                    SubtotalNio = r.GetDecimal(12),
                    DescuentoNio = r.GetDecimal(13),
                    TotalNio = r.GetDecimal(14),
                    SolicitadoPor = r.GetString(15),
                    Observaciones = r.IsDBNull(16) ? "" : r.GetString(16),
                    CantidadItems = Convert.ToInt32(r.GetInt64(17)),
                    RecibidoPor = r.IsDBNull(18) ? null : r.GetString(18),
                    FechaRecepcion = r.IsDBNull(19) ? null : r.GetDateTime(19),
                    LoteFabricante = r.IsDBNull(20) ? null : r.GetString(20),
                    FechaVencimiento = r.IsDBNull(21) ? null : r.GetDateTime(21),
                    BodegaDestino = r.IsDBNull(22) ? null : r.GetString(22)
                });
            }
        }

        // Cargar los detalles de cada orden de compra
        if (vm.OrdenesCompra.Any())
        {
            string sqlDet = @"
                SELECT orden_compra_id, id, catalogo_producto_id, descripcion, cantidad, unidad_medida,
                       precio_unitario_usd, precio_unitario_nio, aplico_descuento, tipo_descuento,
                       monto_descuento_unitario_usd, monto_descuento_linea_usd, subtotal_linea_usd, total_linea_usd,
                       monto_descuento_linea_nio, total_linea_nio
                FROM ordenes_compra_detalles
                ORDER BY created_at;";

            await using var cmdDet = new NpgsqlCommand(sqlDet, conn);
            await using var rDet = await cmdDet.ExecuteReaderAsync();
            while (await rDet.ReadAsync())
            {
                var ordenId = rDet.GetGuid(0);
                var ord = vm.OrdenesCompra.FirstOrDefault(o => o.Id == ordenId);
                if (ord != null)
                {
                    ord.Detalles.Add(new OrdenCompraDetalleItem
                    {
                        Id = rDet.GetGuid(1),
                        CatalogoProductoId = rDet.IsDBNull(2) ? null : rDet.GetGuid(2),
                        Descripcion = rDet.GetString(3),
                        Cantidad = rDet.GetDecimal(4),
                        UnidadMedida = rDet.GetString(5),
                        PrecioUnitarioUsd = rDet.GetDecimal(6),
                        PrecioUnitarioNio = rDet.GetDecimal(7),
                        AplicoDescuento = rDet.GetBoolean(8),
                        TipoDescuento = rDet.IsDBNull(9) ? "ninguno" : rDet.GetString(9),
                        MontoDescuentoUnitarioUsd = rDet.IsDBNull(10) ? 0 : rDet.GetDecimal(10),
                        MontoDescuentoLineaUsd = rDet.GetDecimal(11),
                        SubtotalLineaUsd = rDet.GetDecimal(12),
                        TotalLineaUsd = rDet.GetDecimal(13),
                        MontoDescuentoLineaNio = rDet.GetDecimal(14),
                        TotalLineaNio = rDet.GetDecimal(15)
                    });
                }
            }
        }

        vm.TotalProveedoresActivos = vm.Proveedores.Count(p => p.Activo);
        vm.TotalProductosCatalogo = vm.ProductosCatalogo.Count(p => p.Activo);
        vm.TotalOrdenesPendientes = vm.OrdenesCompra.Count(o => o.Estado == "emitida" || o.Estado == "borrador");
        vm.TotalCompradoUsd = incluirCostos ? vm.OrdenesCompra.Where(o => o.Estado != "cancelada").Sum(o => o.TotalUsd) : 0m;
        vm.TotalCompradoNio = incluirCostos ? vm.OrdenesCompra.Where(o => o.Estado != "cancelada").Sum(o => o.TotalNio) : 0m;

        if (!incluirCostos)
        {
            foreach (var prod in vm.ProductosCatalogo)
            {
                prod.PrecioUnitarioUsd = 0m;
                prod.PrecioUnitarioNio = 0m;
                prod.AplicaDescuento = false;
                prod.DescuentoPorcentaje = 0m;
                prod.DescuentoMonetarioUsd = 0m;
                prod.DescuentoMonetarioNio = 0m;
                prod.NotasPromocion = "";
            }
            foreach (var ord in vm.OrdenesCompra)
            {
                ord.SubtotalUsd = 0m;
                ord.DescuentoUsd = 0m;
                ord.TotalUsd = 0m;
                ord.SubtotalNio = 0m;
                ord.DescuentoNio = 0m;
                ord.TotalNio = 0m;
                ord.TasaCambio = 0m;
                foreach (var det in ord.Detalles)
                {
                    det.PrecioUnitarioUsd = 0m;
                    det.PrecioUnitarioNio = 0m;
                    det.AplicoDescuento = false;
                    det.MontoDescuentoUnitarioUsd = 0m;
                    det.MontoDescuentoLineaUsd = 0m;
                    det.SubtotalLineaUsd = 0m;
                    det.TotalLineaUsd = 0m;
                    det.MontoDescuentoLineaNio = 0m;
                    det.TotalLineaNio = 0m;
                }
            }
        }

        return vm;
    }

    public async Task<(bool Exito, string Mensaje)> CrearProveedorAsync(CrearProveedorInput input)
    {
        if (string.IsNullOrWhiteSpace(input.NombreComercial))
            return (false, "El nombre comercial del proveedor es obligatorio.");

        await EnsureBaseEntitiesAsync();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        var granjaId = Guid.Parse("a0000000-0000-0000-0000-000000000001");
        string codigo = string.IsNullOrWhiteSpace(input.Codigo) 
            ? "PRV-" + Guid.NewGuid().ToString().Substring(0, 6).ToUpper() 
            : input.Codigo.Trim().ToUpperInvariant();

        string sql = @"
            INSERT INTO proveedores (granja_id, codigo, nombre_comercial, razon_social, ruc, contacto_nombre, telefono, email, direccion, condiciones_pago, activo)
            VALUES (@granja, @codigo, @nombre, @razon, @ruc, @contacto, @tel, @email, @dir, @cond, true)
            ON CONFLICT (codigo) DO UPDATE 
            SET nombre_comercial = EXCLUDED.nombre_comercial, telefono = EXCLUDED.telefono, email = EXCLUDED.email;";

        await using var cmd = new NpgsqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("granja", granjaId);
        cmd.Parameters.AddWithValue("codigo", codigo);
        cmd.Parameters.AddWithValue("nombre", input.NombreComercial.Trim());
        cmd.Parameters.AddWithValue("razon", string.IsNullOrWhiteSpace(input.RazonSocial) ? (object)DBNull.Value : input.RazonSocial.Trim());
        cmd.Parameters.AddWithValue("ruc", string.IsNullOrWhiteSpace(input.Ruc) ? (object)DBNull.Value : input.Ruc.Trim());
        cmd.Parameters.AddWithValue("contacto", string.IsNullOrWhiteSpace(input.ContactoNombre) ? (object)DBNull.Value : input.ContactoNombre.Trim());
        cmd.Parameters.AddWithValue("tel", string.IsNullOrWhiteSpace(input.Telefono) ? (object)DBNull.Value : input.Telefono.Trim());
        cmd.Parameters.AddWithValue("email", string.IsNullOrWhiteSpace(input.Email) ? (object)DBNull.Value : input.Email.Trim());
        cmd.Parameters.AddWithValue("dir", string.IsNullOrWhiteSpace(input.Direccion) ? (object)DBNull.Value : input.Direccion.Trim());
        cmd.Parameters.AddWithValue("cond", string.IsNullOrWhiteSpace(input.CondicionesPago) ? "Contado" : input.CondicionesPago.Trim());

        var ok = await cmd.ExecuteNonQueryAsync() > 0;
        if (ok) InvalidarCachesOperativos();
        return (ok, ok ? $"Proveedor '{input.NombreComercial}' guardado con éxito." : "Error al registrar el proveedor.");
    }

    public async Task<(bool Exito, string Mensaje)> GuardarProductoCatalogoAsync(GuardarProductoCatalogoInput input)
    {
        if (input.ProveedorId == Guid.Empty)
            return (false, "Debes seleccionar un proveedor.");
        if (string.IsNullOrWhiteSpace(input.NombreProducto))
            return (false, "El nombre del producto es obligatorio.");
        if (input.PrecioUnitarioUsd <= 0 && input.PrecioUnitarioNio <= 0)
            return (false, "El precio unitario debe ser mayor a cero.");

        await EnsureBaseEntitiesAsync();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        const decimal tc = 36.80m;
        decimal pUsd = input.PrecioUnitarioUsd > 0 ? input.PrecioUnitarioUsd : Math.Round(input.PrecioUnitarioNio / tc, 2);
        decimal pNio = input.PrecioUnitarioNio > 0 ? input.PrecioUnitarioNio : Math.Round(input.PrecioUnitarioUsd * tc, 2);

        string codigo = string.IsNullOrWhiteSpace(input.CodigoProducto)
            ? "PROD-" + Guid.NewGuid().ToString().Substring(0, 6).ToUpper()
            : input.CodigoProducto.Trim().ToUpperInvariant();

        string sql = @"
            INSERT INTO catalogo_proveedores_productos (
                proveedor_id, codigo_producto, nombre_producto, unidad_medida,
                precio_unitario_usd, precio_unitario_nio, aplica_descuento, unidades_minimas_descuento,
                tipo_descuento, descuento_porcentaje, descuento_monetario_usd, descuento_monetario_nio,
                notas_promocion, activo
            ) VALUES (
                @prov, @cod, @nom, @um,
                @pusd, @pnio, @apDesc, @minUds,
                @tipoDesc, @pctDesc, @monUsd, @monNio,
                @notas, true
            );";

        await using var cmd = new NpgsqlCommand(sql, conn);
        cmd.Parameters.AddWithValue("prov", input.ProveedorId);
        cmd.Parameters.AddWithValue("cod", codigo);
        cmd.Parameters.AddWithValue("nom", input.NombreProducto.Trim());
        cmd.Parameters.AddWithValue("um", string.IsNullOrWhiteSpace(input.UnidadMedida) ? "qq (100 lbs)" : input.UnidadMedida.Trim());
        cmd.Parameters.AddWithValue("pusd", pUsd);
        cmd.Parameters.AddWithValue("pnio", pNio);
        cmd.Parameters.AddWithValue("apDesc", input.AplicaDescuento);
        cmd.Parameters.AddWithValue("minUds", input.UnidadesMinimasDescuento <= 0 ? 1 : input.UnidadesMinimasDescuento);
        cmd.Parameters.AddWithValue("tipoDesc", string.IsNullOrWhiteSpace(input.TipoDescuento) ? "porcentaje" : input.TipoDescuento.ToLowerInvariant());
        cmd.Parameters.AddWithValue("pctDesc", input.DescuentoPorcentaje);
        cmd.Parameters.AddWithValue("monUsd", input.DescuentoMonetarioUsd);
        cmd.Parameters.AddWithValue("monNio", input.DescuentoMonetarioNio > 0 ? input.DescuentoMonetarioNio : Math.Round(input.DescuentoMonetarioUsd * tc, 2));
        cmd.Parameters.AddWithValue("notas", string.IsNullOrWhiteSpace(input.NotasPromocion) ? (object)DBNull.Value : input.NotasPromocion.Trim());

        var ok = await cmd.ExecuteNonQueryAsync() > 0;
        if (ok) InvalidarCachesOperativos();
        return (ok, ok ? $"Producto '{input.NombreProducto}' agregado al catálogo con éxito." : "Error al registrar el producto.");
    }

    public async Task<(bool Exito, string Mensaje, Guid? OrdenId)> CrearOrdenCompraAsync(CrearOrdenCompraInput input)
    {
        if (input.ProveedorId == Guid.Empty)
            return (false, "Debes seleccionar un proveedor.", null);

        if (input.Lineas == null || !input.Lineas.Any(l => l.Cantidad > 0))
            return (false, "Debes incluir al menos un producto con cantidad mayor a cero.", null);

        await EnsureBaseEntitiesAsync();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        const decimal tasaCambio = 36.80m;
        var granjaId = Guid.Parse("a0000000-0000-0000-0000-000000000001");

        // Generar número correlativo de orden de compra
        string numOrden;
        await using (var cmdNum = new NpgsqlCommand("SELECT COUNT(*) + 1 FROM ordenes_compra;", conn))
        {
            var count = Convert.ToInt64(await cmdNum.ExecuteScalarAsync());
            numOrden = $"OC-{DateTime.Today.Year}-{count:D4}";
        }

        // Consultar productos del catálogo para recalcular precios y reglas de descuento en servidor
        var prodIds = input.Lineas.Where(l => l.Cantidad > 0).Select(l => l.ProductoCatalogoId).Distinct().ToList();
        var productosDb = new Dictionary<Guid, (string Nombre, string Unidad, decimal PrecioUsd, decimal PrecioNio, bool AplicaDesc, int MinUds, string TipoDesc, decimal PctDesc, decimal MonUsd, decimal MonNio, Guid? InsumoId)>();

        string sqlProd = @"
            SELECT id, nombre_producto, unidad_medida, precio_unitario_usd, precio_unitario_nio,
                   aplica_descuento, unidades_minimas_descuento, tipo_descuento, descuento_porcentaje,
                   descuento_monetario_usd, descuento_monetario_nio, insumo_id
            FROM catalogo_proveedores_productos
            WHERE id = ANY(@ids);";

        await using (var cmdP = new NpgsqlCommand(sqlProd, conn))
        {
            cmdP.Parameters.AddWithValue("ids", prodIds.ToArray());
            await using var rP = await cmdP.ExecuteReaderAsync();
            while (await rP.ReadAsync())
            {
                productosDb[rP.GetGuid(0)] = (
                    rP.GetString(1),
                    rP.GetString(2),
                    rP.GetDecimal(3),
                    rP.GetDecimal(4),
                    rP.GetBoolean(5),
                    rP.GetInt32(6),
                    rP.GetString(7),
                    rP.GetDecimal(8),
                    rP.GetDecimal(9),
                    rP.GetDecimal(10),
                    rP.IsDBNull(11) ? null : rP.GetGuid(11)
                );
            }
        }

        decimal totalSubUsd = 0, totalDescUsd = 0, totalNetoUsd = 0;
        decimal totalSubNio = 0, totalDescNio = 0, totalNetoNio = 0;

        var lineasProcesadas = new List<(
            Guid ProductoId, string Descripcion, decimal Cantidad, string Unidad,
            decimal PrecioUsd, decimal PrecioNio, bool AplicoDesc, string TipoDesc,
            decimal DtoUnitarioUsd, decimal DtoLineaUsd, decimal SubLineaUsd, decimal TotalLineaUsd,
            decimal DtoLineaNio, decimal SubLineaNio, decimal TotalLineaNio, Guid? InsumoId
        )>();

        foreach (var l in input.Lineas.Where(l => l.Cantidad > 0))
        {
            if (!productosDb.TryGetValue(l.ProductoCatalogoId, out var p))
                continue;

            decimal cantidad = l.Cantidad;
            decimal subLineaUsd = Math.Round(cantidad * p.PrecioUsd, 2);
            decimal subLineaNio = Math.Round(cantidad * p.PrecioNio, 2);

            bool aplico = false;
            string tipo = "ninguno";
            decimal dtoUnitUsd = 0;
            decimal dtoUnitNio = 0;

            // Regla de descuento por volumen activada si cantidad >= unidades_minimas
            if (p.AplicaDesc && cantidad >= p.MinUds)
            {
                aplico = true;
                tipo = p.TipoDesc;
                if (p.TipoDesc.Equals("porcentaje", StringComparison.OrdinalIgnoreCase))
                {
                    dtoUnitUsd = Math.Round(p.PrecioUsd * (p.PctDesc / 100m), 2);
                    dtoUnitNio = Math.Round(p.PrecioNio * (p.PctDesc / 100m), 2);
                }
                else // monetario
                {
                    dtoUnitUsd = p.MonUsd > 0 ? p.MonUsd : Math.Round(p.MonNio / tasaCambio, 2);
                    dtoUnitNio = p.MonNio > 0 ? p.MonNio : Math.Round(p.MonUsd * tasaCambio, 2);
                }
            }

            decimal dtoLineaUsd = Math.Round(cantidad * dtoUnitUsd, 2);
            decimal totalLineaUsd = Math.Max(0, subLineaUsd - dtoLineaUsd);

            decimal dtoLineaNio = Math.Round(cantidad * dtoUnitNio, 2);
            decimal totalLineaNio = Math.Max(0, subLineaNio - dtoLineaNio);

            totalSubUsd += subLineaUsd;
            totalDescUsd += dtoLineaUsd;
            totalNetoUsd += totalLineaUsd;

            totalSubNio += subLineaNio;
            totalDescNio += dtoLineaNio;
            totalNetoNio += totalLineaNio;

            lineasProcesadas.Add((
                l.ProductoCatalogoId, p.Nombre, cantidad, p.Unidad,
                p.PrecioUsd, p.PrecioNio, aplico, tipo,
                dtoUnitUsd, dtoLineaUsd, subLineaUsd, totalLineaUsd,
                dtoLineaNio, subLineaNio, totalLineaNio, p.InsumoId
            ));
        }

        if (!lineasProcesadas.Any())
            return (false, "No se encontraron los productos especificados en el catálogo.", null);

        await using var tx = await conn.BeginTransactionAsync();
        try
        {
            var ordenId = Guid.NewGuid();
            string sqlInsertOrden = @"
                INSERT INTO ordenes_compra (
                    id, granja_id, numero_orden, proveedor_id, fecha_emision, fecha_entrega_esperada,
                    moneda, tasa_cambio, estado, subtotal_usd, descuento_usd, total_usd,
                    subtotal_nio, descuento_nio, total_nio, solicitado_por_id, observaciones
                ) VALUES (
                    @id, @granja, @num, @prov, CURRENT_DATE, @fechaEnt,
                    @moneda, @tc, 'emitida', @subUsd, @descUsd, @totUsd,
                    @subNio, @descNio, @totNio, @solicId, @obs
                );";

            await using (var cmdO = new NpgsqlCommand(sqlInsertOrden, conn, tx))
            {
                cmdO.Parameters.AddWithValue("id", ordenId);
                cmdO.Parameters.AddWithValue("granja", granjaId);
                cmdO.Parameters.AddWithValue("num", numOrden);
                cmdO.Parameters.AddWithValue("prov", input.ProveedorId);
                cmdO.Parameters.AddWithValue("fechaEnt", (object?)input.FechaEntregaEsperada ?? DBNull.Value);
                cmdO.Parameters.AddWithValue("moneda", string.IsNullOrWhiteSpace(input.Moneda) ? "USD" : input.Moneda);
                cmdO.Parameters.AddWithValue("tc", tasaCambio);
                cmdO.Parameters.AddWithValue("subUsd", totalSubUsd);
                cmdO.Parameters.AddWithValue("descUsd", totalDescUsd);
                cmdO.Parameters.AddWithValue("totUsd", totalNetoUsd);
                cmdO.Parameters.AddWithValue("subNio", totalSubNio);
                cmdO.Parameters.AddWithValue("descNio", totalDescNio);
                cmdO.Parameters.AddWithValue("totNio", totalNetoNio);
                cmdO.Parameters.AddWithValue("solicId", (object?)CurrentUserIdDb ?? DBNull.Value);
                cmdO.Parameters.AddWithValue("obs", string.IsNullOrWhiteSpace(input.Observaciones) ? (object)DBNull.Value : input.Observaciones.Trim());
                await cmdO.ExecuteNonQueryAsync();
            }

            // Insertar líneas
            foreach (var lp in lineasProcesadas)
            {
                string sqlDet = @"
                    INSERT INTO ordenes_compra_detalles (
                        orden_compra_id, catalogo_producto_id, insumo_id, descripcion, cantidad, unidad_medida,
                        precio_unitario_usd, precio_unitario_nio, aplico_descuento, tipo_descuento,
                        monto_descuento_unitario_usd, monto_descuento_linea_usd, subtotal_linea_usd, total_linea_usd,
                        monto_descuento_linea_nio, total_linea_nio
                    ) VALUES (
                        @ordId, @prodId, @insId, @desc, @cant, @um,
                        @puUsd, @puNio, @apDesc, @tipoDesc,
                        @dtoUnitUsd, @dtoLinUsd, @subUsd, @totUsd,
                        @dtoLinNio, @totNio
                    );";

                await using var cmdD = new NpgsqlCommand(sqlDet, conn, tx);
                cmdD.Parameters.AddWithValue("ordId", ordenId);
                cmdD.Parameters.AddWithValue("prodId", lp.ProductoId);
                cmdD.Parameters.AddWithValue("insId", (object?)lp.InsumoId ?? DBNull.Value);
                cmdD.Parameters.AddWithValue("desc", lp.Descripcion);
                cmdD.Parameters.AddWithValue("cant", lp.Cantidad);
                cmdD.Parameters.AddWithValue("um", lp.Unidad);
                cmdD.Parameters.AddWithValue("puUsd", lp.PrecioUsd);
                cmdD.Parameters.AddWithValue("puNio", lp.PrecioNio);
                cmdD.Parameters.AddWithValue("apDesc", lp.AplicoDesc);
                cmdD.Parameters.AddWithValue("tipoDesc", lp.TipoDesc);
                cmdD.Parameters.AddWithValue("dtoUnitUsd", lp.DtoUnitarioUsd);
                cmdD.Parameters.AddWithValue("dtoLinUsd", lp.DtoLineaUsd);
                cmdD.Parameters.AddWithValue("subUsd", lp.SubLineaUsd);
                cmdD.Parameters.AddWithValue("totUsd", lp.TotalLineaUsd);
                cmdD.Parameters.AddWithValue("dtoLinNio", lp.DtoLineaNio);
                cmdD.Parameters.AddWithValue("totNio", lp.TotalLineaNio);
                await cmdD.ExecuteNonQueryAsync();
            }

            await tx.CommitAsync();
            InvalidarCachesOperativos();

            string mensaje = totalDescUsd > 0 
                ? $"Orden {numOrden} emitida con éxito. Total: ${totalNetoUsd:N2} USD (C$ {totalNetoNio:N2} NIO), ¡con descuento por volumen aplicado de ${totalDescUsd:N2} USD (C$ {totalDescNio:N2} NIO)!"
                : $"Orden {numOrden} emitida con éxito. Total: ${totalNetoUsd:N2} USD (C$ {totalNetoNio:N2} NIO).";

            return (true, mensaje, ordenId);
        }
        catch (Exception ex)
        {
            await tx.RollbackAsync();
            _logger.LogError(ex, "Error al crear orden de compra");
            return (false, "Error interno al generar la orden de compra: " + ex.Message, null);
        }
    }

    public async Task<(bool Exito, string Mensaje)> RecibirOrdenCompraEnKardexAsync(
        Guid ordenId, string recibidoPor, string? loteFabricante = null, DateTime? fechaVencimiento = null, string? bodegaDestino = null)
    {
        await EnsureBaseEntitiesAsync();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        await using var tx = await conn.BeginTransactionAsync();
        try
        {
            string sqlOrden = @"
                SELECT o.numero_orden, o.estado, o.granja_id, COALESCE(p.nombre_comercial, 'Proveedor Homologado')
                FROM ordenes_compra o
                LEFT JOIN proveedores p ON p.id = o.proveedor_id
                WHERE o.id = @id FOR UPDATE OF o;";
            string numOrden;
            string estadoActual;
            Guid granjaId;
            string proveedorNombre;
            await using (var cmdO = new NpgsqlCommand(sqlOrden, conn, tx))
            {
                cmdO.Parameters.AddWithValue("id", ordenId);
                await using var rO = await cmdO.ExecuteReaderAsync();
                if (!await rO.ReadAsync())
                    return (false, "Orden de compra no encontrada.");
                numOrden = rO.GetString(0);
                estadoActual = rO.GetString(1);
                granjaId = rO.GetGuid(2);
                proveedorNombre = rO.GetString(3);
            }

            if (estadoActual == "recibida_kardex")
                return (false, $"La orden {numOrden} ya fue recibida en inventario previamente.");

            // Resolver silo de destino si coincide con bodegaDestino
            Guid? siloId = null;
            if (!string.IsNullOrWhiteSpace(bodegaDestino))
            {
                await using var cmdSilo = new NpgsqlCommand("SELECT id FROM silos_depositos WHERE nombre ILIKE @nom OR codigo ILIKE @nom LIMIT 1;", conn, tx);
                cmdSilo.Parameters.AddWithValue("nom", $"%{bodegaDestino.Trim()}%");
                var resSilo = await cmdSilo.ExecuteScalarAsync();
                if (resSilo != null) siloId = (Guid)resSilo;
            }

            // Leer las líneas de la orden para ingresarlas al Kardex
            var detalles = new List<(Guid DetalleId, Guid? InsumoId, string CodigoProd, string Descripcion, decimal Cantidad, string Unidad, decimal CostoUnitarioUsd)>();
            await using (var cmdDet = new NpgsqlCommand(@"
                SELECT d.id, d.insumo_id, COALESCE(cp.codigo_producto, 'INS-' || substr(d.id::text, 1, 6)),
                       d.descripcion, d.cantidad, d.unidad_medida,
                       CASE WHEN d.cantidad > 0 THEN ROUND(d.total_linea_usd / d.cantidad, 4) ELSE d.precio_unitario_usd END
                FROM ordenes_compra_detalles d
                LEFT JOIN catalogo_proveedores_productos cp ON cp.id = d.catalogo_producto_id
                WHERE d.orden_compra_id = @id;", conn, tx))
            {
                cmdDet.Parameters.AddWithValue("id", ordenId);
                await using var rD = await cmdDet.ExecuteReaderAsync();
                while (await rD.ReadAsync())
                {
                    detalles.Add((
                        rD.GetGuid(0),
                        rD.IsDBNull(1) ? null : rD.GetGuid(1),
                        rD.GetString(2),
                        rD.GetString(3),
                        rD.GetDecimal(4),
                        rD.GetString(5),
                        rD.GetDecimal(6)
                    ));
                }
            }

            foreach (var det in detalles)
            {
                Guid? insumoId = det.InsumoId;
                if (!insumoId.HasValue)
                {
                    await using var cmdFindIns = new NpgsqlCommand(@"
                        SELECT id FROM insumos_catalogo
                        WHERE codigo_sku = @sku OR lower(nombre) = lower(@nom)
                        LIMIT 1;", conn, tx);
                    cmdFindIns.Parameters.AddWithValue("sku", det.CodigoProd);
                    cmdFindIns.Parameters.AddWithValue("nom", det.Descripcion);
                    var found = await cmdFindIns.ExecuteScalarAsync();
                    if (found != null)
                    {
                        insumoId = (Guid)found;
                    }
                    else
                    {
                        var codCat = det.CodigoProd.StartsWith("MED", StringComparison.OrdinalIgnoreCase) ? "MED" : "ALIM";
                        Guid catId;
                        await using (var cmdCat = new NpgsqlCommand("SELECT id FROM categorias_insumos WHERE codigo = @c LIMIT 1;", conn, tx))
                        {
                            cmdCat.Parameters.AddWithValue("c", codCat);
                            catId = (Guid)(await cmdCat.ExecuteScalarAsync())!;
                        }
                        insumoId = Guid.NewGuid();
                        await using var cmdNewIns = new NpgsqlCommand(@"
                            INSERT INTO insumos_catalogo (id, granja_id, categoria_id, codigo_sku, nombre, unidad_medida, stock_actual, stock_minimo_seguridad, costo_unitario_promedio_usd, silo_deposito_id, activo)
                            VALUES (@id, @granja, @cat, @sku, @nom, @um, 0, 10, @costo, @silo, true);", conn, tx);
                        cmdNewIns.Parameters.AddWithValue("id", insumoId.Value);
                        cmdNewIns.Parameters.AddWithValue("granja", granjaId);
                        cmdNewIns.Parameters.AddWithValue("cat", catId);
                        cmdNewIns.Parameters.AddWithValue("sku", det.CodigoProd);
                        cmdNewIns.Parameters.AddWithValue("nom", det.Descripcion);
                        cmdNewIns.Parameters.AddWithValue("um", det.Unidad);
                        cmdNewIns.Parameters.AddWithValue("costo", det.CostoUnitarioUsd);
                        cmdNewIns.Parameters.Add(new NpgsqlParameter("silo", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)siloId ?? DBNull.Value });
                        await cmdNewIns.ExecuteNonQueryAsync();
                    }
                }

                await using var cmdKardex = new NpgsqlCommand(@"
                    INSERT INTO movimientos_kardex (granja_id, insumo_id, silo_id, tipo_movimiento, fecha_hora, cantidad, costo_unitario_usd, documento_referencia, proveedor, responsable_id)
                    VALUES (@granja, @insumo, @silo, 'entrada_compra', NOW(), @cant, @costo, @doc, @prov, @uid);", conn, tx);
                cmdKardex.Parameters.AddWithValue("granja", granjaId);
                cmdKardex.Parameters.AddWithValue("insumo", insumoId.Value);
                cmdKardex.Parameters.Add(new NpgsqlParameter("silo", NpgsqlTypes.NpgsqlDbType.Uuid) { Value = (object?)siloId ?? DBNull.Value });
                cmdKardex.Parameters.AddWithValue("cant", det.Cantidad);
                cmdKardex.Parameters.AddWithValue("costo", det.CostoUnitarioUsd);
                cmdKardex.Parameters.AddWithValue("doc", $"OC {numOrden}" + (string.IsNullOrWhiteSpace(loteFabricante) ? "" : $" · Lote {loteFabricante.Trim()}"));
                cmdKardex.Parameters.AddWithValue("prov", proveedorNombre);
                cmdKardex.Parameters.AddWithValue("uid", CurrentUserIdDb);
                await cmdKardex.ExecuteNonQueryAsync();
            }

            // Actualizar estado a recibida_kardex con lote, fecha de vencimiento y bodega
            string sqlUpd = @"
                UPDATE ordenes_compra 
                SET estado = 'recibida_kardex',
                    recibido_por = @recibido,
                    fecha_recepcion = NOW(),
                    lote_fabricante = @loteFab,
                    fecha_vencimiento = @venc,
                    bodega_destino = @bodega
                WHERE id = @id;";

            await using (var cmdUpd = new NpgsqlCommand(sqlUpd, conn, tx))
            {
                cmdUpd.Parameters.AddWithValue("id", ordenId);
                cmdUpd.Parameters.AddWithValue("recibido", (object?)recibidoPor ?? DBNull.Value);
                cmdUpd.Parameters.AddWithValue("loteFab", string.IsNullOrWhiteSpace(loteFabricante) ? (object)DBNull.Value : loteFabricante.Trim());
                cmdUpd.Parameters.AddWithValue("venc", fechaVencimiento.HasValue ? (object)fechaVencimiento.Value : DBNull.Value);
                cmdUpd.Parameters.AddWithValue("bodega", string.IsNullOrWhiteSpace(bodegaDestino) ? (object)DBNull.Value : bodegaDestino.Trim());
                await cmdUpd.ExecuteNonQueryAsync();
            }

            await tx.CommitAsync();
            InvalidarCachesOperativos();
            return (true, $"Orden {numOrden} recibida e incorporada al inventario satisfactoriamente ({detalles.Count} renglones ingresados al Kardex).");
        }
        catch (Exception ex)
        {
            await tx.RollbackAsync();
            _logger.LogError(ex, "Error al recibir orden de compra");
            return (false, "No se pudo recibir la orden en Kardex.");
        }
    }

    // =========================================================================
    // GALPONES Y JAULAS (6 JAULAS POR GALPÓN, 5x3m = 15m², 15-20 LECHONES / 5-6 ADULTOS)
    // =========================================================================
    public async Task<List<GalponTecnicoItem>> GetGalponesConJaulasAsync()
    {
        await EnsureBaseEntitiesAsync();
        var lista = new List<GalponTecnicoItem>();
        await using var conn = CreateConnection();
        await conn.OpenAsync();

        // 1. Obtener Galpones
        string sqlGalp = "SELECT id, codigo, nombre, COALESCE(capacidad_cabezas, 100) FROM galpones_naves WHERE activo = true ORDER BY codigo;";
        await using (var cmdG = new NpgsqlCommand(sqlGalp, conn))
        await using (var rG = await cmdG.ExecuteReaderAsync())
        {
            while (await rG.ReadAsync())
            {
                lista.Add(new GalponTecnicoItem
                {
                    Id = rG.GetGuid(0),
                    Codigo = rG.GetString(1),
                    Nombre = rG.GetString(2),
                    CapacidadMaxima = rG.GetInt32(3)
                });
            }
        }

        // 2. Obtener Jaulas de cada Galpón
        string sqlJ = @"
            SELECT 
                j.id, j.galpon_id, j.codigo, j.nombre, j.etapa_zootecnica,
                COALESCE(j.largo_fondo_metros, 5.0),
                COALESCE(j.ancho_metros, 3.0),
                COALESCE(j.area_m2, 15.0),
                COALESCE(j.capacidad_lechones, 20),
                COALESCE(j.capacidad_adultos, 6),
                COALESCE(j.numero_jaula, 1),
                j.capacidad_maxima,
                COALESCE(sub.total_alojados, 0) as ocupacion_real,
                j.tipo_instalacion
            FROM jaulas_corrales j
            LEFT JOIN (
                SELECT jaula_corral_id, COUNT(*) as total_alojados
                FROM cerdos_individuales
                WHERE activo = true AND jaula_corral_id IS NOT NULL
                GROUP BY jaula_corral_id
            ) sub ON sub.jaula_corral_id = j.id
            WHERE j.activo = true
            ORDER BY j.numero_jaula, j.codigo;";

        await using (var cmdJ = new NpgsqlCommand(sqlJ, conn))
        await using (var rJ = await cmdJ.ExecuteReaderAsync())
        {
            while (await rJ.ReadAsync())
            {
                var galpId = rJ.IsDBNull(1) ? (Guid?)null : rJ.GetGuid(1);
                var g = lista.FirstOrDefault(gal => gal.Id == galpId);
                if (g != null)
                {
                    int capMax = rJ.GetInt32(11);
                    int ocup = Convert.ToInt32(rJ.GetInt64(12));
                    int cupo = Math.Max(0, capMax - ocup);
                    decimal pct = capMax > 0 ? Math.Round(((decimal)ocup / capMax) * 100m, 1) : 0m;

                    g.Jaulas.Add(new JaulaTecnicaItem
                    {
                        Id = rJ.GetGuid(0),
                        Codigo = rJ.GetString(2),
                        Nombre = rJ.GetString(3),
                        EtapaZootecnica = rJ.GetString(4),
                        LargoFondoMetros = rJ.GetDecimal(5),
                        AnchoMetros = rJ.GetDecimal(6),
                        AreaM2 = rJ.GetDecimal(7),
                        CapacidadLechones = rJ.GetInt32(8),
                        CapacidadAdultos = rJ.GetInt32(9),
                        NumeroJaula = rJ.GetInt32(10),
                        CapacidadMaxima = capMax,
                        OcupacionActual = ocup,
                        CupoDisponible = cupo,
                        PorcentajeOcupacion = pct,
                        TipoInstalacion = rJ.IsDBNull(13) ? "Jaula técnica 5x3m" : rJ.GetString(13)
                    });
                }
            }
        }

        return lista;
    }
}

// =========================================================================
// MODELOS DE VISTA FUERTEMENTE TIPADOS VINCULADOS A POSTGRESQL
// =========================================================================
public class SiloDetalleItem
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string Nombre { get; set; } = "";
    public decimal CapacidadMaximaKg { get; set; }
    public decimal NivelActualKg { get; set; }
    public decimal PorcentajeOcupacion => CapacidadMaximaKg > 0 ? Math.Round((NivelActualKg / CapacidadMaximaKg) * 100m, 1) : 0;
    public string InsumoPrincipal { get; set; } = "";
}

public class DashboardBiViewModel
{
    // Métricas compartidas / Admin
    public decimal CostoKgProducido { get; set; } = 0.00m;
    public decimal VariacionCostoTarget { get; set; } = 0.00m;
    public int GmdPromedio { get; set; } = 0;
    public int LotesActivosCount { get; set; } = 0;
    public decimal TasaFertilidad { get; set; } = 0.0m;
    public int ServiciosMes { get; set; } = 0;
    public int ConfirmadasEco { get; set; } = 0;
    public decimal PromedioNacidosVivos { get; set; } = 0.0m;
    public decimal ToneladasSilo { get; set; } = 0.00m;
    public int PorcentajeCapacidadSilos { get; set; } = 0;
    public List<LoteItemBi> Lotes { get; set; } = new();
    public List<AlertaBiItem> Alertas { get; set; } = new();

    // Métricas específicas de rol (Veterinario y Operario)
    public int TareasPendientesHoy { get; set; } = 0;
    public int TareasCompletadasHoy { get; set; } = 0;
    public decimal AlimentoSuministradoHoyKg { get; set; } = 0.00m;
    public int JaulasOcupadas { get; set; } = 0;
    public int JaulasTotales { get; set; } = 0;
    public int TratamientosAplicadosMes { get; set; } = 0;
    public decimal DosisMedicamentosMlMes { get; set; } = 0.00m;
    public int AnimalesBajoTratamiento { get; set; } = 0;
    public List<SiloDetalleItem> Silos { get; set; } = new();
    public List<TareaActividadItem> TareasHoy { get; set; } = new();
    public List<PartoInminenteItem> RadarPartos { get; set; } = new();

    // Métricas y Gráficos de Barras Financieros (Solo Admin)
    public decimal TotalIngresosVentasUsd { get; set; } = 0.00m;
    public decimal TotalGastosOperativosUsd { get; set; } = 0.00m;
    public decimal UtilidadNetaUsd => TotalIngresosVentasUsd - TotalGastosOperativosUsd;
    public decimal MargenOperativoPct => TotalIngresosVentasUsd > 0 ? Math.Round((UtilidadNetaUsd / TotalIngresosVentasUsd) * 100m, 1) : 0m;
    public List<BarraCentroCostoItem> BarrasCentroCosto { get; set; } = new();
    public List<BarraMesFlujoItem> BarrasMensuales { get; set; } = new();
}

public class LoteItemBi
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string Etapa { get; set; } = "";
    public int Cabezas { get; set; }
    public decimal PesoPromedioKg { get; set; }
    public decimal Gmd { get; set; }
    public string Galpon { get; set; } = "";
    public decimal Fcr { get; set; }
}

public class AlertaBiItem
{
    public string Tipo { get; set; } = "";
    public string Severidad { get; set; } = "";
    public string Descripcion { get; set; } = "";
    public string ValorDetectado { get; set; } = "";
    public DateTime Fecha { get; set; }
}

public class PresupuestosViewModel
{
    public decimal TotalEstimado { get; set; }
    public decimal TotalEjecutado { get; set; }
    public decimal DesviacionNetaGlobal { get; set; }
    public List<PresupuestoRubroItem> Presupuestos { get; set; } = new();
    public List<LoteSelectorItem> ListaLotes { get; set; } = new();
    public List<CentroCostoSelectorItem> ListaCentrosCosto { get; set; } = new();
    public List<GastoOperativoItem> GastosRecientes { get; set; } = new();
    public List<InsumoDbItem> InsumosAlimentos { get; set; } = new();
    public List<InsumoDbItem> InsumosMedicamentos { get; set; } = new();
    public List<TratamientoAuditoriaItem> TratamientosAuditoria { get; set; } = new();

    // Métricas Financieras y Diagramas de Barras
    public decimal TotalIngresosVentasUsd { get; set; } = 0.00m;
    public decimal TotalGastosOperativosUsd { get; set; } = 0.00m;
    public decimal UtilidadNetaUsd => TotalIngresosVentasUsd - TotalGastosOperativosUsd;
    public decimal MargenOperativoPct => TotalIngresosVentasUsd > 0 ? Math.Round((UtilidadNetaUsd / TotalIngresosVentasUsd) * 100m, 1) : 0m;
    public bool TieneDatosTransaccionales { get; set; } = false;
    public List<BarraCentroCostoItem> BarrasCentroCosto { get; set; } = new();
    public List<BarraMesFlujoItem> BarrasMensuales { get; set; } = new();
}

public class BarraCentroCostoItem
{
    public string Codigo { get; set; } = "";
    public string Nombre { get; set; } = "";
    public decimal PresupuestadoUsd { get; set; }
    public decimal EjecutadoUsd { get; set; }
    public decimal DesviacionPct { get; set; }
    public string ColorHex { get; set; } = "#566342";
}

public class BarraMesFlujoItem
{
    public string MesNombre { get; set; } = "";
    public int MesNumero { get; set; }
    public int Anio { get; set; }
    public decimal IngresosUsd { get; set; }
    public decimal GastosUsd { get; set; }
    public decimal UtilidadUsd => IngresosUsd - GastosUsd;
    public decimal MargenPct => IngresosUsd > 0 ? Math.Round((UtilidadUsd / IngresosUsd) * 100m, 1) : 0m;
}

public class TratamientoAuditoriaItem
{
    public Guid Id { get; set; }
    public DateTime FechaHora { get; set; }
    public string CrotalCerda { get; set; } = "";
    public string GalponJaula { get; set; } = "";
    public string Medicamento { get; set; } = "";
    public decimal DosisAplicada { get; set; }
    public string UnidadMedida { get; set; } = "";
    public decimal FrascoInicialMl { get; set; }
    public decimal FrascoRestanteMl { get; set; }
    public string InsumosKitUsados { get; set; } = "";
    public string ViaAdministracion { get; set; } = "";
    public string Diagnostico { get; set; } = "";
    public string VeterinarioResponsable { get; set; } = "";
    public string Matricula { get; set; } = "";
    public decimal CostoTotalUsd { get; set; }
}

public class PresupuestoRubroItem
{
    public Guid Id { get; set; }
    public string Rubro { get; set; } = "";
    public string LoteCodigo { get; set; } = "";
    public decimal MontoEstimado { get; set; }
    public decimal MontoEjecutado { get; set; }
    public decimal PorcentajeDesviacion { get; set; }
    public string EstadoBi { get; set; } = "";
    public string CentroCosto { get; set; } = "";
    public Guid LoteId { get; set; }
    public Guid CentroCostoId { get; set; }
}

public class GastoOperativoItem
{
    public Guid Id { get; set; }
    public Guid LoteId { get; set; }
    public string LoteCodigo { get; set; } = "";
    public string CentroCosto { get; set; } = "";
    public string Concepto { get; set; } = "";
    public string Proveedor { get; set; } = "";
    public decimal MontoUsd { get; set; }
    public DateTime Fecha { get; set; }
    public string TipoMovimiento { get; set; } = "";
    public string RegistradoPor { get; set; } = "";
}

public class LoteSelectorItem
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string Etapa { get; set; } = "";
    public int Cabezas { get; set; }
    public decimal PesoPromedioKg { get; set; }
    public decimal PesoPromedioLbs => Math.Round(PesoPromedioKg * 2.20462m, 1);
    public string Galpon { get; set; } = "";
    public DateTime FechaInicio { get; set; }
    public DateTime? FechaCierreEstimada { get; set; }
}

public class CentroCostoSelectorItem
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string Nombre { get; set; } = "";
}

public class LotesPageViewModel
{
    public decimal ToneladasAlimentoSilo { get; set; }
    public List<GalponItem> Galpones { get; set; } = new();
    public List<LineaGeneticaItem> LineasGeneticas { get; set; } = new();
    public List<LoteCoexistenteItem> LotesCoexistentes { get; set; } = new();
    public List<LoteSelectorItem> LotesDisponibles { get; set; } = new();
    public List<GalponTecnicoItem> GalponesTecnicos { get; set; } = new();
    public ProyeccionLoteFechaTopeResult ProyeccionDefecto { get; set; } = new();
}

public class ProyeccionLoteFechaTopeResult
{
    public Guid? LoteId { get; set; }
    public string CodigoLote { get; set; } = "LOT-ENG-2026-01";
    public string Etapa { get; set; } = "engorde_intensivo";
    public int Cabezas { get; set; } = 150;
    public DateTime FechaInicio { get; set; } = DateTime.Today.AddDays(-30);
    public DateTime FechaTope { get; set; } = DateTime.Today.AddDays(60);
    public int DiasRestantes { get; set; } = 60;

    // Pesos (Libras principales, Kg referencia)
    public decimal PesoActualLbs { get; set; } = 95.0m;
    public decimal PesoActualKg => Math.Round(PesoActualLbs / 2.20462m, 1);
    public decimal GmdLbsDia { get; set; } = 1.80m;
    public decimal PesoGanadoPorCerdoLbs => Math.Round(DiasRestantes * GmdLbsDia, 1);
    public decimal PesoFinalProyectadoLbs => Math.Round(PesoActualLbs + PesoGanadoPorCerdoLbs, 1);
    public decimal PesoFinalProyectadoKg => Math.Round(PesoFinalProyectadoLbs / 2.20462m, 1);
    public decimal PesoTotalLoteLbs => Math.Round(Cabezas * PesoFinalProyectadoLbs, 1);
    public decimal PesoTotalLoteQuintales => Math.Round(PesoTotalLoteLbs / 100.0m, 2);

    // Alimento (Libras y Quintales qq = 100 lbs)
    public decimal ConsumoDiarioCerdoLbs { get; set; } = 5.80m;
    public decimal ConsumoDiarioTomaLbs => Math.Round(ConsumoDiarioCerdoLbs / 2.0m, 2);
    public decimal ConsumoDiarioLoteLbs => Math.Round(Cabezas * ConsumoDiarioCerdoLbs, 1);
    public decimal ConsumoDiarioLoteQuintales => Math.Round(ConsumoDiarioLoteLbs / 100.0m, 2);
    public decimal TotalAlimentoRequeridoLbs => Math.Round(Cabezas * DiasRestantes * ConsumoDiarioCerdoLbs, 1);
    public decimal TotalAlimentoRequeridoQuintales => Math.Round(TotalAlimentoRequeridoLbs / 100.0m, 2);

    // Parámetros económicos bimonetarios (Córdobas C$ / Dólares USD)
    public decimal TipoCambioNioUsd { get; set; } = 36.80m;
    public decimal PrecioAlimentoQuintalNio { get; set; } = 1400.00m; // C$ 1,400.00 / quintal (100 lbs) = C$ 14.00 / lb
    public decimal PrecioVentaLibraPieNio { get; set; } = 45.00m; // C$ 45.00 / lb en pie
    public decimal CostoManoObraPorCerdoNio { get; set; } = 180.00m;
    public decimal CostoSanitarioPorCerdoNio { get; set; } = 120.00m;
    public decimal CostosIndirectosPorCerdoNio { get; set; } = 80.00m;

    // Desglose de Inversión (Costos)
    public decimal InversionAlimentoNio => Math.Round(TotalAlimentoRequeridoQuintales * PrecioAlimentoQuintalNio, 2);
    public decimal InversionAlimentoUsd => TipoCambioNioUsd > 0 ? Math.Round(InversionAlimentoNio / TipoCambioNioUsd, 2) : 0;

    public decimal InversionManoObraNio => Math.Round(Cabezas * CostoManoObraPorCerdoNio, 2);
    public decimal InversionManoObraUsd => TipoCambioNioUsd > 0 ? Math.Round(InversionManoObraNio / TipoCambioNioUsd, 2) : 0;

    public decimal InversionSanitariaNio => Math.Round(Cabezas * CostoSanitarioPorCerdoNio, 2);
    public decimal InversionSanitariaUsd => TipoCambioNioUsd > 0 ? Math.Round(InversionSanitariaNio / TipoCambioNioUsd, 2) : 0;

    public decimal InversionIndirectosNio => Math.Round(Cabezas * CostosIndirectosPorCerdoNio, 2);
    public decimal InversionIndirectosUsd => TipoCambioNioUsd > 0 ? Math.Round(InversionIndirectosNio / TipoCambioNioUsd, 2) : 0;

    public decimal InversionTotalNio => InversionAlimentoNio + InversionManoObraNio + InversionSanitariaNio + InversionIndirectosNio;
    public decimal InversionTotalUsd => TipoCambioNioUsd > 0 ? Math.Round(InversionTotalNio / TipoCambioNioUsd, 2) : 0;

    public decimal CostoPorCerdoNio => Cabezas > 0 ? Math.Round(InversionTotalNio / Cabezas, 2) : 0;
    public decimal CostoPorCerdoUsd => Cabezas > 0 ? Math.Round(InversionTotalUsd / Cabezas, 2) : 0;
    public decimal CostoPorLibraProducidaNio => PesoTotalLoteLbs > 0 ? Math.Round(InversionTotalNio / PesoTotalLoteLbs, 2) : 0;
    public decimal CostoPorLibraProducidaUsd => PesoTotalLoteLbs > 0 ? Math.Round(InversionTotalUsd / PesoTotalLoteLbs, 2) : 0;

    // Ingresos Proyectados (Venta en pie)
    public decimal IngresoBrutoTotalNio => Math.Round(PesoTotalLoteLbs * PrecioVentaLibraPieNio, 2);
    public decimal IngresoBrutoTotalUsd => TipoCambioNioUsd > 0 ? Math.Round(IngresoBrutoTotalNio / TipoCambioNioUsd, 2) : 0;
    public decimal IngresoPorCerdoNio => Cabezas > 0 ? Math.Round(IngresoBrutoTotalNio / Cabezas, 2) : 0;
    public decimal IngresoPorCerdoUsd => Cabezas > 0 ? Math.Round(IngresoBrutoTotalUsd / Cabezas, 2) : 0;

    // Ganancia Neta / Utilidad
    public decimal GananciaNetaTotalNio => IngresoBrutoTotalNio - InversionTotalNio;
    public decimal GananciaNetaTotalUsd => TipoCambioNioUsd > 0 ? Math.Round(GananciaNetaTotalNio / TipoCambioNioUsd, 2) : 0;
    public decimal GananciaNetaPorCerdoNio => Cabezas > 0 ? Math.Round(GananciaNetaTotalNio / Cabezas, 2) : 0;
    public decimal GananciaNetaPorCerdoUsd => Cabezas > 0 ? Math.Round(GananciaNetaTotalUsd / Cabezas, 2) : 0;

    // Ratios Financieros
    public decimal MargenUtilidadPct => IngresoBrutoTotalNio > 0 ? Math.Round((GananciaNetaTotalNio / IngresoBrutoTotalNio) * 100.0m, 1) : 0;
    public decimal RetornoInversionRoiPct => InversionTotalNio > 0 ? Math.Round((GananciaNetaTotalNio / InversionTotalNio) * 100.0m, 1) : 0;

    /// <summary>Anula todos los montos económicos cuando la proyección es consultada por un rol no administrativo.</summary>
    public void LimpiarDatosFinancieros()
    {
        TipoCambioNioUsd = 0m;
        PrecioAlimentoQuintalNio = 0m;
        PrecioVentaLibraPieNio = 0m;
        CostoManoObraPorCerdoNio = 0m;
        CostoSanitarioPorCerdoNio = 0m;
        CostosIndirectosPorCerdoNio = 0m;
    }
}

public class GalponItem
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string Nombre { get; set; } = "";
    public int Capacidad { get; set; }
    public decimal Temperatura { get; set; }
    public decimal Humedad { get; set; }
}

public class LineaGeneticaItem
{
    public Guid Id { get; set; }
    public string Nombre { get; set; } = "";
    public string Casa { get; set; } = "";
}

public class LoteCoexistenteItem
{
    public string Codigo { get; set; } = "";
    public string Etapa { get; set; } = "";
    public int Cabezas { get; set; }
    public decimal PesoPromedioKg { get; set; }
    public string Galpon { get; set; } = "";
    public int DiasCiclo { get; set; }
}

public class InventarioPageViewModel
{
    public decimal ValorTotalInventario { get; set; }
    public int ItemsCriticosCount { get; set; }
    public decimal ToneladasSilos { get; set; }
    public int PorcentajeOcupacionSilos { get; set; }
    public List<InsumoDbItem> Insumos { get; set; } = new();
    public List<KardexMovimientoItem> Movimientos { get; set; } = new();
    public List<CategoriaSelectorItem> CategoriasDisponibles { get; set; } = new();
    public List<SiloSelectorItem> SilosDisponibles { get; set; } = new();
}

public class CategoriaSelectorItem
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string Nombre { get; set; } = "";
}

public class SiloSelectorItem
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string Nombre { get; set; } = "";
}

public class InsumoDbItem
{
    public Guid Id { get; set; }
    public string Sku { get; set; } = "";
    public string Nombre { get; set; } = "";
    public string Categoria { get; set; } = "";
    public string CategoriaCodigo { get; set; } = "";
    public Guid CategoriaId { get; set; }
    public string Unidad { get; set; } = "";
    public decimal StockActual { get; set; }
    public decimal StockMinimo { get; set; }
    public decimal CostoUnitario { get; set; }
    public string Ubicacion { get; set; } = "";
    public Guid? SiloId { get; set; }
    public string Estado { get; set; } = "";
    public string Descripcion { get; set; } = "";
    public decimal ValorTotal => Math.Round(StockActual * CostoUnitario, 2);
    public bool EsCritico => StockActual <= StockMinimo || Estado == "reabastecer_urgente";
}

public class KardexMovimientoItem
{
    public string Tipo { get; set; } = "";
    public decimal Cantidad { get; set; }
    public decimal CostoUnitario { get; set; }
    public decimal ValorTotal { get; set; }
    public DateTime Fecha { get; set; }
    public string InsumoNombre { get; set; } = "";
    public string Documento { get; set; } = "";
    public string LoteCodigo { get; set; } = "";
}

public class ReproduccionPageViewModel
{
    public int TotalPlantelActivo { get; set; }
    public int CerdasGestantes { get; set; }
    public int CerdasLactantes { get; set; }
    public int CerdasVacias { get; set; }
    public int CerdasReemplazo { get; set; }
    public List<CerdaDbItem> Cerdas { get; set; } = new();
    public List<PartoInminenteItem> RadarPartos { get; set; } = new();
    public List<VerracoSelectorItem> Verracos { get; set; } = new();
    public List<PartoHistoricoItem> HistorialPartos { get; set; } = new();
    public List<LoteSelectorItem> LotesDestino { get; set; } = new();
}

public class PartoHistoricoItem
{
    public Guid Id { get; set; }
    public string Caravana { get; set; } = "";
    public string Jaula { get; set; } = "";
    public DateTime FechaHora { get; set; }
    public int TotalNacidos { get; set; }
    public int NacidosVivos { get; set; }
    public int NacidosMuertos { get; set; }
    public int Momias { get; set; }
    public decimal PesoTotalCamadaKg { get; set; }
    public decimal PesoPromedioLechonKg { get; set; }
    public decimal CondicionCorporal { get; set; }
    public bool AsistenciaObstetrica { get; set; }
    public string Observaciones { get; set; } = "";
    public string AtendidoPor { get; set; } = "";
}

public class CerdaDbItem
{
    public Guid Id { get; set; }
    public string Caravana { get; set; } = "";
    public string Rfid { get; set; } = "";
    public int Paridad { get; set; }
    public string Estado { get; set; } = "";
    public string EstatusSanitario { get; set; } = "";
    public decimal Prolificidad { get; set; }
    public string Genetica { get; set; } = "";
}

public class PartoInminenteItem
{
    public Guid CerdaId { get; set; }
    public string Caravana { get; set; } = "";
    public int Paridad { get; set; }
    public string Jaula { get; set; } = "";
    public int DiasGestacion { get; set; }
    public int DiasParaParto { get; set; }
    public decimal PctAvance { get; set; }
    public string AvisoTemporal { get; set; } = "";
    public string Verraco { get; set; } = "";
    public string EstatusSanitario { get; set; } = "";
}

public class VerracoSelectorItem
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string Nombre { get; set; } = "";
}

// Modelos DTO para sincronización de campo Outbox desde la App Móvil
public class SyncBatchRequest
{
    [System.Text.Json.Serialization.JsonPropertyName("deviceId")]
    public string DeviceId { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("clientTimestamp")]
    public long ClientTimestamp { get; set; }
    
    [System.Text.Json.Serialization.JsonPropertyName("tratamientos")]
    public List<SyncTratamientoDto> Tratamientos { get; set; } = new();
    
    [System.Text.Json.Serialization.JsonPropertyName("eventosReproductivos")]
    public List<SyncEventoReproductivoDto> EventosReproductivos { get; set; } = new();
    
    [System.Text.Json.Serialization.JsonPropertyName("consumosAlimento")]
    public List<SyncConsumoAlimentoDto> ConsumosAlimento { get; set; } = new();

    [System.Text.Json.Serialization.JsonPropertyName("bajasMortalidad")]
    public List<SyncBajaMortalidadDto> BajasMortalidad { get; set; } = new();

    [System.Text.Json.Serialization.JsonPropertyName("tareasEstado")]
    public List<SyncTareaEstadoDto> TareasEstado { get; set; } = new();

    [System.Text.Json.Serialization.JsonPropertyName("operacionesGasto")]
    public List<SyncOperacionGastoDto> OperacionesGasto { get; set; } = new();

    [System.Text.Json.Serialization.JsonPropertyName("cerdos")]
    public List<SyncCerdoDto> Cerdos { get; set; } = new();

    [System.Text.Json.Serialization.JsonPropertyName("tareasNuevas")]
    public List<SyncTareaNuevaDto> TareasNuevas { get; set; } = new();
}

public class SyncTareaNuevaDto
{
    [System.Text.Json.Serialization.JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("titulo")]
    public string Titulo { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("descripcion")]
    public string Descripcion { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("prioridad")]
    public string Prioridad { get; set; } = "Media";

    [System.Text.Json.Serialization.JsonPropertyName("tipoActividad")]
    public string TipoActividad { get; set; } = "Manejo";

    [System.Text.Json.Serialization.JsonPropertyName("galponEtapa")]
    public string GalponEtapa { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("loteCodigo")]
    public string? LoteCodigo { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("completada")]
    public bool Completada { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("horaProgramada")]
    public string HoraProgramada { get; set; } = "08:00";

    [System.Text.Json.Serialization.JsonPropertyName("asignadoA")]
    public string AsignadoA { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("observaciones")]
    public string Observaciones { get; set; } = "";
}

public class SyncCerdoDto
{
    [System.Text.Json.Serialization.JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("codigoCrotal")]
    public string CodigoCrotal { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("crotal")]
    public string Crotal { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("rfid")]
    public string Rfid { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("sexo")]
    public string Sexo { get; set; } = "Hembra";

    [System.Text.Json.Serialization.JsonPropertyName("raza")]
    public string Raza { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("fechaNacimiento")]
    public long FechaNacimiento { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("pesoKg")]
    public decimal PesoKg { get; set; } = 15m;

    [System.Text.Json.Serialization.JsonPropertyName("jaula")]
    public string Jaula { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("observaciones")]
    public string Observaciones { get; set; } = "";

    public string CrotalEfectivo => !string.IsNullOrWhiteSpace(Crotal) ? Crotal : CodigoCrotal;
}

public class SyncBajaMortalidadDto
{
    [System.Text.Json.Serialization.JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("crotal")]
    public string Crotal { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("loteCodigo")]
    public string LoteCodigo { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("galpon")]
    public string Galpon { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("causaMuerte")]
    public string CausaMuerte { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("pesoKg")]
    public decimal PesoKg { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("etapa")]
    public string Etapa { get; set; } = "Maternidad";

    [System.Text.Json.Serialization.JsonPropertyName("necropsia")]
    public bool Necropsia { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("hallazgos")]
    public string Hallazgos { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("registradoPor")]
    public string RegistradoPor { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("observaciones")]
    public string Observaciones { get; set; } = "";

    // Nombres con los que la app móvil envía la baja (BajaMortalidadEntity)
    [System.Text.Json.Serialization.JsonPropertyName("etapaZootecnica")]
    public string? EtapaZootecnica { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("necropsiaRealizada")]
    public bool? NecropsiaRealizada { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("hallazgosNecropsia")]
    public string? HallazgosNecropsia { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("galponLote")]
    public string? GalponLote { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("fechaBaja")]
    public long FechaBaja { get; set; }
}

public class SyncTareaEstadoDto
{
    [System.Text.Json.Serialization.JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("estado")]
    public string Estado { get; set; } = "completada";

    [System.Text.Json.Serialization.JsonPropertyName("completada")]
    public bool Completada { get; set; } = true;

    [System.Text.Json.Serialization.JsonPropertyName("completadoPor")]
    public string CompletadoPor { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("observaciones")]
    public string Observaciones { get; set; } = "";

    /// <summary>Clave de idempotencia por acción (id del ítem de cola). Si falta se usa Id.</summary>
    [System.Text.Json.Serialization.JsonPropertyName("syncId")]
    public string SyncId { get; set; } = "";
}

public class SyncTratamientoDto
{
    [System.Text.Json.Serialization.JsonPropertyName("id")]
    public string Id { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("crotal")]
    public string Crotal { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("medicamento")]
    public string Medicamento { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("dosis")]
    public decimal Dosis { get; set; }
    
    [System.Text.Json.Serialization.JsonPropertyName("unidad")]
    public string Unidad { get; set; } = "ml";
    
    [System.Text.Json.Serialization.JsonPropertyName("volumenFrascoInicial")]
    public decimal VolumenFrascoInicial { get; set; } = 100m;
    
    [System.Text.Json.Serialization.JsonPropertyName("volumenFrascoRestante")]
    public decimal VolumenFrascoRestante { get; set; } = 95m;
    
    [System.Text.Json.Serialization.JsonPropertyName("insumosUtilizados")]
    public List<string> InsumosUtilizados { get; set; } = new();
    
    [System.Text.Json.Serialization.JsonPropertyName("viaAdministracion")]
    public string ViaAdministracion { get; set; } = "Intramuscular (IM)";
    
    [System.Text.Json.Serialization.JsonPropertyName("diagnostico")]
    public string Diagnostico { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("veterinarioResponsable")]
    public string VeterinarioResponsable { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("matriculaVeterinario")]
    public string MatriculaVeterinario { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("costoTotalEstimadoUsd")]
    public decimal CostoTotalEstimadoUsd { get; set; }
    
    [System.Text.Json.Serialization.JsonPropertyName("galponJaula")]
    public string GalponJaula { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("fechaAplicacion")]
    public long FechaAplicacion { get; set; }
}

public class SyncEventoReproductivoDto
{
    [System.Text.Json.Serialization.JsonPropertyName("id")]
    public string Id { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("crotalCerda")]
    public string CrotalCerda { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("tipoEvento")]
    public string TipoEvento { get; set; } = "PARTO";
    
    [System.Text.Json.Serialization.JsonPropertyName("lechonesVivos")]
    public int LechonesVivos { get; set; }
    
    [System.Text.Json.Serialization.JsonPropertyName("lechonesMuertos")]
    public int LechonesMuertos { get; set; }
    
    [System.Text.Json.Serialization.JsonPropertyName("momificados")]
    public int Momificados { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("lechonesDestetados")]
    public int LechonesDestetados { get; set; }
    
    [System.Text.Json.Serialization.JsonPropertyName("pesoCamadaKg")]
    public decimal PesoCamadaKg { get; set; }
    
    [System.Text.Json.Serialization.JsonPropertyName("tipoAsistencia")]
    public string TipoAsistencia { get; set; } = "Natural";
    
    [System.Text.Json.Serialization.JsonPropertyName("oxitocinaAplicada")]
    public bool OxitocinaAplicada { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("madreEnObservacion")]
    public bool MadreEnObservacion { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("verracoCodigo")]
    public string VerracoCodigo { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("tecnicaIa")]
    public string TecnicaIa { get; set; } = "Post-cervical";

    [System.Text.Json.Serialization.JsonPropertyName("turnoIa")]
    public string TurnoIa { get; set; } = "AM";

    [System.Text.Json.Serialization.JsonPropertyName("condicionCorporal")]
    public decimal CondicionCorporal { get; set; } = 3.0m;

    [System.Text.Json.Serialization.JsonPropertyName("fechaEvento")]
    public long FechaEvento { get; set; }
}

public class SyncConsumoAlimentoDto
{
    [System.Text.Json.Serialization.JsonPropertyName("id")]
    public string Id { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("loteCodigo")]
    public string LoteCodigo { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("insumo")]
    public string Insumo { get; set; } = "";
    
    [System.Text.Json.Serialization.JsonPropertyName("cantidad")]
    public decimal Cantidad { get; set; }
    
    [System.Text.Json.Serialization.JsonPropertyName("unidad")]
    public string Unidad { get; set; } = "Kg";
    
    [System.Text.Json.Serialization.JsonPropertyName("operario")]
    public string Operario { get; set; } = "";

    // Nombres con los que la app móvil envía el consumo (ConsumoAlimentoEntity)
    [System.Text.Json.Serialization.JsonPropertyName("cantidadKg")]
    public decimal CantidadKg { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("loteNombre")]
    public string LoteNombre { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("tipoAlimento")]
    public string TipoAlimento { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("fechaRegistro")]
    public long FechaRegistro { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("siloCodigo")]
    public string SiloCodigo { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("horarioToma")]
    public string HorarioToma { get; set; } = "";
}

public class SyncOperacionGastoDto
{
    [System.Text.Json.Serialization.JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("tipoRegistro")]
    public string TipoRegistro { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("concepto")]
    public string Concepto { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("categoria")]
    public string Categoria { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("responsableNombre")]
    public string ResponsableNombre { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("galponLote")]
    public string GalponLote { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("montoCordobas")]
    public decimal MontoCordobas { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("montoDolares")]
    public decimal MontoDolares { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("horasOJornal")]
    public decimal HorasOJornal { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("fechaRegistro")]
    public long FechaRegistro { get; set; }

    [System.Text.Json.Serialization.JsonPropertyName("numeroComprobante")]
    public string NumeroComprobante { get; set; } = "";

    [System.Text.Json.Serialization.JsonPropertyName("observaciones")]
    public string Observaciones { get; set; } = "";
}

/// <summary>Registro que el servidor no pudo aplicar (se informa a la app para que lo muestre al usuario).</summary>
public record SyncRechazo(string Id, string Entidad, string Motivo);

public record MovilCerda(string Caravana, string Estado, string Jaula, string Galpon, int Paridad);
public record MovilVerraco(string Codigo, string Nombre);
public record MovilInsumo(string Nombre, string Unidad, decimal Stock);
public record MovilLote(string Codigo, string Galpon, string Etapa, int Animales);
public record MovilTarea(string Id, string Titulo, string Descripcion, string Prioridad, string TipoActividad, string Estado, string FechaProgramada, string HoraProgramada, string Galpon, string Lote, string AsignadoA);
public record MovilNotificacion(string Id, string Titulo, string Mensaje, string Tipo, string Severidad, long FechaHora, bool Leida);
public record MovilSilo(string Codigo, string Nombre, decimal CapacidadKg, decimal NivelActualKg, decimal PctOcupacion, string Insumo, int DiasAutonomia);

public record CatalogoGalponMovilDto(string Codigo, string Nombre, string TipoSistema, int Capacidad, decimal TemperaturaC, decimal HumedadPct);
public record CatalogoJaulaMovilDto(string Codigo, string Nombre, string GalponCodigo, string GalponNombre, string EtapaZootecnica, int CapacidadMaxima, int OcupacionActual);
public record CatalogoCerdoMovilDto(string Id, string Crotal, string Rfid, string Sexo, long FechaNacimientoMs, decimal PesoKg, string Etapa, string Jaula, string Galpon, string Raza, string EstatusSanitario, string Observaciones);
public record CatalogoInsumoDetalleDto(string Id, string Sku, string Nombre, string CategoriaCodigo, string UnidadMedida, decimal StockActual, decimal StockMinimo, decimal CostoUnitarioUsd, string SiloCodigo);
public record CatalogoLaborMovilDto(string Codigo, string Nombre, string TipoRegistro, string Categoria, string EtapaSugerida, string GalponSugerido, string DuracionSugerida, decimal TarifaReferenciaNio, string CentroCostoCodigo, string RolSugerido, bool RequiereInsumo);
public record CatalogoHorarioMovilDto(string Codigo, string Nombre, string TipoHorario, string HoraInicio, string HoraFin, string CategoriaActividad, string EtapaAplicable, string RolResponsable, string PrioridadDefault, string Descripcion);
public record CatalogoOperarioMovilDto(string Id, string NombreUsuario, string Nombre, string Rol, string Matricula);
public record CatalogoClinicoMovilDto(string Codigo, string TipoCatalogo, string Nombre, string Subcategoria, int DiasRetiroReferencia);

public class PreferenciasUiUsuarioDto
{
    public string RolBase { get; set; } = AppRoles.Operario;
    public List<string> ModulosVisibles { get; set; } = new();
    public List<string> AccionesRapidas { get; set; } = new();
    public List<string> NavInferior { get; set; } = new();
    public string GalponPredeterminado { get; set; } = "";
    public string TurnoPredeterminado { get; set; } = "TUR-MAN";
    public string MonedaPreferida { get; set; } = "NIO";
    public bool ModoCompacto { get; set; } = false;
    public long UpdatedAtMs { get; set; } = 0;
}

public class CatalogosFormularioWebViewModel
{
    public List<CatalogoLaborMovilDto> Labores { get; set; } = new();
    public List<CatalogoLaborMovilDto> GastosConceptos { get; set; } = new();
    public List<CatalogoHorarioMovilDto> Horarios { get; set; } = new();
    public List<CatalogoHorarioMovilDto> PlantillasAgenda { get; set; } = new();
    public List<CatalogoHorarioMovilDto> TurnosJornada { get; set; } = new();
    public List<CatalogoHorarioMovilDto> DosisIaHorarios { get; set; } = new();
    public List<CatalogoClinicoMovilDto> CausasMortalidad { get; set; } = new();
    public List<CatalogoClinicoMovilDto> Diagnosticos { get; set; } = new();
    public List<CatalogoClinicoMovilDto> ViasAdministracion { get; set; } = new();
    public List<CatalogoClinicoMovilDto> TecnicasIa { get; set; } = new();
    public List<string> LineasGeneticas { get; set; } = new();
    public List<CatalogoGalponMovilDto> Galpones { get; set; } = new();
    public List<CatalogoJaulaMovilDto> Jaulas { get; set; } = new();
    public List<CatalogoOperarioMovilDto> Operadores { get; set; } = new();
    public List<CatalogoInsumoDetalleDto> Insumos { get; set; } = new();
    public List<MovilSilo> Silos { get; set; } = new();
    public List<MovilLote> Lotes { get; set; } = new();
    public List<string> Proveedores { get; set; } = new();
}

public class MovilCatalogo
{
    public long GeneradoEn { get; set; }
    public List<MovilCerda> Cerdas { get; set; } = new();
    public List<MovilVerraco> Verracos { get; set; } = new();
    public List<MovilInsumo> Medicamentos { get; set; } = new();
    public List<MovilInsumo> Alimentos { get; set; } = new();
    public List<MovilLote> Lotes { get; set; } = new();
    public List<MovilSilo> Silos { get; set; } = new();
    public List<MovilTarea> Tareas { get; set; } = new();
    public List<MovilNotificacion> Notificaciones { get; set; } = new();

    // Catálogos maestros completos consultados desde PostgreSQL
    public List<CatalogoGalponMovilDto> Galpones { get; set; } = new();
    public List<CatalogoJaulaMovilDto> Jaulas { get; set; } = new();
    public List<CatalogoCerdoMovilDto> CerdosIndividuales { get; set; } = new();
    public List<CatalogoInsumoDetalleDto> InsumosDetalle { get; set; } = new();
    public List<CatalogoLaborMovilDto> Labores { get; set; } = new();
    public List<CatalogoHorarioMovilDto> Horarios { get; set; } = new();
    public List<CatalogoOperarioMovilDto> Operarios { get; set; } = new();
    public List<CatalogoClinicoMovilDto> CatalogosClinicos { get; set; } = new();
    public List<string> LineasGeneticas { get; set; } = new();
    public PreferenciasUiUsuarioDto PreferenciasUi { get; set; } = new();
}

// =========================================================================
// MODELOS PARA GESTIÓN DE CERDOS INDIVIDUALES, JAULAS Y PLAN ZOOTÉCNICO
// =========================================================================

public class CerdosPageViewModel
{
    public int TotalCerdos { get; set; }
    public int TotalCapacidadJaulas { get; set; }
    public int TotalOcupacionJaulas { get; set; }
    public decimal PctOcupacionGlobal { get; set; }
    public int CerdosLactancia { get; set; }
    public int CerdosPrecebo { get; set; }
    public int CerdosCrecimiento { get; set; }
    public int CerdosEngorde { get; set; }
    public int CerdosGestacion { get; set; }
    public int VacunacionesUrgentesHoy { get; set; }
    public List<CerdoViewModel> Cerdos { get; set; } = new();
    public List<JaulaCorralViewModel> Jaulas { get; set; } = new();
}

public class CerdoViewModel
{
    public Guid Id { get; set; }
    public string Crotal { get; set; } = "";
    public string Rfid { get; set; } = "";
    public string Sexo { get; set; } = "Hembra";
    public DateTime FechaNacimiento { get; set; }
    public int DiasVida { get; set; }
    public decimal PesoActualKg { get; set; }
    public string EtapaActual { get; set; } = "";
    public string SubEtapa { get; set; } = "";
    public Guid? JaulaCorralId { get; set; }
    public string JaulaCodigo { get; set; } = "";
    public string JaulaNombre { get; set; } = "";
    public string GalponNombre { get; set; } = "";
    public string LineaGenetica { get; set; } = "";
    public string EstatusSanitario { get; set; } = "Saludable";
    public string Observaciones { get; set; } = "";

    // Nutrición & Tomas Diarias
    public string AlimentoTipo { get; set; } = "";
    public decimal ConsumoDiarioKg { get; set; }
    public int ConsumoDiarioGramos { get; set; }
    public int TomasAlDia { get; set; }
    public int CantidadPorTomaGramos { get; set; }
    public decimal CantidadPorTomaKg { get; set; }
    public string HorariosSugeridos { get; set; } = "";
    public string RecomendacionNutricional { get; set; } = "";

    // Calendario Vacunal
    public string ProximaVacunaNombre { get; set; } = "";
    public DateTime? ProximaVacunaFecha { get; set; }
    public int ProximaVacunaDias { get; set; }
    public string ProximaVacunaEstado { get; set; } = "";
    public string ProximaVacunaBadgeClass { get; set; } = "";
    public List<PlanVacunacionItem> PlanVacunacion { get; set; } = new();
}

public class JaulaCorralViewModel
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string Nombre { get; set; } = "";
    public string EtapaZootecnica { get; set; } = "";
    public int CapacidadMaxima { get; set; }
    public int OcupacionActual { get; set; }
    public int CupoDisponible { get; set; }
    public decimal PorcentajeOcupacion { get; set; }
    public string TipoInstalacion { get; set; } = "";
    public string GalponNombre { get; set; } = "";
    public string EstadoBadge { get; set; } = "";
    public string EstadoBadgeClass { get; set; } = "";
    public List<string> CerdosAlojados { get; set; } = new();

    // Medidas canónicas y capacidades por jaula (5m fondo x 3m ancho = 15m²)
    public decimal LargoFondoMetros { get; set; } = 5.0m;
    public decimal AnchoMetros { get; set; } = 3.0m;
    public decimal AreaM2 { get; set; } = 15.0m;
    public int CapacidadLechones { get; set; } = 20;
    public int CapacidadAdultos { get; set; } = 6;
    public int NumeroJaula { get; set; } = 1;

    public string DimensionesTexto => $"{LargoFondoMetros:0.0}m fondo × {AnchoMetros:0.0}m ancho ({AreaM2:0.0} m²)";
    public string CapacidadesTexto => $"{CapacidadLechones} lechones (recría/precebo) ó {CapacidadAdultos} cerdos adultos";
}

public class PlanVacunacionItem
{
    public int DiaVidaProgramado { get; set; }
    public DateTime FechaEstimada { get; set; }
    public string VacunaBiológico { get; set; } = "";
    public string EnfermedadPrevenida { get; set; } = "";
    public string Dosis { get; set; } = "";
    public string Via { get; set; } = "";
    public string Estado { get; set; } = "";
    public string BadgeClass { get; set; } = "";
    public string Icono { get; set; } = "";
}

public class CalculoZootecnicoResult
{
    public DateTime FechaNacimiento { get; set; }
    public int DiasVida { get; set; }
    public string EtapaClasificada { get; set; } = "";
    public string SubEtapa { get; set; } = "";
    public string JaulaSugeridaTipo { get; set; } = "";
    public string RangoPesoEsperado { get; set; } = "";
    public decimal PesoActualKg { get; set; }

    public string AlimentoTipo { get; set; } = "";
    public decimal ConsumoDiarioKg { get; set; }
    public int ConsumoDiarioGramos { get; set; }
    public int TomasAlDia { get; set; }
    public int CantidadPorTomaGramos { get; set; }
    public decimal CantidadPorTomaKg { get; set; }
    public string HorariosSugeridos { get; set; } = "";
    public string RecomendacionNutricional { get; set; } = "";

    public List<PlanVacunacionItem> PlanVacunacion { get; set; } = new();
}

public class CerdoInputModel
{
    public Guid? Id { get; set; }
    public string Crotal { get; set; } = "";
    public string? Rfid { get; set; }
    public string Sexo { get; set; } = "Hembra";
    public DateTime FechaNacimiento { get; set; } = DateTime.Today;
    public decimal PesoActualKg { get; set; } = 15.0m;
    public Guid? JaulaCorralId { get; set; }
    public string LineaGenetica { get; set; } = "Topigs Norsvin 70";
    public string EstatusSanitario { get; set; } = "Saludable";
    public string? Observaciones { get; set; }
}

// =========================================================================
// MODELOS PARA CRONOGRAMA, TAREAS, HORARIOS Y CENTRO DE NOTIFICACIONES
// =========================================================================

public class TareaActividadItem
{
    public Guid Id { get; set; }
    public string Titulo { get; set; } = "";
    public string Descripcion { get; set; } = "";
    public string TipoActividad { get; set; } = "Sanidad"; // Sanidad, Reproduccion, Alimentacion, Manejo, Bioseguridad, Mantenimiento
    public string Prioridad { get; set; } = "media"; // baja, media, alta, urgente
    public string Estado { get; set; } = "pendiente"; // pendiente, en_progreso, completada, cancelada
    public DateTime FechaProgramada { get; set; }
    public string HoraProgramada { get; set; } = "08:00";
    public string GalponNombre { get; set; } = "";
    public string LoteCodigo { get; set; } = "";
    public string CerdaCaravana { get; set; } = "";
    public string AsignadoANombre { get; set; } = "";
    public string AsignadoARol { get; set; } = "";
    public string CompletadoPor { get; set; } = "";
    public DateTime? FechaCompletado { get; set; }
    public string Observaciones { get; set; } = "";
    
    // Helpers para UI
    public bool EsUrgente => Prioridad.Equals("urgente", StringComparison.OrdinalIgnoreCase) || Prioridad.Equals("alta", StringComparison.OrdinalIgnoreCase);
    public bool EstaCompletada => Estado.Equals("completada", StringComparison.OrdinalIgnoreCase);
    public bool EstaVencida => !EstaCompletada && FechaProgramada.Date < DateTime.Today;
    
    public string ColorBadge => TipoActividad switch {
        "Sanidad" => "bg-teal-50 text-teal-800 border-teal-300",
        "Reproduccion" => "bg-purple-50 text-purple-800 border-purple-300",
        "Alimentacion" => "bg-amber-50 text-amber-800 border-amber-300",
        "Bioseguridad" => "bg-blue-50 text-blue-800 border-blue-300",
        "Mantenimiento" => "bg-stone-100 text-stone-800 border-stone-300",
        _ => "bg-emerald-50 text-emerald-800 border-emerald-300"
    };
    
    public string Icono => TipoActividad switch {
        "Sanidad" => "vaccines",
        "Reproduccion" => "child_care",
        "Alimentacion" => "inventory_2",
        "Bioseguridad" => "shield",
        "Mantenimiento" => "build",
        _ => "assignment"
    };
}

public class TareaActividadInputModel
{
    public Guid? Id { get; set; }
    public string Titulo { get; set; } = "";
    public string? Descripcion { get; set; }
    public string TipoActividad { get; set; } = "Sanidad";
    public string Prioridad { get; set; } = "media";
    public string Estado { get; set; } = "pendiente";
    public DateTime FechaProgramada { get; set; } = DateTime.Today;
    public string HoraProgramada { get; set; } = "08:00";
    public string? GalponNombre { get; set; }
    public string? LoteCodigo { get; set; }
    public string? CerdaCaravana { get; set; }
    public string? AsignadoANombre { get; set; }
    public string? AsignadoARol { get; set; }
    public string? Observaciones { get; set; }
}

public class BajaMortalidadInputModel
{
    public string Crotal { get; set; } = "";
    public string? LoteCodigo { get; set; }
    public string? GalponNombre { get; set; }
    public string CausaMuerte { get; set; } = "Aplastamiento perinatal";
    public decimal PesoKg { get; set; } = 1.2m;
    public string EtapaZootecnica { get; set; } = "Maternidad";
    public bool NecropsiaRealizada { get; set; } = false;
    public string? HallazgosNecropsia { get; set; }
    public string RegistradoPor { get; set; } = "";
    public string? Observaciones { get; set; }
    public DateTime? FechaBaja { get; set; }
}

public class NotificacionCentroItem
{
    public Guid Id { get; set; }
    public string Titulo { get; set; } = "";
    public string Mensaje { get; set; } = "";
    public string Tipo { get; set; } = "info";
    public string Severidad { get; set; } = "preventiva";
    public DateTime FechaHora { get; set; }
    public bool Leida { get; set; }
    public string? UrlDestino { get; set; }
    
    public string Icono => Tipo switch {
        "sanitaria" or "protocolo_sanitario" => "vaccines",
        "reproduccion" or "parto_inminente" => "child_care",
        "alimento" or "silo_critico" or "sobreconsumo_alimento" => "inventory_2",
        "tarea" => "event_note",
        _ => "warning"
    };
    
    public string BadgeColor => Severidad switch {
        "critica" => "bg-red-100 text-red-800 border-red-300",
        "preventiva" => "bg-amber-100 text-amber-800 border-amber-300",
        _ => "bg-blue-100 text-blue-800 border-blue-300"
    };
}

public class CalendarioPageViewModel
{
    public DateTime FechaSeleccionada { get; set; } = DateTime.Today;
    public int MesSeleccionado { get; set; } = DateTime.Today.Month;
    public int AnioSeleccionado { get; set; } = DateTime.Today.Year;
    public string? FiltroTipo { get; set; }
    public string? FiltroEstado { get; set; }
    public string? FiltroRol { get; set; }
    
    public int TareasHoyTotal { get; set; }
    public int TareasHoyPendientes { get; set; }
    public int TareasHoyCompletadas { get; set; }
    public int TareasUrgentes { get; set; }
    
    public List<TareaActividadItem> TareasHorarioDia { get; set; } = new();
    public List<TareaActividadItem> TareasMes { get; set; } = new();
    public List<NotificacionCentroItem> Notificaciones { get; set; } = new();
    
    public List<string> GalponesDisponibles { get; set; } = new();
    public List<string> LotesDisponibles { get; set; } = new();
    public List<string> OperadoresDisponibles { get; set; } = new();
}

// =========================================================================
// MODELOS PARA GESTIÓN DE USUARIOS
// =========================================================================
public class UsuariosPageViewModel
{
    public int TotalUsuarios { get; set; }
    public int TotalAdmins { get; set; }
    public int TotalVeterinarios { get; set; }
    public int TotalOperarios { get; set; }
    public int TotalActivos { get; set; }
    public List<UsuarioSistemaItem> Usuarios { get; set; } = new();
}

public class UsuarioSistemaItem
{
    public Guid Id { get; set; }
    public string NombreUsuario { get; set; } = "";
    public string Nombre { get; set; } = "";
    public string Email { get; set; } = "";
    public string Rol { get; set; } = "";
    public string? MatriculaProfesional { get; set; }
    public string? Telefono { get; set; }
    public bool Activo { get; set; }
    public bool DebeCambiarPassword { get; set; }
    public DateTime? UltimoLogin { get; set; }
    public DateTime FechaCreacion { get; set; }

    public string RolNombreLegible => Rol switch
    {
        "administrador_financiero" => "Administrador General",
        "director_veterinario" => "Director Veterinario (MVZ)",
        "operario_galpon" => "Operario de Campo",
        _ => Rol
    };

    public string RolBadgeClass => Rol switch
    {
        "administrador_financiero" => "bg-emerald-100 text-emerald-800 border-emerald-300",
        "director_veterinario" => "bg-teal-100 text-teal-800 border-teal-300",
        _ => "bg-amber-100 text-amber-800 border-amber-300"
    };

    public string IconoRol => Rol switch
    {
        "administrador_financiero" => "admin_panel_settings",
        "director_veterinario" => "vaccines",
        _ => "engineering"
    };
}

public class CrearUsuarioInput
{
    public string NombreUsuario { get; set; } = "";
    public string Nombre { get; set; } = "";
    public string Rol { get; set; } = "operario_galpon";
    public string Password { get; set; } = "Prueba#2026";
    public string? MatriculaProfesional { get; set; }
    public string? Telefono { get; set; }
    public string? Email { get; set; }
}

// =========================================================================
// MODELOS PARA COMPRAS, PROVEEDORES, CATÁLOGO Y ÓRDENES
// =========================================================================
public class ComprasViewModel
{
    public decimal TotalCompradoUsd { get; set; }
    public decimal TotalCompradoNio { get; set; }
    public int TotalProveedoresActivos { get; set; }
    public int TotalProductosCatalogo { get; set; }
    public int TotalOrdenesPendientes { get; set; }
    public decimal TasaCambioOficial { get; set; } = 36.80m;

    public List<ProveedorItem> Proveedores { get; set; } = new();
    public List<ProductoCatalogoCompraItem> ProductosCatalogo { get; set; } = new();
    public List<OrdenCompraItem> OrdenesCompra { get; set; } = new();
}

public class ProveedorItem
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string NombreComercial { get; set; } = "";
    public string RazonSocial { get; set; } = "";
    public string Ruc { get; set; } = "";
    public string ContactoNombre { get; set; } = "";
    public string Telefono { get; set; } = "";
    public string Email { get; set; } = "";
    public string Direccion { get; set; } = "";
    public string CondicionesPago { get; set; } = "Contado";
    public bool Activo { get; set; } = true;
    public int TotalProductosOfrecidos { get; set; }
}

public class ProductoCatalogoCompraItem
{
    public Guid Id { get; set; }
    public Guid ProveedorId { get; set; }
    public string ProveedorNombre { get; set; } = "";
    public string CodigoProducto { get; set; } = "";
    public string NombreProducto { get; set; } = "";
    public string UnidadMedida { get; set; } = "qq (100 lbs)";
    public decimal PrecioUnitarioUsd { get; set; }
    public decimal PrecioUnitarioNio { get; set; }
    public bool AplicaDescuento { get; set; }
    public int UnidadesMinimasDescuento { get; set; } = 10;
    public string TipoDescuento { get; set; } = "porcentaje"; // 'porcentaje' o 'monetario'
    public decimal DescuentoPorcentaje { get; set; }
    public decimal DescuentoMonetarioUsd { get; set; }
    public decimal DescuentoMonetarioNio { get; set; }
    public string NotasPromocion { get; set; } = "";
    public bool Activo { get; set; } = true;

    public string ReglaDescuentoResumen => AplicaDescuento
        ? (TipoDescuento == "porcentaje"
            ? $"{DescuentoPorcentaje:0.#}% dto. desde {UnidadesMinimasDescuento} {UnidadMedida}"
            : $"Rebaja ${DescuentoMonetarioUsd:N2} USD (C$ {DescuentoMonetarioNio:N2}) desde {UnidadesMinimasDescuento} {UnidadMedida}")
        : "Precio neto (sin descuento por volumen)";
}

public class OrdenCompraItem
{
    public Guid Id { get; set; }
    public string NumeroOrden { get; set; } = "";
    public Guid ProveedorId { get; set; }
    public string ProveedorNombre { get; set; } = "";
    public DateTime FechaEmision { get; set; }
    public DateTime? FechaEntregaEsperada { get; set; }
    public string Moneda { get; set; } = "USD";
    public decimal TasaCambio { get; set; } = 36.80m;
    public string Estado { get; set; } = "emitida"; // 'borrador', 'emitida', 'recibida_kardex', 'cancelada'
    public decimal SubtotalUsd { get; set; }
    public decimal DescuentoUsd { get; set; }
    public decimal TotalUsd { get; set; }
    public decimal SubtotalNio { get; set; }
    public decimal DescuentoNio { get; set; }
    public decimal TotalNio { get; set; }
    public string SolicitadoPor { get; set; } = "";
    public string Observaciones { get; set; } = "";
    public int CantidadItems { get; set; }
    public string? RecibidoPor { get; set; }
    public DateTime? FechaRecepcion { get; set; }
    public string? LoteFabricante { get; set; }
    public DateTime? FechaVencimiento { get; set; }
    public string? BodegaDestino { get; set; }
    public List<OrdenCompraDetalleItem> Detalles { get; set; } = new();

    public string EstadoBadgeClass => Estado switch
    {
        "recibida_kardex" => "bg-emerald-100 text-emerald-800 border-emerald-300",
        "emitida" => "bg-blue-100 text-blue-800 border-blue-300",
        "borrador" => "bg-amber-100 text-amber-800 border-amber-300",
        _ => "bg-red-100 text-red-800 border-red-300"
    };

    public string EstadoTexto => Estado switch
    {
        "recibida_kardex" => "Recibida en Kardex",
        "emitida" => "Emitida a Proveedor",
        "borrador" => "Borrador",
        _ => "Cancelada"
    };
}

public class OrdenCompraDetalleItem
{
    public Guid Id { get; set; }
    public Guid? CatalogoProductoId { get; set; }
    public string Descripcion { get; set; } = "";
    public decimal Cantidad { get; set; }
    public string UnidadMedida { get; set; } = "";
    public decimal PrecioUnitarioUsd { get; set; }
    public decimal PrecioUnitarioNio { get; set; }
    public bool AplicoDescuento { get; set; }
    public string TipoDescuento { get; set; } = "ninguno";
    public decimal MontoDescuentoUnitarioUsd { get; set; }
    public decimal MontoDescuentoLineaUsd { get; set; }
    public decimal SubtotalLineaUsd { get; set; }
    public decimal TotalLineaUsd { get; set; }
    public decimal MontoDescuentoLineaNio { get; set; }
    public decimal TotalLineaNio { get; set; }
}

public class CrearOrdenCompraInput
{
    public Guid ProveedorId { get; set; }
    public string Moneda { get; set; } = "USD";
    public DateTime? FechaEntregaEsperada { get; set; }
    public string? Observaciones { get; set; }
    public List<CrearOrdenCompraDetalleInput> Lineas { get; set; } = new();
}

public class CrearOrdenCompraDetalleInput
{
    public Guid ProductoCatalogoId { get; set; }
    public decimal Cantidad { get; set; }
}

public class CrearProveedorInput
{
    public string? Codigo { get; set; }
    public string NombreComercial { get; set; } = "";
    public string? RazonSocial { get; set; }
    public string? Ruc { get; set; }
    public string? ContactoNombre { get; set; }
    public string? Telefono { get; set; }
    public string? Email { get; set; }
    public string? Direccion { get; set; }
    public string CondicionesPago { get; set; } = "Contado";
}

public class GuardarProductoCatalogoInput
{
    public Guid ProveedorId { get; set; }
    public string? CodigoProducto { get; set; }
    public string NombreProducto { get; set; } = "";
    public string UnidadMedida { get; set; } = "qq (100 lbs)";
    public decimal PrecioUnitarioUsd { get; set; }
    public decimal PrecioUnitarioNio { get; set; }
    public bool AplicaDescuento { get; set; }
    public int UnidadesMinimasDescuento { get; set; } = 10;
    public string TipoDescuento { get; set; } = "porcentaje";
    public decimal DescuentoPorcentaje { get; set; }
    public decimal DescuentoMonetarioUsd { get; set; }
    public decimal DescuentoMonetarioNio { get; set; }
    public string? NotasPromocion { get; set; }
}

// =========================================================================
// MODELOS PARA GALPONES Y JAULAS (6 JAULAS, 5x3m = 15m², 15-20 LECHONES / 5-6 ADULTOS)
// =========================================================================
public class GalponTecnicoItem
{
    public Guid Id { get; set; }
    public string Codigo { get; set; } = "";
    public string Nombre { get; set; } = "";
    public int CapacidadMaxima { get; set; }
    public List<JaulaTecnicaItem> Jaulas { get; set; } = new();
}

public class JaulaTecnicaItem
{
    public Guid Id { get; set; }
    public int NumeroJaula { get; set; } = 1;
    public string Codigo { get; set; } = "";
    public string Nombre { get; set; } = "";
    public string EtapaZootecnica { get; set; } = "";
    public decimal LargoFondoMetros { get; set; } = 5.0m;
    public decimal AnchoMetros { get; set; } = 3.0m;
    public decimal AreaM2 { get; set; } = 15.0m;
    public int CapacidadLechones { get; set; } = 20;
    public int CapacidadAdultos { get; set; } = 6;
    public int CapacidadMaxima { get; set; } = 20;
    public int OcupacionActual { get; set; }
    public int CupoDisponible { get; set; }
    public decimal PorcentajeOcupacion { get; set; }
    public string TipoInstalacion { get; set; } = "";

    public string DimensionesTexto => $"{LargoFondoMetros:0.0}m fondo × {AnchoMetros:0.0}m ancho ({AreaM2:0.0} m²)";
    public string CapacidadesTexto => $"{CapacidadLechones} lechones (recría/precebo) ó {CapacidadAdultos} cerdos adultos";
}
