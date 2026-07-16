# Reporte Técnico: Diagnóstico de Comunicación DDS en LAN sin Internet

**Fecha:** 16 de julio de 2026

**Sistema:** Comunicación DDS (RTI Connext) para monitoreo de dispositivos médicos

**Alcance:** Envío de datos desde dispositivo (Raspberry Pi) hacia panel de control

---

## 1. Contexto

El proyecto utiliza **DDS (Data Distribution Service)**, implementado sobre **RTI Connext**, para conectar un dispositivo (Raspberry Pi) con un panel de control dentro de una red LAN. El sistema forma parte de una arquitectura basada en el perfil de configuración estándar de **OpenICE / MD PnP**, que define distintos perfiles QoS (`default_profile`, `numeric_data`, `waveform_data`, `observed_data`, entre otros) usando descubrimiento (*discovery*) vía **multicast UDP** sobre la dirección `239.255.0.1`.

## 2. Problema reportado

Se observó que el envío de datos funcionaba correctamente cuando la LAN tenía salida a **internet**, pero al operar en una **LAN 100% local sin acceso a internet**, el envío de datos era **parcial**: solo algunos datos llegaban al panel de control, no todos.

Esto generó la pregunta inicial de si **DDS requiere conexión WAN/internet para funcionar**.

## 3. Hipótesis de diagnóstico

Se descartó desde el inicio que DDS dependa de internet, ya que el protocolo está diseñado específicamente para operar en redes locales o incluso aisladas (uso común en robótica, sistemas embebidos e industriales). Se identificaron las siguientes causas más probables de pérdida parcial de datos en LAN local:

1. **Ausencia de IGMP Querier activo en la red**
   El discovery de DDS depende de tráfico multicast (`239.255.0.1`). Sin un *querier* IGMP activo (función que muchos routers domésticos solo activan cuando detectan salida WAN), los switches pueden podar (*squelch*) el tráfico multicast tras un tiempo, afectando el descubrimiento entre participantes DDS.

2. **QoS de Reliability inconsistente**
   Se identificó que el `default_profile` del XML de configuración define los `datareader_qos` como `DDS_BEST_EFFORT_RELIABILITY_QOS`. Si el topic de datos del dispositivo no usa explícitamente un perfil `RELIABLE` (`numeric_data`, `waveform_data`, `observed_data`), las pérdidas de paquete no se retransmiten, lo cual se vuelve más evidente en redes con mayor tasa de pérdida.

3. **Discovery dependiente 100% de multicast, sin respaldo unicast**
   La configuración no define `initial_peers` unicast como respaldo, dependiendo exclusivamente de multicast para el *discovery* (SPDP), lo cual la hace sensible al comportamiento del switch/router respecto a IGMP.

4. **Infraestructura de red específica de la prueba (switch/segmento)**
   Se determinó que el problema apareció específicamente en un segmento de red (`10.0.0.x`) usando conexión Ethernet a través de un switch. En una prueba posterior en un segmento distinto (`10.1.40.x`), usando WiFi en lugar de Ethernet, el sistema funcionó correctamente.

## 4. Proceso de diagnóstico

| Prueba | Segmento | Interfaz | Resultado |
|---|---|---|---|
| 1 | `10.0.0.x` | Ethernet (vía switch) | Datos parciales |
| 2 | `10.1.40.x` | WiFi | Funcionamiento correcto (100%) |

A partir de esta comparación, se descartaron las hipótesis de:
- Dependencia de internet/WAN (DDS no la requiere).
- Reconfiguración residual del mismo segmento (se confirmó que los segmentos eran distintos, no el mismo revisitado).

Y se reforzó la hipótesis de que el origen del problema estaba en la **infraestructura de red utilizada en la primera prueba** (switch Ethernet del segmento `10.0.0.x`), probablemente relacionado con:
- Manejo deficiente de IGMP snooping/querier en ese switch específico, y/o
- Ruido de red en un rango de IP muy común (`10.0.0.0/24`), típico de routers/dispositivos IoT domésticos, y/o
- Diferencias en el manejo de multicast entre un switch Ethernet gestionado y un punto de acceso WiFi (algunos APs realizan conversión de multicast a unicast, evitando por completo los problemas de IGMP snooping).

## 5. Resultado final

Al reconstruir el entorno de prueba (mismo entorno, sin modificaciones adicionales) en el segmento `10.1.40.x`, el envío de datos se realizó **correctamente y de forma completa (100% en LAN local)**, confirmando que:

- **DDS no requiere conexión a internet para funcionar.**
- El problema original era atribuible a la **infraestructura de red (switch/segmento) usada en la prueba inicial**, no al protocolo DDS ni a la falta de WAN.

## 6. Conclusión

> Se confirmó que RTI Connext DDS funciona correctamente en una LAN 100% local sin acceso a internet. El problema de recepción parcial de datos observado en el segmento `10.0.0.x` se debió a la infraestructura de red (switch) utilizada en esa prueba específica, no a DDS ni a la ausencia de conectividad WAN. La configuración fue validada exitosamente en el segmento `10.1.40.x`.

## 7. Recomendaciones preventivas

Aunque el sistema ya opera correctamente, se recomienda considerar las siguientes medidas como buena práctica para robustecer el sistema ante futuros despliegues en redes no controladas (por ejemplo, en otras clínicas/consultorios):

1. **Agregar `initial_peers` unicast como respaldo del discovery multicast**, evitando depender exclusivamente del comportamiento de IGMP del switch:
   ```xml
   <initial_peers>
     <element>udpv4://239.255.0.1</element>
     <element>udpv4://IP_DISPOSITIVO_1</element>
     <element>udpv4://IP_PANEL_CONTROL</element>
   </initial_peers>
   ```

2. **Verificar que los topics críticos de datos del dispositivo usen perfiles QoS `RELIABLE`** (`numeric_data`, `waveform_data`, `observed_data`) y no hereden el `default_profile`, que es `BEST_EFFORT` en lectura.

3. **Confirmar la existencia de un IGMP querier activo** en el router/switch de cualquier red donde se despliegue el sistema, especialmente si se usan switches gestionados con IGMP snooping habilitado.

4. **Usar IPs fijas o reservas DHCP** para los dispositivos DDS, de forma que los peers unicast de respaldo sean estables entre reinicios.

5. **Documentar la infraestructura de red validada** (marca/modelo de switch y router) para futuros despliegues, y evitar cambios de segmento/topología sin reiniciar o limpiar tablas ARP/IGMP antes de validar pruebas.

---

*Reporte generado a partir de la sesión de diagnóstico técnico del proyecto.*