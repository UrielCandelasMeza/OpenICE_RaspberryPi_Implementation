# Caso de Uso: Interfaz Raspberry Pi (Nodo de Quirófano)

**ID:** UC-RPI-001
**Versión:** 1.1
**Fecha:** 2026-08-27
**Actor Principal:** Biomédica (administración) y Anestesista (operación)
**Sistema:** Interfaz Raspberry Pi (RPi + pantalla OLED + botones de navegación)
**Alcance:** Flujo de configuración, operación, redundancia y seguridad del nodo de quirófano basado en Raspberry Pi.

---

## 1. Breve Descripción

La Raspberry Pi es el nodo de quirófano de OpenICE: su hardware está compuesto por una pantalla **OLED**, **botones de navegación**, una **interfaz gráfica** y **conectividad a internet** (además de la red LAN/DDS). Su función es ejecutar los *headless adapters* de los dispositivos médicos conectados al paciente y servir de puente con el **Supervisor** (tipo Android).

El nodo **no adjudica** veredictos: su fuente de verdad única es la **API propia**, que a su vez mantiene la base de datos central de perfiles, dispositivos y su ubicación. La API del hospital provee los datos clínicos (quirófanos activos, nombre completo del paciente, EMN/CURP y cirugías activas). La Raspberry Pi solo **consulta y aplica** el estado que le dicta la API, y mantiene una **copia local en SQLite** para operar sin conexión.

Existen **solo dos niveles de privilegio**: **Biomédica** (administración) y **Anestesista** (operación en sala).

---

## 2. Actores y Niveles de Privilegio

| Nivel | Rol | Responsabilidades | PIN |
|---|---|---|---|
| **Administración** | **Biomédica** | Crear / cambiar / limpiar perfiles. Mover y reasignar dispositivos entre quirófanos. Sustitución temporal o permanente de devices. Disparar el failover de emergencia raspi1→raspi2. Generar y validar el QR de conexión. Gestionar registros/logs. | Sí (PIN biomédica) |
| **Operación** | **Anestesista** | Operar el dispositivo en sala. Ver datos y gráficas. Probar medical devices antes de operar. Escanear el QR para abrir el Supervisor. | Sí (PIN de operación) |

**Regla estructural:** la creación/cambio de perfiles y el movimiento de dispositivos es **exclusivo de Biomédica**; el Anestesista **no** tiene acceso de emergencia a esas acciones. El Anestesista solo opera en sala.

---

## 3. Arquitectura de Componentes: Daemons y Logs

El nodo se implementa con **procesos (daemons) independientes**, no con hilos dentro de un único proceso, para aislar fallos y hacerlos recuperables por separado:

| Daemon | Responsabilidad |
|---|---|
| `daemon-ui` | Interfaz OLED, navegación y renderizado. |
| `daemon-db` | Acceso al almacenamiento local SQLite y caché de estado. |
| `daemon-api` | Cliente de la API propia y de la API del hospital (sincronización). |
| `daemon-device-<N>` | Un headless adapter por dispositivo médico (inicialmente 3). |

**Justificación de usar daemons:** un proceso de dispositivo que falle (colgado, saturación de memoria, caída de protocolo serial/TCP) **no derriba** la UI ni los demás dispositivos. Además, cada daemon es reiniciable de forma independiente por un watchdog y produce sus **logs**.

**Logs:** cada daemon escribe su propio log (p. ej. `~/daemon-device-<N>.log`), y existe un log consolidado y fácilmente consultable. Los logs no contienen datos clínicos sensibles (solo referencias no sensibles y marcas de evento).

---

## 4. Almacenamiento Local y Modo Offline (SQLite)

Al conectarse, el nodo consulta la **API propia** y la **API del hospital** y **persiste en SQLite local**:

- Quirófanos y perfiles existentes.
- Dispositivos (headless) y su asignación.
- **PIN** (almacenado **hasheado**, nunca en claro).
- Configuración del nodo (IP, domain, etc.).
- Estado y vigencia del *lapso* de sustitución.
- Registro/log de reasignaciones y de auditoría.
- Estado local del paciente/perfil usado por el QR.

**Modo offline:** si la API no está disponible (sin internet o servidor caído), el nodo opera con la **última copia SQLite**. La fuente de verdad sigue siendo la API/DB central: la Raspberry Pi **no decide** por sí misma, solo **aplica la última regla cacheada**. Cuando la conexión se restablece, sincroniza.

> **Dependencia técnica:** SQLite no está presente en el proyecto. Requiere añadir la dependencia de driver (p. ej. `sqlite-jdbc`). Este patrón (SQLite como buffer de continuidad en el *edge*) ya está respaldado en `docs/architecture/Propuesta de Arquitectura de Comunicación (DDS + MQTT).md`.

---

## 5. Prueba de Medical Devices

El flujo de prueba es **secuencial y simple**:

1. Probar el **primer** headless adapter.
2. Si hay **conexión y envío de datos dentro de 1 minuto** → pasar al siguiente headless.
3. Repetir con el segundo y el tercero.

Todos los resultados (éxito/fallo, tiempos, métricas recibidas) quedan registrados en los **logs** del daemon correspondiente y en el log consolidado.

---

## 6. Verificación de Paciente en Cirugía (por si las dudas)

Antes de **habilitar un perfil** y de operar los dispositivos, el nodo debe **confirmar que el paciente está en cirugía activa**:

- Consulta la API del hospital (y usa la caché local en offline) para comprobar que el paciente asociado al quirófano está marcado como **en cirugía activa**.
- Si el paciente **no** está en cirugía, el nodo muestra un aviso y solicita confirmación explícita antes de habilitar el perfil (no lo habilita de forma silenciosa).

---

## 7. Failover de Emergencia: Rasp1 → Rasp2

Para "sacar la bronca" si la raspberry 1 se quema o falla, se usa el patrón de **cold-spare con reaprovisionamiento desde la fuente de verdad**:

1. **Biomédica** enciende la **raspberry 2**, que arranca en modo *provisioning*.
2. La raspberry 2 se autentica con el **PIN de biomédica** + consulta a la **API/DB** (decisión: solo PIN + DB).
3. La **API/DB reconstruye el perfil del quirófano** al vuelo: domain, dispositivos, paciente, configuración y estado del lapso.
4. Al haber solo un dispositivo/raspi activo por quirófano, la DB **marca la raspberry 1 como inactiva** y asigna a la raspberry 2.

**Por qué esta solución y no otras:**

- **Hot-standby (activo en paralelo):** dos escritores DDS con el mismo UDI de dispositivo generan **colisiones y duplicados** en el tópico; el QoS de DDS (`state`/`observed_data`) no tolera bien dos publicadores idénticos. Técnicamente problemático y oneroso.
- **Cold-spare manual:** exige reconfigurar a mano en plena emergencia → **error humano** bajo presión.
- **Cold-spare con reaprovisionamiento (elegida):** simple, idempotente, sin proceso DDS duplicado ni gasto de recursos, y cumple que **la raspberry no decide quién es**: lo controla la DB. Es *fail-stop + reaprovisionamiento*, no *failover activo*.

**Invariante:** solo puede haber **una raspberry/dispositivo activo por quirófano, nunca más de uno.**

---

## 8. Pantalla Principal

Tras aceptar un perfil, el nodo muestra:

- **IP del dispositivo**
- **Domain** (asignado por la API propia)
- **Fecha y hora**
- **Headless** activos
- **Nombre del perfil**
- **Quirófano asignado**

A la derecha, iconos/acciones para: **Configuración**, **Añadir otro headless**, **Cambiar perfil**, **Probar medical devices** y **QR de conexión**.

---

## 9. QR de Conexión

El nodo ofrece la opción **"QR de conexión"**:

- **Quién lo genera y valida:** Biomédica (requiere PIN biomédica; el QR se genera bajo demanda y con caducidad).
- **Quién lo escanea:** el Anestesista, para **abrir el Supervisor** y validar la conexión.
- **Contenido (payload) del QR:**
  - **`domain`** — asignado por la **API**.
  - **Headless activos**.
  - **Datos del paciente** — tomados **del SQLite local**, no de la API.
- **Fallback:** si la cámara del Supervisor no funciona, el `domain` se **ingresa manualmente**; se muestra en la pantalla del QR **y** en la pantalla principal del nodo.

**Justificación del QR:** funciona como **verificación cruzada de integridad** entre lo que la raspberry cree que es/hace (estado local SQLite) y lo que el **Supervisor ve en el bus DDS**. Ni la raspberry ni el supervisor se fían ciegamente.

**Seguridad del QR:** se genera solo bajo demanda, con PIN de biomédica y con **caducidad**, porque contiene datos del paciente (PHI).

---

## 10. Flujo Principal (Happy Path)

```
UC-RPI-001: Configurar y operar el nodo Raspberry Pi
  1. La Raspberry Pi se enciende. Muestra el logo de la empresa.
  2. El sistema consulta la API propia (y, si hay red, la del hospital)
     y sincroniza la copia local SQLite.
  3. Se muestra la lista de perfiles existentes (uno por quirófano)
     con la opción "Crear perfil".
  4. (Creación, por Biomédica):
     4.1 Se solicita el PIN de biomédica.
     4.2 Se selecciona el quirófano. Si el quirófano ya tiene perfil,
         se marca error.
     4.3 Se verifica que el paciente esté en cirugía activa (sección 6).
     4.4 Se seleccionan los 3 headless adapters (uno de cada tipo),
         eligiendo de la lista de headless disponibles.
     4.5 Se muestra un resumen del perfil y Biomédica acepta.
  5. Se muestran la pantalla principal (sección 8) con IP, domain,
     fecha/hora, headless, perfil y quirófano.
  6. (Operación, por Anestesista):
     6.1 El Anestesista prueba los medical devices de forma secuencial
         (1 minuto por headless; sección 5) y revisa los logs.
     6.2 Biomédica genera el QR de conexión; el Anestesista lo escanea
         (o ingresa el domain manualmente) para abrir el Supervisor.
  7. El Supervisor se conecta y muestra los 3 dispositivos con sus
     métricas y gráficas (ver Implementacion OpenICE Quirofano).
```

---

## 11. Flujos Alternos

### FA-01: API/hospital no disponible (modo offline)
1. El nodo no alcanza la API propia ni la del hospital.
2. Opera con la última copia SQLite.
3. Muestra un aviso de modo offline.
4. Cuando la conexión se restablece, sincroniza.

### FA-02: Quirófano ya tiene perfil
1. Biomédica intenta crear un perfil para un quirófano ya asignado.
2. El sistema marca error y no permite la creación duplicada.

### FA-03: Dispositivo no registrado
1. Biomédica intenta añadir un headless no registrado en la DB.
2. El sistema no permite agregarlo.

### FA-04: Sustitución de dispositivo perteneciente a otro perfil
1. Biomédica añade un device que está registrado en otro perfil.
2. El sistema registra en la DB: *dispositivo X del quirófano Y ahora en el quirófano Z*.
3. Se solicita el lapso de tiempo.
4. La operación **siempre** exige PIN de biomédica y deja auditoría (sin excepción por duración).
5. Si el device no llegara a estar registrado, no se permite la sustitución.

### FA-05: PIN incorrecto / bloqueo
1. Se ingresa un PIN incorrecto.
2. Tras N intentos fallidos, el acceso se bloquea temporalmente y queda en el log de auditoría.

### FA-06: Failover de emergencia raspi1→raspi2
1. La raspberry 1 se daña o deja de operar.
2. Biomédica enciende la raspberry 2 (modo provisioning).
3. PIN de biomédica + consulta a la API/DB.
4. La DB reconstruye el perfil y marca la raspi1 como inactiva.
5. La raspi2 queda como único dispositivo activo del quirófano.

### FA-07: Lapso de sustitución expirado
1. Transcurre el *lapso* de una sustitución temporal.
2. El sistema **regresa automáticamente a la configuración original** (decidido por la API/DB, no por la raspi).
3. Se ignora la presencia del device/raspi de reemplazo dentro del perfil.

### FA-08: QR no cuadra con el Supervisor
1. El Supervisor escanea el QR.
2. Los datos del QR (domain, headless activos, paciente desde SQLite) no corresponden con lo que el Supervisor ve en el bus DDS.
3. Se muestra un aviso de discrepancia y se solicita revisión de Biomédica antes de operar.

---

## 12. Reglas de Negocio

| ID | Regla |
|---|---|
| RN-01 | La creación/cambio de perfiles y el movimiento de dispositivos es **exclusivo de Biomédica**. |
| RN-02 | **Toda reasignación entre quirófanos** (temporal o permanente) **exige PIN de biomédica y deja auditoría** — sin excepción por duración. El *lapso* solo gobierna el auto-revert de estado, no la autorización. |
| RN-03 | La **fuente de verdad** es la API propia/DB; la Raspberry Pi solo consulta y aplica, no adjudica. |
| RN-04 | Solo existe **un dispositivo/raspi activo por quirófano, nunca más de uno**. |
| RN-05 | Se mantiene una **copia local SQLite** (quirófanos, perfiles, devices, PIN hasheado, config, estado de lapso, log de reasignación, estado local para QR). |
| RN-06 | Antes de habilitar un perfil se debe **confirmar que el paciente está en cirugía activa** (API + caché local). |
| RN-07 | El **QR de conexión** lleva `domain` (API) + headless activos + datos del paciente (**desde SQLite**) y sirve para que el Supervisor valide contra el bus DDS. |
| RN-08 | El PIN se almacena **hasheado**, nunca en claro. |
| RN-09 | El estado del *lapso* y el failover se resuelven desde la **API/DB** para que el apagado o el reloj de la raspi no lo evadan. |

---

## 13. Seguridad (PHI)

- **PIN:** de 5 dígitos, **hasheado**, con **bloqueo tras N intentos** y **auditoría** de toda acción sensible.
- **Datos del paciente** (nombre completo, EMN/CURP) se tratan como **información médica protegida (PHI)**:
  - **TLS** en tránsito hacia las APIs.
  - **Cifrado en reposo** en SQLite.
  - La CURP/EMN **no** se expone innecesariamente en pantalla ni en logs.
- **"Limpiar perfil":** elimina todos los ajustes del perfil, **sobrescribiendo los datos**, pero deja la entidad lógica del perfil intacta. Requiere PIN (acceso a configuración).
- **QR:** se genera bajo demanda, con PIN y **caducidad**, por contener datos del paciente.
- **Logs:** sin datos clínicos sensibles; solo referencias no sensibles y marcas de evento.

---

## 14. Requisitos No Funcionales

| ID | Requisito |
|---|---|
| RNF-01 | Resiliencia a apagón/reinicio: los **daemons se reinician automáticamente** (autostart, p. ej. `device_adapter.sh`) y el estado se recupera desde SQLite. |
| RNF-02 | Existe un **watchdog** que reinicia daemons individuales sin afectar a los demás. |
| RNF-03 | El *lapso* y el estado de reasignación se aplican desde la **API/DB** aunque la raspberry haya estado apagada al expirar. |
| RNF-04 | El failover raspi1→raspi2 debe completarse en un tiempo acotado (re-aprovisionamiento desde la DB). |
| RNF-05 | La prueba de medical devices valida **conexión y envío de datos en ≤1 minuto** por headless, con resultados a logs. |
| RNF-06 | Los logs son **fácilmente consultables** (por daemon y consolidados). |
| RNF-07 | El modo offline opera con la última copia SQLite y vuelve a sincronizar al restablecer la conexión. |

---

## 15. Notas y Observaciones

- El nodo se complementa con **`Implementacion OpenICE Quirofano.md`** (UC-QUI), que describe el Supervisor y el flujo tras la conexión (gráficas, unidades, envío FHIR).
- Los **perfiles** —entidades lógicas que definen con qué dispositivos se realiza la conexión— quedan definidos en su documento de referencia.
- SQLite requiere añadir la dependencia (`sqlite-jdbc`) al proyecto; no está presente actualmente.
- El **flujo de prueba de medical devices** y el **inicio de cirugía** (que activa el envío FHIR) están descritos con más detalle en el documento del Supervisor.

---

## 16. Historial de Revisiones

| Versión | Fecha | Autor | Cambio |
|---|---|---|---|
| 1.0 | — | — | Versión inicial (borrador de requisitos). |
| 1.1 | 2026-08-27 | — | Especificación formal: roles (Biomédica/Anestesista), modo offline SQLite, daemons, failover raspi1→raspi2, QR de conexión, verificación de paciente en cirugía, seguridad PHI. |

---

*Caso de uso generado a partir de la revisión de los requisitos de la interfaz Raspberry Pi y su reconciliación con el documento de implementación del quirófano.*
