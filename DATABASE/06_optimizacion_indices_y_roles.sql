-- ============================================================================
-- AGROPIG INTELLIGENCE SUITE - MIGRACIÓN IDEMPOTENTE 06
-- Índices compuestos para optimizar consultas por rol, fecha, catálogos y Sync
-- ============================================================================

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
    ON usuarios_operadores (lower(nombre_usuario));
