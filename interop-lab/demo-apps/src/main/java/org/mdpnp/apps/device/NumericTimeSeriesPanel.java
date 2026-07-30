package org.mdpnp.apps.device;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.testapp.chart.DateAxis;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.TilePane;
import javafx.util.Duration;

public class NumericTimeSeriesPanel extends DevicePanel {
    private static final int WINDOW_SECONDS = 30;
    private static final int MAX_POINTS = 300;
    private static final int CHART_WIDTH = 250;
    private static final int CHART_HEIGHT = 170;

    private static final String[] NUMERIC_METRICS = {
        rosetta.MDC_PULS_OXIM_SAT_O2.VALUE,
        rosetta.MDC_PULS_OXIM_PULS_RATE.VALUE,
        rosetta.MDC_PULS_OXIM_PERF_REL.VALUE,
        rosetta.MDC_ECG_HEART_RATE.VALUE,
        rosetta.MDC_CO2_RESP_RATE.VALUE,
        "Masimo_SPHB",
        "Masimo_SPOC",
        "Masimo_PVI",
        "Masimo_DESAT",
        "Masimo_eegPSI",
        "Masimo_eegEMG",
        "Masimo_eegSEFL",
        "Masimo_eegSEFR"
    };

    private final TilePane chartPane;
    private final ScrollPane scrollPane;
    private Timeline timeline;

    private final Map<String, List<ChartInfo>> metricCharts = new HashMap<>();
    private final Map<NumericFx, ChartInfo> fxToChart = new HashMap<>();
    private ListChangeListener<NumericFx> listListener;

    private static class ChartInfo {
        LineChart<Date, Number> chart;
        DateAxis xAxis;
        XYChart.Series<Date, Number> series;
        ChangeListener<Date> timestampListener;
    }

    public NumericTimeSeriesPanel() {
        chartPane = new TilePane();
        chartPane.setPrefColumns(3);
        chartPane.setHgap(6);
        chartPane.setVgap(6);
        chartPane.setPadding(new Insets(6));

        scrollPane = new ScrollPane(chartPane);
        scrollPane.setFitToWidth(true);
        setCenter(scrollPane);
    }

    public static boolean supported(Set<String> identifiers) {
        for (String m : NUMERIC_METRICS) {
            if (identifiers.contains(m)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void set(DeviceDataMonitor deviceMonitor) {
        super.set(deviceMonitor);
        destroy();

        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> scrollTime()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();

        ObservableList<NumericFx> numerics = deviceMonitor.getNumericModel();
        listListener = change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (NumericFx fx : change.getAddedSubList()) {
                        addNumericFx(fx);
                    }
                }
                if (change.wasRemoved()) {
                    for (NumericFx fx : change.getRemoved()) {
                        removeNumericFx(fx);
                    }
                }
            }
        };
        numerics.addListener(listListener);

        for (NumericFx fx : numerics) {
            addNumericFx(fx);
        }
    }

    private void addNumericFx(NumericFx fx) {
        if (fxToChart.containsKey(fx)) {
            return;
        }
        String metricId = fx.getMetric_id();

        DateAxis xAxis = new DateAxis(
                new Date(System.currentTimeMillis() - WINDOW_SECONDS * 1000L),
                new Date());
        NumberAxis yAxis = new NumberAxis();
        yAxis.setForceZeroInRange(false);
        yAxis.setAutoRanging(true);

        LineChart<Date, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setPrefSize(CHART_WIDTH, CHART_HEIGHT);
        chart.setTitle(metricId);

        XYChart.Series<Date, Number> series = new XYChart.Series<>();
        chart.getData().add(series);

        ChartInfo info = new ChartInfo();
        info.chart = chart;
        info.xAxis = xAxis;
        info.series = series;
        info.timestampListener = (obs, oldVal, newVal) -> {
            if (newVal != null) {
                ObservableList<XYChart.Data<Date, Number>> data = series.getData();
                if (data.size() > MAX_POINTS) {
                    data.remove(0);
                }
                data.add(new XYChart.Data<>(newVal, fx.getValue()));
            }
        };
        fx.source_timestampProperty().addListener(info.timestampListener);

        fxToChart.put(fx, info);
        metricCharts.computeIfAbsent(metricId, k -> new ArrayList<>()).add(info);
        chartPane.getChildren().add(chart);
    }

    private void removeNumericFx(NumericFx fx) {
        ChartInfo info = fxToChart.remove(fx);
        if (info != null) {
            fx.source_timestampProperty().removeListener(info.timestampListener);
            String metricId = fx.getMetric_id();
            List<ChartInfo> charts = metricCharts.get(metricId);
            if (charts != null) {
                charts.remove(info);
                if (charts.isEmpty()) {
                    metricCharts.remove(metricId);
                }
            }
            chartPane.getChildren().remove(info.chart);
        }
    }

    private void scrollTime() {
        long now = System.currentTimeMillis();
        Date lower = new Date(now - WINDOW_SECONDS * 1000L);
        Date upper = new Date(now);
        for (List<ChartInfo> charts : metricCharts.values()) {
            for (ChartInfo info : charts) {
                info.xAxis.setLowerBound(lower);
                info.xAxis.setUpperBound(upper);
            }
        }
    }

    @Override
    public void destroy() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        if (listListener != null) {
            if (deviceMonitor != null) {
                deviceMonitor.getNumericModel().removeListener(listListener);
            }
            listListener = null;
        }
        for (Map.Entry<NumericFx, ChartInfo> e : fxToChart.entrySet()) {
            e.getKey().source_timestampProperty().removeListener(e.getValue().timestampListener);
        }
        fxToChart.clear();
        metricCharts.clear();
        chartPane.getChildren().clear();
    }
}
