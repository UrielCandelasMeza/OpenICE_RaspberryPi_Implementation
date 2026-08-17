package org.mdpnp.apps.device;

import java.util.Set;

import org.mdpnp.apps.fxbeans.NumericFx;

import javafx.beans.binding.Bindings;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

public class EfficiaWaveAndParamsPanel extends AbstractWaveAndParamsPanel {

    private final static String[] WAVEFORMS = new String[] { rosetta.MDC_PULS_OXIM_PLETH.VALUE };
    private final static String[] WAVEFORM_LABELS = new String[] { "Plethysmogram" };

    private final static String[][] PARAMS = new String[][] {
        { rosetta.MDC_ECG_HEART_RATE.VALUE },
        { rosetta.MDC_PULS_OXIM_SAT_O2.VALUE },
        { rosetta.MDC_CO2_RESP_RATE.VALUE },
        { rosetta.MDC_PULS_OXIM_PULS_RATE.VALUE },
        { rosetta.MDC_PULS_OXIM_PERF_REL.VALUE },
        { "Efficia_PVC" },
        { rosetta.MDC_TEMP_BLD.VALUE },
        { "Efficia_ST_I" },
        { "Efficia_ST_II" },
        { "Efficia_ST_III" },
        { "Efficia_ST_aVR" },
        { "Efficia_ST_aVL" },
        { "Efficia_ST_aVF" },
        { "Efficia_ST_V" },
        { "Efficia_ST_MCL" }
    };

    private final static String[] PARAM_LABELS = new String[] {
        "Heart Rate", "SpO\u2082", "Respiration", "Pulse",
        "Perf Index", "PVC", "Temp",
        "ST I", "ST II", "ST III", "ST aVR", "ST aVL", "ST aVF",
        "ST V", "ST MCL"
    };

    private final static String[] PARAM_UNITS = new String[] {
        "BPM", "%", "rpm", "BPM",
        "", "/min", "\u00B0C",
        "mm", "mm", "mm", "mm", "mm", "mm",
        "mm", "mm"
    };

    private static final Set<String> ONE_DECIMAL = Set.of(
        rosetta.MDC_PULS_OXIM_PERF_REL.VALUE,
        rosetta.MDC_TEMP_BLD.VALUE,
        "Efficia_ST_I", "Efficia_ST_II", "Efficia_ST_III",
        "Efficia_ST_aVR", "Efficia_ST_aVL", "Efficia_ST_aVF",
        "Efficia_ST_V", "Efficia_ST_MCL"
    );

    private static final Set<String> DISCONNECTED_SHOWS_DASH = Set.of(
        "Efficia_ST_V", "Efficia_ST_MCL"
    );

    public EfficiaWaveAndParamsPanel() {
        super();
        setMaxWidth(400);
    }

    @Override
    protected void add(NumericFx data) {
        String metricId = data.getMetric_id();

        for (int i = 0; i < getParameterCount(); i++) {
            for (String m : getParameterMetricIds(i)) {
                if (m.equals(metricId)) {
                    String fmt = ONE_DECIMAL.contains(metricId) ? "%.1f" : "%.0f";
                    boolean dashDash = DISCONNECTED_SHOWS_DASH.contains(metricId);
                    params[i].textProperty().bind(
                        Bindings.createStringBinding(
                            () -> {
                                Number val = data.getValue();
                                if (val == null) return "-?";
                                float f = val.floatValue();
                                if (Float.isNaN(f)) return "-?";
                                if (Float.isInfinite(f) && f < 0) {
                                    return dashDash ? "-?-" : "-?";
                                }
                                return String.format(fmt, f);
                            },
                            data.valueProperty()
                        )
                    );
                    return;
                }
            }
        }
        addToHeader(data);
    }

    @Override
    protected void remove(NumericFx data) {
        String metricId = data.getMetric_id();
        for (int i = 0; i < getParameterCount(); i++) {
            for (String m : getParameterMetricIds(i)) {
                if (m.equals(metricId)) {
                    params[i].textProperty().unbind();
                    return;
                }
            }
        }
    }

    @Override
    public String getStyleClassName() {
        return "pulse-oximeter-panel";
    }

    @Override
    public int getParameterCount() {
        return PARAMS.length;
    }

    @Override
    public String getParameterLabel(int i) {
        return PARAM_LABELS[i];
    }

    @Override
    public String[] getParameterMetricIds(int i) {
        return PARAMS[i];
    }

    @Override
    public String getParameterUnits(int i) {
        return PARAM_UNITS[i];
    }

    @Override
    public String[] getWaveformLabels() {
        return WAVEFORM_LABELS;
    }

    @Override
    public String[] getWaveformMetricIds() {
        return WAVEFORMS;
    }

    @Override
    public Paint getWaveformPaint() {
        return Color.CYAN;
    }

    public static boolean supported(Set<String> identifiers) {
        return identifiers.contains("Efficia_PVC")
            || identifiers.contains("Efficia_ST_I");
    }
}
