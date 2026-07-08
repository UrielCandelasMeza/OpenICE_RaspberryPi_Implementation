# Caso de Uso: Data Recorder (Grabación de Datos de Dispositivos Médicos)

**ID:** UC-DR-001  
**Versión:** 1.0  
**Fecha:** 2026-07-08  
**Actor Principal:** Clínico / Ingeniero Biomédico  
**Sistema:** OpenICE — DataCollectorApp (Módulo Data Recorder)

---

## 1. Breve Descripción

El Data Recorder permite al usuario seleccionar uno o más dispositivos médicos conectados a la red OpenICE, elegir un mecanismo de persistencia (CSV, SQL, MongoDB o Verilog VCD), y grabar en tiempo real los datos fisiológicos (numéricos, formas de onda y evaluaciones clínicas) que dichos dispositivos publican a través del bus DDS. La grabación puede iniciarse y detenerse manualmente, y los datos pueden filtrarse por dispositivo, métrica e instancia mediante un árbol de selección.

---

## 2. Actores

| Actor | Descripción |
|---|---|
| **Usuario** (Clínico / Ingeniero) | Opera la interfaz gráfica del Data Recorder: selecciona dispositivos, elige persister, inicia/detiene la grabación. |
| **Dispositivo Médico ICE** | Cualquier dispositivo (simulado o real) conectado a OpenICE que publique tópicos DDS (Numeric, SampleArray, PatientAssessment). |
| **Bus DDS (RTI Connext)** | Middleware de datos en tiempo real que transporta las muestras desde los dispositivos hacia el Data Recorder. |

---

## 3. Precondiciones

1. El sistema OpenICE debe estar en ejecución con al menos un dispositivo médico (real o simulador) conectado y publicando datos en el bus DDS.
2. El Data Recorder debe estar instalado y registrado como `IceApplicationProvider` en el SPI (`org.mdpnp.apps.testapp.export.DataCollectorAppFactory`).
3. La interfaz de usuario del Data Recorder debe haberse cargado correctamente (ventana con árbol de dispositivos, tabla de vista previa, panel de persisters y botón Start/Stop).
4. Para JDBC: la base de datos destino debe ser accesible desde la red y la tabla `VITAL_VALUES` debe existir (ver `DbSchema.sql`).
5. Para MongoDB: el servidor MongoDB debe estar en ejecución y el script JavaScript (`MongoPersisterWF.js`) debe ser válido.
6. Para CSV: el directorio de salida debe tener permisos de escritura.
7. Para Verilog VCD: el directorio de salida debe tener permisos de escritura.

---

## 4. Postcondiciones

### Postcondiciones de Éxito
1. Los datos de los dispositivos seleccionados se almacenan en el medio de persistencia elegido (archivo CSV, base de datos SQL, MongoDB o archivos VCD) en el formato correspondiente.
2. El archivo/base de datos contiene los valores numéricos, formas de onda y observaciones generadas durante el período de grabación.
3. La interfaz muestra en la tabla de vista previa las últimas 250 muestras registradas como confirmación visual.

### Postcondiciones de Fracaso
1. No se persiste ningún dato si no se inicia la grabación o si ocurre un error durante el inicio.
2. Si ocurre un error durante la grabación, se muestra un diálogo de error al usuario y la grabación se detiene.
3. Los datos que ya estaban almacenados antes del error no se pierden (el persister termina el lote actual de manera segura).

---

## 5. Flujo Principal (Happy Path)

```
┌─────────────────────────────────────────────────────────────────┐
│  UC-DR-001: Grabar datos de dispositivos médicos               │
├─────────────────────────────────────────────────────────────────┤
│  1. El usuario abre la aplicación Data Recorder desde el menú   │
│     de aplicaciones de OpenICE.                                 │
│  2. El sistema carga la interfaz con:                           │
│     - Árbol de dispositivos (izquierda)                         │
│     - Tabla de vista previa (centro)                            │
│     - Radio buttons de persisters (abajo-izquierda)             │
│     - Panel de configuración del persister (abajo-centro)       │
│     - Botón "Start" / "Stop" (abajo-derecha)                    │
│  3. El sistema comienza a recibir datos de los dispositivos     │
│     a través del bus DDS y los muestra en el árbol.             │
│  4. El usuario selecciona los dispositivos, métricas e          │
│     instancias que desea grabar marcando checkboxes en el       │
│     árbol de dispositivos. Por defecto, todo está seleccionado. │
│  5. El usuario selecciona un persister haciendo clic en un      │
│     radio button (CSV, SQL, VCD o MongoDB).                     │
│  6. El sistema muestra el panel de configuración del            │
│     persister seleccionado.                                     │
│  7. El usuario configura los parámetros del persister:          │
│     - CSV: ruta del archivo, número de backups, tamaño máximo  │
│       y separador.                                              │
│     - SQL: driver JDBC, URL, usuario y contraseña.             │
│     - MongoDB: host, puerto, nombre de base de datos y         │
│       script JavaScript.                                        │
│     - VCD: (solo lectura, usa la fecha actual como directorio). │
│  8. El usuario hace clic en el botón "Start".                   │
│  9. El sistema valida la configuración del persister.           │
│ 10. El sistema inicializa el persister (abre archivo/conexión). │
│ 11. El sistema cambia la etiqueta del botón a "Stop".          │
│ 12. El sistema comienza a recibir eventos de datos desde los    │
│     DataCollectors (NumericsDataCollector,                       │
│     SampleArrayDataCollector, PatientAssessmentDataCollector).  │
│ 13. Por cada muestra recibida:                                  │
│     13.1 El DataCollector emite un evento DataSampleEvent.      │
│     13.2 El DataFilter filtra según las selecciones del árbol.  │
│     13.3 Si el sample está habilitado, se envía al Persister    │
│          activo y se agrega a la tabla de vista previa.         │
│     13.4 El Persister almacena la muestra (CSV: escribe línea,  │
│          SQL: INSERT, MongoDB: ejecuta script JS, VCD: escribe  │
│          cambio de valor).                                      │
│ 14. [Opcional] El usuario marca/desmarca "Raw Times" para       │
│     alternar entre timestamp formateado y epoch ms.             │
│ 15. El usuario hace clic en el botón "Stop".                    │
│ 16. El sistema desregistra el listener del persister activo.    │
│ 17. El sistema cierra el persister (cierra archivo/conexión).   │
│ 18. El sistema cambia la etiqueta del botón a "Start".          │
│ 19. El sistema queda listo para una nueva grabación.            │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. Flujos Alternos

### FA-01: Cambiar de persister durante la grabación
```
1. El usuario selecciona un radio button de otro persister 
   mientras la grabación está activa.
2. El sistema detiene automáticamente el persister actual 
   (misma lógica que Stop).
3. El sistema carga el panel de configuración del nuevo persister.
4. El usuario debe hacer clic en "Start" para iniciar la 
   grabación con el nuevo persister.
```

### FA-02: Seleccionar/deseleccionar dispositivos en el árbol
```
1. Durante la grabación, el usuario desmarca un dispositivo 
   en el árbol de dispositivos.
2. El DataFilter deja de enviar eventos de ese dispositivo 
   al persister activo.
3. La tabla de vista previa deja de mostrar muestras de 
   ese dispositivo (aunque las muestras anteriores 
   permanecen en la tabla).
4. El persister ya no recibe datos de ese dispositivo.
```

### FA-03: Cambiar "Raw Times" durante la grabación
```
1. El usuario marca/desmarca el checkbox "Raw Times" mientras 
   la grabación está activa.
2. La tabla de vista previa comienza a mostrar los timestamps 
   en el nuevo formato (epoch ms o fecha formateada).
3. El persister activo recibe la señal setRawDateFormat() 
   y ajusta el formato de salida (si el persister lo soporta).
```

### FA-04: Persister no disponible
```
1. El usuario hace clic en "Start".
2. El persister falla al inicializar (archivo no escribible, 
   base de datos inaccesible, MongoDB caído, etc.).
3. El sistema captura la excepción.
4. El sistema muestra un diálogo de error al usuario con 
   el mensaje de la causa.
5. El botón permanece en "Start".
6. El usuario puede corregir la configuración e intentar 
   nuevamente.
```

### FA-05: Sin dispositivos conectados
```
1. El usuario abre el Data Recorder sin que haya dispositivos 
   en la red OpenICE.
2. El árbol de dispositivos se muestra vacío.
3. La tabla de vista previa está vacía.
4. El usuario puede configurar un persister y hacer clic en 
   "Start".
5. La grabación comienza pero no se almacena ningún dato 
   hasta que un dispositivo se conecte.
6. Cuando un dispositivo se conecta y publica datos, estos 
   se graban automáticamente.
```

### FA-06: Desconexión de dispositivo durante grabación
```
1. Un dispositivo que se estaba grabando se desconecta de 
   la red.
2. El MDSHandler detecta el cambio de conectividad.
3. El dispositivo desaparece del árbol.
4. Ya no se reciben eventos de ese dispositivo.
5. El persister no escribe más datos de ese dispositivo.
6. La grabación continúa con los demás dispositivos.
```

### FA-07: Error de E/S durante grabación
```
1. Durante la grabación, ocurre un error de E/S (disco lleno, 
   pérdida de conexión a BD, etc.).
2. El persister lanza una excepción.
3. El Data Recorder captura la excepción y la registra en 
   el log.
4. El sistema muestra un diálogo de error al usuario.
5. Se remueve el listener del persister (efectivamente 
   deteniendo la grabación).
6. El botón cambia a "Start".
```

---

## 7. Reglas de Negocio

| ID | Regla |
|---|---|
| RN-01 | Solo se puede tener **un persister activo a la vez**. Cambiar de persister detiene automáticamente el actual. |
| RN-02 | La tabla de vista previa mantiene un máximo de **250 filas** (las más recientes). Las filas más antiguas se descartan. |
| RN-03 | Los datos de `SampleArray` se despliegan en múltiples columnas (valores individuales del arreglo), y en el caso de JDBC/CSV se descomponen en filas individuales. |
| RN-04 | El persister Verilog VCD solo soporta datos `Numeric` y `SampleArray`; no soporta `PatientAssessment`. |
| RN-05 | El persister MongoDB solo soporta datos `Numeric`; los eventos `SampleArray` y `PatientAssessment` se ignoran. |
| RN-06 | El formato de timestamp por defecto es `yyyyMMddHHmmssZ`; si se marca "Raw Times" se usa epoch milliseconds. |
| RN-07 | La visibilidad del menú "Add Data" (para inyectar datos mock) está controlada por la propiedad de sistema `-DDataCollectorApp.debug=true`. |
| RN-08 | Un persister no puede iniciarse si ya hay uno activo (el botón "Start" cambia a "Stop" mientras graba). |

---

## 8. Requisitos No Funcionales

| ID | Requisito |
|---|---|
| RNF-01 | La latencia entre la recepción de un sample DDS y su persistencia debe ser inferior a 100 ms. |
| RNF-02 | La interfaz debe permanecer responsiva durante la grabación (las operaciones de E/S no deben bloquear el hilo de JavaFX). |
| RNF-03 | El sistema debe soportar la grabación simultánea de al menos 10 dispositivos. |
| RNF-04 | Los archivos CSV deben soportar rotación automática al alcanzar el tamaño máximo configurado. |
| RNF-05 | El sistema debe registrar eventos de error en el log de la aplicación (`log4j2-test.xml` → `~/demo-apps.log`). |

---

## 9. Diagrama de Arquitectura (Flujo de Datos)

```
  ┌──────────────┐     ┌──────────────┐     ┌──────────────────┐
  │  Dispositivo  │────▶│  Bus DDS     │────▶│  NumericFxList   │
  │  Médico ICE   │     │  RTI Connext │     │  SampleArrayFx   │
  │  (HW/SW)      │     │  (Domain 10) │     │  PatientAssessFx │
  └──────────────┘     └──────────────┘     └────────┬─────────┘
                                                      │
                                                      ▼
  ┌───────────────────────────────────────────────────────────┐
  │              DataCollectors (Escuchan FX lists)            │
  │  ┌─────────────────┐  ┌────────────────┐  ┌────────────┐ │
  │  │NumericDataCollect│  │SampleArrayData │  │PatientAssess│ │
  │  │                 │  │Collector       │  │DataCollect │ │
  │  └────────┬────────┘  └───────┬────────┘  └──────┬─────┘ │
  └───────────┼──────────────────┼───────────────────┼────────┘
              │                  │                   │
              ▼                  ▼                   ▼
  ┌───────────────────────────────────────────────────────────┐
  │              DataFilter (Filtro por árbol)                 │
  └──────────────────────────┬────────────────────────────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │  DataCollector│  │  Tabla de    │  │  Persister    │
  │  App (UI)     │  │  Vista Previa│  │  Activo       │
  │  (últimas 250)│  │  (TableView) │  │  (CSV/SQL/    │
  └──────────────┘  └──────────────┘  │  VCD/Mongo)   │
                                       └──────┬───────┘
                                              ▼
                                ┌─────────────────────┐
                                │  Archivo / BD /     │
                                │  MongoDB            │
                                └─────────────────────┘
```

---

## 10. Persisters — Tabla Comparativa

| Persister | Formato | Soportado | Configuración | Uso típico |
|---|---|---|---|---|
| **CSV** | `openicedataexport-%g.csv` | Numeric, SampleArray, PatientAssessment | Ruta, backups, tamaño máximo, separador | Exportación a Excel / análisis offline |
| **JDBC (SQL)** | Tabla `VITAL_VALUES`, `OBSERVATION_VALUES` | Numeric, SampleArray, PatientAssessment | Driver, URL, usuario, contraseña | Integración con base de datos corporativa |
| **Verilog VCD** | `{yyyy.MMddHH.mmss}/{deviceUID}-{metricId}-{instanceId}.vcd` | Numeric, SampleArray (NO PatientAssessment) | Tamaño máximo por archivo | Depuración con GTKWave / ingeniería |
| **MongoDB** | Colección `datasample_second` | Numeric ONLY | Host, puerto, BD, script JS | Big data / análisis agregado |

---

## 11. Notas y Observaciones

- El Data Recorder se registra en el SPI como `org.mdpnp.apps.testapp.export.DataCollectorAppFactory` con el nombre visible `"Data Recorder"` y tag `"NOCSV"`.
- El árbol de dispositivos soporta selección multiple con checkboxes en tres niveles: **dispositivo → métrica → instancia**.
- Los timestamps se derivan de `presentation_time` (para Numeric y SampleArray) o `date_and_time` (para PatientAssessment).
- El `SampleArrayDataCollector` incluye un conversor interno `ArrayToNumeric` que descompone arreglos de muestras en valores numéricos individuales usando la frecuencia del arreglo para calcular timestamps individuales.
- El `DataFilter` actúa como un proxy basado en Guava EventBus, permitiendo que múltiples suscriptores reciban eventos sin acoplamiento directo.
- Toda la configuración de conectividad DDS (domain participant, subscriber, event loop) se inyecta a través del contexto Spring (`IceAppContainerContext.xml` y `RtConfig.xml`).

---

## 12. Historial de Revisiones

| Versión | Fecha | Autor | Cambio |
|---|---|---|---|
| 1.0 | 2026-07-08 | — | Versión inicial |
