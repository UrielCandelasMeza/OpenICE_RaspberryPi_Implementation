package org.mdpnp.headless.db;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.infrastructure.Condition;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.infrastructure.ResourceLimitsQosPolicy;
import com.rti.dds.subscription.*;
import com.rti.dds.topic.Topic;
import ice.*;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.TopicUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges the DDS network layer and the SQL database persistent storage.
 * Subscribes to various DDS topics (Numeric values, SampleArray waveforms, Alert states, 
 * DeviceIdentity, InfusionStatus), reacts to incoming samples asynchronously using an EventLoop,
 * and writes them into TimescaleDB/PostgreSQL using connection leasing from the ConnectionPool.
 *
 * @author Uriel Candelas
 */
public class TimescalePersister {

    private static final Logger log = LoggerFactory.getLogger(TimescalePersister.class);

    private static final int LENGTH_UNLIMITED = ResourceLimitsQosPolicy.LENGTH_UNLIMITED;

    private final DomainParticipant participant;
    private final Subscriber subscriber;
    private final EventLoop eventLoop;
    private final ConnectionPool pool;
    private final DeviceRegistry registry;

    private volatile boolean started;

    private NumericDataReader numericReader;
    private SampleArrayDataReader sampleArrayReader;
    private AlertDataReader patientAlertReader;
    private AlertDataReader technicalAlertReader;
    private DeviceIdentityDataReader deviceIdentityReader;
    private InfusionStatusDataReader infusionStatusReader;

    private ReadCondition numericCondition;
    private ReadCondition sampleArrayCondition;
    private ReadCondition patientAlertCondition;
    private ReadCondition technicalAlertCondition;
    private ReadCondition deviceIdentityCondition;
    private ReadCondition infusionStatusCondition;

    private final List<ReadCondition> allConditions = new ArrayList<>();
    private final List<DataReaderImpl> allReaders = new ArrayList<>();

    private final NumericHandler numericHandler = new NumericHandler();
    private final SampleArrayHandler sampleArrayHandler = new SampleArrayHandler();
    private final AlertHandler patientAlertHandler = new AlertHandler("PatientAlert");
    private final AlertHandler technicalAlertHandler = new AlertHandler("TechnicalAlert");
    private final DeviceIdentityHandler deviceIdentityHandler = new DeviceIdentityHandler();
    private final InfusionStatusHandler infusionStatusHandler = new InfusionStatusHandler();

    public TimescalePersister(DomainParticipant participant, Subscriber subscriber,
            EventLoop eventLoop, ConnectionPool pool,
            DeviceRegistry registry) {
        this.participant = participant;
        this.subscriber = subscriber;
        this.eventLoop = eventLoop;
        this.pool = pool;
        this.registry = registry;
    }

    public void start() {
        if (started)
            return;
        started = true;

        if (!pool.isAvailable()) {
            log.warn("TimescaleDB not available, persister started in no-op mode.");
            return;
        }

        createNumericReader();
        createSampleArrayReader();
        createPatientAlertReader();
        createTechnicalAlertReader();
        createDeviceIdentityReader();
        createInfusionStatusReader();

        log.info("TimescalePersister started – subscribing to Numeric, SampleArray, Alert, DeviceIdentity, InfusionStatus topics.");
    }

    public void stop() {
        if (!started)
            return;
        started = false;

        for (ReadCondition rc : allConditions) {
            eventLoop.removeHandler(rc);
            // El DataReader padre debe eliminar la ReadCondition
            if (rc.get_datareader() != null) {
                rc.get_datareader().delete_readcondition(rc);
            }
        }
        allConditions.clear();

        for (DataReaderImpl reader : allReaders) {
            // El Subscriber padre debe eliminar el DataReader
            if (reader.get_subscriber() != null) {
                reader.get_subscriber().delete_datareader(reader);
            }
        }
        allReaders.clear();

        log.info("TimescalePersister stopped.");
    }

    private void createNumericReader() {
        try {
            NumericTypeSupport.register_type(participant, NumericTypeSupport.get_type_name());
            Topic topic = TopicUtil.findOrCreateTopic(participant, NumericTopic.VALUE, NumericTypeSupport.class);
            numericReader = (NumericDataReader) subscriber.create_datareader_with_profile(
                    topic, "ice_library", "numeric_data", null, StatusKind.STATUS_MASK_NONE);
            allReaders.add(numericReader);
            numericReader.set_listener(logReaderStatus, StatusKind.STATUS_MASK_ALL ^ StatusKind.DATA_AVAILABLE_STATUS);
            numericCondition = numericReader.create_readcondition(
                    SampleStateKind.NOT_READ_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
                    InstanceStateKind.ANY_INSTANCE_STATE);
            allConditions.add(numericCondition);
            eventLoop.addHandler(numericCondition, numericHandler);
            numericReader.enable();
            log.debug("Numeric reader created.");
        } catch (Exception e) {
            log.warn("Failed to create Numeric reader: {}", e.getMessage());
        }
    }

    private void createSampleArrayReader() {
        try {
            SampleArrayTypeSupport.register_type(participant, SampleArrayTypeSupport.get_type_name());
            Topic topic = TopicUtil.findOrCreateTopic(participant, SampleArrayTopic.VALUE,
                    SampleArrayTypeSupport.class);
            sampleArrayReader = (SampleArrayDataReader) subscriber.create_datareader_with_profile(
                    topic, "ice_library", "waveform_data", null, StatusKind.STATUS_MASK_NONE);
            allReaders.add(sampleArrayReader);
            sampleArrayReader.set_listener(logReaderStatus,
                    StatusKind.STATUS_MASK_ALL ^ StatusKind.DATA_AVAILABLE_STATUS);
            sampleArrayCondition = sampleArrayReader.create_readcondition(
                    SampleStateKind.NOT_READ_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
                    InstanceStateKind.ANY_INSTANCE_STATE);
            allConditions.add(sampleArrayCondition);
            eventLoop.addHandler(sampleArrayCondition, sampleArrayHandler);
            sampleArrayReader.enable();
            log.debug("SampleArray reader created.");
        } catch (Exception e) {
            log.warn("Failed to create SampleArray reader: {}", e.getMessage());
        }
    }

    private void createPatientAlertReader() {
        try {
            AlertTypeSupport.register_type(participant, AlertTypeSupport.get_type_name());
            Topic topic = TopicUtil.findOrCreateTopic(participant, PatientAlertTopic.VALUE, AlertTypeSupport.class);
            patientAlertReader = (AlertDataReader) subscriber.create_datareader_with_profile(
                    topic, "ice_library", "state", null, StatusKind.STATUS_MASK_NONE);
            allReaders.add(patientAlertReader);
            patientAlertReader.set_listener(logReaderStatus,
                    StatusKind.STATUS_MASK_ALL ^ StatusKind.DATA_AVAILABLE_STATUS);
            patientAlertCondition = patientAlertReader.create_readcondition(
                    SampleStateKind.NOT_READ_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
                    InstanceStateKind.ANY_INSTANCE_STATE);
            allConditions.add(patientAlertCondition);
            eventLoop.addHandler(patientAlertCondition, patientAlertHandler);
            patientAlertReader.enable();
            log.debug("PatientAlert reader created.");
        } catch (Exception e) {
            log.warn("Failed to create PatientAlert reader: {}", e.getMessage());
        }
    }

    private void createTechnicalAlertReader() {
        try {
            AlertTypeSupport.register_type(participant, AlertTypeSupport.get_type_name());
            Topic topic = TopicUtil.findOrCreateTopic(participant, TechnicalAlertTopic.VALUE, AlertTypeSupport.class);
            technicalAlertReader = (AlertDataReader) subscriber.create_datareader_with_profile(
                    topic, "ice_library", "state", null, StatusKind.STATUS_MASK_NONE);
            allReaders.add(technicalAlertReader);
            technicalAlertReader.set_listener(logReaderStatus,
                    StatusKind.STATUS_MASK_ALL ^ StatusKind.DATA_AVAILABLE_STATUS);
            technicalAlertCondition = technicalAlertReader.create_readcondition(
                    SampleStateKind.NOT_READ_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
                    InstanceStateKind.ANY_INSTANCE_STATE);
            allConditions.add(technicalAlertCondition);
            eventLoop.addHandler(technicalAlertCondition, technicalAlertHandler);
            technicalAlertReader.enable();
            log.debug("TechnicalAlert reader created.");
        } catch (Exception e) {
            log.warn("Failed to create TechnicalAlert reader: {}", e.getMessage());
        }
    }

    private void createDeviceIdentityReader() {
        try {
            DeviceIdentityTypeSupport.register_type(participant, DeviceIdentityTypeSupport.get_type_name());
            Topic topic = TopicUtil.findOrCreateTopic(participant, DeviceIdentityTopic.VALUE,
                    DeviceIdentityTypeSupport.class);
            deviceIdentityReader = (DeviceIdentityDataReader) subscriber.create_datareader_with_profile(
                    topic, "ice_library", "device_identity", null, StatusKind.STATUS_MASK_NONE);
            allReaders.add(deviceIdentityReader);
            deviceIdentityReader.set_listener(logReaderStatus,
                    StatusKind.STATUS_MASK_ALL ^ StatusKind.DATA_AVAILABLE_STATUS);
            deviceIdentityCondition = deviceIdentityReader.create_readcondition(
                    SampleStateKind.NOT_READ_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
                    InstanceStateKind.ANY_INSTANCE_STATE);
            allConditions.add(deviceIdentityCondition);
            eventLoop.addHandler(deviceIdentityCondition, deviceIdentityHandler);
            deviceIdentityReader.enable();
            log.debug("DeviceIdentity reader created.");
        } catch (Exception e) {
            log.warn("Failed to create DeviceIdentity reader: {}", e.getMessage());
        }
    }

    private void createInfusionStatusReader() {
        try {
            InfusionStatusTypeSupport.register_type(participant, InfusionStatusTypeSupport.get_type_name());
            Topic topic = TopicUtil.findOrCreateTopic(participant, InfusionStatusTopic.VALUE,
                    InfusionStatusTypeSupport.class);
            infusionStatusReader = (InfusionStatusDataReader) subscriber.create_datareader_with_profile(
                    topic, "ice_library", "state", null, StatusKind.STATUS_MASK_NONE);
            allReaders.add(infusionStatusReader);
            infusionStatusReader.set_listener(logReaderStatus,
                    StatusKind.STATUS_MASK_ALL ^ StatusKind.DATA_AVAILABLE_STATUS);
            infusionStatusCondition = infusionStatusReader.create_readcondition(
                    SampleStateKind.NOT_READ_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
                    InstanceStateKind.ANY_INSTANCE_STATE);
            allConditions.add(infusionStatusCondition);
            eventLoop.addHandler(infusionStatusCondition, infusionStatusHandler);
            infusionStatusReader.enable();
            log.debug("InfusionStatus reader created.");
        } catch (Exception e) {
            log.warn("Failed to create InfusionStatus reader: {}", e.getMessage());
        }
    }

    private class NumericHandler implements EventLoop.ConditionHandler {
        private final NumericSeq dataSeq = new NumericSeq();
        private final SampleInfoSeq infoSeq = new SampleInfoSeq();

        @Override
        public void conditionChanged(Condition condition) {
            if (!started)
                return;
            try {
                numericReader.read_w_condition(dataSeq, infoSeq, LENGTH_UNLIMITED, numericCondition);
                int size = dataSeq.size();
                for (int i = 0; i < size; i++) {
                    SampleInfo info = (SampleInfo) infoSeq.get(i);
                    if (info.valid_data) {
                        Numeric sample = (Numeric) dataSeq.get(i);
                        writeNumeric(sample);
                    }
                }
            } catch (Exception e) {
                log.warn("Error reading Numeric samples: {}", e.getMessage());
            } finally {
                numericReader.return_loan(dataSeq, infoSeq);
            }
        }
    }

    private class SampleArrayHandler implements EventLoop.ConditionHandler {
        private final SampleArraySeq dataSeq = new SampleArraySeq();
        private final SampleInfoSeq infoSeq = new SampleInfoSeq();

        @Override
        public void conditionChanged(Condition condition) {
            if (!started)
                return;
            try {
                sampleArrayReader.read_w_condition(dataSeq, infoSeq, LENGTH_UNLIMITED, sampleArrayCondition);
                int size = dataSeq.size();
                for (int i = 0; i < size; i++) {
                    SampleInfo info = (SampleInfo) infoSeq.get(i);
                    if (info.valid_data) {
                        SampleArray sample = (SampleArray) dataSeq.get(i);
                        writeWaveform(sample);
                    }
                }
            } catch (Exception e) {
                log.warn("Error reading SampleArray samples: {}", e.getMessage());
            } finally {
                sampleArrayReader.return_loan(dataSeq, infoSeq);
            }
        }
    }

    private class AlertHandler implements EventLoop.ConditionHandler {
        private final String alertSource;
        private final AlertSeq dataSeq = new AlertSeq();
        private final SampleInfoSeq infoSeq = new SampleInfoSeq();

        AlertHandler(String alertSource) {
            this.alertSource = alertSource;
        }

        @Override
        public void conditionChanged(Condition condition) {
            if (!started)
                return;
            AlertDataReader reader = PatientAlertTopic.VALUE.equals(alertSource)
                    ? patientAlertReader
                    : technicalAlertReader;
            ReadCondition cond = PatientAlertTopic.VALUE.equals(alertSource)
                    ? patientAlertCondition
                    : technicalAlertCondition;
            try {
                reader.read_w_condition(dataSeq, infoSeq, LENGTH_UNLIMITED, cond);
                int size = dataSeq.size();
                for (int i = 0; i < size; i++) {
                    SampleInfo info = (SampleInfo) infoSeq.get(i);
                    if (info.valid_data) {
                        Alert alert = (Alert) dataSeq.get(i);
                        writeAlert(alert);
                    }
                }
            } catch (Exception e) {
                log.warn("Error reading {} samples: {}", alertSource, e.getMessage());
            } finally {
                reader.return_loan(dataSeq, infoSeq);
            }
        }
    }

    private class DeviceIdentityHandler implements EventLoop.ConditionHandler {
        private final DeviceIdentitySeq dataSeq = new DeviceIdentitySeq();
        private final SampleInfoSeq infoSeq = new SampleInfoSeq();

        @Override
        public void conditionChanged(Condition condition) {
            if (!started)
                return;
            try {
                deviceIdentityReader.read_w_condition(dataSeq, infoSeq, LENGTH_UNLIMITED, deviceIdentityCondition);
                int size = dataSeq.size();
                for (int i = 0; i < size; i++) {
                    SampleInfo info = (SampleInfo) infoSeq.get(i);
                    if (info.valid_data) {
                        DeviceIdentity devId = (DeviceIdentity) dataSeq.get(i);
                        registry.registerFromIdentity(
                                devId.unique_device_identifier,
                                devId.manufacturer,
                                devId.model,
                                devId.serial_number,
                                devId.build,
                                devId.operating_system);
                    }
                }
            } catch (Exception e) {
                log.warn("Error reading DeviceIdentity samples: {}", e.getMessage());
            } finally {
                deviceIdentityReader.return_loan(dataSeq, infoSeq);
            }
        }
    }

    private class InfusionStatusHandler implements EventLoop.ConditionHandler {
        private final InfusionStatusSeq dataSeq = new InfusionStatusSeq();
        private final SampleInfoSeq infoSeq = new SampleInfoSeq();

        @Override
        public void conditionChanged(Condition condition) {
            if (!started)
                return;
            try {
                infusionStatusReader.read_w_condition(dataSeq, infoSeq, LENGTH_UNLIMITED, infusionStatusCondition);
                int size = dataSeq.size();
                for (int i = 0; i < size; i++) {
                    SampleInfo info = (SampleInfo) infoSeq.get(i);
                    if (info.valid_data) {
                        InfusionStatus sample = (InfusionStatus) dataSeq.get(i);
                        writeInfusionStatus(sample);
                    }
                }
            } catch (Exception e) {
                log.warn("Error reading InfusionStatus samples: {}", e.getMessage());
            } finally {
                infusionStatusReader.return_loan(dataSeq, infoSeq);
            }
        }
    }

    private void writeNumeric(Numeric sample) {
        if (!pool.isAvailable())
            return;
        String sql = "INSERT INTO vital_values (time_tick, device_id, metric_id, instance_id, patient_id, unit_id, vital_value) "
                +
                "VALUES (?, ?, ?, ?, 'OFFLINE_PATIENT', ?, ?)";
        try (Connection c = pool.getConnection();
                PreparedStatement st = c.prepareStatement(sql)) {
            st.setTimestamp(1, toTimestamp(sample.presentation_time));
            st.setString(2, sample.unique_device_identifier);
            st.setString(3, sample.metric_id);
            st.setInt(4, sample.instance_id);
            st.setString(5, sample.unit_id);
            st.setDouble(6, sample.value);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to write numeric {} for device {}: {}",
                    sample.metric_id, sample.unique_device_identifier, e.getMessage());
        }
    }

    private void writeWaveform(SampleArray sample) {
        if (!pool.isAvailable())
            return;
        String sql = "INSERT INTO waveform_data (time_tick, device_id, metric_id, instance_id, patient_id, frequency_hz, unit_id, values) "
                +
                "VALUES (?, ?, ?, ?, 'OFFLINE_PATIENT', ?, ?, ?)";
        try (Connection c = pool.getConnection();
                PreparedStatement st = c.prepareStatement(sql)) {
            int count = sample.values.userData.size();
            float[] floatValues = new float[count];
            for (int i = 0; i < count; i++) {
                floatValues[i] = (float) sample.values.userData.get(i);
            }
            Array sqlArray = c.createArrayOf("real", toFloatObjArray(floatValues));

            st.setTimestamp(1, toTimestamp(sample.presentation_time));
            st.setString(2, sample.unique_device_identifier);
            st.setString(3, sample.metric_id);
            st.setInt(4, sample.instance_id);
            st.setInt(5, sample.frequency);
            st.setString(6, sample.unit_id);
            st.setArray(7, sqlArray);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to write waveform {} for device {}: {}",
                    sample.metric_id, sample.unique_device_identifier, e.getMessage());
        }
    }

    private void writeAlert(Alert alert) {
        if (!pool.isAvailable())
            return;
        String sql = "INSERT INTO device_alerts (time_tick, device_id, metric_id, alert_type, alert_message, priority) "
                +
                "VALUES (?, ?, ?, ?, ?, 'medium')";
        try (Connection c = pool.getConnection();
                PreparedStatement st = c.prepareStatement(sql)) {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            String metricId = extractMetricId(alert.identifier);
            st.setTimestamp(1, now);
            st.setString(2, alert.unique_device_identifier);
            st.setString(3, metricId);
            st.setString(4, alert.identifier);
            st.setString(5, alert.text);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to write alert for device {}: {}",
                    alert.unique_device_identifier, e.getMessage());
        }
    }

    private void writeInfusionStatus(InfusionStatus sample) {
        if (!pool.isAvailable())
            return;
        String sql = "INSERT INTO infusion_pump_status (time_tick, device_id, patient_id, infusion_active, drug_name, "
                +
                "drug_mass_mcg, solution_volume_ml, volume_to_be_infused_ml, infusion_duration_seconds, " +
                "infusion_fraction_complete) " +
                "VALUES (?, ?, 'OFFLINE_PATIENT', ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = pool.getConnection();
                PreparedStatement st = c.prepareStatement(sql)) {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            st.setTimestamp(1, now);
            st.setString(2, sample.unique_device_identifier);
            st.setBoolean(3, sample.infusionActive);
            st.setString(4, sample.drug_name);
            st.setInt(5, sample.drug_mass_mcg);
            st.setInt(6, sample.solution_volume_ml);
            st.setInt(7, sample.volume_to_be_infused_ml);
            st.setInt(8, sample.infusion_duration_seconds);
            st.setFloat(9, sample.infusion_fraction_complete);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to write infusion status for device {}: {}",
                    sample.unique_device_identifier, e.getMessage());
        }
    }

    private static String extractMetricId(String identifier) {
        if (identifier == null)
            return null;
        int idx = identifier.lastIndexOf('_');
        if (idx > 0 && idx < identifier.length() - 1) {
            String candidate = identifier.substring(0, idx);
            if (candidate.startsWith("MDC_") || candidate.startsWith("ICE_")) {
                return candidate;
            }
        }
        return null;
    }

    private static Timestamp toTimestamp(ice.Time_t t) {
        long millis = (long) t.sec * 1000L + t.nanosec / 1000000L;
        return new Timestamp(millis);
    }

    private static Float[] toFloatObjArray(float[] arr) {
        Float[] result = new Float[arr.length];
        for (int i = 0; i < arr.length; i++) {
            result[i] = arr[i];
        }
        return result;
    }

    private static final DataReaderListener logReaderStatus = new DataReaderListener() {
        @Override
        public void on_requested_deadline_missed(DataReader reader, RequestedDeadlineMissedStatus status) {
            log.debug("Deadline missed on {}", reader.get_topicdescription().get_name());
        }

        @Override
        public void on_requested_incompatible_qos(DataReader reader, RequestedIncompatibleQosStatus status) {
            log.warn("Incompatible QoS on {}", reader.get_topicdescription().get_name());
        }

        @Override
        public void on_sample_rejected(DataReader reader, SampleRejectedStatus status) {
            log.debug("Sample rejected on {}", reader.get_topicdescription().get_name());
        }

        @Override
        public void on_liveliness_changed(DataReader reader, LivelinessChangedStatus status) {
        }

        @Override
        public void on_sample_lost(DataReader reader, SampleLostStatus status) {
            log.debug("Sample lost on {}", reader.get_topicdescription().get_name());
        }

        @Override
        public void on_subscription_matched(DataReader reader, SubscriptionMatchedStatus status) {
            log.debug("Subscription matched on {} (cur:{} chg:{})",
                    reader.get_topicdescription().get_name(),
                    status.current_count, status.current_count_change);
        }

        @Override
        public void on_data_available(DataReader reader) {
        }
    };
}
