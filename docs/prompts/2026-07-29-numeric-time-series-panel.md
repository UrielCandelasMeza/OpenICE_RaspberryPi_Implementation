# Numeric Time-Series Panel for Device Detail View

## Objective

When selecting a Masimo Radical-7 or Efficia device in the Supervisor's device grid, the detail panel currently shows `PulseOximeterPanel` (with empty waveform area and current numeric values). Add a new `NumericTimeSeriesPanel` that displays **all numeric metrics** from the device as scrolling time-series `LineChart<Date, Number>` with a 30-second window.

## Requirements

1. **New file**: `interop-lab/demo-apps/src/main/java/org/mdpnp/apps/device/NumericTimeSeriesPanel.java`
2. **Extends**: `DevicePanel` (not `AbstractWaveAndParamsPanel`)
3. **Data source**: `DeviceDataMonitor.getNumericModel()` → `FilteredList<NumericFx>`
4. **Chart creation**: For each unique `metric_id` in the numeric data, create a `LineChart<Date, Number>`:
   - X axis: `DateAxis` (reuse from `org.mdpnp.apps.testapp.chart` package)
   - Y axis: `NumberAxis` auto-ranging
   - Window: 30 seconds, scrolling forward via `Timeline`
   - Size: ~200×150px per chart in a wrapping `FlowPane`
   - Max ~300 data points per series (FIFO eviction)
   - No point symbols (`setCreateSymbols(false)`)
5. **Data binding**: Register `ChangeListener<Number>` on `NumericFx.valueProperty()` + `ChangeListener<Date>` on `NumericFx.source_timestampProperty()`. When both change, append `new XYChart.Data<>(timestamp, value)` to the chart series.
   - Alternative if timestamp already set: listen on `source_timestampProperty()` and read `getValue()` at that point.
6. **Layout wrap**: Use `TilePane` or `FlowPane` so charts auto-flow horizontally
7. **Panel reuse**: `set(DeviceDataMonitor)` should support being called multiple times (for panel reuse by `DevicePanelFactory`)
8. **supported()**: Return `true` when the tags set contains known numeric-only metrics from Masimo/Efficia (SpO2, PI, SpHb, SpOC, PVI, Desat, EEG PSI, EEG EMG, EEG SEFL, EEG SEFR, etc.), but do NOT match when waveform metrics (`MDC_PULS_OXIM_PLETH`, etc.) are present — let PulseOximeterPanel handle those.
9. **Cleanup**: `destroy()` should stop the timeline and clear all listeners

## File to modify

**`interop-lab/demo-apps/src/main/java/org/mdpnp/apps/device/DevicePanelFactory.java`**

- Add `NumericTimeSeriesPanel.class` to the `PANELS` array after the existing entries.

## Files to NOT modify

- `PulseOximeterPanel.java` — remains unchanged
- `AbstractWaveAndParamsPanel.java` — remains unchanged
- `CompositeDevicePanel.java` — remains unchanged (resolvePanels handles multiple panels already)
- `DevicePanelFactory.resolvePanels()` logic — remains unchanged

## Implementation details

### Metric coverage (supported())
Check for the following metric IDs (from `DemoRadical7.firePulseOximeter()`):
- `MDC_PULS_OXIM_SAT_O2` (SpO2)
- `MDC_PULS_OXIM_PULS_RATE` (Pulse)
- `MDC_PULS_OXIM_PERF_REL` (PI)
- `Masimo_SPHB`
- `Masimo_SPOC`
- `Masimo_PVI`
- `Masimo_DESAT`
- `Masimo_eegPSI`
- `Masimo_eegEMG`
- `Masimo_eegSEFL`
- `Masimo_eegSEFR`

Plus Efficia-specific numeric metrics as needed.

The method should also check that NO waveform metric is present (to avoid duplicating PulseOximeterPanel for devices that have pleth).

Actually, simpler design: always return `true` if any numeric metric is found AND the device is not already covered by a waveform panel. OR: check that the device has at least N numeric metrics (e.g. > 2) without waveform counterparts.

Simplest viable approach: `supported()` returns `true` when the tags contain at least one of the known numeric metrics listed above AND does NOT contain `MDC_PULS_OXIM_PLETH`.

### Chart layout
```
FlowPane wrap (or TilePane)
  ├── [SpO2]   LineChart 200x150
  ├── [PI]     LineChart 200x150
  ├── [SpHb]   LineChart 200x150
  └── [PVI]    LineChart 200x150
       ... (flow wraps to next row)
```

### Scrolling timeline
- A `Timeline` with 1-second interval updates the `DateAxis` bounds:
  ```java
  long now = System.currentTimeMillis();
  axis.setLowerBound(new Date(now - 30000)); // 30s window
  axis.setUpperBound(new Date(now));
  ```
- Same pattern as `ChartApplication.handle()`.

### Data point accumulation
- Maintain a `Map<String, XYChart.Series<Date, Number>>` keyed by metric_id
- When a `NumericFx` update arrives, append data point
- If series exceeds 300 points, remove oldest

## Testing
- `./gradlew :interop-lab:demo-apps:compileJava` must pass
- `./gradlew :interop-lab:demo-apps:test` must pass (no new tests needed; existing tests should not break)
