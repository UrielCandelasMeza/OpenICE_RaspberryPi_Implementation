# Reporte Técnico: Manejo de Fallos de Comunicación Device Adapter – Supervisor

**Fecha:** 20 de julio de 2026

**Sistema:** OpenICE / MD PnP – Comunicación entre Device Adapters y Supervisor vía DDS

**Alcance:** Mecanismos de detección y recuperación de fallos en conexiones LAN y Serial

---

## 1. Contexto

En la arquitectura OpenICE, los **device adapters** (Raspberry Pi u otros dispositivos embebidos) ejecutan controladores de dispositivos médicos y publican datos al **supervisor** (aplicación JavaFX de escritorio) a través de **DDS (RTI Connext)**. La comunicación puede realizarse por:

- **Serial (RS-232/USB):** Dispositivos médicos con puerto COM (ventiladores, bombas, pulsioxímetros)
- **LAN (TCP/UDP):** Dispositivos con conectividad de red (monitores Intellivue, Bernoulli)

Cada medio tiene escenarios de fallo distintos, pero ambas rutas comparten la misma **máquina de estados** y las **capas superiores** de detección (heartbeat DDS, liveliness, DeviceConnectivity).

---

## 2. Arquitectura de Detección de Fallos

### 2.1 Capa 1: Watchdog por Dispositivo (Aplicación)

Cada driver implementa su propio watchdog que detecta silencio en el canal de comunicación. El mecanismo varía según el medio y el dispositivo:

#### Serial – AbstractSerialDevice (Framework base para todos los drivers seriales)

**Archivo:** `interop-lab/demo-devices/src/main/java/org/mdpnp/devices/serial/AbstractSerialDevice.java`

El watchdog serial es el mecanismo más elaborado del sistema. Corre a **10 Hz** (cada 100ms) y tiene **dos funciones**:

**Función 1 — Detección de silencio (líneas 343-364):**

```java
// AbstractSerialDevice.java:343-364
protected void watchdog() {
    synchronized (stateMachine) {
        if (ice.ConnectionState.Connected.equals(getState())) {
            for(int idx = 0; idx < this.timeAwareInputStream.length; idx++) {
                TimeAwareInputStream tais = this.timeAwareInputStream[idx];
                if (null != tais) {
                    long quietTime = System.currentTimeMillis() - tais.getLastReadTime();
                    if (quietTime > getMaximumQuietTime(idx)) {
                        log.warn("WATCHDOG("+idx+") - back to Negotiating after " + quietTime + "ms");
                        stateMachine.transitionIfLegal(ice.ConnectionState.Negotiating,
                            "watchdog("+idx+") "+quietTime + "ms quiet time");
                    }
                }
            }
        }
    }
```

Si el estado es `Connected` y no se ha leído nada del puerto serial por más de `getMaximumQuietTime()` ms, transiciona a `Negotiating`. Esto fuerza la renegociación del protocolo.

**Función 2 — Reenvío de comandos de inicialización (líneas 366-387):**

```java
    synchronized (stateMachine) {
        if (ice.ConnectionState.Negotiating.equals(getState())) {
            for(int idx = 0; idx < AbstractSerialDevice.this.socket.length; idx++) {
                if (System.currentTimeMillis() >= (lastIssueInitCommands[idx] + getNegotiateInterval(idx))) {
                    lastIssueInitCommands[idx] = System.currentTimeMillis();
                    SerialSocket socket = AbstractSerialDevice.this.socket[idx];
                    if (null != socket) {
                        doInitCommands(idx);  // cada driver implementa su handshake
                    }
                }
            }
        }
    }
}
```

Cuando el estado es `Negotiating`, el watchdog reenvía los comandos de inicialización del protocolo cada `getNegotiateInterval()` ms. Cada driver concreto implementa `doInitCommands()` con sus bytes de handshake específicos.

**Sensor de datos — TimeAwareInputStream:**

```java
// TimeAwareInputStream.java:31-37
@Override
public int read() throws IOException {
    int r = super.read();
    if (r > 0) {
        lastRead = System.currentTimeMillis();
    }
    return r;
}
```

Envuelve el `InputStream` del puerto serial y actualiza `lastRead` en cada read exitoso. Cuando se establece conexión, `reportConnected()` llama `promoteLastReadTime()` para resetear el reloj y evitar un trigger inmediato del watchdog.

**Tabla de timeouts por dispositivo serial:**

| Dispositivo | Clase | MaxQuietTime | ConnectInterval | NegotiateInterval |
|---|---|---|---|---|
| Draeger Ventiladores | `AbstractDraegerVent` | 3000ms | 3000ms | 1000ms |
| GE Dash Serial | `DemoGESerial` | 3000ms | 500ms | 2500ms |
| Nellcor N-595 | `DemoN595` | 2200ms | 4000ms | 2000ms |
| Masimo Radical-7 | `DemoRadical7` | 1100ms | 20s (default) | 10s (default) |
| Oridion Capnostream20 | `DemoCapnostream20` | 900ms | 3000ms | 200ms |
| Nonin PulseOx | `DemoNoninPulseOx` | 3000ms | 20s (default) | 500ms |
| Alaris Asena Pump | `Asena` | 10000ms | 20s (default) | 10s (default) |
| PB840 (idx 0, comandos) | `DemoPB840` | 10000ms | 20s (default) | 10s (default) |
| PB840 (idx 1, waveforms) | `DemoPB840` | `Long.MAX_VALUE` | 20s (default) | 10s (default) |
| Baxter AS50 | `AS50` | 30000ms | 20s (default) | 10s (default) |
| VitalsBridge | `VitalsBridgeDevice` | 15000ms | 20s (default) | 10s (default) |

**Nota sobre PB840 idx 1:** El segundo stream (waveforms) tiene `MaxQuietTime = Long.MAX_VALUE`, lo que desactiva efectivamente el watchdog para ese stream — las waveforms se consideran opcionales.

**Caso especial — Keepalive proactivo en Draeger:**

```java
// AbstractDraegerVent.java:599-604
if ((now - timeAwareInputStream[0].getLastReadTime()) >= (getMaximumQuietTime(0) / 2L)) {
    medibus.sendCommand(Command.NoOperation);
    return;
}
```

El ventilador Draeger envía un `NoOperation` cuando la silencio alcanza la mitad del max quiet time (1500ms de 3000ms), de forma proactiva para evitar que el watchdog se active.

**Reconexión serial automática (inner class SerialDevice, líneas 262-340):**

```java
// AbstractSerialDevice.java:317-338
} finally {
    close(socket);
    AbstractSerialDevice.this.socket[idx] = null;
    AbstractSerialDevice.this.timeAwareInputStream[idx] = null;
    stateMachine.transitionIfLegal(ice.ConnectionState.Connecting, "serial port reached EOF, reconnecting...");
    AbstractSerialDevice.this.connect(idx);  // spawn nuevo thread de reconexión
}
```

Cuando `process()` retorna (EOF o IOException), se cierra el puerto, se transiciona a `Connecting`, y se llama `connect(idx)` que spawnea un nuevo thread con backoff de `getConnectInterval()` ms antes de reabrir el COM port. Este ciclo se repite indefinidamente.

#### Intellivue (Philips) – UDP/TCP con Protocolo de Asociación

**Archivo:** `interop-lab/demo-devices/src/main/java/org/mdpnp/devices/philips/intellivue/AbstractDemoIntellivue.java`

- **Mecanismo:** Watchdog corre cada **200ms** sobre el `NetworkLoop` (no es un timer separado)
- **Timeout de recepción:** 5 segundos sin ningún mensaje → transición a `Negotiating`
- **Keepalive activo:** Envía polls de atributos estáticos cada 4-8s si el canal está idle
- **Renegociación:** Automática en `AssociationRefuse` y `AssociationAbort` del protocolo

```java
// AbstractDemoIntellivue.java:195-256
case ice.ConnectionState._Connected:
    if (now - lastMessageReceived >= IN_CONNECTION_TIMEOUT) {
        // 5 segundos de silencio → renegociar
        state(ice.ConnectionState.Negotiating, "timeout receiving messages");
        return;
    } else if ((now - lastMessageReceived >= IN_CONNECTION_ASSERT 
                || now - lastMessageSentTime >= OUT_CONNECTION_ASSERT)
            && now - lastKeepAlive >= Math.min(OUT_CONNECTION_ASSERT, IN_CONNECTION_ASSERT)) {
        // Enviar keepalive poll
        myIntellivue.requestSinglePoll(ObjectClass.NOM_MOC_VMO_AL_MON, AttributeId.NOM_ATTR_GRP_VMO_STATIC);
        lastKeepAlive = now;
    }
    break;
```

#### NKV550 (Nihon Koden) – TCP Socket (sin reconexión)

**Archivo:** `interop-lab/demo-devices/src/main/java/org/mdpnp/devices/nihon/koden/NKV550.java`

- **Mecanismo:** Read loop con catch de `IOException`
- **NOTA:** **No tiene reconexión automática** — cuando el socket muere, el read loop termina sin intentar reconectar. Es la excepción al patrón.

```java
// NKV550.java:933-971
try {
    xmlDoc = getNextBlock();
    processChildren(xmlDoc);
} catch (IOException e) {
    e.printStackTrace();  // solo log, NO reconecta
}
```

---

### 2.2 Capa 2: Máquina de Estados (Nervio Central)

**Archivo:** `interop-lab/demo-devices/src/main/java/org/mdpnp/devices/connected/AbstractConnectedDevice.java`

Toda la vida del ciclo de conexión está gobernada por una máquina de estados formal con transiciones legales definidas.

#### Estados

```
Initial → Connecting → Negotiating → Connected
                                   → Connecting (reconexión tras fallo fatal)
                                   → Terminal (error irrecuperable)
```

#### Transiciones Legales (AbstractConnectedDevice.java:116-141)

```java
private static final ice.ConnectionState[][] legalTransitions = new ice.ConnectionState[][] {
    { Initial, Connecting },      // solicitud de conexión
    { Connecting, Negotiating },  // conexión TCP/UDP establecida
    { Connected, Negotiating },   // sesión perdida pero socket abierto
    { Connected, Connecting },    // error fatal, reconectando
    { Negotiating, Connected },   // negociación exitosa
    { Negotiating, Connecting },  // conexión perdida durante negociación
    { Negotiating, Terminal },    // error fatal
    { Connecting, Terminal },     // error fatal
    { Connected, Terminal },      // error fatal
};
```

#### Propagación al Supervisor

Cada transición emite un nuevo estado al DataWriter de DDS:

```java
// AbstractConnectedDevice.java:41-55
public void emit(ice.ConnectionState newState, ice.ConnectionState oldState, String transitionNote) {
    stateChanging(newState, oldState, transitionNote);
    deviceConnectivity.state = newState;
    deviceConnectivity.info = transitionNote;
    writeDeviceConnectivity();  // escribe a DDS → supervisor recibe
    stateChanged(newState, oldState, transitionNote);
};
```

Al perder el estado `Connected`, se desregistan todas las instancias de datos:

```java
// AbstractConnectedDevice.java:62-69
protected void stateChanged(ice.ConnectionState newState, ice.ConnectionState oldState, String transitionNote) {
    if (ice.ConnectionState.Connected.equals(oldState) && !ice.ConnectionState.Connected.equals(newState)) {
        eventLoop.doLater(() -> unregisterAllInstances());  // elimina numéricos/waveform de DDS
    }
}
```

---

### 2.3 Capa 3: Heartbeat DDS (TimeManager)

**Archivo:** `interop-lab/demo-devices/src/main/java/org/mdpnp/devices/TimeManager.java`

Mecanismo de keepalive a nivel de aplicación sobre DDS, independiente del watchdog por dispositivo.

| Parámetro | Valor | Fuente |
|---|---|---|
| Intervalo de escritura | 2 segundos | `TimeManager.java:283` |
| QoS liveliness | `MANUAL_BY_TOPIC` | `ice_library.xml` (heartbeat profile) |
| Lease de escritura | 3 segundos | `ice_library.xml` |
| Lease de lectura | 5 segundos | `ice_library.xml` |

#### Flujo de heartbeat

1. **Escritura:** `TimeManager` escribe un `HeartBeat` al topic DDS cada 2s
2. **Detección:** Si el adapter muere, DDS detecta `NOT_ALIVE_INSTANCE_STATE` en el topic
3. **Respuesta:** `TimeManagerListener.notAliveHeartbeat()` → `DeviceListModelImpl.deactivateDevice()` → el dispositivo desaparece de la UI del supervisor

```java
// DeviceListModelImpl.java:113-123
public void notAliveHeartbeat(final String unique_device_identifier, final String type) {
    if("Device".equals(type)) {
        log.debug(unique_device_identifier + " IS NO LONGER ALIVE");
        runLaterOnPlatform(() -> {
            Device d = findDevice(unique_device_identifier);
            if(d != null) deactivateDevice(d);  // remueve de la lista de UI
        });
    }
}
```

---

### 2.4 Capa 4: Liveliness DDS (Participante)

**Archivo:** `data-types/x73-idl/src/main/idl/ice/samples/ice_library.xml` (líneas 83-96)

Configuración del liveliness a nivel de participante DDS:

```xml
<!-- "Participant liveliness is no longer used to detect device connectivity" -->
<participant_liveliness_lease_duration>
    <sec>10</sec>
</participant_liveliness_lease_duration>
<participant_liveliness_assert_period>
    <sec>3</sec>
</participant_liveliness_assert_period>
```

**Nota crítica:** Hay un comentario explícito en el XML indicando que el liveliness de participante **ya no se usa** para detectar conectividad de dispositivos. El sistema confía en sus mecanismos propios (heartbeat + state machine). Si el proceso adapter muere completamente, el participante DDS pierde liveliness en ~10s como respaldo.

---

### 2.5 Capa 5: DeviceConnectivity (Señalización al Supervisor)

**Archivo:** `interop-lab/demo-devices/src/main/java/org/mdpnp/devices/connected/AbstractConnectedDevice.java`

El adapter publica `DeviceConnectivity` sobre DDS con el QoS profile `state`:

| Parámetro | Valor | Propósito |
|---|---|---|
| Reliability | `RELIABLE` | Garantiza entrega del estado |
| Durability | `TRANSIENT_LOCAL` | Late joiners reciben último estado |
| Liveliness | `AUTOMATIC`, lease 5s | Detecta muerte del writer |

El supervisor lo recibe en `DeviceListModelImpl.java:153-167` y actualiza el modelo:

```java
// Device.java:280-285
public void setDeviceConnectivity(DeviceConnectivity deviceConnectivity) {
    connectivityStateProperty().set(deviceConnectivity.state);
    connectivityInfoProperty().set(deviceConnectivity.info);
    connectedProperty().set(ice.ConnectionState.Connected.equals(deviceConnectivity.state));
}
```

---

## 3. Flujo Completo en Fallo

### 3.1 Fallo Serial

```
┌──────────────────────────────────────────────────────────────────┐
│ 1. Puerto serial se cae (cable desconectado, dispositivo apagado)│
│                                                                  │
│ 2. Watchdog (100ms) detecta silencio en TimeAwareInputStream:    │
│    - Sin datos por > getMaximumQuietTime() (varía por driver)    │
│    - Transición: Connected → Negotiating                         │
│                                                                  │
│ 3. Watchdog reenvía doInitCommands() cada getNegotiateInterval:  │
│    - Cada driver envía sus bytes de handshake específicos        │
│    - Draeger envía NoOperation cada 1500ms como keepalive        │
│                                                                  │
│ 4. Si el process() thread muere (EOF/IOException):               │
│    - Se cierra el puerto                                         │
│    - Transición: → Connecting                                    │
│    - Nuevo thread con backoff de getConnectInterval() ms         │
│    - Se reabre el COM port via SerialProvider                    │
│                                                                  │
│ 5. Si reconecta:                                                 │
│    Connecting → Negotiating → Connected                          │
│    → reportConnected() resetea el reloj del watchdog             │
│                                                                  │
│ 6. Si el adapter entero muere:                                   │
│    → heartbeat not_alive tras 5s → dispositivo removido de UI    │
└──────────────────────────────────────────────────────────────────┘
```

### 3.2 Fallo LAN

```
┌──────────────────────────────────────────────────────────────────┐
│ 1. Socket se cae (cable, switch, timeout, firewall)              │
│                                                                  │
│ 2. Watchdog detecta silencio:                                    │
│    - Intellivue: 5s sin mensajes → Negotiating                   │
│    - Intellivue envía keepalive polls cada 4-8s si idle          │
│                                                                  │
│ 3. Máquina de estados transiciona:                               │
│    Connected → Connecting (o Negotiating)                        │
│    → writeDeviceConnectivity() notifica al supervisor vía DDS    │
│                                                                  │
│ 4. Intento de reconexión con backoff:                            │
│    - Intellivue: reenvío de AssociationRequest cada 2s           │
│                                                                  │
│ 5. Si reconecta:                                                 │
│    Connecting → Negotiating → Connected                          │
│    → unregisterAllInstances() → re-registra instancias           │
│                                                                  │
│ 6. Si el adapter entero muere:                                   │
│    → heartbeat not_alive tras 5s → dispositivo removido de UI    │
│    → participant liveliness expira tras ~10s (respaldo)          │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Comparación: Serial vs LAN

| Aspecto | Serial | LAN |
|---|---|---|
| **Clase base** | `AbstractSerialDevice` | `AbstractConnectedDevice` (directamente) |
| **Watchdog** | 100ms (10 Hz), detecta silencio en `TimeAwareInputStream` | Por driver (200ms Intellivue, 4s Bernoulli) |
| **Timeout de silencio** | Configurable por driver (900ms – 30s) | 5s Intellivue, 4s Bernoulli |
| **Reconexión** | Nuevo thread con `getConnectInterval()` backoff, reabre COM port | Reabre socket TCP/UDP, renegotiación de protocolo |
| **Backoff default** | 20 segundos (override por driver) | 5s Bernoulli, 2s Intellivue |
| **Negociación de protocolo** | `doInitCommands()` reenvía handshake cada `getNegotiateInterval()` | Association requests (Intellivue) |
| **Keepalive proactivo** | Draeger: `NoOperation` a la mitad del max quiet time | Intellivue: polls de atributos estáticos cada 4-8s |
| **Heartbeat DDS** | Mismo mecanismo (TimeManager, 2s) | Mismo mecanismo (TimeManager, 2s) |
| **Liveliness DDS** | Mismo mecanismo | Mismo mecanismo |
| **DeviceConnectivity** | Mismo mecanismo | Mismo mecanismo |
| **Capas de detección** | 4 (watchdog + state machine + heartbeat + DeviceConnectivity) | 5 (watchdog + state machine + heartbeat + liveliness + DeviceConnectivity) |
| **Proveedor de puerto** | `PureJavaCommSerialProvider` (purejavacomm) | `TCPSerialProvider` (socket TCP) |

---

## 5. Archivos Clave Referenciados

| Archivo | Ruta | Rol |
|---|---|---|
| AbstractConnectedDevice | `interop-lab/demo-devices/.../connected/AbstractConnectedDevice.java` | Máquina de estados, emisión DDS |
| AbstractSerialDevice | `interop-lab/demo-devices/.../serial/AbstractSerialDevice.java` | Watchdog serial (100ms), reconexión de COM port |
| StateMachine | `devices/common/.../io/util/StateMachine.java` | Transiciones atómicas sincronizadas |
| TimeManager | `interop-lab/demo-devices/.../TimeManager.java` | Heartbeat DDS cada 2s |
| AbstractDemoIntellivue | `interop-lab/demo-devices/.../philips/intellivue/AbstractDemoIntellivue.java` | Watchdog UDP/TCP + renegotiación |
| NKV550 | `interop-lab/demo-devices/.../nihon/koden/NKV550.java` | Sin reconexión automática (excepción) |
| TimeAwareInputStream | `interop-lab/demo-devices/.../connected/TimeAwareInputStream.java` | Tracking de último read válido |
| SerialProviderFactory | `interop-lab/demo-devices/.../serial/SerialProviderFactory.java` | Factory de proveedores serial |
| PureJavaCommSerialProvider | `interop-lab/demo-purejavacomm/.../PureJavaCommSerialProvider.java` | Apertura de COM ports via purejavacomm |
| TCPSerialProvider | `interop-lab/demo-devices/.../serial/TCPSerialProvider.java` | Serial-over-TCP (fallback) |
| DeviceListModelImpl | `interop-lab/demo-apps/.../DeviceListModelImpl.java` | Recepción de heartbeat en supervisor |
| Device | `interop-lab/demo-apps/.../Device.java` | Modelo de estado en supervisor |
| ice_library.xml | `data-types/x73-idl/.../ice/samples/ice_library.xml` | Configuración QoS (heartbeat, state, liveliness) |

---

## 6. Limitaciones y Riesgos Identificados

1. **NKV550 sin reconexión automática:** Si el socket se cae, el device adapter queda inactivo sin intentar reconectar. Requiere reinicio manual del adapter.

2. **Watchdog serial depende de silencio total:** Si el dispositivo envía datos corruptos o parciales (no completamente silenciados), el watchdog no se activa y el sistema puede permanecer en estado `Connected` con datos degradados.

3. **Backoff serial configurable pero no exponencial:** El intervalo de reconexión es fijo por driver (no exponencial). En escenarios de puerto inestable, esto podría generar reconexiones excesivas o insuficientes.

4. **PB840 idx 1 sin watchdog:** El stream de waveforms del PB840 tiene `MaxQuietTime = Long.MAX_VALUE`, lo que desactiva la detección de silencio para ese stream. Si las waveforms se pierden, el sistema no lo detecta.

5. **Liveliness de participante como respaldo débil:** El lease de 10s es largo comparado con los 5s del heartbeat. Si el heartbeat falla, el sistema tarda hasta 10s adicionales en detectar la muerte del participante.

6. **No hay mecanismo de health check proactivo:** El sistema reacciona a fallos pero no verifica proactivamente la salud del canal antes de enviar datos críticos.

---

*Reporte generado a partir del análisis del código fuente de OpenICE / MD PnP.*
