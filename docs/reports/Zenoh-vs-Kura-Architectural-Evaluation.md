# Reporte Técnico de Evaluación Arquitectónica: Eclipse Kura vs Eclipse Zenoh para OpenICE (Edge-to-Fog-to-Cloud)

**Fecha:** 01/10/2026  
**Proyecto:** OpenICE / MD PnP (`1.5.1-SNAPSHOT`)  
**Sistema:** Arquitectura IoT Médico (Edge–Fog–Cloud)  
**Alcance:** Comparativa técnica y justificación de selección entre Eclipse Kura y Eclipse Zenoh para transmisión de datos clínicos

**Versión:** 1.0.0

---

## 1. Resumen Ejecutivo

**Veredicto técnico:** Eclipse **Zenoh** es la opción óptima frente a Eclipse **Kura** para la transmisión Edge-to-Fog-to-Cloud en el sistema OpenICE.

La razón fundamental radica en la alineación arquitectónica con el núcleo de OpenICE. OpenICE ya opera sobre **DDS (RTI Connext)** como bus de datos clínico en tiempo real, con tipado estricto, QoS y descubrimiento distribuido. Zenoh permite **puentear DDS hacia Kafka/Ditto sin introducir una capa de traducción redundante**, aprovechando su modelo pub/sub/query con enrutamiento distribuido, **zero-copy**, footprint reducido y baja latencia. En contraste, Kura (Java/OSGi, centrado en gestión de gateways y exposición MQTT) añadiría complejidad innecesaria, mayor consumo de recursos y cuellos de botella en redes WAN/celulares, sin aportar valor directo al flujo de datos clínicos.

---

## 2. Análisis Técnico Comparativo

| Métrica | Eclipse Kura | Eclipse Zenoh | Implicación en OpenICE |
|---|---|---|---|
| **Arquitectura y Paradigma** | Java 11+, OSGi (Equinox), modular por bundles. Enfoque orientado a gateway/device management. | Rust (núcleo), bindings en C, C++, Java, Python, etc. Protocolo unificado (pub/sub/query/geo/req-rep). Diseñado para edge distribuido. | Kura introduce JVM/OSGi (mayor footprint, arranque y GC) en edge crítico; Zenoh es nativo, liviano y sin sobrecarga de contenedor OSGi innecesaria para transmisión de datos. |
| **Integración con OpenICE / DDS** | No posee puente nativo DDS-first. Requiere traducir flujos (típicamente DDS → MQTT vía puente externo o adaptar datos) para integrarse con ecosistemas MQTT/Kafka. | **Zenoh DDS Plugin** permite exponer tópicos DDS de RTI directamente como recursos Zenoh (o hacer bridge DDS<->Zenoh). Integración nativa, sin re-escritura de lógica de dispositivos. | Zenoh se integra con el bus DDS existente sin duplicar capas. Kura fuerza un modelo Gateway+MQTT que no coincide con el modelo distribuido de OpenICE. |
| **Latencia, Overhead de Red y Rendimiento** | MQTT (sobre TCP) añade overhead por cabezales, QoS con confirmaciones y mayor latencia en WAN. JVM + serialización JSON/XML añaden coste de CPU/memoria. | Protocolo ligero, **zero-copy**, soporte para UDP/quic-like y batching eficiente. Transmite datos en formato binario eficiente. Minimiza round-trips. | En escenarios clínicos (monitorización continua, alertas) latencia baja y overhead reducido son críticos; Zenoh reduce carga en redes limitadas (celular/WAN) vs MQTT+Kura. |
| **Complejidad de Despliegue y Footprint de Hardware** | Requiere JVM + OSGi, múltiples bundles, mayor RAM/CPU. Despliegue más pesado en SBCs (Raspberry Pi 3/4/5) con recursos limitados. | Binarios estáticos/ligeros (Rust), sin JVM. Menor consumo de memoria, tiempo de arranque reducido. Soporta contenedores mínimos. | Raspberry Pi edge se beneficia de menor footprint. Kura incrementa complejidad operativa y consumo en despliegues homogéneos con headless-adapter. |
| **Enrutamiento Multicapa (Edge → Fog → Cloud) y Travesía de WAN/Firewalls** | Modelo estrella (broker MQTT central) o topologías bridged complejas. Traversal NAT/firewall requiere túneles o exposición de brokers. Escalado distribuido limitado. | **Topología distribuida peer-to-peer/router**: routers Zenoh en Edge/Fog/Cloud forman una red de enrutamiento, permiten **pub/sub con alcance geográfico**, store, query y routing optimizado. Soporta NAT traversal, conexiones outbound, menor dependencia de broker central. | Ideal para arquitectura Edge→Fog (red local clínica) → Cloud (regional/externo). Reduce puntos únicos de fallo y simplifica travesía WAN vs modelo broker central de MQTT/Kura. |

---

## 3. Inconvenientes Técnicos de usar Eclipse Kura (Redundancia y Limitaciones)

### 3.1 Duplicidad de Capas
- **Abstracción ya existente en OpenICE:** OpenICE ya abstrae lectura de dispositivos médicos (framework DeviceDriver, DDS, modelos ICE/x73). Añadir Kura significa introducir otra capa (Gateway Application, Cloud Service, DataService MQTT) para traducir datos que ya están estructurados en DDS.
- **Traducción innecesaria:** El flujo natural DDS→aplicaciones se rompe al forzar DDS → (adaptador) → Kura → MQTT. Cada salto añade mapeo de tópicos, transformación de tipos y riesgo de pérdida semántica (QoS, timestamps, claves @key DDS).
- **Mantenimiento duplicado:** Dos modelos de configuración (Spring/XML de OpenICE + configuración OSGi/Kura) aumentan superficie de error y complejidad de CI/despliegue.

### 3.2 Cuellos de Botella
- **Runtime Java/OSGi:** JVM implica GC, mayor uso de heap, arranque más lento y mayor superficie de ataque. En edge con carga continua (múltiples dispositivos) esto compite con procesos críticos (headless-adapter, DDS).
- **Overhead MQTT sobre WAN/celulares:** MQTT sobre TCP requiere persistencia de conexión, head-of-line blocking, mayor overhead por mensaje vs UDP/transportes ligeros. En redes inestables (móviles, clínicas aisladas) esto degrada rendimiento.
- **Serialización y paso de mensajes:** Kura suele publicar vía MQTT en JSON (común), incrementando tamaño vs payload binario nativo DDS; conversión adicional añade CPU.
- **Escalado:** Arquitectura broker-centric concentra carga (ancho de banda, memoria, conexiones) en un único punto cuando atraviesa WAN.

### 3.3 Incompatibilidad de Topología
- **Modelo estrella vs distribuido:** MQTT (predominante en Kura) asume broker central. Para Edge→Fog→Cloud esto obliga a puentes MQTT (bridges) complejos, con gestión de rutas, retención y loops.
- **Descubrimiento y resiliencia:** DDS ofrece descubrimiento dinámico; Kura+MQTT pierde granularidad de descubrimiento y dificulta routing basado en localidad (fog más cercano). Fallos de broker central afectan toda la cadena.
- **Acoplamiento de red:** Topología distribuida de Zenoh (routers/peers, routing por interés) permite mantener procesamiento en Fog incluso con conectividad Cloud intermitente; modelo broker central de Kura es menos resiliente a desconexiones WAN.

---

## 4. Ventajas de la Arquitectura Propuesta con Zenoh

### 4.1 Integración nativa mediante Zenoh DDS Plugin (cero código de traducción)
- **Puente DDS<->Zenoh sin adaptar drivers:** `zenoh-plugin-dds` mapea tópicos DDS (RTI) a espacios de recursos Zenoh (`/ice/...`) respetando tipos, claves y QoS relevantes. No requiere reescribir lógica de dispositivos OpenICE.
- **Bidireccional y selectivo:** Permite filtrar tópicos por dominio/paquete, controlar alcance (local/fog/cloud) y evitar duplicación innecesaria.
- **Zero-code translation:** Reduce superficie de errores, mantiene semántica clínica (timestamps, UDI, métricas) y evita sobrecarga de desarrollo/validación.

### 4.2 Enrutamiento eficiente entre Edge, Fog y Cloud
- **Jerarquía natural:** Edge (dispositivos) publica a routers Zenoh locales (Fog/Raspberry Pi 5 propuesta para servicios locales); Fog enruta selectivamente hacia Cloud según políticas (agregación, retención, anonimización).
- **Optimización WAN:** Routing por interés (subscriber-driven), compresión/agrupado y menor overhead permiten eficiencia en enlaces celulares o con ancho de banda limitado.
- **Resiliencia y autonomía:** Fog puede operar autónomo ante pérdida de Cloud; Edge puede seguir publicando localmente. Esto se alinea con requisitos clínicos de continuidad operativa.

### 4.3 Flujo de salida directo hacia Apache Kafka y Eclipse Ditto
- **Zenoh-to-Kafka Plugin:** Permite publicar selectivamente flujos clínicos desde Zenoh hacia Kafka (topics por dispositivo/tipo de métrica) sin intermediario pesado. Facilita ingesta para analítica, almacenamiento histórico y pipelines de datos.
- **Integración con Eclipse Ditto (Digital Twins):** Ditto gestiona twins de dispositivos (UDI, estado, alertas). Zenoh puede alimentar Ditto vía Kafka o con adaptadores, habilitando modelado, telemetría estructurada y APIs para aplicaciones/cloud.
- **Arquitectura desacoplada:** Separación clara entre bus tiempo-real (DDS/Zenoh) y capa de persistencia/eventos (Kafka) y capa semántica/twins (Ditto). Evita contaminar edge con lógica cloud.

---

## 5. Caso Único de Coexistencia (Excepción)

Kura **solo aportaría valor** en un caso muy específico y acotado: **cuando se requiere gestión física remota del SO/Gateway o lectura de sensores industriales secundarios (Modbus/BACnet)**.

- **Gestión remota (Device Management):** Actualizaciones OTA del SO, configuración de red, watchdog, telemetría del sistema (CPU/RAM/almacenamiento, logs), firewall, VPN. Esto es funcionalidad complementaria, no de bus de datos.
- **Sensores industriales secundarios:** Integración con dispositivos no médicos (Modbus RTU/TCP, BACnet) que no forman parte del modelo ICE/x73 y no publican vía DDS OpenICE.
- **Principio de separación:** En ese caso, Kura debería actuar **únicamente como contenedor/gestor del gateway** (administración, monitoreo del sistema, drivers industriales puntuales), **nunca como bus de datos clínicos**. El flujo médico debe mantenerse en DDS/Zenoh, evitando duplicidad de capas.

**Conclusión de excepción:** La coexistencia no justifica usar Kura como puente de datos. Solo sería razonable si existe un requisito operativo explícito de device management remoto no cubierto por otras herramientas, y aun así, manteniendo separación estricta entre gestión del SO y transmisión de datos clínicos.

---

*Reporte generado a partir de análisis arquitectónico comparativo entre Eclipse Kura y Eclipse Zenoh en el contexto de OpenICE / MD PnP.*
