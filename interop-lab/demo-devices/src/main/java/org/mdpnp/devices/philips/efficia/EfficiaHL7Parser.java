package org.mdpnp.devices.philips.efficia;

import ca.uhn.hl7v2.model.v24.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v24.group.ORU_R01_OBSERVATION;
import ca.uhn.hl7v2.model.v24.message.ORU_R01;
import ca.uhn.hl7v2.model.v24.segment.OBR;
import ca.uhn.hl7v2.model.v24.segment.OBX;
import ca.uhn.hl7v2.model.v24.segment.PID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser específico de mensajes HL7 v2.4 ORU^R01 provenientes del Philips
 * Efficia CM.
 *
 * <p>
 * Extrae los campos de observación (OBX) y los mapea a un {@link EfficiaData}
 * con los valores numéricos y información de alarma.
 * </p>
 *
 * <h3>Mapeo de códigos MDIL</h3>
 * 
 * <pre>
 *   0002-4182  →  heartRate        (HR)
 *   0002-4bb8  →  spo2             (SpO2)
 *   0002-5000  →  respRate         (Resp)
 *   0002-4bb0  →  perfusionIndex   (Perf)
 *   0002-480a  →  pulse            (Pulse)
 *   0002-0302  →  stII             (ST-II)
 *   0002-4261  →  pvc              (PVC)
 * </pre>
 * 
 * @author Uriel Candelas
 */
public class EfficiaHL7Parser {

    private static final Logger log = LoggerFactory.getLogger(EfficiaHL7Parser.class);

    /**
     * Parsea un ORU_R01 completo y retorna un EfficiaData con los campos extraídos.
     *
     * @param message ORU_R01 ya parseado por HAPI
     * @return EfficiaData con los valores extraídos
     */
    public EfficiaData parse(ORU_R01 message) {
        EfficiaData data = new EfficiaData();

        try {
            // 1. Extraer PID
            PID pid = message.getPATIENT_RESULT().getPATIENT().getPID();
            String patientId = pid.getPatientIdentifierList()[0].getID().getValue();
            data.setPatientId(patientId);

            // 2. Extraer OBR
            ORU_R01_ORDER_OBSERVATION order = message.getPATIENT_RESULT().getORDER_OBSERVATION();
            OBR obr = order.getOBR();
            String observationType = obr.getUniversalServiceIdentifier().getIdentifier().getValue();

            // 3. Extraer timestamp del OBR-7
            String obrDateTime = obr.getObservationDateTime().getTimeOfAnEvent().getValue();
            data.setTimestamp(obrDateTime);

            // 4. Determinar si es MONITOR o ALARM
            if ("ALARM".equals(observationType)) {
                parseAlarm(order, data);
            } else {
                parseMonitorData(order, data);
            }

        } catch (Exception e) {
            log.error("Error parsing ORU_R01 message", e);
        }

        return data;
    }

    /**
     * Parsea datos de tipo MONITOR (OBR-4 = MONITOR).
     * Cada OBX contiene una lectura numérica con su código MDIL.
     *
     * @param order El grupo de observación de orden ORU_R01_ORDER_OBSERVATION a parsear.
     * @param data  El objeto EfficiaData donde se guardarán los datos extraídos.
     */
    private void parseMonitorData(ORU_R01_ORDER_OBSERVATION order, EfficiaData data) {
        try {
            java.util.List<ORU_R01_OBSERVATION> observations = order.getOBSERVATIONAll();
            for (ORU_R01_OBSERVATION obs : observations) {
                OBX obx = obs.getOBX();

                String identifier = obx.getObservationIdentifier().getIdentifier().getValue();
                String statusCode = obx.getObservationResultStatus().getValue();

                // Mapear status
                data.setStatus(EfficiaData.ObservationStatus.fromHl7Code(statusCode));

                // Extraer código MDIL del identifier (formato: "0002-4182^HR^MDIL")
                String mdilCode = identifier.split("\\^")[0];

                // Si status es X (Disconnected), marcar el métrico como desconectado
                if (EfficiaData.ObservationStatus.DISCONNECTED == data.getStatus()) {
                    data.addDisconnectedMetric(mdilCode);
                    continue;
                }

                // Obtener valor
                String value = obx.getObservationValue(0).getData().toString();
                if (value == null || value.isEmpty())
                    continue;

                // Parsear valor numérico
                float numValue = Float.parseFloat(value);

                // Mapear al campo correspondiente
                switch (mdilCode) {
                    case "0002-4182":
                        data.setHeartRate(numValue);
                        break;
                    case "0002-4bb8":
                        data.setSpo2(numValue);
                        break;
                    case "0002-5000":
                        data.setRespRate(numValue);
                        break;
                    case "0002-4bb0":
                        data.setPerfusionIndex(numValue);
                        break;
                    case "0002-480a":
                        data.setPulse(numValue);
                        break;
                    case "0002-4261":
                        data.setPvc((int) numValue);
                        break;
                    case "0002-0301":
                        data.setStI(numValue);
                        break;
                    case "0002-0302":
                        data.setStII(numValue);
                        break;
                    case "0002-033d":
                        data.setStIII(numValue);
                        break;
                    case "0002-033e":
                        data.setStAVR(numValue);
                        break;
                    case "0002-033f":
                        data.setStAVL(numValue);
                        break;
                    case "0002-0340":
                        data.setStAVF(numValue);
                        break;
                    case "0002-0343":
                        data.setStV(numValue);
                        break;
                    case "0002-034b":
                        data.setStMCL(numValue);
                        break;
                    default:
                        log.debug("Unrecognized MDIL code: {}", mdilCode);
                }
            }
        } catch (Exception e) {
            log.error("Error parsing MONITOR data", e);
        }
    }

    /**
     * Parsea datos de tipo ALARM (OBR-4 = ALARM).
     * Los OBX son de tipo TX con información de texto de alarma.
     *
     * @param order El grupo de observación de orden ORU_R01_ORDER_OBSERVATION a parsear.
     * @param data  El objeto EfficiaData donde se guardarán los datos extraídos.
     */
    private void parseAlarm(ORU_R01_ORDER_OBSERVATION order, EfficiaData data) {
        try {
            java.util.List<ORU_R01_OBSERVATION> observations = order.getOBSERVATIONAll();
            for (ORU_R01_OBSERVATION obs : observations) {
                OBX obx = obs.getOBX();
                String value = obx.getObservationValue(0).getData().toString();

                if (value != null && !value.isEmpty()) {
                    // Extraer tipo de alarma del texto
                    // Ej: "Yellow Alarm" o "Red Alarm" o "Soft Inop"
                    String alarmText = value.trim();
                    data.setAlarmText(alarmText);

                    if (alarmText.contains("Red")) {
                        data.setAlarmType(EfficiaData.AlarmType.RED_ALARM);
                        data.setAlarmPriority(EfficiaData.AlarmPriority.HIGH);
                    } else if (alarmText.contains("Yellow")) {
                        data.setAlarmType(EfficiaData.AlarmType.YELLOW_ALARM);
                        data.setAlarmPriority(EfficiaData.AlarmPriority.MEDIUM);
                    } else if (alarmText.contains("Inop")) {
                        data.setAlarmType(EfficiaData.AlarmType.SOFT_INOP);
                        data.setAlarmPriority(EfficiaData.AlarmPriority.LOW);
                    }
                } else {
                    // Valor vacío = ALARM_CLEAR
                    data.setAlarmType(EfficiaData.AlarmType.ALARM_CLEAR);
                }
            }
        } catch (Exception e) {
            log.error("Error parsing ALARM data", e);
        }
    }
}
