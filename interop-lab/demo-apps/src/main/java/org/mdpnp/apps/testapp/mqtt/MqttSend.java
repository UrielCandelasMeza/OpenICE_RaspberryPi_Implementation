package org.mdpnp.apps.testapp.mqtt;

import java.text.SimpleDateFormat;
import java.util.Date;
// import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
// import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldListCell;

import org.mdpnp.apps.fxbeans.AlertFx;
import org.mdpnp.apps.fxbeans.AlertFxList;
import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.fxbeans.NumericFxList;
import org.mdpnp.apps.fxbeans.SampleArrayFx;
import org.mdpnp.apps.fxbeans.SampleArrayFxList;
import org.mdpnp.apps.testapp.Device;
import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.subscription.Subscriber;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
// import jakarta.json.JsonWriter;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttSend {
    private static final Logger log = LoggerFactory.getLogger(MqttSend.class);

    private static final long SAMPLE_ARRAY_THROTTLE_MS = 1000;
    private static final int MAX_LOG_ENTRIES = 200;
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss");

    @FXML
    private TextField brokerUrlField;
    @FXML
    private TextField usernameField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private TextField topicPrefixField;
    @FXML
    private Button connectButton;
    @FXML
    private Button disconnectButton;
    @FXML
    private Label statusLabel;
    @FXML
    private Label sentLabel;
    @FXML
    private ListView<String> logView;
    @FXML
    private ListView<Device> deviceList;
    @FXML
    private Label deviceCountLabel;

    private final StringProperty statusProperty = new SimpleStringProperty("Disconnected");
    private final IntegerProperty messagesSent = new SimpleIntegerProperty(0);

    private NumericFxList numericList;
    private SampleArrayFxList sampleArrayList;
    private AlertFxList patientAlertList;
    private AlertFxList technicalAlertList;

    private MqttClient mqttClient;
    private final ExecutorService publishExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mqtt-publish");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, Long> lastSampleArrayPublish = new ConcurrentHashMap<>();
    private volatile boolean connected = false;
    private ScheduledExecutorService republishScheduler;
    private ScheduledFuture<?> republishTask;

    public void start(EventLoop eventLoop, Subscriber subscriber, DeviceListModel deviceListModel) {
        numericList = new NumericFxList(ice.NumericTopic.VALUE);
        sampleArrayList = new SampleArrayFxList(ice.SampleArrayTopic.VALUE);
        patientAlertList = new AlertFxList(ice.PatientAlertTopic.VALUE);
        technicalAlertList = new AlertFxList(ice.TechnicalAlertTopic.VALUE);

        numericList.start(subscriber, eventLoop, null, null, QosProfiles.ice_library, QosProfiles.state);
        sampleArrayList.start(subscriber, eventLoop, null, null, QosProfiles.ice_library, QosProfiles.state);
        patientAlertList.start(subscriber, eventLoop, null, null, QosProfiles.ice_library, QosProfiles.state);
        technicalAlertList.start(subscriber, eventLoop, null, null, QosProfiles.ice_library, QosProfiles.state);

        numericList.addListener(this::onNumericChanged);
        sampleArrayList.addListener(this::onSampleArrayChanged);
        patientAlertList.addListener((ListChangeListener<AlertFx>) c -> onAlertChanged(c, "patient_alert"));
        technicalAlertList.addListener((ListChangeListener<AlertFx>) c -> onAlertChanged(c, "technical_alert"));

        statusLabel.textProperty().bind(statusProperty);
        sentLabel.textProperty().bind(messagesSent.asString());

        deviceList.setCellFactory(list -> new TextFieldListCell<Device>() {
            @Override
            public void updateItem(Device device, boolean empty) {
                super.updateItem(device, empty);
                if (empty || device == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    String status = device.getConnected() ? "[ON]" : "[OFF]";
                    setText(status + " " + device.getMakeAndModel());
                }
            }
        });

        ObservableList<Device> devices = deviceListModel.getContents();
        deviceList.setItems(devices);
        deviceCountLabel.textProperty().bind(
                Bindings.format("%d devices", Bindings.size(devices)));

        devices.addListener((ListChangeListener<Device>) c -> {
            appendLog("Devices changed: " + devices.size() + " connected");
        });
    }

    public void stop() {
        disconnect();
        if (numericList != null)
            numericList.stop();
        if (sampleArrayList != null)
            sampleArrayList.stop();
        if (patientAlertList != null)
            patientAlertList.stop();
        if (technicalAlertList != null)
            technicalAlertList.stop();
        publishExecutor.shutdownNow();
        if (republishScheduler != null)
            republishScheduler.shutdownNow();
    }

    @FXML
    private void onConnect() {
        String broker = brokerUrlField.getText().trim();
        String user = usernameField.getText().trim();
        String pass = passwordField.getText();
        final String prefix = getTopicPrefix();

        if (broker.isEmpty()) {
            appendLog("ERROR: Broker URL is required");
            return;
        }

        publishExecutor.execute(() -> {
            try {
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setAutomaticReconnect(true);
                if (!user.isEmpty()) {
                    opts.setUserName(user);
                }
                if (pass != null && !pass.isEmpty()) {
                    opts.setPassword(pass.toCharArray());
                }

                mqttClient = new MqttClient(broker, "openice-mqtt-send-" + System.currentTimeMillis(),
                        new MemoryPersistence());
                mqttClient.setCallback(new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable cause) {
                        Platform.runLater(() -> {
                            connected = false;
                            statusProperty.set("Connection lost: " + cause.getMessage());
                            appendLog("Connection lost: " + cause.getMessage());
                        });
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                    }
                });

                mqttClient.connect(opts);
                connected = true;
                Platform.runLater(() -> {
                    statusProperty.set("Connected");
                    connectButton.setDisable(true);
                    disconnectButton.setDisable(false);
                    appendLog("Connected to " + broker + " (prefix: " + prefix + ")");
                });
                publishAllExistingData();
                startPeriodicRepublish();
            } catch (MqttException e) {
                connected = false;
                Platform.runLater(() -> {
                    statusProperty.set("Error: " + e.getMessage());
                    appendLog("Connect failed: " + e.getMessage());
                });
            }
        });
    }

    @FXML
    private void onDisconnect() {
        disconnect();
    }

    private void disconnect() {
        connected = false;
        stopPeriodicRepublish();
        if (mqttClient != null) {
            publishExecutor.execute(() -> {
                try {
                    if (mqttClient.isConnected()) {
                        mqttClient.disconnect();
                    }
                    mqttClient.close();
                } catch (MqttException e) {
                    log.error("Error disconnecting MQTT", e);
                }
            });
        }
        Platform.runLater(() -> {
            statusProperty.set("Disconnected");
            connectButton.setDisable(false);
            disconnectButton.setDisable(true);
            appendLog("Disconnected");
        });
    }

    private void onNumericChanged(ListChangeListener.Change<? extends NumericFx> change) {
        if (!connected || mqttClient == null)
            return;
        while (change.next()) {
            if (change.wasAdded() || change.wasUpdated()) {
                for (NumericFx fx : change.getAddedSubList()) {
                    publishNumeric(fx);
                }
                if (change.wasUpdated()) {
                    for (int i = change.getFrom(); i < change.getTo(); i++) {
                        publishNumeric(change.getList().get(i));
                    }
                }
            }
        }
    }

    private void publishNumeric(NumericFx fx) {
        String prefix = getTopicPrefix();
        String udi = fx.getUnique_device_identifier();
        String metricId = fx.getMetric_id();
        if (udi == null || udi.isEmpty() || metricId == null || metricId.isEmpty())
            return;

        String topic = prefix + "/" + sanitizeTopic(udi) + "/" + sanitizeTopic(metricId);

        JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("device_udi", safeString(udi))
                .add("metric_id", safeString(metricId))
                .add("vendor_metric_id", safeString(fx.getVendor_metric_id()))
                .add("instance_id", fx.getInstance_id())
                .add("value", fx.getValue())
                .add("unit_id", safeString(fx.getUnit_id()));

        Date dt = fx.getDevice_time();
        if (dt != null)
            builder.add("device_time", dt.toInstant().toString());
        Date pt = fx.getPresentation_time();
        if (pt != null)
            builder.add("presentation_time", pt.toInstant().toString());

        publishJson(topic, builder.build(), udi, metricId);
    }

    private void onSampleArrayChanged(ListChangeListener.Change<? extends SampleArrayFx> change) {
        if (!connected || mqttClient == null)
            return;
        while (change.next()) {
            if (change.wasAdded() || change.wasUpdated()) {
                for (SampleArrayFx fx : change.getAddedSubList()) {
                    throttledPublishSampleArray(fx);
                }
                if (change.wasUpdated()) {
                    for (int i = change.getFrom(); i < change.getTo(); i++) {
                        throttledPublishSampleArray(change.getList().get(i));
                    }
                }
            }
        }
    }

    private void throttledPublishSampleArray(SampleArrayFx fx) {
        String key = fx.getUnique_device_identifier() + "/" + fx.getMetric_id();
        long now = System.currentTimeMillis();
        Long last = lastSampleArrayPublish.get(key);
        if (last != null && (now - last) < SAMPLE_ARRAY_THROTTLE_MS)
            return;
        lastSampleArrayPublish.put(key, now);
        publishSampleArray(fx);
    }

    private void publishSampleArray(SampleArrayFx fx) {
        String prefix = getTopicPrefix();
        String udi = fx.getUnique_device_identifier();
        String metricId = fx.getMetric_id();
        if (udi == null || udi.isEmpty() || metricId == null || metricId.isEmpty())
            return;

        String topic = prefix + "/" + sanitizeTopic(udi) + "/" + sanitizeTopic(metricId);

        JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("device_udi", safeString(udi))
                .add("metric_id", safeString(metricId))
                .add("vendor_metric_id", safeString(fx.getVendor_metric_id()))
                .add("instance_id", fx.getInstance_id())
                .add("frequency", fx.getFrequency())
                .add("unit_id", safeString(fx.getUnit_id()));

        Number[] vals = fx.getValues();
        if (vals != null) {
            JsonArrayBuilder arr = Json.createArrayBuilder();
            for (Number n : vals) {
                arr.add(n.floatValue());
            }
            builder.add("values", arr);
        }

        Date dt = fx.getDevice_time();
        if (dt != null)
            builder.add("device_time", dt.toInstant().toString());

        publishJson(topic, builder.build(), udi, metricId);
    }

    private void onAlertChanged(ListChangeListener.Change<? extends AlertFx> change, String alertType) {
        if (!connected || mqttClient == null)
            return;
        while (change.next()) {
            if (change.wasAdded() || change.wasUpdated()) {
                for (AlertFx fx : change.getAddedSubList()) {
                    publishAlert(fx, alertType);
                }
                if (change.wasUpdated()) {
                    for (int i = change.getFrom(); i < change.getTo(); i++) {
                        publishAlert(change.getList().get(i), alertType);
                    }
                }
            }
        }
    }

    private void publishAlert(AlertFx fx, String alertType) {
        String prefix = getTopicPrefix();
        String udi = fx.getUnique_device_identifier();
        if (udi == null || udi.isEmpty())
            return;

        String topic = prefix + "/" + sanitizeTopic(udi) + "/" + alertType;

        JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("device_udi", safeString(udi))
                .add("alert_type", alertType)
                .add("identifier", safeString(fx.getIdentifier()))
                .add("text", safeString(fx.getText()));

        publishJson(topic, builder.build(), udi, alertType);
    }

    private void publishJson(String topic, JsonObject json, String udi, String metricId) {
        if (!connected || mqttClient == null || !mqttClient.isConnected())
            return;

        String jsonStr = json.toString();
        publishExecutor.execute(() -> {
            try {
                MqttMessage msg = new MqttMessage(jsonStr.getBytes());
                msg.setQos(1);
                mqttClient.publish(topic, msg);
                Platform.runLater(() -> {
                    messagesSent.set(messagesSent.get() + 1);
                    appendLog("Published " + topic);
                });
            } catch (MqttException e) {
                log.error("MQTT publish failed for topic {}", topic, e);
                Platform.runLater(() -> appendLog("FAIL " + topic + ": " + e.getMessage()));
            }
        });
    }

    private void publishAllExistingData() {
        if (!connected || mqttClient == null || !mqttClient.isConnected())
            return;

        Platform.runLater(() -> appendLog("Publishing all existing device data..."));

        int count = 0;
        for (NumericFx fx : numericList) {
            publishNumeric(fx);
            count++;
        }
        for (SampleArrayFx fx : sampleArrayList) {
            publishSampleArray(fx);
            count++;
        }
        for (AlertFx fx : patientAlertList) {
            publishAlert(fx, "patient_alert");
            count++;
        }
        for (AlertFx fx : technicalAlertList) {
            publishAlert(fx, "technical_alert");
            count++;
        }

        final int total = count;
        Platform.runLater(() -> appendLog("Published " + total + " existing data points"));
    }

    private void startPeriodicRepublish() {
        stopPeriodicRepublish();
        republishScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mqtt-republish");
            t.setDaemon(true);
            return t;
        });
        republishTask = republishScheduler.scheduleWithFixedDelay(this::publishAllExistingData, 5, 5, TimeUnit.SECONDS);
    }

    private void stopPeriodicRepublish() {
        if (republishTask != null) {
            republishTask.cancel(false);
            republishTask = null;
        }
        if (republishScheduler != null) {
            republishScheduler.shutdownNow();
            republishScheduler = null;
        }
    }

    private void appendLog(String entry) {
        String timestamped = TIME_FMT.format(new Date()) + " " + entry;
        logView.getItems().add(timestamped);
        if (logView.getItems().size() > MAX_LOG_ENTRIES) {
            logView.getItems().remove(0, logView.getItems().size() - MAX_LOG_ENTRIES);
        }
        logView.scrollTo(logView.getItems().size() - 1);
    }

    private String getTopicPrefix() {
        String prefix = topicPrefixField.getText().trim();
        return prefix.isEmpty() ? "openice" : prefix;
    }

    private static String sanitizeTopic(String s) {
        return s.replaceAll("[/#+]", "_");
    }

    private static String safeString(String s) {
        return s != null ? s : "";
    }
}
