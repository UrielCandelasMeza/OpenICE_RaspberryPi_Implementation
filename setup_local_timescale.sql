-- =============================================================================
-- Script de Inicialización de TimescaleDB para Dispositivos Headless (Modo Local)
-- =============================================================================
-- Este script configura la base de datos local del dispositivo, incluyendo
-- tanto las tablas del nuevo diseño como las tablas legacy utilizadas por
-- la lógica interna de OpenICE (AbstractDevice, OpenEMRTestApplication, etc.).
--
-- Para evitar saturar el almacenamiento local (tarjeta SD), todas las tablas
-- de series temporales (incluidas las legacy) se definen como Hypertables de
-- TimescaleDB con compresión automática y políticas de retención de 3 días.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. CREACIÓN DE USUARIO Y BASE DE DATOS (Ejecutar como superusuario 'postgres')
-- -----------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'openice_local_user') THEN
        CREATE ROLE openice_local_user WITH LOGIN PASSWORD 'openice_local_secure_pass';
    END IF;
END
$$;

-- Crear base de datos si no existe
SELECT 'CREATE DATABASE openice_local'
WHERE NOT EXISTS (SELECT FROM pg_databases WHERE datname = 'openice_local')
\gexec

-- Conectar a la base de datos local
\c openice_local;

-- Habilitar la extensión TimescaleDB
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Asignar permisos al usuario local
GRANT ALL PRIVILEGES ON SCHEMA public TO openice_local_user;


-- -----------------------------------------------------------------------------
-- 2. TABLAS DE METADATOS Y CATÁLOGOS RELACIONALES
-- -----------------------------------------------------------------------------

-- Tabla de dispositivos (compatible con el campo device_id y el alias legacy 'udi')
CREATE TABLE IF NOT EXISTS devices (
    device_id VARCHAR(64) PRIMARY KEY,
    udi VARCHAR(64) GENERATED ALWAYS AS (device_id) STORED UNIQUE, -- Alias legacy compatible con joins pd.udi = d.udi
    manufacturer VARCHAR(128) NOT NULL,
    model VARCHAR(128) NOT NULL,
    serial_number VARCHAR(128),
    connection_type VARCHAR(32) NOT NULL,
    build_info VARCHAR(128),
    operating_system VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS metric_types (
    metric_id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    default_unit VARCHAR(32) NOT NULL,
    loinc_code VARCHAR(32),
    ucum_code VARCHAR(32)
);

-- Registro de asociación paciente-dispositivo utilizado por OpenICE (PatientInfoController/OpenEMR)
CREATE TABLE IF NOT EXISTS patientdevice (
    mrn VARCHAR(64) NOT NULL,
    udi VARCHAR(64) NOT NULL,
    associated BIGINT NOT NULL,      -- Epoch timestamp en segundos (System.currentTimeMillis()/1000)
    dissociated BIGINT               -- Epoch timestamp en segundos, nullable
);


-- -----------------------------------------------------------------------------
-- 3. TABLAS DE SERIES TEMPORALES Y LOGS (Hypertables de TimescaleDB)
-- -----------------------------------------------------------------------------

-- A. Registros de conectividad local
CREATE TABLE IF NOT EXISTS device_connectivity_events (
    time_tick TIMESTAMP WITH TIME ZONE NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    info TEXT,
    com_port VARCHAR(32)
);

-- B. Valores numéricos del nuevo diseño (time_tick TIMESTAMP)
CREATE TABLE IF NOT EXISTS vital_values (
    time_tick TIMESTAMP WITH TIME ZONE NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    metric_id VARCHAR(64) NOT NULL,
    instance_id INT NOT NULL DEFAULT 0,
    patient_id VARCHAR(64) DEFAULT 'OFFLINE_PATIENT',
    unit_id VARCHAR(32),
    vital_value DOUBLE PRECISION NOT NULL
);

-- C. Ondas de alta frecuencia del nuevo diseño (time_tick TIMESTAMP)
CREATE TABLE IF NOT EXISTS waveform_data (
    time_tick TIMESTAMP WITH TIME ZONE NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    metric_id VARCHAR(64) NOT NULL,
    instance_id INT NOT NULL DEFAULT 0,
    patient_id VARCHAR(64) DEFAULT 'OFFLINE_PATIENT',
    frequency_hz INT NOT NULL,
    unit_id VARCHAR(32),
    values REAL[] NOT NULL
);

-- D. Alertas del dispositivo
CREATE TABLE IF NOT EXISTS device_alerts (
    time_tick TIMESTAMP WITH TIME ZONE NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    metric_id VARCHAR(64),
    alert_type VARCHAR(64),
    alert_message TEXT NOT NULL,
    priority VARCHAR(16) CHECK (priority IN ('low', 'medium', 'high'))
);

-- E. Datos de bombas de infusión
CREATE TABLE IF NOT EXISTS infusion_pump_status (
    time_tick TIMESTAMP WITH TIME ZONE NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    patient_id VARCHAR(64) DEFAULT 'OFFLINE_PATIENT',
    infusion_active BOOLEAN NOT NULL,
    drug_name VARCHAR(128) NOT NULL,
    drug_mass_mcg INT,
    solution_volume_ml INT,
    volume_to_be_infused_ml INT,
    infusion_duration_seconds INT,
    infusion_fraction_complete REAL,
    interlock_stop BOOLEAN DEFAULT FALSE
);

-- F. Tabla legacy de logs generales (utilizada por SQLLogging.java)
CREATE TABLE IF NOT EXISTS devicelogs (
    log_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sourceclass VARCHAR(255) NOT NULL,
    eventtext TEXT NOT NULL
);

-- G. Tablas legacy de telemetría (utilizadas por AbstractDevice e integraciones de exportación)
-- Se definen usando t_sec (epoch segundos) como marca de tiempo principal.
CREATE TABLE IF NOT EXISTS allnumerics (
    t_sec BIGINT NOT NULL,
    t_nanosec INT NOT NULL,
    udi VARCHAR(64) NOT NULL,
    metric_id VARCHAR(64) NOT NULL,
    val DOUBLE PRECISION NOT NULL
);

CREATE TABLE IF NOT EXISTS allsamples (
    t_sec BIGINT NOT NULL,
    t_nanosec INT NOT NULL,
    udi VARCHAR(64) NOT NULL,
    metric_id VARCHAR(64) NOT NULL,
    floats TEXT NOT NULL -- Datos de ondas serializados en JSON
);

CREATE TABLE IF NOT EXISTS numerics_for_export (
    seqnum BIGSERIAL NOT NULL,
    t_sec BIGINT NOT NULL,
    t_nanosec INT NOT NULL,
    udi VARCHAR(64) NOT NULL,
    metric_id VARCHAR(64) NOT NULL,
    val DOUBLE PRECISION NOT NULL
);

CREATE TABLE IF NOT EXISTS samples_for_export (
    seqnum BIGSERIAL NOT NULL,
    t_sec BIGINT NOT NULL,
    t_nanosec INT NOT NULL,
    udi VARCHAR(64) NOT NULL,
    metric_id VARCHAR(64) NOT NULL,
    floats TEXT NOT NULL
);

-- Otorgar permisos al usuario sobre las tablas y secuencias
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO openice_local_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO openice_local_user;


-- -----------------------------------------------------------------------------
-- 4. CONVERSIÓN A HYPERTABLES (TimescaleDB)
-- -----------------------------------------------------------------------------
-- Para las tablas legacy basadas en epoch de segundos (t_sec), le indicamos a 
-- TimescaleDB que el campo de tiempo es un entero (segundos en un día = 86400).

DO $$
BEGIN
    PERFORM create_hypertable('device_connectivity_events', 'time_tick', if_not_exists => TRUE, chunk_time_interval => INTERVAL '6 hours');
    PERFORM create_hypertable('vital_values', 'time_tick', if_not_exists => TRUE, chunk_time_interval => INTERVAL '6 hours');
    PERFORM create_hypertable('waveform_data', 'time_tick', if_not_exists => TRUE, chunk_time_interval => INTERVAL '6 hours');
    PERFORM create_hypertable('device_alerts', 'time_tick', if_not_exists => TRUE, chunk_time_interval => INTERVAL '6 hours');
    PERFORM create_hypertable('infusion_pump_status', 'time_tick', if_not_exists => TRUE, chunk_time_interval => INTERVAL '6 hours');
    PERFORM create_hypertable('devicelogs', 'log_time', if_not_exists => TRUE, chunk_time_interval => INTERVAL '6 hours');
    
    -- Hypertables con marca de tiempo entera (t_sec - segundos epoch)
    PERFORM create_hypertable('allnumerics', 't_sec', if_not_exists => TRUE, chunk_time_interval => 21600); -- Chunks de 6 horas (21600s)
    PERFORM create_hypertable('allsamples', 't_sec', if_not_exists => TRUE, chunk_time_interval => 21600);
    PERFORM create_hypertable('numerics_for_export', 't_sec', if_not_exists => TRUE, chunk_time_interval => 21600);
    PERFORM create_hypertable('samples_for_export', 't_sec', if_not_exists => TRUE, chunk_time_interval => 21600);
END
$$;


-- -----------------------------------------------------------------------------
-- 5. ÍNDICES DE OPTIMIZACIÓN
-- -----------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_local_vital ON vital_values (device_id, metric_id, time_tick DESC);
CREATE INDEX IF NOT EXISTS idx_local_waveform ON waveform_data (device_id, metric_id, time_tick DESC);
CREATE INDEX IF NOT EXISTS idx_local_infusion ON infusion_pump_status (device_id, time_tick DESC);

-- Índices en tablas legacy
CREATE INDEX IF NOT EXISTS idx_legacy_numeric ON allnumerics (udi, metric_id, t_sec DESC);
CREATE INDEX IF NOT EXISTS idx_legacy_sample ON allsamples (udi, metric_id, t_sec DESC);
CREATE INDEX IF NOT EXISTS idx_legacy_num_exp ON numerics_for_export (udi, seqnum DESC);
CREATE INDEX IF NOT EXISTS idx_legacy_sam_exp ON samples_for_export (udi, seqnum DESC);
CREATE INDEX IF NOT EXISTS idx_legacy_ptdev ON patientdevice (udi, mrn);


-- -----------------------------------------------------------------------------
-- 6. POLÍTICAS DE COMPRESIÓN COLUMNAR
-- -----------------------------------------------------------------------------

ALTER TABLE vital_values SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, metric_id',
    timescaledb.compress_orderby = 'time_tick DESC'
);

ALTER TABLE waveform_data SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, metric_id',
    timescaledb.compress_orderby = 'time_tick DESC'
);

ALTER TABLE infusion_pump_status SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, drug_name',
    timescaledb.compress_orderby = 'time_tick DESC'
);

-- Compresión de tablas legacy
ALTER TABLE allnumerics SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'udi, metric_id',
    timescaledb.compress_orderby = 't_sec DESC'
);

ALTER TABLE allsamples SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'udi, metric_id',
    timescaledb.compress_orderby = 't_sec DESC'
);

ALTER TABLE numerics_for_export SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'udi, metric_id',
    timescaledb.compress_orderby = 't_sec DESC'
);

ALTER TABLE samples_for_export SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'udi, metric_id',
    timescaledb.compress_orderby = 't_sec DESC'
);

-- Programar compresión automática (12 horas o 43200 segundos)
DO $$
BEGIN
    PERFORM add_compression_policy('vital_values', INTERVAL '12 hours', if_not_exists => TRUE);
    PERFORM add_compression_policy('waveform_data', INTERVAL '12 hours', if_not_exists => TRUE);
    PERFORM add_compression_policy('infusion_pump_status', INTERVAL '12 hours', if_not_exists => TRUE);
    
    -- Compresión para tablas legacy basadas en enteros
    PERFORM add_compression_policy('allnumerics', 43200, if_not_exists => TRUE);
    PERFORM add_compression_policy('allsamples', 43200, if_not_exists => TRUE);
    PERFORM add_compression_policy('numerics_for_export', 43200, if_not_exists => TRUE);
    PERFORM add_compression_policy('samples_for_export', 43200, if_not_exists => TRUE);
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'Las políticas de compresión ya existen o no pudieron configurarse.';
END
$$;


-- -----------------------------------------------------------------------------
-- 7. POLÍTICAS DE RETENCIÓN (Auto-pruning de 3 días para búfer local)
-- -----------------------------------------------------------------------------
-- Borra datos con más de 3 días (259200 segundos para tablas legacy)

DO $$
BEGIN
    PERFORM add_retention_policy('vital_values', INTERVAL '3 days', if_not_exists => TRUE);
    PERFORM add_retention_policy('waveform_data', INTERVAL '3 days', if_not_exists => TRUE);
    PERFORM add_retention_policy('device_connectivity_events', INTERVAL '3 days', if_not_exists => TRUE);
    PERFORM add_retention_policy('device_alerts', INTERVAL '3 days', if_not_exists => TRUE);
    PERFORM add_retention_policy('infusion_pump_status', INTERVAL '3 days', if_not_exists => TRUE);
    PERFORM add_retention_policy('devicelogs', INTERVAL '3 days', if_not_exists => TRUE);
    
    -- Retención para tablas legacy basadas en enteros
    PERFORM add_retention_policy('allnumerics', 259200, if_not_exists => TRUE);
    PERFORM add_retention_policy('allsamples', 259200, if_not_exists => TRUE);
    PERFORM add_retention_policy('numerics_for_export', 259200, if_not_exists => TRUE);
    PERFORM add_retention_policy('samples_for_export', 259200, if_not_exists => TRUE);
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'Las políticas de retención ya existen o no pudieron configurarse.';
END
$$;


-- -----------------------------------------------------------------------------
-- 8. INSERCIÓN DE METADATOS INICIALES
-- -----------------------------------------------------------------------------

INSERT INTO metric_types (metric_id, display_name, default_unit, loinc_code, ucum_code) VALUES
('MDC_ECG_HEART_RATE', 'Frecuencia Cardíaca (ECG)', 'bpm', '8867-4', '/min'),
('MDC_SPO2', 'Saturación de Oxígeno (SpO2)', '%', '2708-6', '%'),
('MDC_PULS_OXIM_PULS_RATE', 'Frecuencia de Pulso por Oximetría', 'bpm', '8889-8', '/min'),
('MDC_CO2_RESP_RATE', 'Frecuencia Respiratoria (Capnometría)', 'rpm', '9279-1', '/min'),
('MDC_AWY_CO2', 'Presión Parcial de CO2 en Vía Aérea (EtCO2)', 'mmHg', '19889-5', 'mm[Hg]'),
('MDC_PRESS_BLD_SYS', 'Presión Arterial Sistólica', 'mmHg', '8480-6', 'mm[Hg]'),
('MDC_PRESS_BLD_DIA', 'Presión Arterial Diastólica', 'mmHg', '8462-4', 'mm[Hg]'),
('MDC_PRESS_BLD_MEAN', 'Presión Arterial Media', 'mmHg', '8478-0', 'mm[Hg]'),
('MDC_PRESS_BLD_NONINV_SYS', 'Presión Arterial No Invasiva Sistólica', 'mmHg', '8460-8', 'mm[Hg]'),
('MDC_PRESS_BLD_NONINV_DIA', 'Presión Arterial No Invasiva Diastólica', 'mmHg', '8458-2', 'mm[Hg]'),
('MDC_TEMP_BLD', 'Temperatura Corporal', '°C', '8310-5', 'Cel'),
('MDC_FLOW_FLU_INFUS', 'Flujo de Infusión', 'mL/h', '75685-8', 'mL/h'),
('MDC_VOL_INFUS_DELIV', 'Volumen de Infusión Entregado', 'mL', '37030-4', 'mL'),
('MDC_ECG_LEAD_I', 'Electrocardiograma - Derivación I', 'mV', '8601-7', 'mV'),
('MDC_ECG_LEAD_II', 'Electrocardiograma - Derivación II', 'mV', '8602-5', 'mV'),
('MDC_ECG_LEAD_III', 'Electrocardiograma - Derivación III', 'mV', '8603-3', 'mV')
ON CONFLICT (metric_id) DO NOTHING;
