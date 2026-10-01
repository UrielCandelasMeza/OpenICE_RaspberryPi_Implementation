# Reporte de Implementación para RTC y NTP en la Raspberry Pi

**Fecha:** 01/10/2026  
**Proyecto:** OpenICE / MD PnP (`1.5.1-SNAPSHOT`)  
**Sistema:** Dispositivos Edge (Raspberry Pi)  
**Alcance:** Propuesta de solución para sincronización de tiempo en dispositivos desconectados de red

**Versión:** 1.0.0

---

## Objetivo del reporte

El objetivo de este reporte es proponer una implementación para el uso de un reloj en tiempo real (RTC) en los dispositivos Edge (Raspberry Pi), con el fin de asegurar la correcta sincronización horaria para el envío de datos mediante DDS.

Actualmente, cuando las Raspberry Pi pierden alimentación o se desconectan de la red eléctrica, pierden la hora del sistema. La única forma de recuperarla es mediante un servidor NTP accesible a través de Internet, lo cual no está garantizado en entornos clínicos (limpieza, mantenimiento, traslados, redes aisladas, etc.).

## Elementos que interactúan

Los elementos involucrados en la sincronización de tiempo son:

1. **Reloj físico RTC (Real Time Clock)**: mantiene la hora cuando no hay alimentación del sistema. En el ámbito anestésico, su desfase progresivo representa una vulnerabilidad crítica para la trazabilidad de datos.
2. **Servidor NTP (Network Time Protocol)**: proporciona sincronización horaria precisa cuando existe conectividad de red. Su principal limitación es la dependencia de conectividad a red (idealmente con acceso a Internet) en el arranque o durante el mantenimiento del equipo.

Cada uno resuelve parcialmente el problema y presenta limitaciones complementarias.

## Solución propuesta

Se propone una solución integral que combine ambos mecanismos, dando prioridad al servidor NTP cuando esté disponible y utilizando el RTC como respaldo cuando no lo esté.

### 1. Integración de RTC en cada Raspberry Pi

- **Hardware**: Adquirir e instalar un módulo RTC compatible con I2C en cada Raspberry Pi.
- **Configuración**: Conectar el RTC al bus I2C para que mantenga la hora incluso ante cortes de alimentación.
- **Objetivo**: Garantizar que el sistema disponga de una referencia horaria válida al arrancar, independientemente de la disponibilidad de red.

### 2. Implementación de servidor NTP local en capa Fog

- **Ubicación**: Desplegar un servidor NTP local en la capa Fog. Se propone aprovechar la Raspberry Pi 5 asignada a dicha capa para este fin, ampliando su rol (almacenamiento, estandarización y ahora también servidor de tiempo).
- **Software**: Utilizar **Chrony** para la gestión de NTP. Chrony resulta más adecuado para entornos con conectividad intermitente o aislados, ofreciendo mejor control sobre la sincronización y tiempos de convergencia.
- **Objetivo**: Proporcionar una fuente de tiempo precisa y siempre disponible dentro de la red local (LAN), evitando la dependencia de servidores NTP externos.

### 3. Lógica de sincronización (flujo de arranque)

Se establece el siguiente flujo de prioridad para la inicialización del reloj del sistema:

```text
[Arranque del sistema]
  ↓
Leer hora del RTC (referencia local persistente)
  ↓
Inicializar reloj del sistema con hora del RTC
  ↓
¿Existe conectividad de red?
  ├─ SÍ → Sincronizar con servidor NTP local mediante Chrony
  │        (prioriza NTP de capa Fog; si no responde, puede usar fuentes externas si estuvieran disponibles)
  └─ NO  → Utilizar hora proporcionada por RTC (modo autónomo)
```

### 4. Arquitectura de implementación

La arquitectura propuesta debe contemplar:

- **Inicialización temprana**: Carga de hora desde RTC durante el arranque (antes de servicios críticos DDS) para evitar timestamps incorrectos.
- **Estrategia de fallback**: RTC como respaldo automático cuando el servidor NTP local no es alcanzable.
- **Persistencia y corrección**: El RTC puede corregirse periódicamente cuando NTP esté disponible, minimizando su desfase a largo plazo.
- **Integración con OpenICE**: Garantizar que los timestamps generados por los dispositivos Edge sean coherentes y válidos para publicación DDS, independientemente del estado de red.

## Conclusión

La combinación de un RTC por dispositivo Edge y un servidor NTP local gestionado con Chrony permite resolver ambas limitaciones: elimina la pérdida de hora ante apagados y reduce la dependencia crítica de conectividad a Internet. Esta solución resulta especialmente adecuada para entornos clínicos donde el mantenimiento, los traslados y las redes aisladas son habituales.

---

*Reporte generado a partir de análisis de requisitos para sincronización de tiempo en dispositivos OpenICE Edge.*
