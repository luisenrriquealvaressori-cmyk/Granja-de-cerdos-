-- ============================================================================
-- AGROPIG INTELLIGENCE SUITE - BASE DE DATOS POSTGRESQL (SCHEMA CENTRAL)
-- Versión: 4.2.0 Enterprise
-- Base de datos: agropig_db
-- ============================================================================

-- Habilitar extensiones requeridas
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Eliminar tablas en orden inverso de dependencias si existen
DROP VIEW IF EXISTS v_radar_partos_inminentes CASCADE;
DROP VIEW IF EXISTS v_resumen_bi_lotes_activos CASCADE;
DROP VIEW IF EXISTS v_balance_kardex_silos CASCADE;
DROP VIEW IF EXISTS v_analisis_costo_kg_producido CASCADE;

DROP TABLE IF EXISTS sync_outbox_logs CASCADE;
DROP TABLE IF EXISTS alertas_operativas_desviaciones CASCADE;
DROP TABLE IF EXISTS lecturas_telemetria_historico CASCADE;
DROP TABLE IF EXISTS sensores_telemetria CASCADE;
DROP TABLE IF EXISTS aplicaciones_sanitarias_lote CASCADE;
DROP TABLE IF EXISTS protocolos_sanitarios CASCADE;
DROP TABLE IF EXISTS liquidaciones_ventas CASCADE;
DROP TABLE IF EXISTS gastos_operativos_transacciones CASCADE;
DROP TABLE IF EXISTS presupuestos_lote CASCADE;
DROP TABLE IF EXISTS centros_costo CASCADE;
DROP TABLE IF EXISTS movimientos_kardex CASCADE;
DROP TABLE IF EXISTS insumos_catalogo CASCADE;
DROP TABLE IF EXISTS categorias_insumos CASCADE;
DROP TABLE IF EXISTS mortalidad_bajas CASCADE;
DROP TABLE IF EXISTS pesajes_monitoreo CASCADE;
DROP TABLE IF EXISTS transiciones_etapa_lote CASCADE;
DROP TABLE IF EXISTS perdidas_perinatales CASCADE;
DROP TABLE IF EXISTS destetes CASCADE;
DROP TABLE IF EXISTS partos CASCADE;
DROP TABLE IF EXISTS servicios_inseminacion CASCADE;
DROP TABLE IF EXISTS cerdas_reproductoras CASCADE;
DROP TABLE IF EXISTS verracos_semen CASCADE;
DROP TABLE IF EXISTS lineas_geneticas CASCADE;
DROP TABLE IF EXISTS lotes_produccion CASCADE;
DROP TABLE IF EXISTS silos_depositos CASCADE;
DROP TABLE IF EXISTS galpones_naves CASCADE;
DROP TABLE IF EXISTS usuarios_operadores CASCADE;
DROP TABLE IF EXISTS granjas CASCADE;

-- ============================================================================
-- 1. MÓDULO MAESTRO Y ESTRUCTURA DE INSTALACIONES (CORE)
-- ============================================================================

CREATE TABLE granjas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo VARCHAR(30) NOT NULL UNIQUE,
    nombre VARCHAR(120) NOT NULL,
    ubicacion VARCHAR(250),
    superficie_hectareas NUMERIC(8,2) DEFAULT 0,
    capacidad_total_cabezas INT NOT NULL DEFAULT 0,
    estatus_sanitario_nacional VARCHAR(100) DEFAULT 'Certificado SPF - Libre PRRS y Peste Porcina Clásica',
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE galpones_naves (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    codigo VARCHAR(30) NOT NULL,
    nombre VARCHAR(120) NOT NULL,
    tipo_sistema VARCHAR(60) NOT NULL, -- 'climatizado_automatizado', 'ventilacion_natural', 'presion_negativa', 'tunel_viento'
    capacidad_cabezas INT NOT NULL,
    temperatura_ambiente_celsius NUMERIC(5,2) DEFAULT 22.0,
    humedad_relativa_pct NUMERIC(5,2) DEFAULT 60.0,
    estado_sanitizacion VARCHAR(50) DEFAULT 'Aprobada Post-Vacío Sanitario',
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_granja_galpon UNIQUE (granja_id, codigo)
);

CREATE TABLE silos_depositos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    codigo VARCHAR(30) NOT NULL UNIQUE,
    nombre VARCHAR(120) NOT NULL,
    capacidad_maxima_kg NUMERIC(12,2) NOT NULL,
    nivel_actual_kg NUMERIC(12,2) NOT NULL DEFAULT 0,
    tipo_insumo_principal VARCHAR(100),
    sistema_dispensador VARCHAR(60) DEFAULT 'Auger Sinfín Automatizado',
    estado_sensor VARCHAR(30) DEFAULT 'online',
    ultima_sincronizacion TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE usuarios_operadores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID REFERENCES granjas(id) ON DELETE SET NULL,
    nombre_usuario VARCHAR(60) UNIQUE,
    nombre VARCHAR(120) NOT NULL,
    email VARCHAR(150),
    password_hash VARCHAR(255) NOT NULL,
    rol VARCHAR(60) NOT NULL, -- 'director_veterinario', 'zootecnista_jefe', 'operario_galpon', 'administrador_financiero'
    matricula_profesional VARCHAR(60),
    telefono VARCHAR(30),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- 2. MÓDULO GENÉTICO Y REPRODUCTIVO (REPRODUCCIÓN & SANIDAD)
-- ============================================================================

CREATE TABLE lineas_geneticas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre VARCHAR(100) NOT NULL UNIQUE,
    casa_genetica VARCHAR(100) NOT NULL, -- 'Topigs Norsvin', 'PIC', 'DanBred', 'Hypor'
    potencial_gmd_g_dia INT DEFAULT 950,
    fcr_teorico_objetivo NUMERIC(4,2) DEFAULT 2.45,
    prolificidad_objetivo_nac_vivos NUMERIC(4,2) DEFAULT 14.0,
    descripcion TEXT
);

CREATE TABLE verracos_semen (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    codigo VARCHAR(50) NOT NULL UNIQUE,
    nombre VARCHAR(100) NOT NULL,
    linea_genetica_id UUID REFERENCES lineas_geneticas(id),
    tipo_reproductor VARCHAR(40) NOT NULL, -- 'verraco_en_pie', 'dosis_semen_refrigerado'
    viabilidad_espermatica_pct NUMERIC(5,2) DEFAULT 85.0,
    concentracion_billones NUMERIC(5,2) DEFAULT 1.5,
    lote_dosis VARCHAR(50),
    fecha_extraccion_recepcion DATE DEFAULT CURRENT_DATE,
    activo BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE cerdas_reproductoras (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    caravana VARCHAR(50) NOT NULL UNIQUE,
    rfid VARCHAR(50) UNIQUE,
    linea_genetica_id UUID REFERENCES lineas_geneticas(id),
    galpon_id UUID REFERENCES galpones_naves(id),
    jaula_numero VARCHAR(30),
    fecha_nacimiento DATE,
    fecha_ingreso DATE NOT NULL DEFAULT CURRENT_DATE,
    peso_actual_kg NUMERIC(6,2) DEFAULT 140.0,
    paridad_acumulada INT NOT NULL DEFAULT 0,
    estado_fisiologico VARCHAR(50) NOT NULL DEFAULT 'vacia', -- 'vacia', 'celo_activo', 'servida_gestante', 'lactante', 'descarte'
    estatus_sanitario VARCHAR(120) DEFAULT 'PLE + E. Coli Vigente',
    prolificidad_historica_media NUMERIC(4,2) DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE servicios_inseminacion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cerda_id UUID NOT NULL REFERENCES cerdas_reproductoras(id) ON DELETE CASCADE,
    verraco_id UUID REFERENCES verracos_semen(id),
    fecha_servicio DATE NOT NULL,
    turno VARCHAR(30) NOT NULL, -- '1_dosis_manana', '2_dosis_tarde', '3_dosis_refuerzo'
    tecnica_ia VARCHAR(60) NOT NULL, -- 'inseminacion_post_cervical', 'cervical_tradicional', 'monta_natural'
    dosis_numero INT NOT NULL DEFAULT 1,
    viabilidad_espermatica_pct NUMERIC(5,2),
    responsable_id UUID REFERENCES usuarios_operadores(id),
    resultado_concepcion VARCHAR(40) DEFAULT 'pendiente', -- 'pendiente', 'confirmada_positiva', 'repeticion_celo', 'aborto'
    fecha_ecografia_estimada DATE,            -- Regla Zootécnica: +28 días
    fecha_ecografia_real DATE,
    fecha_traslado_maternidad_estimada DATE,   -- Regla Zootécnica: +107 días
    fecha_parto_estimada DATE,                -- Regla Zootécnica: +114 días exactos
    observaciones TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE partos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cerda_id UUID NOT NULL REFERENCES cerdas_reproductoras(id),
    servicio_ia_id UUID REFERENCES servicios_inseminacion(id),
    galpon_id UUID REFERENCES galpones_naves(id),
    jaula_maternidad VARCHAR(30) NOT NULL,
    fecha_hora_parto TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    duracion_minutos INT DEFAULT 180,
    total_nacidos INT NOT NULL,
    nacidos_vivos INT NOT NULL,
    nacidos_muertos INT NOT NULL DEFAULT 0,
    momias INT NOT NULL DEFAULT 0,
    peso_total_camada_kg NUMERIC(6,2),
    peso_promedio_lechon_kg NUMERIC(4,2),
    condicion_corporal_cerda NUMERIC(3,1) DEFAULT 3.0,
    asistencia_obstetrica BOOLEAN DEFAULT FALSE,
    responsable_id UUID REFERENCES usuarios_operadores(id),
    observaciones TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE destetes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parto_id UUID NOT NULL REFERENCES partos(id),
    cerda_id UUID NOT NULL REFERENCES cerdas_reproductoras(id),
    fecha_destete DATE NOT NULL DEFAULT CURRENT_DATE,
    lechones_destetados INT NOT NULL,
    peso_total_destete_kg NUMERIC(8,2) NOT NULL,
    peso_promedio_destete_kg NUMERIC(5,2) NOT NULL,
    dias_lactancia INT NOT NULL DEFAULT 21,
    lote_destino_id UUID,
    observaciones TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE perdidas_perinatales (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parto_id UUID NOT NULL REFERENCES partos(id),
    cerda_id UUID NOT NULL REFERENCES cerdas_reproductoras(id),
    causa VARCHAR(60) NOT NULL, -- 'aplastamiento_cerda', 'bajo_peso_hipoglucemia', 'hipotermia', 'diarrea_neonatal', 'otros'
    cantidad_lechones INT NOT NULL DEFAULT 1,
    horas_vida INT DEFAULT 24,
    observaciones TEXT,
    fecha_registro TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- 3. MÓDULO DE LOTES Y TRAZABILIDAD PRODUCTIVA (ENGORDE & ZOOTECNIA)
-- ============================================================================

CREATE TABLE lotes_produccion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    galpon_id UUID REFERENCES galpones_naves(id),
    codigo_lote VARCHAR(50) NOT NULL UNIQUE, -- Estandarizado: LOT-YYYY-MM-DD
    etapa_zootecnica VARCHAR(50) NOT NULL,   -- 'maternidad', 'precebo', 'engorde_intensivo', 'acabado'
    fecha_inicio DATE NOT NULL,
    fecha_cierre_estimada DATE,
    fecha_cierre_real DATE,
    cantidad_inicial_cabezas INT NOT NULL,
    cantidad_actual_cabezas INT NOT NULL,
    bajas_acumuladas INT NOT NULL DEFAULT 0,
    peso_promedio_entrada_kg NUMERIC(6,2) NOT NULL,
    peso_promedio_actual_kg NUMERIC(6,2),
    peso_objetivo_salida_kg NUMERIC(6,2) DEFAULT 115.0,
    linea_genetica_id UUID REFERENCES lineas_geneticas(id),
    procedencia_camada VARCHAR(150),
    gmd_actual_g_dia NUMERIC(6,1) DEFAULT 0,
    fcr_actual NUMERIC(4,2) DEFAULT 0,
    presupuesto_asignado_usd NUMERIC(12,2) DEFAULT 0,
    costo_acumulado_usd NUMERIC(12,2) DEFAULT 0,
    estado VARCHAR(30) NOT NULL DEFAULT 'activo', -- 'borrador', 'activo', 'cerrado', 'liquidado'
    responsable_id UUID REFERENCES usuarios_operadores(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE pesajes_monitoreo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id) ON DELETE CASCADE,
    fecha_pesaje DATE NOT NULL DEFAULT CURRENT_DATE,
    muestra_cabezas INT NOT NULL,
    peso_total_kg NUMERIC(10,2) NOT NULL,
    peso_promedio_kg NUMERIC(6,2) NOT NULL,
    gmd_calculado_g_dia NUMERIC(6,1),
    responsable_id UUID REFERENCES usuarios_operadores(id),
    observaciones TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE mortalidad_bajas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id) ON DELETE CASCADE,
    fecha_hora TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    etapa VARCHAR(40) NOT NULL,
    cantidad_cabezas INT NOT NULL DEFAULT 1,
    causa_muerte VARCHAR(120) NOT NULL,
    necropsia_realizada BOOLEAN DEFAULT FALSE,
    diagnostico_veterinario TEXT,
    responsable_id UUID REFERENCES usuarios_operadores(id)
);

CREATE TABLE transiciones_etapa_lote (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id) ON DELETE CASCADE,
    etapa_origen VARCHAR(40) NOT NULL,
    etapa_destino VARCHAR(40) NOT NULL,
    galpon_origen_id UUID REFERENCES galpones_naves(id),
    galpon_destino_id UUID REFERENCES galpones_naves(id),
    fecha_transicion DATE NOT NULL DEFAULT CURRENT_DATE,
    cabezas_trasladadas INT NOT NULL,
    peso_promedio_kg NUMERIC(6,2) NOT NULL,
    responsable_id UUID REFERENCES usuarios_operadores(id)
);

-- ============================================================================
-- 4. MÓDULO DE INVENTARIOS, KARDEX & SILOS
-- ============================================================================

CREATE TABLE categorias_insumos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo VARCHAR(30) NOT NULL UNIQUE,
    nombre VARCHAR(100) NOT NULL,
    descripcion TEXT
);

CREATE TABLE insumos_catalogo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    categoria_id UUID NOT NULL REFERENCES categorias_insumos(id),
    codigo_sku VARCHAR(50) NOT NULL UNIQUE,
    codigo_barras VARCHAR(50),
    nombre VARCHAR(150) NOT NULL,
    descripcion TEXT,
    unidad_medida VARCHAR(30) NOT NULL, -- 'Kg', 'Litros', 'Frascos', 'Bultos'
    stock_actual NUMERIC(12,2) NOT NULL DEFAULT 0,
    stock_minimo_seguridad NUMERIC(12,2) NOT NULL,
    stock_maximo_capacidad NUMERIC(12,2) NOT NULL DEFAULT 1000,
    costo_unitario_promedio_usd NUMERIC(10,4) NOT NULL DEFAULT 0,
    silo_deposito_id UUID REFERENCES silos_depositos(id),
    estado_stock VARCHAR(30) DEFAULT 'optimo', -- 'optimo', 'reabastecer_urgente', 'en_transito'
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE movimientos_kardex (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    insumo_id UUID NOT NULL REFERENCES insumos_catalogo(id),
    lote_id UUID REFERENCES lotes_produccion(id),
    silo_id UUID REFERENCES silos_depositos(id),
    tipo_movimiento VARCHAR(50) NOT NULL, -- 'entrada_compra', 'salida_alimentacion_automatica', 'salida_tratamiento_sanitario', 'merma_perdida', 'ajuste_inventario'
    fecha_hora TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cantidad NUMERIC(12,2) NOT NULL,
    costo_unitario_usd NUMERIC(10,4) NOT NULL,
    valor_total_usd NUMERIC(12,2) GENERATED ALWAYS AS (ROUND(cantidad * costo_unitario_usd, 2)) STORED,
    documento_referencia VARCHAR(120),
    proveedor VARCHAR(150),
    responsable_id UUID REFERENCES usuarios_operadores(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- 5. MÓDULO FINANCIERO Y PRESUPUESTOS AUTOMÁTICOS
-- ============================================================================

CREATE TABLE centros_costo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    codigo VARCHAR(30) NOT NULL UNIQUE,
    nombre VARCHAR(120) NOT NULL,
    porcentaje_peso_presupuestal NUMERIC(5,2) DEFAULT 0
);

CREATE TABLE presupuestos_lote (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id) ON DELETE CASCADE,
    centro_costo_id UUID NOT NULL REFERENCES centros_costo(id),
    rubro_nombre VARCHAR(150) NOT NULL,
    monto_estimado_usd NUMERIC(12,2) NOT NULL,
    monto_ejecutado_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
    porcentaje_desviacion NUMERIC(6,2) DEFAULT 0,
    estado_bi VARCHAR(40) DEFAULT 'en_norma', -- 'optimo', 'en_norma', 'subejecutado', 'desviado_critico'
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_lote_centro_costo UNIQUE (lote_id, centro_costo_id)
);

CREATE TABLE gastos_operativos_transacciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    lote_id UUID REFERENCES lotes_produccion(id),
    centro_costo_id UUID NOT NULL REFERENCES centros_costo(id),
    tipo_movimiento VARCHAR(40) NOT NULL, -- 'gasto_operativo', 'salida_caja'
    fecha_contable DATE NOT NULL DEFAULT CURRENT_DATE,
    monto_usd NUMERIC(12,2) NOT NULL,
    concepto_detalle TEXT NOT NULL,
    proveedor_factura VARCHAR(150),
    impacto_desviacion_pct NUMERIC(5,2) DEFAULT 0,
    registrado_por_id UUID REFERENCES usuarios_operadores(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE liquidaciones_ventas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id),
    fecha_venta DATE NOT NULL DEFAULT CURRENT_DATE,
    cliente_comprador VARCHAR(150) NOT NULL,
    cantidad_cabezas INT NOT NULL,
    peso_total_kg NUMERIC(10,2) NOT NULL,
    peso_promedio_kg NUMERIC(6,2) GENERATED ALWAYS AS (CASE WHEN cantidad_cabezas > 0 THEN ROUND(peso_total_kg / cantidad_cabezas, 2) ELSE 0 END) STORED,
    precio_kg_usd NUMERIC(6,2) NOT NULL,
    monto_bruto_usd NUMERIC(12,2) GENERATED ALWAYS AS (ROUND(peso_total_kg * precio_kg_usd, 2)) STORED,
    costo_total_producido_usd NUMERIC(12,2) NOT NULL,
    margen_bruto_usd NUMERIC(12,2) GENERATED ALWAYS AS (ROUND((peso_total_kg * precio_kg_usd) - costo_total_producido_usd, 2)) STORED,
    margen_pct NUMERIC(5,2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- 6. PROTOCOLOS SANITARIOS, TELEMETRÍA IOT Y ALERTAS
-- ============================================================================

CREATE TABLE protocolos_sanitarios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre VARCHAR(150) NOT NULL,
    etapa_aplicacion VARCHAR(50) NOT NULL,
    dia_ciclo_aplicacion INT NOT NULL DEFAULT 0,
    insumo_id UUID REFERENCES insumos_catalogo(id),
    dosis_recomendada VARCHAR(50),
    via_administracion VARCHAR(50) DEFAULT 'intramuscular', -- 'intramuscular', 'oral_agua', 'subcutanea', 'topico'
    es_obligatorio BOOLEAN DEFAULT TRUE,
    descripcion TEXT
);

CREATE TABLE aplicaciones_sanitarias_lote (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID REFERENCES lotes_produccion(id),
    cerda_id UUID REFERENCES cerdas_reproductoras(id),
    protocolo_id UUID NOT NULL REFERENCES protocolos_sanitarios(id),
    insumo_id UUID REFERENCES insumos_catalogo(id),
    fecha_programada DATE NOT NULL,
    fecha_aplicacion DATE,
    dosis_aplicada VARCHAR(50),
    estado VARCHAR(30) DEFAULT 'programado', -- 'programado', 'completado', 'cancelado'
    aplicado_por_id UUID REFERENCES usuarios_operadores(id),
    observaciones TEXT
);

CREATE TABLE sensores_telemetria (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    galpon_id UUID REFERENCES galpones_naves(id),
    silo_id UUID REFERENCES silos_depositos(id),
    codigo_sensor VARCHAR(50) NOT NULL UNIQUE,
    tipo_sensor VARCHAR(50) NOT NULL, -- 'temperatura', 'humedad', 'peso_celda_carga', 'amoniaco_nh3', 'flujo_agua'
    valor_actual NUMERIC(8,2) NOT NULL DEFAULT 0,
    unidad_medida VARCHAR(20) NOT NULL,
    estado VARCHAR(30) DEFAULT 'online',
    ultima_lectura TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE lecturas_telemetria_historico (
    id BIGSERIAL PRIMARY KEY,
    sensor_id UUID NOT NULL REFERENCES sensores_telemetria(id) ON DELETE CASCADE,
    fecha_hora TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valor NUMERIC(8,2) NOT NULL,
    fuera_rango BOOLEAN DEFAULT FALSE
);

CREATE TABLE alertas_operativas_desviaciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    lote_id UUID REFERENCES lotes_produccion(id),
    tipo_alerta VARCHAR(60) NOT NULL, -- 'desviacion_financiera', 'protocolo_sanitario', 'sobreconsumo_alimento', 'anomalia_ambiental', 'mortalidad_anormal'
    nivel_severidad VARCHAR(30) NOT NULL DEFAULT 'preventiva', -- 'info', 'preventiva', 'critica'
    descripcion TEXT NOT NULL,
    valor_detectado VARCHAR(60),
    umbral_esperado VARCHAR(60),
    estado VARCHAR(30) NOT NULL DEFAULT 'abierta', -- 'abierta', 'en_revision', 'resuelta'
    fecha_deteccion TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resuelto_por_id UUID REFERENCES usuarios_operadores(id),
    fecha_resolucion TIMESTAMPTZ
);

-- ============================================================================
-- 7. TABLA DE COLA DE SINCRONIZACIÓN Y AUDITORÍA OUTBOX (MÓVIL FIELD SYNC)
-- ============================================================================

CREATE TABLE sync_outbox_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_sync_id VARCHAR(100) NOT NULL UNIQUE,
    dispositivo_id VARCHAR(100),
    operario_id UUID REFERENCES usuarios_operadores(id),
    entidad_tipo VARCHAR(60) NOT NULL,
    operacion VARCHAR(30) NOT NULL, -- 'INSERT', 'UPDATE', 'DELETE'
    payload_json JSONB NOT NULL,
    fecha_cliente TIMESTAMPTZ NOT NULL,
    fecha_servidor TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    estado_procesamiento VARCHAR(30) NOT NULL DEFAULT 'procesado',
    mensaje_error TEXT
);

-- ============================================================================
-- 8. ÍNDICES DE RENDIMIENTO Y CONSULTA BI
-- ============================================================================

CREATE INDEX idx_lotes_codigo ON lotes_produccion(codigo_lote);
CREATE INDEX idx_lotes_granja_estado ON lotes_produccion(granja_id, estado);
CREATE INDEX idx_cerdas_caravana ON cerdas_reproductoras(caravana);
CREATE INDEX idx_cerdas_estado_fis ON cerdas_reproductoras(estado_fisiologico);
CREATE INDEX idx_servicios_fecha_parto ON servicios_inseminacion(fecha_parto_estimada);
CREATE INDEX idx_kardex_insumo_fecha ON movimientos_kardex(insumo_id, fecha_hora DESC);
CREATE INDEX idx_kardex_lote ON movimientos_kardex(lote_id);
CREATE INDEX idx_gastos_lote ON gastos_operativos_transacciones(lote_id, fecha_contable DESC);
CREATE INDEX idx_telemetria_sensor_fecha ON lecturas_telemetria_historico(sensor_id, fecha_hora DESC);
CREATE INDEX idx_alertas_estado ON alertas_operativas_desviaciones(estado, nivel_severidad);
