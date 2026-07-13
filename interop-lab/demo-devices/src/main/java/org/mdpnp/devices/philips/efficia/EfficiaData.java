package org.mdpnp.devices.philips.efficia;

/**
 * Data holder for Philips Efficia CM Series device driver.
 * Contains the following patient telemetry and alarm parameters:
 * <ul>
 *   <li><b>heartRate</b>: Heart rate value in beats per minute (bpm).</li>
 *   <li><b>spo2</b>: Oxygen saturation percentage.</li>
 *   <li><b>respRate</b>: Respiration rate in breaths per minute.</li>
 *   <li><b>perfusionIndex</b>: Perfusion index percentage.</li>
 *   <li><b>pulse</b>: Pulse rate in beats per minute (bpm).</li>
 *   <li><b>stII</b>: ST segment elevation/depression for lead II in millimeters (mm).</li>
 *   <li><b>pvc</b>: Premature ventricular contraction count.</li>
 *   <li><b>alarmType</b>: Type of alarm (NONE, YELLOW_ALARM, RED_ALARM, SOFT_INOP, ALARM_CLEAR).</li>
 *   <li><b>alarmPriority</b>: Priority level of the alarm (HIGH, MEDIUM, LOW).</li>
 *   <li><b>alarmText</b>: Descriptive text message for the active alarm.</li>
 *   <li><b>status</b>: Status of the observation (FINAL, DISCONNECTED, UNKNOWN).</li>
 *   <li><b>timestamp</b>: Date-time string of the data recording.</li>
 *   <li><b>patientId</b>: Unique patient identifier.</li>
 * </ul>
 *
 * @author Uriel Candelas
 */
public class EfficiaData {

    public enum AlarmType {
        NONE,
        YELLOW_ALARM,
        RED_ALARM,
        SOFT_INOP,
        ALARM_CLEAR
    }

    public enum AlarmPriority {
        HIGH(1),
        MEDIUM(2),
        LOW(6);

        private final int code;

        /**
         * @param code The numeric code representing the alarm priority.
         */
        AlarmPriority(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        /**
         * @param code The numeric code to map.
         * @return The matching AlarmPriority, or null if not found.
         */
        public static AlarmPriority fromCode(int code) {
            for (AlarmPriority p : values()) {
                if (p.code == code)
                    return p;
            }
            return null;
        }
    }

    public enum ObservationStatus {
        FINAL("F"),
        DISCONNECTED("X"),
        UNKNOWN("");

        private final String hl7Code;

        /**
         * @param hl7Code The HL7 representation code for this observation status.
         */
        ObservationStatus(String hl7Code) {
            this.hl7Code = hl7Code;
        }

        public String getHl7Code() {
            return hl7Code;
        }

        /**
         * @param code The HL7 representation code to map.
         * @return The matching ObservationStatus, or UNKNOWN if not found.
         */
        public static ObservationStatus fromHl7Code(String code) {
            if (code == null)
                return UNKNOWN;
            for (ObservationStatus s : values()) {
                if (s.hl7Code.equals(code))
                    return s;
            }
            return UNKNOWN;
        }
    }

    private Float heartRate;
    private Float spo2;
    private Float respRate;
    private Float perfusionIndex;
    private Float pulse;
    private Float stII;
    private Integer pvc;
    private AlarmType alarmType = AlarmType.NONE;
    private AlarmPriority alarmPriority;
    private String alarmText;
    private ObservationStatus status = ObservationStatus.UNKNOWN;
    private String timestamp;
    private String patientId;

    public Float getHeartRate() {
        return heartRate;
    }

    /**
     * @param heartRate The heart rate value in beats per minute.
     */
    public void setHeartRate(Float heartRate) {
        this.heartRate = heartRate;
    }

    public Float getSpo2() {
        return spo2;
    }

    /**
     * @param spo2 The oxygen saturation percentage.
     */
    public void setSpo2(Float spo2) {
        this.spo2 = spo2;
    }

    public Float getRespRate() {
        return respRate;
    }

    /**
     * @param respRate The respiration rate value in breaths per minute.
     */
    public void setRespRate(Float respRate) {
        this.respRate = respRate;
    }

    public Float getPerfusionIndex() {
        return perfusionIndex;
    }

    /**
     * @param perfusionIndex The perfusion index percentage value.
     */
    public void setPerfusionIndex(Float perfusionIndex) {
        this.perfusionIndex = perfusionIndex;
    }

    public Float getPulse() {
        return pulse;
    }

    /**
     * @param pulse The pulse rate value in beats per minute.
     */
    public void setPulse(Float pulse) {
        this.pulse = pulse;
    }

    public Float getStII() {
        return stII;
    }

    /**
     * @param stII The ST segment elevation/depression value for lead II in millimeters.
     */
    public void setStII(Float stII) {
        this.stII = stII;
    }

    public Integer getPvc() {
        return pvc;
    }

    /**
     * @param pvc The premature ventricular contraction count.
     */
    public void setPvc(Integer pvc) {
        this.pvc = pvc;
    }

    public AlarmType getAlarmType() {
        return alarmType;
    }

    /**
     * @param alarmType The type of alarm.
     */
    public void setAlarmType(AlarmType alarmType) {
        this.alarmType = alarmType;
    }

    public AlarmPriority getAlarmPriority() {
        return alarmPriority;
    }

    /**
     * @param alarmPriority The priority level of the alarm.
     */
    public void setAlarmPriority(AlarmPriority alarmPriority) {
        this.alarmPriority = alarmPriority;
    }

    public String getAlarmText() {
        return alarmText;
    }

    /**
     * @param alarmText The descriptive text message for the alarm.
     */
    public void setAlarmText(String alarmText) {
        this.alarmText = alarmText;
    }

    public ObservationStatus getStatus() {
        return status;
    }

    /**
     * @param status The status of the current observation.
     */
    public void setStatus(ObservationStatus status) {
        this.status = status;
    }

    public String getTimestamp() {
        return timestamp;
    }

    /**
     * @param timestamp The date-time string of the data recording.
     */
    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getPatientId() {
        return patientId;
    }

    /**
     * @param patientId The unique identifier of the patient.
     */
    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }
}
