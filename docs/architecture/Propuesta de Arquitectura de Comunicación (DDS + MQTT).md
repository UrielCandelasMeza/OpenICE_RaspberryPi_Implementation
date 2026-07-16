# Reporte Técnico: Propuesta de Arquitectura de Comunicación (DDS + MQTT)

**Fecha:** 16 de julio de 2026

**Proyecto:** Implementacion de OpenICE para servicios especializados en los quirofanos.

**Alcance:** Arquitectura de comunicación desde dispositivo médico hasta dashboard central por área clínica

**Version:** 1.0.0

---

## 1. Objetivo

Definir una arquitectura de comunicación que permita:

1. Monitorear dispositivos médicos vía **LAN/Serial** dentro de un quirófano, con comunicación en tiempo real y de baja latencia.
2. Estandarizar y agregar los datos de múltiples quirófanos dentro de un mismo edificio/área (ej. edificio de Cardiología).
3. Permitir que un **área clínica** (ej. neurología, cardiología) consulte datos de **todos sus quirófanos**, y que el hospital consulte datos de **todas sus áreas**, mediante una interfaz web centralizada.

La jerarquía física del hospital queda representada así:

```
Hospital
 └── Edificio de Área (ej. Cardiología, Neurología)
      └── Quirófano
           └── Dispositivo(s) médico(s)
```

## 2. Justificación: por qué una arquitectura híbrida (DDS + MQTT)

| Aspecto | DDS | MQTT |
|---|---|---|
| Topología | Peer-to-peer, sin intermediario | Jerárquica (hub-and-spoke) vía broker |
| Caso de uso ideal | Comunicación en tiempo real dentro de una LAN (dispositivo médico ↔ supervisor) | Agregación y transporte de datos entre sitios remotos (quirófano → edificio de área → hospital) |
| QoS | Granular por topic (reliability, deadline, liveliness) | QoS 0/1/2, más simple |
| Punto único de falla | No (descubrimiento distribuido) | Sí (el broker), mitigable con clustering |
| Soporte WAN / multi-sitio | Limitado sin componentes adicionales (routing services) | Nativo, mediante *bridging* entre brokers |
| Soporte para dashboards web | No nativo | Nativo (WebSocket), amplio ecosistema (Grafana, Node-RED, etc.) |

**Conclusión:** ningún protocolo cubre bien la totalidad del sistema por sí solo. Se propone usar cada uno donde es más fuerte:

- **DDS** para el segmento crítico de tiempo real: dispositivo médico ↔ supervisor, dentro de la LAN local del quirófano.
- **MQTT** para la agregación jerárquica de datos: quirófano → edificio de área → servidor central del hospital, y para alimentar los dashboards web.

## 3. Arquitectura propuesta

```
[Dispositivo médico]
      │ DDS (Cyclone DDS) — LAN/Serial del quirófano
      ▼
[Supervisor (Android)]  ← participante DDS + traductor DDS→MQTT
      │ MQTT (publica datos estandarizados)
      ▼
[Broker MQTT — Quirófano]
      │ bridge
      ▼
[Broker MQTT — Edificio de Área] (Cardiología, Neurología, etc.)
      │ bridge
      ▼
[Broker MQTT — Central del hospital]
      │
      ▼
[Backend + TimescaleDB]
      │
      ▼
[Dashboard web por área clínica]
```

## 4. Componentes y decisiones de diseño

### 4.1 Capa de dispositivo → supervisor (DDS)

- **Middleware DDS:** se sustituye RTI Connext por **Cyclone DDS**.
  - Es una implementación **open source**, sin costos de licenciamiento, con buen soporte de la comunidad y compatibilidad con el estándar DDS-RTPS (interoperable con otras implementaciones DDS si fuera necesario).
  - Se debe migrar/adaptar el archivo de configuración QoS (actualmente en formato RTI XML) al formato de configuración de Cyclone DDS (`CycloneDDS` XML config), respetando los perfiles ya definidos (reliability, durability, history) para los distintos tipos de dato (`numeric_data`, `waveform_data`, `observed_data`, etc.).
  - Se recomienda mantener las lecciones del diagnóstico previo: evitar depender 100% de multicast para discovery, agregando peers configurados explícitamente cuando la topología de red lo permita.

- **Supervisor:** ya existe una aplicación supervisor, actualmente implementada en **Java 25**. Para este proyecto se requiere una **nueva implementación del supervisor para Android**, que se ejecutará dentro del quirófano como participante DDS y panel de control local.
  - Implica implementar el cliente Cyclone DDS en el entorno Android (Cyclone DDS tiene bindings/soporte para integrarse vía JNI/C, o mediante wrappers existentes de la comunidad; se debe validar la vía de integración con Kotlin/Java sobre Android).
  - Al tratarse de una nueva implementación (no una migración directa), se recomienda evaluar qué lógica de negocio del supervisor en Java 25 puede reutilizarse o portarse (reglas de estandarización de datos, manejo de topics, etc.), y qué partes deben rediseñarse específicamente para el entorno Android (ciclo de vida de la app, gestión de batería, permisos de red/USB-Serial, almacenamiento local).
  - El supervisor Android es responsable de:
    - Suscribirse a los topics DDS publicados por los dispositivos médicos en la LAN del quirófano.
    - **Guardar los datos de forma temporal en SQLite** (vía Room) como buffer de continuidad, mientras se confirma su envío hacia el broker MQTT (ver sección 4.2).
    - Traducir y publicar los datos estandarizados hacia el broker MQTT del quirófano.
    - Mostrar la información en tiempo real al personal del quirófano (funcionalidad local, sin depender del enlace hacia el hospital central).

### 4.2 Persistencia local en el supervisor (Android)

- Se utiliza **SQLite (vía Room)** como almacenamiento **temporal** en el supervisor Android, no como almacenamiento definitivo — el dato "vive" oficialmente en el servidor central (TimescaleDB); SQLite solo actúa como buffer local mientras se garantiza su envío.
- Debe cubrir un mínimo de 24 horas de datos, permitiendo operación continua del quirófano aunque se pierda temporalmente la conexión hacia el broker del edificio de área/hospital.
- Los registros se marcan como sincronizados una vez confirmada su publicación exitosa en MQTT, habilitando reintentos ante fallos de red.
- Una vez sincronizados (y superado el margen de retención local), los registros se purgan de SQLite mediante un proceso periódico (ej. WorkManager), evitando que el almacenamiento del dispositivo crezca indefinidamente.

### 4.3 Capa de agregación (MQTT)

- **Broker por quirófano (opcional):** recibe los datos publicados por el supervisor Android.
- **Bridging entre brokers:** cada nivel reenvía automáticamente al siguiente (quirófano → edificio de área → central), usando la función nativa de *bridge* del broker (ej. Mosquitto, EMQX, HiveMQ).
- **Estructura de topics jerárquica**, que permite filtrar por área clínica sin importar el quirófano de origen:
  ```
  hospital/{area_clinica}/{quirofano}/{dispositivo}/{metrica}
  ```
  Ejemplo:
  ```
  hospital/cardiologia/quirofano_3/monitor_hr/valor
  ```
- Las vistas por área (ej. cardiología) se suscriben con wildcard, ej.: `hospital/cardiologia/#`.

> **Pendiente de definir:** si se requiere resiliencia offline por quirófano (operar de forma autónoma si se cae el enlace al edificio de área/hospital central) o si basta con centralizar directamente sin brokers intermedios por quirófano. Esto determina si se implementa bridging en cascada completo o una topología más simple (quirófano → central).

### 4.4 Capa central

- **Base de datos:** TimescaleDB (extensión de PostgreSQL), aprovechando hypertables, compresión nativa y *continuous aggregates* para las consultas de los dashboards.
- **Backend:** servicio suscrito al broker MQTT central, encargado de insertar los datos recibidos en TimescaleDB (en batch, no registro por registro, para minimizar overhead).
- **Dashboard web:** consulta los datos agregados por área clínica, permitiendo ver información de todos los quirófanos del hospital de forma unificada.

## 5. Resumen de cambios respecto a la arquitectura original

| Elemento | Antes | Ahora (definido) |
|---|---|---|
| Middleware DDS | RTI Connext | **Cyclone DDS** |
| Plataforma del supervisor | Existente, en **Java 25** | Nueva implementación para **Android**, dentro del quirófano |
| Transporte de agregación multi-sitio | No definido | MQTT con bridging jerárquico |
| Almacenamiento local (supervisor) | No definido | SQLite/Room — almacenamiento **temporal** (buffer mínimo 24h, se purga tras sincronizar) |
| Almacenamiento central | — | TimescaleDB sobre PostgreSQL |

## 6. Próximos pasos sugeridos

1. Validar la vía de integración de Cyclone DDS en Android (librería nativa, bindings disponibles, o necesidad de wrapper propio).
2. Evaluar qué lógica del supervisor actual (Java 25) puede reutilizarse/portarse hacia la nueva implementación Android, y qué debe rediseñarse.
3. Migrar el archivo de configuración QoS de RTI XML al formato de Cyclone DDS, preservando los perfiles reliability/durability/history ya definidos.
4. Definir si habrá broker MQTT físico por quirófano/edificio de área o una topología más simple hacia el broker central.
5. Definir el esquema de topics MQTT definitivo (nomenclatura de área clínica, quirófano y dispositivo).
6. Diseñar el esquema de la hypertable en TimescaleDB y las políticas de retención/compresión.

---

*Reporte generado a partir de la sesión de diseño de arquitectura del proyecto.*