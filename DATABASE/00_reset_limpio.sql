-- ============================================================================
-- AGROPIG INTELLIGENCE SUITE - RESET MAESTRO LIMPIO (CERO DATOS FICTICIOS)
-- Recrea el esquema completo, infraestructura física base, catálogo e insumos,
-- vistas BI y los 3 accesos de rol sin cerdos ni transacciones falsas.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ----------------------------------------------------------------------------
-- 1. ELIMINACIÓN TOTAL DE TABLAS Y VISTAS PREVIAS
-- ----------------------------------------------------------------------------
DROP VIEW IF EXISTS v_radar_partos_inminentes CASCADE;
DROP VIEW IF EXISTS v_resumen_bi_lotes_activos CASCADE;
DROP VIEW IF EXISTS v_balance_kardex_silos CASCADE;
DROP VIEW IF EXISTS v_analisis_costo_kg_producido CASCADE;

DROP TABLE IF EXISTS registro_tratamientos_clinicos CASCADE;
DROP TABLE IF EXISTS cerdos_individuales CASCADE;
DROP TABLE IF EXISTS jaulas_corrales CASCADE;
DROP TABLE IF EXISTS tareas_actividades_granja CASCADE;
DROP TABLE IF EXISTS registro_bajas_mortalidad CASCADE;
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

-- ----------------------------------------------------------------------------
-- 2. DDL: ESQUEMA BASE DE DATOS
-- ----------------------------------------------------------------------------

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
    tipo_sistema VARCHAR(60) NOT NULL,
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
    rol VARCHAR(60) NOT NULL,
    matricula_profesional VARCHAR(60),
    telefono VARCHAR(30),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    debe_cambiar_password BOOLEAN NOT NULL DEFAULT FALSE,
    ultimo_login TIMESTAMPTZ,
    intentos_fallidos INT NOT NULL DEFAULT 0,
    bloqueado_hasta TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE lineas_geneticas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre VARCHAR(100) NOT NULL UNIQUE,
    casa_genetica VARCHAR(100) NOT NULL,
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
    tipo_reproductor VARCHAR(40) NOT NULL,
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
    fecha_ingreso_hato DATE DEFAULT CURRENT_DATE,
    paridad_acumulada INT NOT NULL DEFAULT 0,
    estado_fisiologico VARCHAR(50) NOT NULL DEFAULT 'vacia_espera_celo',
    estatus_sanitario VARCHAR(50) DEFAULT 'optimo',
    prolificidad_historica_media NUMERIC(4,2) DEFAULT 0.0,
    activa BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE servicios_inseminacion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    cerda_id UUID NOT NULL REFERENCES cerdas_reproductoras(id) ON DELETE CASCADE,
    verraco_semen_id UUID REFERENCES verracos_semen(id),
    operario_id UUID REFERENCES usuarios_operadores(id),
    numero_servicio_celo INT NOT NULL DEFAULT 1,
    fecha_servicio TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fecha_ecografia_estimada DATE,
    fecha_traslado_maternidad_estimada DATE,
    fecha_parto_estimada DATE,
    resultado_concepcion VARCHAR(40) DEFAULT 'pendiente_diagnostico',
    observaciones TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE partos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    servicio_id UUID REFERENCES servicios_inseminacion(id),
    cerda_id UUID NOT NULL REFERENCES cerdas_reproductoras(id) ON DELETE CASCADE,
    galpon_maternidad_id UUID REFERENCES galpones_naves(id),
    jaula_paridera VARCHAR(30) NOT NULL,
    fecha_hora_parto TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    nacidos_vivos INT NOT NULL DEFAULT 0,
    nacidos_muertos INT NOT NULL DEFAULT 0,
    momias INT NOT NULL DEFAULT 0,
    peso_camada_total_kg NUMERIC(6,2) NOT NULL DEFAULT 0,
    duracion_parto_horas NUMERIC(4,2),
    atendido_por_id UUID REFERENCES usuarios_operadores(id),
    calidad_calostro VARCHAR(30) DEFAULT 'excelente',
    observaciones TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE destetes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parto_id UUID NOT NULL REFERENCES partos(id) ON DELETE CASCADE,
    cerda_id UUID NOT NULL REFERENCES cerdas_reproductoras(id) ON DELETE CASCADE,
    fecha_destete DATE NOT NULL DEFAULT CURRENT_DATE,
    dias_lactancia INT NOT NULL,
    lechones_destetados INT NOT NULL,
    peso_total_destete_kg NUMERIC(8,2) NOT NULL,
    peso_promedio_lechone_kg NUMERIC(5,2) GENERATED ALWAYS AS (peso_total_destete_kg / NULLIF(lechones_destetados, 0)) STORED,
    condicion_corporal_cerda NUMERIC(3,1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE perdidas_perinatales (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parto_id UUID NOT NULL REFERENCES partos(id) ON DELETE CASCADE,
    fecha_hora TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    causa_muerte VARCHAR(100) NOT NULL,
    peso_lechone_kg NUMERIC(4,2),
    responsable_registro_id UUID REFERENCES usuarios_operadores(id)
);

CREATE TABLE lotes_produccion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    codigo_lote VARCHAR(50) NOT NULL UNIQUE,
    galpon_id UUID REFERENCES galpones_naves(id),
    etapa_zootecnica VARCHAR(50) NOT NULL DEFAULT 'engorde_intensivo',
    fase_actual VARCHAR(50) NOT NULL DEFAULT 'engorde_intensivo',
    fecha_inicio DATE NOT NULL DEFAULT CURRENT_DATE,
    fecha_ingreso_fase DATE NOT NULL DEFAULT CURRENT_DATE,
    fecha_cierre_estimada DATE,
    fecha_estimada_salida DATE,
    fecha_cierre_real DATE,
    cantidad_inicial_cabezas INT NOT NULL DEFAULT 0,
    cantidad_inicial INT NOT NULL DEFAULT 0,
    cantidad_actual_cabezas INT NOT NULL DEFAULT 0,
    cantidad_actual INT NOT NULL DEFAULT 0,
    bajas_acumuladas INT NOT NULL DEFAULT 0,
    mortalidad_acumulada_cabezas INT DEFAULT 0,
    peso_promedio_entrada_kg NUMERIC(6,2) NOT NULL DEFAULT 0,
    peso_promedio_actual_kg NUMERIC(6,2) DEFAULT 0,
    peso_ingreso_total_kg NUMERIC(10,2) NOT NULL DEFAULT 0,
    peso_actual_total_kg NUMERIC(10,2) NOT NULL DEFAULT 0,
    peso_objetivo_salida_kg NUMERIC(6,2) DEFAULT 115.0,
    linea_genetica_id UUID REFERENCES lineas_geneticas(id),
    procedencia_camada VARCHAR(150),
    alimento_acumulado_kg NUMERIC(12,2) DEFAULT 0,
    fcr_actual NUMERIC(4,2) DEFAULT 0,
    gmd_actual_g_dia NUMERIC(6,2) DEFAULT 0,
    presupuesto_asignado_usd NUMERIC(12,2) DEFAULT 0,
    costo_acumulado_usd NUMERIC(12,2) DEFAULT 0,
    estado VARCHAR(30) NOT NULL DEFAULT 'activo',
    responsable_id UUID REFERENCES usuarios_operadores(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE transiciones_etapa_lote (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id) ON DELETE CASCADE,
    etapa_origen VARCHAR(40) NOT NULL,
    etapa_destino VARCHAR(40) NOT NULL,
    fecha_transicion DATE NOT NULL DEFAULT CURRENT_DATE,
    cantidad_cabezas INT NOT NULL,
    peso_total_transferido_kg NUMERIC(10,2) NOT NULL,
    galpon_origen_id UUID REFERENCES galpones_naves(id),
    galpon_destino_id UUID REFERENCES galpones_naves(id),
    aprobado_por_id UUID REFERENCES usuarios_operadores(id)
);

CREATE TABLE pesajes_monitoreo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id) ON DELETE CASCADE,
    fecha_pesaje DATE NOT NULL DEFAULT CURRENT_DATE,
    tipo_pesaje VARCHAR(40) NOT NULL DEFAULT 'muestreo_estadistico',
    cabezas_pesadas INT NOT NULL,
    peso_muestra_total_kg NUMERIC(8,2) NOT NULL,
    peso_promedio_kg NUMERIC(6,2) GENERATED ALWAYS AS (peso_muestra_total_kg / NULLIF(cabezas_pesadas, 0)) STORED,
    uniformidad_camada_pct NUMERIC(5,2),
    registrado_por_id UUID REFERENCES usuarios_operadores(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE mortalidad_bajas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id) ON DELETE CASCADE,
    fecha_baja TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cantidad_cabezas INT NOT NULL DEFAULT 1,
    peso_estimado_kg NUMERIC(6,2),
    causa_diagnostico VARCHAR(150) NOT NULL,
    necropsia_realizada BOOLEAN DEFAULT FALSE,
    hallazgos_necropsia TEXT,
    reportado_por_id UUID REFERENCES usuarios_operadores(id)
);

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
    nombre VARCHAR(150) NOT NULL,
    unidad_medida VARCHAR(20) NOT NULL,
    stock_actual NUMERIC(12,2) NOT NULL DEFAULT 0,
    stock_minimo_seguridad NUMERIC(12,2) NOT NULL DEFAULT 0,
    costo_unitario_promedio_usd NUMERIC(10,4) NOT NULL DEFAULT 0,
    silo_deposito_id UUID REFERENCES silos_depositos(id),
    estado_stock VARCHAR(50) NOT NULL DEFAULT 'optimo',
    descripcion TEXT,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE movimientos_kardex (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    insumo_id UUID NOT NULL REFERENCES insumos_catalogo(id),
    lote_id UUID REFERENCES lotes_produccion(id),
    silo_id UUID REFERENCES silos_depositos(id),
    tipo_movimiento VARCHAR(50) NOT NULL,
    fecha_hora TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    cantidad NUMERIC(12,2) NOT NULL,
    costo_unitario_usd NUMERIC(10,4) NOT NULL,
    valor_total_usd NUMERIC(12,2) GENERATED ALWAYS AS (ROUND(cantidad * costo_unitario_usd, 2)) STORED,
    saldo_stock_resultante NUMERIC(12,2) DEFAULT 0,
    documento_referencia VARCHAR(120),
    numero_documento VARCHAR(80),
    proveedor VARCHAR(150),
    responsable_id UUID REFERENCES usuarios_operadores(id),
    observaciones TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE centros_costo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    codigo VARCHAR(30) NOT NULL UNIQUE,
    nombre VARCHAR(100) NOT NULL,
    porcentaje_peso_presupuestal NUMERIC(5,2) DEFAULT 0
);

CREATE TABLE presupuestos_lote (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id) ON DELETE CASCADE,
    centro_costo_id UUID NOT NULL REFERENCES centros_costo(id),
    rubro_nombre VARCHAR(120) NOT NULL DEFAULT 'Rubro Operativo',
    monto_estimado_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
    monto_ejecutado_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
    porcentaje_desviacion NUMERIC(5,2) DEFAULT 0,
    estado_bi VARCHAR(40) DEFAULT 'optimo',
    monto_presupuestado_usd NUMERIC(12,2),
    costo_objetivo_por_kg_usd NUMERIC(6,3),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_lote_centro_costo UNIQUE (lote_id, centro_costo_id)
);

CREATE TABLE gastos_operativos_transacciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    lote_id UUID REFERENCES lotes_produccion(id),
    centro_costo_id UUID NOT NULL REFERENCES centros_costo(id),
    tipo_movimiento VARCHAR(40) NOT NULL DEFAULT 'gasto_operativo',
    fecha_contable DATE NOT NULL DEFAULT CURRENT_DATE,
    monto_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
    monto_total_usd NUMERIC(12,2) DEFAULT 0,
    concepto_detalle TEXT NOT NULL DEFAULT '',
    concepto VARCHAR(200) DEFAULT '',
    proveedor_factura VARCHAR(150),
    proveedor_beneficiario VARCHAR(150),
    numero_factura_soporte VARCHAR(100),
    impacto_desviacion_pct NUMERIC(5,2) DEFAULT 0,
    registrado_por_id UUID REFERENCES usuarios_operadores(id),
    autorizado_por_id UUID REFERENCES usuarios_operadores(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE liquidaciones_ventas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id),
    comprador_frigorifico VARCHAR(150) NOT NULL DEFAULT 'Frigorífico Matriz',
    cliente_comprador VARCHAR(150) DEFAULT 'Frigorífico Matriz',
    fecha_liquidacion DATE NOT NULL DEFAULT CURRENT_DATE,
    fecha_venta DATE DEFAULT CURRENT_DATE,
    cabezas_vendidas INT NOT NULL DEFAULT 0,
    cantidad_cabezas INT DEFAULT 0,
    peso_en_pie_total_kg NUMERIC(10,2) NOT NULL DEFAULT 0,
    peso_total_kg NUMERIC(10,2) DEFAULT 0,
    peso_canal_total_kg NUMERIC(10,2),
    rendimiento_canal_pct NUMERIC(5,2),
    precio_por_kg_en_pie_usd NUMERIC(6,3) NOT NULL DEFAULT 0,
    precio_kg_usd NUMERIC(6,2) DEFAULT 0,
    ingreso_bruto_total_usd NUMERIC(12,2) NOT NULL DEFAULT 0,
    monto_bruto_usd NUMERIC(12,2) DEFAULT 0,
    descuentos_transporte_usd NUMERIC(10,2) DEFAULT 0,
    ingreso_neto_usd NUMERIC(12,2) DEFAULT 0,
    costo_total_producido_usd NUMERIC(12,2) DEFAULT 0,
    margen_bruto_usd NUMERIC(12,2) DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE protocolos_sanitarios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo_plan VARCHAR(30) NOT NULL UNIQUE,
    nombre VARCHAR(120) NOT NULL,
    etapa_aplicacion VARCHAR(50) NOT NULL,
    dia_vida_aplicacion INT NOT NULL,
    tipo_biologico VARCHAR(60) NOT NULL,
    dosis_recomendada VARCHAR(60) NOT NULL,
    via_administracion VARCHAR(50) NOT NULL
);

CREATE TABLE aplicaciones_sanitarias_lote (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lote_id UUID NOT NULL REFERENCES lotes_produccion(id) ON DELETE CASCADE,
    protocolo_id UUID REFERENCES protocolos_sanitarios(id),
    insumo_id UUID NOT NULL REFERENCES insumos_catalogo(id),
    fecha_aplicacion DATE NOT NULL DEFAULT CURRENT_DATE,
    dosis_aplicada_total NUMERIC(10,2) NOT NULL,
    unidad_medida VARCHAR(20) NOT NULL,
    costo_aplicacion_usd NUMERIC(10,2) NOT NULL,
    responsable_veterinario_id UUID REFERENCES usuarios_operadores(id),
    tiempo_retiro_dias INT NOT NULL DEFAULT 0,
    fecha_fin_retiro DATE,
    observaciones TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE sensores_telemetria (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    galpon_id UUID NOT NULL REFERENCES galpones_naves(id) ON DELETE CASCADE,
    codigo_sensor VARCHAR(50) NOT NULL UNIQUE,
    tipo_variable VARCHAR(40) NOT NULL,
    unidad_medida VARCHAR(20) NOT NULL,
    valor_minimo_umbral NUMERIC(6,2),
    valor_maximo_umbral NUMERIC(6,2),
    estado_operativo VARCHAR(20) DEFAULT 'activo',
    ultima_lectura_valor NUMERIC(6,2),
    ultima_lectura_fecha TIMESTAMPTZ
);

CREATE TABLE lecturas_telemetria_historico (
    id BIGSERIAL PRIMARY KEY,
    sensor_id UUID NOT NULL REFERENCES sensores_telemetria(id) ON DELETE CASCADE,
    valor_leido NUMERIC(6,2) NOT NULL,
    fecha_hora TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE alertas_operativas_desviaciones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    lote_id UUID REFERENCES lotes_produccion(id),
    tipo_alerta VARCHAR(60) NOT NULL,
    nivel_severidad VARCHAR(30) NOT NULL DEFAULT 'preventiva',
    descripcion TEXT NOT NULL,
    valor_detectado VARCHAR(60),
    umbral_esperado VARCHAR(60),
    estado VARCHAR(30) NOT NULL DEFAULT 'abierta',
    fecha_deteccion TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resuelto_por_id UUID REFERENCES usuarios_operadores(id),
    fecha_resolucion TIMESTAMPTZ
);

CREATE TABLE sync_outbox_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_sync_id VARCHAR(100) NOT NULL UNIQUE,
    dispositivo_id VARCHAR(100),
    operario_id UUID REFERENCES usuarios_operadores(id),
    entidad_tipo VARCHAR(60) NOT NULL,
    operacion VARCHAR(30) NOT NULL DEFAULT 'INSERT',
    payload_json JSONB NOT NULL,
    fecha_cliente TIMESTAMPTZ NOT NULL,
    fecha_servidor TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    estado_procesamiento VARCHAR(30) NOT NULL DEFAULT 'procesado',
    mensaje_error TEXT
);

CREATE TABLE jaulas_corrales (
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

CREATE TABLE cerdos_individuales (
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
);

CREATE TABLE registro_tratamientos_clinicos (
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
);

CREATE TABLE tareas_actividades_granja (
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

CREATE TABLE registro_bajas_mortalidad (
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
);

-- Índices de Rendimiento
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
CREATE INDEX idx_cerdos_ind_crotal ON cerdos_individuales(crotal);
CREATE INDEX idx_tareas_act_fecha ON tareas_actividades_granja(fecha_programada, estado);

-- ----------------------------------------------------------------------------
-- 3. FUNCIONES, TRIGGERS Y VISTAS BI
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_calcular_hitos_reproductivos()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.fecha_ecografia_estimada IS NULL THEN
        NEW.fecha_ecografia_estimada := NEW.fecha_servicio + INTERVAL '28 days';
    END IF;
    IF NEW.fecha_traslado_maternidad_estimada IS NULL THEN
        NEW.fecha_traslado_maternidad_estimada := NEW.fecha_servicio + INTERVAL '107 days';
    END IF;
    IF NEW.fecha_parto_estimada IS NULL THEN
        NEW.fecha_parto_estimada := NEW.fecha_servicio + INTERVAL '114 days';
    END IF;
    UPDATE cerdas_reproductoras
    SET estado_fisiologico = 'servida_gestante', updated_at = NOW()
    WHERE id = NEW.cerda_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_calcular_hitos_reproductivos ON servicios_inseminacion;
CREATE TRIGGER trg_calcular_hitos_reproductivos
BEFORE INSERT OR UPDATE OF fecha_servicio ON servicios_inseminacion
FOR EACH ROW EXECUTE FUNCTION fn_calcular_hitos_reproductivos();

CREATE OR REPLACE FUNCTION fn_actualizar_stock_kardex()
RETURNS TRIGGER AS $$
DECLARE
    v_factor INT := 1;
BEGIN
    IF NEW.tipo_movimiento LIKE 'salida%' OR NEW.tipo_movimiento = 'merma_perdida' THEN
        v_factor := -1;
    ELSE
        v_factor := 1;
    END IF;

    UPDATE insumos_catalogo
    SET stock_actual = stock_actual + (NEW.cantidad * v_factor), updated_at = NOW()
    WHERE id = NEW.insumo_id;

    IF NEW.silo_id IS NOT NULL THEN
        UPDATE silos_depositos
        SET nivel_actual_kg = GREATEST(0, nivel_actual_kg + (NEW.cantidad * v_factor)),
            ultima_sincronizacion = NOW()
        WHERE id = NEW.silo_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_actualizar_stock_kardex ON movimientos_kardex;
CREATE TRIGGER trg_actualizar_stock_kardex
AFTER INSERT ON movimientos_kardex
FOR EACH ROW EXECUTE FUNCTION fn_actualizar_stock_kardex();

CREATE OR REPLACE FUNCTION fn_auditar_presupuesto_gastos()
RETURNS TRIGGER AS $$
DECLARE
    v_total_ejecutado NUMERIC(12,2);
    v_presupuesto NUMERIC(12,2);
    v_lote_codigo VARCHAR(50);
BEGIN
    IF NEW.lote_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT COALESCE(SUM(monto_total_usd), 0) INTO v_total_ejecutado
    FROM gastos_operativos_transacciones
    WHERE lote_id = NEW.lote_id AND centro_costo_id = NEW.centro_costo_id;

    SELECT monto_presupuestado_usd INTO v_presupuesto
    FROM presupuestos_lote
    WHERE lote_id = NEW.lote_id AND centro_costo_id = NEW.centro_costo_id;

    IF v_presupuesto IS NOT NULL AND v_total_ejecutado > v_presupuesto THEN
        SELECT codigo_lote INTO v_lote_codigo FROM lotes_produccion WHERE id = NEW.lote_id;
        INSERT INTO alertas_operativas_desviaciones (
            granja_id, lote_id, tipo_alerta, nivel_severidad, descripcion, valor_detectado, umbral_esperado
        ) VALUES (
            NEW.granja_id, NEW.lote_id, 'desviacion_financiera', 'critica',
            'Sobregiro en Lote ' || v_lote_codigo || ': Ejecutado $' || v_total_ejecutado || ' vs Presupuesto $' || v_presupuesto,
            v_total_ejecutado::text, v_presupuesto::text
        );
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_auditar_presupuesto_gastos ON gastos_operativos_transacciones;
CREATE TRIGGER trg_auditar_presupuesto_gastos
AFTER INSERT OR UPDATE ON gastos_operativos_transacciones
FOR EACH ROW EXECUTE FUNCTION fn_auditar_presupuesto_gastos();

-- Vistas BI
CREATE OR REPLACE VIEW v_analisis_costo_kg_producido AS
WITH resumen_costos AS (
    SELECT 
        l.id AS lote_id,
        l.codigo_lote,
        l.fase_actual,
        l.cantidad_actual,
        (l.peso_actual_total_kg - l.peso_ingreso_total_kg) AS kg_ganados_lote,
        COALESCE(SUM(g.monto_total_usd), 0) AS total_gastos_operativos_usd,
        COALESCE(SUM(k.valor_total_usd), 0) AS total_costo_alimento_kardex_usd
    FROM lotes_produccion l
    LEFT JOIN gastos_operativos_transacciones g ON g.lote_id = l.id
    LEFT JOIN movimientos_kardex k ON k.lote_id = l.id AND k.tipo_movimiento LIKE 'salida%'
    WHERE l.estado = 'activo'
    GROUP BY l.id, l.codigo_lote, l.fase_actual, l.cantidad_actual, l.peso_actual_total_kg, l.peso_ingreso_total_kg
)
SELECT 
    lote_id,
    codigo_lote,
    fase_actual,
    cantidad_actual,
    kg_ganados_lote,
    (total_gastos_operativos_usd + total_costo_alimento_kardex_usd) AS costo_total_acumulado_usd,
    ROUND(
        (total_gastos_operativos_usd + total_costo_alimento_kardex_usd) / NULLIF(kg_ganados_lote, 0),
        3
    ) AS costo_por_kg_producido_usd,
    ROUND(
        ((total_gastos_operativos_usd + total_costo_alimento_kardex_usd) / NULLIF(kg_ganados_lote, 0)) - 1.480,
        3
    ) AS variacion_vs_target_usd
FROM resumen_costos;

CREATE OR REPLACE VIEW v_resumen_bi_lotes_activos AS
SELECT 
    l.id AS lote_id,
    l.codigo_lote,
    g.nombre AS galpon_nombre,
    l.fase_actual,
    l.cantidad_actual,
    l.fcr_actual,
    l.gmd_actual_g_dia,
    l.mortalidad_acumulada_cabezas,
    ROUND((l.mortalidad_acumulada_cabezas::numeric / NULLIF(l.cantidad_inicial, 0)) * 100, 2) AS tasa_mortalidad_pct,
    COALESCE(SUM(p.monto_presupuestado_usd), 0) AS presupuesto_total_usd,
    COALESCE(SUM(gt.monto_total_usd), 0) AS ejecutado_total_usd,
    ROUND(
        (COALESCE(SUM(gt.monto_total_usd), 0) / NULLIF(COALESCE(SUM(p.monto_presupuestado_usd), 0), 0)) * 100,
        1
    ) AS ejecucion_presupuesto_pct
FROM lotes_produccion l
LEFT JOIN galpones_naves g ON g.id = l.galpon_id
LEFT JOIN presupuestos_lote p ON p.lote_id = l.id
LEFT JOIN gastos_operativos_transacciones gt ON gt.lote_id = l.id
WHERE l.estado = 'activo'
GROUP BY l.id, l.codigo_lote, g.nombre, l.fase_actual, l.cantidad_actual, l.fcr_actual, l.gmd_actual_g_dia, l.mortalidad_acumulada_cabezas, l.cantidad_inicial;

CREATE OR REPLACE VIEW v_radar_partos_inminentes AS
SELECT 
    s.id AS servicio_id,
    c.caravana AS crotal_cerda,
    c.rfid,
    c.jaula_numero,
    g.nombre AS galpon_actual,
    s.fecha_servicio::date AS fecha_inseminacion,
    s.fecha_parto_estimada,
    (s.fecha_parto_estimada - CURRENT_DATE) AS dias_para_parto,
    CASE 
        WHEN (s.fecha_parto_estimada - CURRENT_DATE) < 0 THEN 'Parto Retrasado / Crítico'
        WHEN (s.fecha_parto_estimada - CURRENT_DATE) <= 2 THEN 'Parto Inminente (48h)'
        WHEN (s.fecha_parto_estimada - CURRENT_DATE) <= 7 THEN 'Semana de Parición'
        ELSE 'Gestación Regular'
    END AS estado_alerta
FROM servicios_inseminacion s
JOIN cerdas_reproductoras c ON c.id = s.cerda_id
LEFT JOIN galpones_naves g ON g.id = c.galpon_id
WHERE s.resultado_concepcion IN ('confirmada_positiva', 'pendiente_diagnostico')
  AND s.fecha_parto_estimada BETWEEN (CURRENT_DATE - INTERVAL '3 days') AND (CURRENT_DATE + INTERVAL '14 days')
ORDER BY s.fecha_parto_estimada ASC;

CREATE OR REPLACE VIEW v_balance_kardex_silos AS
SELECT 
    s.id AS silo_id,
    s.codigo AS silo_codigo,
    s.nombre AS silo_nombre,
    s.capacidad_maxima_kg,
    s.nivel_actual_kg,
    ROUND((s.nivel_actual_kg / NULLIF(s.capacidad_maxima_kg, 0)) * 100, 1) AS porcentaje_ocupacion,
    s.tipo_insumo_principal,
    s.estado_sensor,
    s.ultima_sincronizacion
FROM silos_depositos s;

-- ----------------------------------------------------------------------------
-- 4. INFRAESTRUCTURA FÍSICA Y CATÁLOGOS BASE (CERO REGISTROS FICTICIOS)
-- ----------------------------------------------------------------------------

-- Granja Principal
INSERT INTO granjas (id, codigo, nombre, ubicacion, superficie_hectareas, capacidad_total_cabezas)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'GRANJA-SJ-01',
    'Granja San José (Principal)',
    'Valle Central Porcino, Km 42 Carretera Panamericana',
    28.5,
    3200
) ON CONFLICT (codigo) DO NOTHING;

-- Galpones y Naves
INSERT INTO galpones_naves (id, granja_id, codigo, nombre, tipo_sistema, capacidad_cabezas, temperatura_ambiente_celsius, humedad_relativa_pct)
VALUES 
    ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'GALP-01', 'Galpón 1 - Maternidad & Lactancia', 'climatizado_automatizado', 450, 24.0, 60.0),
    ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'GALP-02', 'Galpón 2 - Gestación Confirmada', 'ventilacion_natural', 500, 22.5, 62.0),
    ('b0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'GALP-03', 'Galpón 3 - Precebo / Destete', 'presion_negativa', 750, 26.0, 58.0),
    ('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'GALP-04', 'Galpón 4 - Crecimiento y Engorde', 'tunel_viento', 1500, 21.5, 65.0)
ON CONFLICT (granja_id, codigo) DO NOTHING;

-- Silos y Depósitos (Capacidades reales, nivel inicial 0 kg)
INSERT INTO silos_depositos (id, granja_id, codigo, nombre, capacidad_maxima_kg, nivel_actual_kg, tipo_insumo_principal)
VALUES 
    ('c0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'SILO-A1', 'Silo A-1 (Engorde)', 48000.0, 0.0, 'Concentrado Engorde Fase 2 Pelleteado'),
    ('c0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'SILO-A2', 'Silo A-2 (Precebo)', 35000.0, 0.0, 'Preiniciador II Extruido'),
    ('c0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'SILO-B1', 'Silo B-1 (Maternidad / Gestación)', 25000.0, 0.0, 'Concentrado Lactancia Alta Densidad')
ON CONFLICT (codigo) DO NOTHING;

-- Jaulas y Corrales Técnicos (Ocupación inicial 0)
INSERT INTO jaulas_corrales (granja_id, codigo, nombre, etapa_zootecnica, capacidad_maxima, ocupacion_actual, tipo_instalacion)
VALUES 
    ('a0000000-0000-0000-0000-000000000001', 'J-MAT-01', 'Maternidad A - Jaula 01', 'Maternidad', 15, 0, 'Jaula paridera con piso térmico y lechonera infrarroja'),
    ('a0000000-0000-0000-0000-000000000001', 'J-MAT-02', 'Maternidad A - Jaula 02', 'Maternidad', 15, 0, 'Jaula paridera con piso térmico y lechonera infrarroja'),
    ('a0000000-0000-0000-0000-000000000001', 'J-MAT-03', 'Maternidad B - Jaula 03', 'Maternidad', 15, 0, 'Jaula paridera con piso térmico y lechonera infrarroja'),
    ('a0000000-0000-0000-0000-000000000001', 'C-PREC-01', 'Precebo Nave 1 - Corral 01', 'Precebo', 30, 0, 'Corral slat plástico térmico con tolva seco-húmedo'),
    ('a0000000-0000-0000-0000-000000000001', 'C-PREC-02', 'Precebo Nave 1 - Corral 02', 'Precebo', 30, 0, 'Corral slat plástico térmico con tolva seco-húmedo'),
    ('a0000000-0000-0000-0000-000000000001', 'C-PREC-03', 'Precebo Nave 2 - Corral 03', 'Precebo', 30, 0, 'Corral slat plástico térmico con comedero regulable'),
    ('a0000000-0000-0000-0000-000000000001', 'C-CREC-01', 'Crecimiento Nave 1 - Corral 01', 'Crecimiento', 25, 0, 'Corral semieslatado con tolva redonda y doble bebedero'),
    ('a0000000-0000-0000-0000-000000000001', 'C-CREC-02', 'Crecimiento Nave 1 - Corral 02', 'Crecimiento', 25, 0, 'Corral semieslatado con tolva redonda y doble bebedero'),
    ('a0000000-0000-0000-0000-000000000001', 'C-CREC-03', 'Crecimiento Nave 2 - Corral 03', 'Crecimiento', 25, 0, 'Corral semieslatado con tolva redonda y doble bebedero'),
    ('a0000000-0000-0000-0000-000000000001', 'C-ENG-01', 'Engorde Nave 1 - Corral 01', 'Engorde', 20, 0, 'Corral terminación intensiva tolva inox y aspersores'),
    ('a0000000-0000-0000-0000-000000000001', 'C-ENG-02', 'Engorde Nave 1 - Corral 02', 'Engorde', 20, 0, 'Corral terminación intensiva tolva inox y aspersores'),
    ('a0000000-0000-0000-0000-000000000001', 'C-ENG-03', 'Engorde Nave 2 - Corral 03', 'Engorde', 20, 0, 'Corral terminación intensiva tolva inox y aspersores'),
    ('a0000000-0000-0000-0000-000000000001', 'J-GEST-01', 'Gestación Nave 1 - Jaula 01', 'Gestación', 1, 0, 'Jaula individual de inseminación y control gestacional'),
    ('a0000000-0000-0000-0000-000000000001', 'J-GEST-02', 'Gestación Nave 1 - Jaula 02', 'Gestación', 1, 0, 'Jaula individual de inseminación y control gestacional'),
    ('a0000000-0000-0000-0000-000000000001', 'C-GEST-01', 'Gestación Colectiva - Corral 01', 'Gestación', 8, 0, 'Corral dinámico con estación de alimentación electrónica')
ON CONFLICT (codigo) DO NOTHING;

-- Líneas Genéticas de Referencia Zootécnica
INSERT INTO lineas_geneticas (id, nombre, casa_genetica, potencial_gmd_g_dia, fcr_teorico_objetivo, prolificidad_objetivo_nac_vivos, descripcion)
VALUES 
    ('40000000-0000-0000-0000-000000000001', 'Topigs Norsvin TN70', 'Topigs Norsvin', 980, 2.38, 15.5, 'Línea materna hiperprolífica con alta capacidad lechera y docilidad'),
    ('40000000-0000-0000-0000-000000000002', 'PIC Camborough', 'PIC', 960, 2.42, 14.8, 'Eficiencia en costo de lechón destetado y rusticidad ambiental'),
    ('40000000-0000-0000-0000-000000000003', 'DanBred Hybrid', 'DanBred', 1010, 2.32, 16.5, 'Máxima prolificidad mundial con alto índice de magro en canal')
ON CONFLICT (nombre) DO NOTHING;

-- Categorías de Insumos
INSERT INTO categorias_insumos (id, codigo, nombre, descripcion)
VALUES 
    ('e0000000-0000-0000-0000-000000000001', 'ALIM', 'Alimento Balanceado', 'Fórmulas terminadas precebo, engorde y cerdas'),
    ('e0000000-0000-0000-0000-000000000002', 'MP', 'Materias Primas / Granos', 'Maíz, torta de soya, harina de pescado'),
    ('e0000000-0000-0000-0000-000000000003', 'PRE', 'Preiniciadores & Aditivos', 'Preiniciadores de alta digestibilidad y acidificantes'),
    ('e0000000-0000-0000-0000-000000000004', 'MED', 'Medicamentos & Vacunas', 'Biológicos, antibióticos, antiparasitarios'),
    ('e0000000-0000-0000-0000-000000000005', 'BIO', 'Desinfectantes & Bioseguridad', 'Detergentes, cal viva, amonios cuaternarios')
ON CONFLICT (codigo) DO NOTHING;

-- Centros de Costo
INSERT INTO centros_costo (id, granja_id, codigo, nombre, porcentaje_peso_presupuestal)
VALUES 
    ('f0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'CC-ALIM', 'Alimento Balanceado & Nutrición', 62.0),
    ('f0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'CC-NOM', 'Mano de Obra & Operarios Galpón', 18.0),
    ('f0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'CC-SERV', 'Servicios Generales (Energía, Agua, Gas)', 8.0),
    ('f0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000001', 'CC-SAN', 'Sanidad & Plan Vacunal', 6.0),
    ('f0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000001', 'CC-VET', 'Servicios Veterinarios & Asistencia Externa', 4.0),
    ('f0000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000001', 'CC-MANT', 'Mantenimiento & Reparaciones', 2.0)
ON CONFLICT (codigo) DO NOTHING;

-- Catálogo de Insumos Médicos y Kit Descartable (para uso clínico real)
INSERT INTO insumos_catalogo (
    granja_id, categoria_id, codigo_sku, nombre, unidad_medida, stock_actual, stock_minimo_seguridad, costo_unitario_promedio_usd, activo
)
SELECT 
    'a0000000-0000-0000-0000-000000000001', c.id, d.sku, d.nombre, d.unidad, d.stock, d.stock_min, d.costo, true
FROM (VALUES
    ('MED-HIE-100', 'Hierro Dextrano Frasco 100ml', 'ml', 1000.0, 100.0, 0.0800),
    ('MED-OXI-100', 'Oxitetraciclina L.A. 200 Frasco 100ml', 'ml', 800.0, 100.0, 0.1200),
    ('MED-FLU-100', 'Flunixin Meglumine 5% Frasco 100ml', 'ml', 600.0, 80.0, 0.1500),
    ('MED-AMO-100', 'Amoxicilina Trihidrato L.A. 100ml', 'ml', 500.0, 50.0, 0.1100),
    ('MED-JER-05ML', 'Jeringa Descartable 5 ml con Émbolo Luer', 'ud', 450.0, 50.0, 0.1200),
    ('MED-AGU-16G', 'Aguja Hipodérmica Veterinaria 16G x 1.5 Pulgada', 'ud', 900.0, 100.0, 0.0600),
    ('MED-YOD-10', 'Solución Yodo Povidona 10% Antiséptico Tópico', 'ml', 2500.0, 200.0, 0.0150),
    ('MED-TOR-ALG', 'Torunda de Algodón Antiséptico / Gasa Estéril', 'ud', 850.0, 100.0, 0.0300)
) AS d(sku, nombre, unidad, stock, stock_min, costo)
CROSS JOIN (SELECT id FROM categorias_insumos WHERE codigo = 'MED' LIMIT 1) c
ON CONFLICT (codigo_sku) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 5. USUARIOS DE ACCESO INICIALES (ADMIN, VETERINARIO, OPERARIO)
-- Cuentas operativas listas para ingreso web y app móvil
-- ----------------------------------------------------------------------------

INSERT INTO usuarios_operadores
    (id, granja_id, nombre_usuario, nombre, email, password_hash, rol, matricula_profesional, activo, debe_cambiar_password)
VALUES
    ('d0000000-0000-0000-0000-0000000000a1', 'a0000000-0000-0000-0000-000000000001',
     'admin', 'Administrador', 'admin@agropig.com',
     crypt('Prueba#2026', gen_salt('bf', 10)), 'administrador_financiero', 'DIR-001', TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000a2', 'a0000000-0000-0000-0000-000000000001',
     'vet', 'Veterinario', 'veterinaria@agropig.com',
     crypt('Prueba#2026', gen_salt('bf', 10)), 'director_veterinario', 'MVZ-9402', TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000a3', 'a0000000-0000-0000-0000-000000000001',
     'operario', 'Operario', 'operario@agropig.com',
     crypt('Prueba#2026', gen_salt('bf', 10)), 'operario_galpon', NULL, TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000a4', 'a0000000-0000-0000-0000-000000000001',
     'luisenrique', 'Luis Enrique (Administrador)', 'luisenrique@granjasanjose.com',
     crypt('LuisAdmin2026', gen_salt('bf', 10)), 'administrador_financiero', 'DIR-002', TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000a5', 'a0000000-0000-0000-0000-000000000001',
     'gerente', 'Gerencia General', 'gerencia@granjasanjose.com',
     crypt('Granja2026', gen_salt('bf', 10)), 'administrador_financiero', 'DIR-003', TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000a6', 'a0000000-0000-0000-0000-000000000001',
     'luis', 'Luis Admin', 'luis@agropig.com',
     crypt('Prueba#2026', gen_salt('bf', 10)), 'administrador_financiero', 'DIR-004', TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000a7', 'a0000000-0000-0000-0000-000000000001',
     'junior', 'Junior MVZ', 'veterinaria@agropig.com',
     crypt('Prueba#2026', gen_salt('bf', 10)), 'director_veterinario', 'MVZ-9402', TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000a8', 'a0000000-0000-0000-0000-000000000001',
     'veterinario', 'Médico Veterinario Titular', 'vet.titular@granjasanjose.com',
     crypt('VetGranja2026', gen_salt('bf', 10)), 'director_veterinario', 'MVZ-9501', TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000a9', 'a0000000-0000-0000-0000-000000000001',
     'jorgejr', 'Jorge Jr.', 'operario@agropig.com',
     crypt('Prueba#2026', gen_salt('bf', 10)), 'operario_galpon', NULL, TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000aa', 'a0000000-0000-0000-0000-000000000001',
     'encargado', 'Encargado de Galpones', 'campo@granjasanjose.com',
     crypt('Campo2026', gen_salt('bf', 10)), 'operario_galpon', NULL, TRUE, FALSE)
ON CONFLICT (nombre_usuario) DO UPDATE
SET nombre                 = EXCLUDED.nombre,
    email                  = EXCLUDED.email,
    password_hash          = EXCLUDED.password_hash,
    rol                    = EXCLUDED.rol,
    matricula_profesional  = EXCLUDED.matricula_profesional,
    activo                 = TRUE,
    debe_cambiar_password  = FALSE,
    intentos_fallidos      = 0,
    bloqueado_hasta        = NULL;

-- ============================================================================
-- FIN DEL RESET LIMPIO. BASE DE DATOS PREPARADA CON CERO DATOS FICTICIOS.
-- ============================================================================
