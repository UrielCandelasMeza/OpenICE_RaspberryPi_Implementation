package org.mdpnp.apps.testapp.hl7;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.testapp.validate.Validation;
import org.mdpnp.apps.testapp.validate.ValidationOracle;
import org.mdpnp.rtiapi.data.EventLoop;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.Connection;
import ca.uhn.hl7v2.app.Initiator;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v26.datatype.NM;
import ca.uhn.hl7v2.model.v26.group.ORU_R01_OBSERVATION;
import ca.uhn.hl7v2.model.v26.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v26.group.ORU_R01_PATIENT;
import ca.uhn.hl7v2.model.v26.message.ORU_R01;
import ca.uhn.hl7v2.model.v26.segment.MSH;
import ca.uhn.hl7v2.model.v26.segment.OBX;
import ca.uhn.hl7v2.model.v26.segment.PID;
import ca.uhn.hl7v2.parser.Parser;

import com.rti.dds.subscription.Subscriber;

/**
 * Emisor especializado exclusivamente en el protocolo HL7 v2.6 (mensajes
 * ORU^R01 vía TCP sockets).
 */
public class HL7v26Emitter extends AbstractEmitter {

    private final HapiContext hl7Context;
    private Connection hl7Connection;

    public HL7v26Emitter(Subscriber subscriber, EventLoop eventLoop, ValidationOracle validationOracle) {
        super(subscriber, eventLoop, validationOracle);
        this.hl7Context = new DefaultHapiContext();
    }

    @Override
    public void start(String host, int port, long interval) {
        if (host != null && !host.isEmpty()) {
            try {
                hl7Connection = hl7Context.newClient(host, port, false);
                fireStarted();
            } catch (HL7Exception e) {
                log.error("Error al conectar con servidor HL7 v2.6", e);
                stop();
                return;
            } catch (RuntimeException re) {
                log.error("Error en tiempo de ejecución al conectar HL7 v2.6", re);
                stop();
                return;
            }
        } else {
            fireStarted();
        }
        if (null == emit) {
            emit = executor.scheduleAtFixedRate(this, 0L, interval, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        super.stop();
        if (hl7Connection != null) {
            hl7Connection.close();
            hl7Connection = null;
        }
    }

    @Override
    protected void emitData(List<Validation> pendingUpdates) throws Exception {
        sendHL7v26(pendingUpdates);
    }

    public void sendHL7v26(List<Validation> pendingUpdates) throws InterruptedException {
        List<ORU_R01> bundle = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                for (Validation fx : pendingUpdates) {
                    if (fx.getNumeric().getMetric_id().startsWith(METRIC_PREFIX)) {
                        try {
                            ORU_R01 obs = hl7Observation(fx.getNumeric());
                            if (null != obs) {
                                bundle.add(obs);
                            }
                        } catch (Exception e) {
                            log.error("unable to create HL7 observation", e);
                        }
                    }
                }
            } finally {
                latch.countDown();
            }
        });
        latch.await();

        Parser parser = hl7Context.getPipeParser();
        Connection hapiConnection = this.hl7Connection;

        for (ORU_R01 x : bundle) {
            try {
                String encodedMessage = parser.encode(x);
                fireLine(encodedMessage);
                if (null != hapiConnection) {
                    Initiator initiator = hapiConnection.getInitiator();
                    Message response = initiator.sendAndReceive(x);
                    String responseString = parser.encode(response);
                    log.debug("Received Response:" + responseString);
                }
            } catch (Exception e) {
                log.error("unable to send HL7 message", e);
            }
        }
    }

    public ORU_R01 hl7Observation(NumericFx data) throws HL7Exception, IOException {
        ORU_R01 r01 = new ORU_R01();
        r01.initQuickstart("ORU", "R01", "T");

        MSH mshSegment = r01.getMSH();
        mshSegment.getSendingApplication().getNamespaceID().setValue("ICE");
        mshSegment.getSequenceNumber().setValue("123");

        ORU_R01_PATIENT patient = r01.getPATIENT_RESULT().getPATIENT();
        PID pid = patient.getPID();
        pid.getPatientName(0).getFamilyName().getSurname().setValue("Doe");
        pid.getPatientName(0).getGivenName().setValue("John");
        pid.getPatientIdentifierList(0).getIDNumber().setValue("123456");

        ORU_R01_ORDER_OBSERVATION orderObservation = r01.getPATIENT_RESULT().getORDER_OBSERVATION();
        orderObservation.getOBR().getObr7_ObservationDateTime().setValueToSecond(new Date());

        ORU_R01_OBSERVATION observation = orderObservation.getOBSERVATION(0);
        OBX obx = observation.getOBX();
        obx.getObservationIdentifier().getIdentifier().setValue("0002-4182");
        obx.getObservationIdentifier().getText().setValue("HR");
        obx.getObservationIdentifier().getCwe3_NameOfCodingSystem().setValue("MDIL");
        obx.getObservationSubID().setValue("0");
        obx.getUnits().getIdentifier().setValue("0004-0aa0");
        obx.getUnits().getText().setValue("bpm");
        obx.getUnits().getCwe3_NameOfCodingSystem().setValue("MDIL");
        obx.getObservationResultStatus().setValue("F");
        obx.getValueType().setValue("NM");

        NM nm = new NM(r01);
        nm.setValue(Float.toString(data.getValue()));

        obx.getObservationValue(0).setData(nm);
        return r01;
    }
}
