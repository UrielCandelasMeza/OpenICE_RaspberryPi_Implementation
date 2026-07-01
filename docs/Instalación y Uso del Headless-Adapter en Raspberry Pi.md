# Instructivo: Instalación y Uso del Headless-Adapter en Raspberry Pi

## 1. ¿Qué es el Headless-Adapter?

Es un nuevo módulo, separado del supervisor, que permite ejecutar un **device simulado** de forma independiente (sin interfaz gráfica) directamente en la Raspberry Pi. Al separarlo del supervisor ya no es necesario cargar librerías gráficas como **JavaFX**, lo que soluciona los problemas de compilación que antes existían al intentar compilar únicamente la parte headless.

**Componentes principales del módulo:**

- **Factory headless**: copia del `DeviceFactory` original, sin las dependencias de JavaFX.
- **Main headless**: copia del `Main` original, adaptado al nuevo módulo.
- **`META-INF/services`** (`headless/src/main/resources/META-INF/services`): lista de todos los devices soportados.
- **`META-INF`** (`headless/src/main/resources/META-INF`): configuración de Spring para los beans, copiada del original.

> ⚠️ **Importante:** cada vez que se quiera añadir un nuevo device al sistema, hay que registrarlo en **dos lugares**:
> 1. El archivo `META-INF/services`.
> 2. La Factory del módulo `headless-adapter`.

---

## 2. Ejecutar un Device Manualmente (modo desarrollo)

Para correr un device simulado directamente con Gradle:

```bash
./gradlew :headless-adapter:run --args="--device <DEVICE> --domain <DOMAIN_NUMBER>"
```

- `<DEVICE>`: nombre del device a simular.
- `<DOMAIN_NUMBER>`: número de dominio asignado.

Para ver la lista de devices disponibles y las opciones del comando:

```bash
./gradlew :headless-adapter:run --help
```

---

## 3. Instalación como Servicio en la Raspberry Pi

Para que el device se inicie automáticamente cada vez que se enciende la Raspberry Pi, se utiliza el script **`device-adapter.sh`**, el cual compila, instala y levanta el servicio correspondiente.

### 3.1 Comandos disponibles

```
Uso: ./device_adapter.sh [comando] [argumentos...]

Comandos:
  list                      Muestra la lista de todos los devices soportados.
  install DEVICE [DOMAIN]   Compila, instala y levanta el servicio para el device indicado.
                            (El dominio por defecto es 0 si no se provee).
  device                    Muestra el device que se encuentra corriendo actualmente.
```

### 3.2 Ejemplos de uso

```bash
# Ver todos los devices soportados
./device_adapter.sh list

# Instalar y levantar el servicio con dominio por defecto (0)
./device_adapter.sh install Pump_Simulator

# Instalar y levantar el servicio indicando el dominio
./device_adapter.sh install DraegerV500 15

# Ver qué device está corriendo actualmente
./device_adapter.sh device
```

### 3.3 Pasos para una instalación nueva

1. Copiar/ubicar el script `device-adapter.sh` en la Raspberry Pi.
2. (Opcional) Ejecutar `./device_adapter.sh list` para confirmar el nombre exacto del device que se desea instalar.
3. Ejecutar `./device_adapter.sh install <DEVICE> [DOMAIN]`.
4. El script compila el proyecto, instala el servicio y lo deja corriendo. A partir de este momento, el device se iniciará automáticamente cada vez que se encienda la Raspberry Pi.

---

## 4. Archivos y Carpetas Generados

Después de la instalación, en la ruta `~/` (home) del usuario en la Raspberry Pi quedan disponibles:

| Elemento | Descripción |
|---|---|
| `~/device.this` | Archivo que identifica el device actualmente instalado/en ejecución. |
| `~/OpenICE/` | Carpeta con el compilado completo del proyecto. |
| `~/headless-adapter.log` | Log con el resultado de la ejecución del servicio. |

### Consultar el log del servicio

```bash
cat ~/headless-adapter.log
```

---

## 5. Cambiar de Device

Existen dos formas de trabajar con un device distinto al que ya está instalado como servicio:

### Opción A — Reinstalación completa (recomendada si el cambio es permanente)

```bash
./device_adapter.sh install <NUEVO_DEVICE> [DOMAIN]
```

Esto reconfigura el servicio para que arranque con el nuevo device en cada encendido de la Raspberry Pi.

### Opción B — Ejecución temporal (sin afectar el servicio instalado)

Útil para pruebas puntuales, ya que no modifica la configuración del servicio activo:

```bash
cd ~/OpenICE/OpenICE.current/bin
./OpenICE-headless --device <DISPOSITIVO> --domain <DOMINIO>
```

### Ver ayuda y devices disponibles desde el binario

```bash
./OpenICE-headless --help
```

---

## 6. Resumen Rápido de Comandos

| Acción | Comando |
|---|---|
| Ver devices soportados | `./device_adapter.sh list` |
| Instalar/levantar servicio | `./device_adapter.sh install <DEVICE> [DOMAIN]` |
| Ver device corriendo actualmente | `./device_adapter.sh device` |
| Ver logs del servicio | `cat ~/headless-adapter.log` |
| Ejecutar device temporalmente | `cd ~/OpenICE/OpenICE.current/bin && ./OpenICE-headless --device <DEVICE> --domain <DOMAIN>` |
| Ayuda del binario | `./OpenICE-headless --help` |
| Ejecutar en modo desarrollo (Gradle) | `./gradlew :headless-adapter:run --args="--device <DEVICE> --domain <DOMAIN_NUMBER>"` |
| Ayuda de Gradle | `./gradlew :headless-adapter:run --help` |

---

## 7. Notas Finales

- El módulo `headless-adapter` está desacoplado del supervisor, por lo que no requiere JavaFX ni otras librerías gráficas.
- El binario del servicio fue depurado de problemas de dependencias y funciona correctamente junto con el supervisor.
- Para añadir soporte a un nuevo device en el futuro, recordar registrarlo tanto en `META-INF/services` como en la Factory del módulo.