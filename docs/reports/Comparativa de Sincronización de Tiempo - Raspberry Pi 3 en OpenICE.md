# Reporte Técnico: Comparativa de Sincronización de Tiempo para Raspberry Pi 3 en OpenICE

**Fecha:** 17 de agosto de 2026

**Sistema:** OpenICE / MD PnP – Headless Adapter en Raspberry Pi 3

**Alcance:** Soluciones de sincronización de reloj para dispositivos embebidos sin acceso a internet

---

## 1. Contexto

El proyecto OpenICE despliega **headless adapters** en Raspberry Pi 3 para capturar telemetría de dispositivos médicos y transmitirla vía **DDS (RTI Connext)** a un Supervisor centralizado. El escenario operativo actual define las siguientes restricciones:

- **50 Raspberry Pi 3** distribuidas en **10 edificios** distintos.
- **Sin acceso a internet** — únicamente red LAN hospitalaria.
- **PCB personalizada** en desarrollo que integra el módulo **DS3231** (RTC con batería CR2032) y puertos **RS232** para conexión directa a dispositivos médicos seriales.
- La Raspberry Pi 3 **no tiene RTC integrado** (a diferencia del Compute Module 4 o Pi 5), por lo que pierde la hora del sistema cada vez que se apaga.

## 2. Problema

Cuando la Raspberry Pi 3 pierde alimentación, el reloj del sistema se reinicia a un valor por defecto (típicamente epoch o una fecha antigua). Esto afecta directamente los **3 campos de tiempo** que OpenICE publica en cada mensaje DDS:

| Campo DDS | Fuente | Quién lo setea |
|---|---|---|
| `source_timestamp` (en `SampleInfo`) | `DomainParticipant.get_current_time()` | RTI automáticamente al hacer `write()` |
| `presentation_time` (in-band IDL) | `DeviceClock.Reading.getTime()` → `DomainClock` | `AbstractDevice.numericSample()` / `publish()` |
| `device_time` (in-band IDL) | `DeviceClock.Reading.getDeviceTime()` | Solo si el dispositivo físico tiene reloj propio |

**Todos dependen del reloj del sistema.** Si la Pi arranca con hora incorrecta, los 3 campos se publican con timestamps inválidos. Esto impacta:

- **Visualización en el Supervisor** — las gráficas y tablas muestran horas incorrectas.
- **Exportación FHIR/HL7** — las observaciones se envían con timestamps erróneos al gateway.
- **Diagnóstico** — los logs y eventos de conectividad quedan con marcas de tiempo inútiles.

**Nota sobre ordenamiento DDS:** El QoS de OpenICE usa `DDS_BY_RECEPTION_TIMESTAMP_DESTINATIONORDER_QOS` deliberadamente (ver `ice_library.xml:317-330`), por lo que el orden de los mensajes **no se ve afectado** por relojes desincronizados. El problema es puramente de **presentación y auditoría**.

## 3. Flujo de Tiempo en OpenICE

### 3.1 Cadena de producción de timestamps

El mecanismo predeterminado es `DomainClock` (`DomainClock.java:121-126`), que obtiene el tiempo del participante DDS:

```java
Instant currentTime() {
    Time_t dds = new Time_t(0, 0);
    domainParticipant.get_current_time(dds);
    return Instant.ofEpochSecond(dds.sec, dds.nanosec);
}
```

`AbstractDevice` usa este reloj para setear los campos in-band al publicar datos (`AbstractDevice.java:321-337`):

```java
protected void numericSample(InstanceHolder<Numeric> holder, float newValue, DeviceClock.Reading time) {
    holder.data.value = value;
    Time_t t = DomainClock.toDDSTime(time.getTime());
    holder.data.presentation_time.sec = (int)(t.sec);
    holder.data.presentation_time.nanosec = (int)t.nanosec;
    numericDataWriter.write(holder.data, holder.handle);
}
```

### 3.2 Protocolo existente de sincronización vía DDS

OpenICE ya incluye un protocolo de sincronización de reloj de aplicación (`TimeManager.java`, `TimeSyncHandler.java`) basado en un algoritmo tipo NTP:

1. El Supervisor envía un `HeartBeat` cada **2000 ms** con su `source_timestamp`.
2. El device adapter responde con un `TimeSync` que incluye timestamps de recepción y envío.
3. `TimeManager` calcula la latencia de ida y vuelta y estima el offset del reloj.

**Sin embargo, el mecanismo actual tiene 3 problemas que lo hacen no confiable:**

| Problema | Ubicación | Impacto |
|---|---|---|
| Umbral incorrecto | `TimeManager.java:364` — compara contra `31536000000L` (~1971) | Si la Pi arranca con hora 2000-2025, **no se activa** |
| One-shot | `TimeManager.java:374` — `df = null` después del primer intento | Si falla, nunca reintenta |
| Requiere `sudo` | `Runtime.exec("sudo date --set")` | Sin permitos root, falla silenciosamente |

## 4. Soluciones Evaluadas

### 4.1 Solución 1: DS3231 en PCB (Hardware)

El módulo DS3231 es un RTC I2C de precisión con batería CR2032 integrada. Con la PCB personalizada que ya se está diseñando (que incluye RS232 para dispositivos médicos), el DS3231 se integra como componente adicional de bajo costo.

**Configuración en Raspberry Pi 3:**
- Conexión I2C a los GPIO pins 1 (SDA) y 3 (SCL).
- Habilitar overlay en `/boot/config.txt`: `dtoverlay=i2c-rtc,ds3231`.
- Sincronizar una vez: `sudo hwclock -w`.
- Al boot: `sudo hwclock -s` para cargar la hora al sistema.

| Ventaja | Detalle |
|---|---|
| Sin dependencia de red | Funciona desde el primer segundo después del boot |
| Precisión | ±2 ppm (~1 minuto/año de deriva) |
| Costo marginal | El DS3231 ya está en la PCB; módulo adicional ~$1-2 USD |
| Batería durable | CR2032 dura 5-10 años |

| Desventaja | Detalle |
|---|---|
| Sin fallback | Si la batería muere, el problema regresa silenciosamente |
| Mantenimiento HW | Reemplazo programado de baterías en 50 unidades cada 5-10 años |

### 4.2 Solución 2: Cliente NTP (Software)

El cliente NTP (`chrony` o `ntpdate`) se sincroniza con un servidor NTP en la red.

| Ventaja | Detalle |
|---|---|
| Sin hardware adicional | Solo software |
| Autoconfiguración | El servicio se auto-sincroniza periódicamente |

| Desventaja | Detalle |
|---|---|
| **Requiere servidor NTP** | Sin internet, se necesita desplegar un servidor NTP en cada edificio o uno central |
| Infraestructura adicional | Servidor, configuración de red (puerto 123 UDP), mantenimiento |
| Fragilidad en red hospitalaria | Hospital IT frecuentemente bloquea tráfico NTP; si el servidor cae, todas las Pi pierden hora |
| **No viable en este escenario** | Sin internet y con restricciones de red, esta solución agrega complejidad innecesaria |

### 4.3 Solución 3: DDS Time Sync (Software)

El Supervisor (que sí tiene hora correcta) envía su timestamp vía DDS, y la Pi lo usa para ajustar su reloj. El mecanismo ya existe en `TimeManager` / `TimeSyncHandler` pero requiere correcciones.

| Ventaja | Detalle |
|---|---|
| Sin hardware adicional | Solo modificaciones de código |
| Sin infraestructura | Funciona sobre la red DDS existente (LAN) |
| Autovalidación | El Supervisor puede corregir deriva del DS3231 periódicamente |

| Desventaja | Detalle |
|---|---|
| Dependencia del Supervisor | Si el Supervisor no está encendido, la Pi no se sincroniza |
| Precisión menor | ~1-10 ms (depende de latencia de red), suficiente para timestamps médicos |
| Código actual no confiable | Umbral incorrecto, one-shot, requiere sudo (ver sección 3.2) |

## 5. Comparativa

| Criterio | DS3231 (HW) | NTP (SW) | DDS Time Sync (SW) |
|---|---|---|---|
| **Costo inicial (50 Pis)** | ~$100-150 (PCB ya existente) | $0 | $0 |
| **Costo anual** | ~$25 (baterías cada 5-10 años) | $0 | $0 |
| **Infraestructura adicional** | Ninguna | Servidor NTP por edificio | Ninguna |
| **Precisión** | ±2 ppm | <1 ms | ~1-10 ms |
| **Dependencia de red externa** | No | Sí (servidor NTP) | No (solo DDS LAN) |
| **Funciona sin internet** | Sí | No (sin servidor NTP local) | Sí |
| **Funciona sin Supervisor** | Sí | Sí | No |
| **Mantenimiento** | Bajo (baterías) | Mínimo | Mínimo |
| **Robustez ante caídas** | Alta | Media | Media |
| **Esfuerzo de implementación** | Bajo (config HW) | Bajo (config SW) | Bajo (corregir código existente) |

## 6. Resultado

### Solución descartada: NTP

La solución NTP queda descartada por las restricciones del escenario:
- Sin internet, se requeriría desplegar un servidor NTP en cada edificio (o uno central con routing entre edificios).
- Eso implica infraestructura adicional, mantenimiento, y dependencia de la red hospitalaria.
- La combinación DS3231 + DDS Time Sync resuelve lo mismo sin infraestructura adicional.

### Solución óptima: DS3231 + DDS Time Sync corregido

La combinación de ambas soluciones ofrece la máxima confiabilidad:

```
Boot Raspberry Pi 3
       │
       ▼
hwclock -s  ←── DS3231 en PCB (hora exacta desde el primer segundo)
       │
       ▼
DDS conecta al Supervisor
       │
       ▼
TimeManager periódico  ←── Validación/corrección vía DDS (cada 5 minutos)
       │
       ▼
Si DS3231 falla  ←── DDS Time Sync mantiene la hora (~1-10 ms precisión)
```

**Por qué esta combinación es la óptima:**

1. **DS3231 resuelve el problema de raíz** — la Pi nunca pierde la hora.
2. **DDS Time Sync valida y corrige** — detecta deriva del DS3231 y la corrige periódicamente.
3. **Sin internet ni infraestructura adicional** — ambas soluciones operan sobre LAN.
4. **La PCB ya se está fabricando** — el DS3231 es costo marginal en el BOM.
5. **Doble propósito** — la PCB además provee RS232 para conexión serial a dispositivos médicos.
6. **Tolerancia a fallos** — si la batería del DS3231 muere, el DDS Time Sync mantiene la hora aproximadamente.

## 7. Conclusión

> Para el despliegue de 50 headless adapters en Raspberry Pi 3 distribuidos en 10 edificios sin acceso a internet, la solución óptima es **DS3231 en PCB como fuente primaria de tiempo, combinada con DDS Time Sync como capa de validación y fallback**. El DS3231 garantiza hora precisa desde el primer segundo sin dependencia de red, mientras que el DDS Time Sync (con las correcciones necesarias en `TimeManager`) provee auto-validación periódica y resiliencia ante fallos del hardware RTC. La solución NTP queda descartada por la ausencia de internet y la complejidad de infraestructura que implicaría.

## 8. Recomendaciones

### 8.1 Cambios en `TimeManager.java`

El código actual de sincronización DDS requiere 3 correcciones para funcionar como fallback confiable:

1. **Corregir el umbral de detección** — reemplazar `System.currentTimeMillis() < 31536000000L` por una comparación contra la hora recibida del Supervisor (el `source_timestamp` del HeartBeat). Si la diferencia supera un umbral razonable (ej. 60 segundos), ajustar el reloj.

2. **Hacer sincronización periódica** — eliminar el one-shot (`df = null`). Ejecutar la corrección cada 5 minutos mientras haya conectividad con el Supervisor, no solo la primera vez.

3. **Eliminar dependencia de `sudo`** — usar `SystemClock` de Java o la API del DS3231 (`hwclock`) en lugar de `Runtime.exec("sudo date --set")`, que requiere permisos root y falla silenciosamente sin ellos.

### 8.2 Configuración de DS3231 en Raspberry Pi 3

1. Agregar `dtoverlay=i2c-rtc,ds3231` en `/boot/config.txt`.
2. Verificar con `sudo hwclock -r` que el módulo responde.
3. Sincronizar la primera vez: `sudo hwclock -w`.
4. En el script de boot, ejecutar `sudo hwclock -s` antes de iniciar el headless adapter.
5. Validar con `timedatectl` que el sistema usa el Hardware Clock como referencia.

### 8.3 Pruebas de validación

| Prueba | Criterio de éxito |
|---|---|
| Boot sin DS3231 (batería removida) | DDS Time Sync corrige la hora en <30 segundos |
| Boot con DS3231 | Hora correcta desde el primer segundo |
| Desconexión del Supervisor | DS3231 mantiene hora; al reconectar, Time Sync valida |
| Deriva simulada del DS3231 | Time Sync detecta y corrige la diferencia |
| Reinicio abrupto (simulación de corte de luz) | DS3231 conserva hora; Pi arranca con tiempo correcto |

---

*Reporte generado a partir del análisis técnico de sincronización de tiempo para el despliegue de OpenICE en Raspberry Pi 3.*
