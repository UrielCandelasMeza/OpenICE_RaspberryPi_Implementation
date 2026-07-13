# Prompt: Rename Efficia Simulator — SimEfficiaMonitor → SimulatedEfficia

## Objetivo

Renombrar las clases del simulador Efficia existente para distinguirlas claramente del nuevo driver real (`Efficia.java`).

## Cambios de nombre

| Elemento actual | Nuevo nombre |
|-----------------|--------------|
| `SimEfficiaMonitor.java` | `SimulatedEfficia.java` |
| `EfficiaClinicalEngine.java` | `SimulatedEfficiaClinicalEngine.java` |
| `class SimEfficiaMonitor` | `class SimulatedEfficia` |
| `class EfficiaClinicalEngine` | `class SimulatedEfficiaClinicalEngine` |
| `EfficiaMonitorProvider` (DeviceFactory) | `SimulatedEfficiaProvider` |
| `EfficiaMonitorProvider` (HeadlessDeviceFactory) | `SimulatedEfficiaProvider` |
| alias `"EfficiaMonitor"` | `"SimulatedEfficia"` |

## Archivos a modificar

### 1. Renombrar archivos

```bash
cd interop-lab/demo-devices/src/main/java/org/mdpnp/devices/philips/efficia/
mv SimEfficiaMonitor.java SimulatedEfficia.java
mv EfficiaClinicalEngine.java SimulatedEfficiaClinicalEngine.java
```

### 2. SimulatedEfficia.java (antes SimEfficiaMonitor.java)

Cambios internos:
- `class SimEfficiaMonitor` → `class SimulatedEfficia`
- `LoggerFactory.getLogger(SimEfficiaMonitor.class)` → `LoggerFactory.getLogger(SimulatedEfficia.class)`
- `public SimEfficiaMonitor(...)` → `public SimulatedEfficia(...)`
- Referencias a `EfficiaClinicalEngine` → `SimulatedEfficiaClinicalEngine`
- Referencias a `SimEfficiaMonitor.HL7_PORT` → `SimulatedEfficia.HL7_PORT` (etc.)

### 3. SimulatedEfficiaClinicalEngine.java (antes EfficiaClinicalEngine.java)

Cambios internos:
- `class EfficiaClinicalEngine` → `class SimulatedEfficiaClinicalEngine`
- `LoggerFactory.getLogger(EfficiaClinicalEngine.class)` → `LoggerFactory.getLogger(SimulatedEfficiaClinicalEngine.class)`
- `EfficiaClinicalEngine(SimEfficiaMonitor device, ...)` → `SimulatedEfficiaClinicalEngine(SimulatedEfficia device, ...)`
- Todas las referencias a `SimEfficiaMonitor` → `SimulatedEfficia`
- Referencias a `SimEfficiaMonitor.HL7_HOST` → `SimulatedEfficia.HL7_HOST` (etc.)

### 4. DeviceFactory.java

```java
// Antes:
import org.mdpnp.devices.philips.efficia.SimEfficiaMonitor;
// ...
public static class EfficiaMonitorProvider extends SpringLoadedDriver {
    public DeviceType getDeviceType() {
        return new DeviceType(ice.ConnectionType.Simulated, "Philips",
            "Efficia CM Series", "EfficiaMonitor", 1);
    }
    public AbstractDevice newInstance(AbstractApplicationContext context) throws Exception {
        // ...
        return new SimEfficiaMonitor(subscriber, publisher, eventLoop);
    }
}

// Después:
import org.mdpnp.devices.philips.efficia.SimulatedEfficia;
// ...
public static class SimulatedEfficiaProvider extends SpringLoadedDriver {
    public DeviceType getDeviceType() {
        return new DeviceType(ice.ConnectionType.Simulated, "Philips",
            "Efficia CM Series", "SimulatedEfficia", 1);
    }
    public AbstractDevice newInstance(AbstractApplicationContext context) throws Exception {
        // ...
        return new SimulatedEfficia(subscriber, publisher, eventLoop);
    }
}
```

### 5. HeadlessDeviceFactory.java

Mismo patrón que DeviceFactory:
- Import: `SimEfficiaMonitor` → `SimulatedEfficia`
- Provider: `EfficiaMonitorProvider` → `SimulatedEfficiaProvider`
- Alias: `"EfficiaMonitor"` → `"SimulatedEfficia"`
- Constructor: `new SimEfficiaMonitor(...)` → `new SimulatedEfficia(...)`

### 6. Archivos SPI

**demo-devices SPI:**
```
# Antes:
org.mdpnp.apps.testapp.DeviceFactory$EfficiaMonitorProvider
# Después:
org.mdpnp.apps.testapp.DeviceFactory$SimulatedEfficiaProvider
```

**headless-adapter SPI:**
```
# Antes:
org.mdpnp.headless.HeadlessDeviceFactory$EfficiaMonitorProvider
# Después:
org.mdpnp.headless.HeadlessDeviceFactory$SimulatedEfficiaProvider
```

### 7. Scripts y docs

**scripts/EfficiaHL7Listener.java** (linea 13):
```java
// Antes:
// El puerto debe coincidir con SimEfficiaMonitor.HL7_PORT (2575)
// Después:
// El puerto debe coincidir con SimulatedEfficia.HL7_PORT (2575)
```

**headless-adapter/README.md**:
- `EfficiaMonitor` → `SimulatedEfficia` en los ejemplos de ejecución

**docs/prompts/implement_phillips_efficia_simulator.md**:
- Actualizar todas las referencias

## Verificación

```bash
# Buscar referencias residuales
grep -r "SimEfficiaMonitor" --include="*.java" .
grep -r "EfficiaMonitorProvider" --include="*.java" .
grep -r "EfficiaMonitor" --include="*.java" .

# Compilar
./gradlew :interop-lab:demo-devices:compileJava -x setupLocalDb
./gradlew :headless-adapter:compileJava -x setupLocalDb

# Ejecutar simulador
./gradlew :headless-adapter:run -x setupLocalDb \
  --args="-domain 0 -device SimulatedEfficia"
```
