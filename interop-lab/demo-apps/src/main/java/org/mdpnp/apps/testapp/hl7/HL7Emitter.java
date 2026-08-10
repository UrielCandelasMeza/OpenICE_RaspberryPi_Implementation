package org.mdpnp.apps.testapp.hl7;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Observation;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.v26.message.ORU_R01;

import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.testapp.patient.EMRFacade;
import org.mdpnp.apps.testapp.validate.Validation;
import org.mdpnp.apps.testapp.validate.ValidationOracle;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.subscription.Subscriber;

/**
 * Fachada (Facade/Delegator) para mantener compatibilidad con el controlador
 * JavaFX {@link HL7Application}
 * y la fábrica {@link HL7ApplicationFactory}.
 * Administra y delega las operaciones al emisor concreto adecuado
 * ({@link HL7v26Emitter} o {@link FhirEmitter}).
 */
public class HL7Emitter {

    protected static final Logger log = LoggerFactory.getLogger(HL7Emitter.class);

    public static final String METRIC_PREFIX = AbstractEmitter.METRIC_PREFIX;
    public static final String PTID_SYSTEM = AbstractEmitter.PTID_SYSTEM;

    private final HL7v26Emitter v26Emitter;
    private final FhirEmitter fhirEmitter;
    private AbstractEmitter activeEmitter;
    private EmitterType type;

    public HL7Emitter(final Subscriber subscriber, final EventLoop eventLoop,
            final ValidationOracle validationOracle,
            final FhirContext fhirContext,
            final EMRFacade emr) {
        this.v26Emitter = new HL7v26Emitter(subscriber, eventLoop, validationOracle);
        this.fhirEmitter = new FhirEmitter(subscriber, eventLoop, validationOracle, fhirContext);
        this.activeEmitter = v26Emitter;
    }

    public void start(final String host, final int port, final EmitterType type, final long interval) {
        this.type = type;
        if (activeEmitter != null) {
            activeEmitter.stop();
        }
        if (EmitterType.V26.equals(type)) {
            activeEmitter = v26Emitter;
        } else if (EmitterType.FHIR_R4.equals(type)) {
            activeEmitter = fhirEmitter;
        }
        if (activeEmitter != null) {
            activeEmitter.start(host, port, interval);
        }
    }

    public void stop() {
        if (activeEmitter != null) {
            activeEmitter.stop();
        }
    }

    public void shutdown() {
        v26Emitter.shutdown();
        fhirEmitter.shutdown();
    }

    public void setSelectedPatientMRN(String mrn) {
        v26Emitter.setSelectedPatientMRN(mrn);
        fhirEmitter.setSelectedPatientMRN(mrn);
    }

    public String getSelectedPatientMRN() {
        return activeEmitter != null ? activeEmitter.getSelectedPatientMRN() : null;
    }

    public ValidationOracle getValidationOracle() {
        return activeEmitter != null ? activeEmitter.getValidationOracle() : v26Emitter.getValidationOracle();
    }

    public Set<Validation> getRecentUpdates() {
        return activeEmitter != null ? activeEmitter.getRecentUpdates() : v26Emitter.getRecentUpdates();
    }

    public void addLineEmitterListener(LineEmitterListener listener) {
        v26Emitter.addLineEmitterListener(listener);
        fhirEmitter.addLineEmitterListener(listener);
    }

    public void removeLineEmitterListener(LineEmitterListener listener) {
        v26Emitter.removeLineEmitterListener(listener);
        fhirEmitter.removeLineEmitterListener(listener);
    }

    public void addStartStopListener(StartStopListener listener) {
        v26Emitter.addStartStopListener(listener);
        fhirEmitter.addStartStopListener(listener);
    }

    public void removeStartStopListener(StartStopListener listener) {
        v26Emitter.removeStartStopListener(listener);
        fhirEmitter.removeStartStopListener(listener);
    }

    // Métodos delegados de compatibilidad
    public ORU_R01 hl7Observation(NumericFx data) throws HL7Exception, IOException {
        return v26Emitter.hl7Observation(data);
    }

    public Observation fhirObservation(Validation validation) {
        return fhirEmitter.fhirObservation(validation);
    }

    public IIdType getPatientResource(String mrn) {
        return fhirEmitter.getPatientResource(mrn);
    }

    public IIdType getDeviceResource(String udi, IIdType patientResourceId) {
        return fhirEmitter.getDeviceResource(udi, patientResourceId);
    }

    public void sendHL7v26(List<Validation> pendingUpdates) throws InterruptedException {
        v26Emitter.sendHL7v26(pendingUpdates);
    }

    public void sendFHIR(List<Validation> pendingUpdates) throws InterruptedException {
        fhirEmitter.sendFHIR(pendingUpdates);
    }
}
