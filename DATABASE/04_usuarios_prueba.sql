-- ============================================================================
-- 04_usuarios_prueba.sql
-- Usuarios de PRUEBA para web y app móvil (SIN CORREO ELECTRÓNICO).
-- Solo utiliza: Nombre de Usuario, Nombre Completo y Contraseña.
--
-- Contraseña de acceso para los tres:  Prueba#2026
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Asegurar columna 'nombre_usuario' y quitar obligación de 'email'
ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS nombre_usuario VARCHAR(60);
ALTER TABLE usuarios_operadores ALTER COLUMN email DROP NOT NULL;

-- 2. Asegurar restricción UNIQUE en nombre_usuario si aún no existe
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_usuarios_nombre_usuario'
    ) THEN
        ALTER TABLE usuarios_operadores ADD CONSTRAINT uq_usuarios_nombre_usuario UNIQUE (nombre_usuario);
    END IF;
END $$;

-- 3. Columnas de control de acceso y seguridad
ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS debe_cambiar_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS ultimo_login TIMESTAMPTZ;
ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS intentos_fallidos INT NOT NULL DEFAULT 0;
ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS bloqueado_hasta TIMESTAMPTZ;

-- 4. Si ya existían registros previos con estos IDs, asignarles su nombre_usuario
UPDATE usuarios_operadores SET nombre_usuario = 'admin' WHERE id = 'd0000000-0000-0000-0000-0000000000a1';
UPDATE usuarios_operadores SET nombre_usuario = 'vet' WHERE id = 'd0000000-0000-0000-0000-0000000000a2';
UPDATE usuarios_operadores SET nombre_usuario = 'operario' WHERE id = 'd0000000-0000-0000-0000-0000000000a3';

-- 5. Insertar / Actualizar los 3 Usuarios (SIN EMAIL)
INSERT INTO usuarios_operadores
    (id, granja_id, nombre_usuario, nombre, password_hash, rol, matricula_profesional, activo, debe_cambiar_password)
VALUES
    ('d0000000-0000-0000-0000-0000000000a1', 'a0000000-0000-0000-0000-000000000001',
     'admin', 'Administrador',
     crypt('Prueba#2026', gen_salt('bf', 10)), 'administrador_financiero', NULL, TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000a2', 'a0000000-0000-0000-0000-000000000001',
     'vet', 'Veterinario',
     crypt('Prueba#2026', gen_salt('bf', 10)), 'director_veterinario', 'MV-0000', TRUE, FALSE),

    ('d0000000-0000-0000-0000-0000000000a3', 'a0000000-0000-0000-0000-000000000001',
     'operario', 'Operario',
     crypt('Prueba#2026', gen_salt('bf', 10)), 'operario_galpon', NULL, TRUE, FALSE)
ON CONFLICT (id) DO UPDATE
SET nombre_usuario         = EXCLUDED.nombre_usuario,
    nombre                 = EXCLUDED.nombre,
    password_hash          = EXCLUDED.password_hash,
    rol                    = EXCLUDED.rol,
    activo                 = TRUE,
    debe_cambiar_password  = FALSE,
    intentos_fallidos      = 0,
    bloqueado_hasta        = NULL;

-- 6. Verificación rápida (debe mostrar los 3 usuarios con password_ok = true)
SELECT 
    nombre_usuario, 
    nombre, 
    rol, 
    activo, 
    (password_hash = crypt('Prueba#2026', password_hash)) AS password_ok
FROM usuarios_operadores
WHERE nombre_usuario IN ('admin', 'vet', 'operario')
ORDER BY nombre_usuario;
