package org.mdpnp.apps.testapp.hl7;

import ice.MDSConnectivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.util.Callback;

import org.mdpnp.apps.device.OnListChange;
import org.mdpnp.apps.fxbeans.ElementObserver;
import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.testapp.validate.Validation;
import org.mdpnp.apps.testapp.validate.ValidationOracle;
import org.mdpnp.devices.MDSHandler;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSEvent;
import org.mdpnp.devices.MDSHandler.Connectivity.MDSListener;
import org.mdpnp.devices.PartitionAssignmentController;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.ListenerList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
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

public class HL7Emitter implements MDSListener, Runnable {
    public enum Type {
        FHIR_R4, V26,
    }

    protected static final Logger log = LoggerFactory.getLogger(HL7Emitter.class);

    private final HapiContext hl7Context;
    protected final FhirContext fhirContext;
    protected final MDSHandler mdsHandler;

    protected Connection hl7Connection;
    protected IGenericClient fhirClient;
    protected final ScheduledExecutorService executor;

    private final Map<String, String> deviceUdiToPatientMRN = Collections
            .synchronizedMap(new HashMap<String, String>());
    private final Map<String, IIdType> patientMRNtoResourceId = Collections.synchronizedMap(new HashMap<>());

    private final Map<String, IIdType> deviceUDItoResourceId = Collections.synchronizedMap(new HashMap<>());
    private final Set<Validation> recentUpdates = Collections.synchronizedSet(new HashSet<>());

    private final ListenerList<LineEmitterListener> listeners = new ListenerList<LineEmitterListener>(
            LineEmitterListener.class);
    private final ListenerList<StartStopListener> ssListeners = new ListenerList<StartStopListener>(
            StartStopListener.class);

    public HL7Emitter(final Subscriber subscriber, final EventLoop eventLoop,
            final ValidationOracle validationOracle,
            final FhirContext fhirContext) {

        executor = Executors.newSingleThreadScheduledExecutor();
        hl7Context = new DefaultHapiContext();
        this.fhirContext = fhirContext;
        this.validationOracle = validationOracle;

        if (validationOracle != null) {
            validationObserver = attachValidationObserver(validationOracle);
            validationOracle.forEach((t) -> add(t));
        }

        this.mdsHandler = new MDSHandler(eventLoop, subscriber.get_participant());
        mdsHandler.addConnectivityListener(this);
        mdsHandler.start();

    }

    ElementObserver<Validation> attachValidationObserver(ValidationOracle validationOracle) {
        // Observes changes to source_timestamp and queues the NumericFx for emission
        ElementObserver<Validation> observer = new ElementObserver<Validation>(
                new Callback<Validation, Observable[]>() {

                    @Override
                    public Observable[] call(Validation param) {
                        return new Observable[] { param.getNumeric().presentation_timeProperty() };
                    }

                }, new Callback<Validation, InvalidationListener>() {

                    @Override
                    public InvalidationListener call(final Validation param) {
                        return new InvalidationListener() {
                            private Date lastPresentationTime = null;

                            @Override
                            public void invalidated(Observable observable) {
                                Date dt = param.getNumeric().getPresentation_time();
                                if (null == lastPresentationTime || !lastPresentationTime.equals(dt)) {
                                    recentUpdates.add(param);
                                    lastPresentationTime = dt;
                                } else {
                                    log.trace("Ignoring a redundant " + param.getNumeric().getMetric_id());
                                }
                            }
                        };
                    }

                }, validationOracle);

        validationOracle.addListener(new OnListChange<>((t) -> add(t), null, (t) -> remove(t)));

        return observer;
    }

    private final ValidationOracle validationOracle;
    private Type type;
    private ScheduledFuture<?> emit;

    public void start(final String host, final int port, final Type type, final long interval) {
        this.type = type;

        if (host != null && !host.isEmpty()) {
            if (Type.V26.equals(type)) {
                try {

                    hl7Connection = hl7Context.newClient(host, port, false);
                    ssListeners.fire(started);

                } catch (HL7Exception e) {
                    log.error("", e);
                    stop();
                } catch (RuntimeException re) {
                    log.error("", re);
                    stop();
                }
            } else if (Type.FHIR_R4.equals(type)) {
                fhirClient = fhirContext.newRestfulGenericClient(host);
                ssListeners.fire(started);
            }
        } else {
            // We'll make it ok to start with no external connection
            // just to demo the ability to compose HL7 messages
            ssListeners.fire(started);
        }
        if (null == emit) {
            emit = executor.scheduleAtFixedRate(this, 0L, interval, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        if (null != emit) {
            emit.cancel(true);
            emit = null;
        }
        ssListeners.fire(stopped);
        if (hl7Connection != null) {
            hl7Connection.close();
            hl7Connection = null;
        }
        if (fhirClient != null) {
            // TODO is there an active connection to disconnect?
            fhirClient = null;
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        mdsHandler.shutdown();
    }

    static class DispatchStartStop implements ListenerList.Dispatcher<StartStopListener> {
        final boolean started;

        DispatchStartStop(final boolean started) {
            this.started = started;
        }

        @Override
        public void dispatch(StartStopListener l) {
            if (started) {
                l.started();
            } else {
                l.stopped();
            }
        }

    }

    private final static DispatchStartStop started = new DispatchStartStop(true);
    private final static DispatchStartStop stopped = new DispatchStartStop(false);

    static class DispatchLine implements ListenerList.Dispatcher<LineEmitterListener> {
        private final String line;

        public DispatchLine(final String line) {
            this.line = line;
        }

        @Override
        public void dispatch(LineEmitterListener l) {
            l.newLine(line);
        }

    }

    public ValidationOracle getValidationOracle() {
        return validationOracle;
    }

    public void addLineEmitterListener(LineEmitterListener listener) {
        listeners.addListener(listener);
    }

    public void removeLineEmitterListener(LineEmitterListener listener) {
        listeners.removeListener(listener);
    }

    public void addStartStopListener(StartStopListener listener) {
        ssListeners.addListener(listener);
    }

    public void removeStartStopListener(StartStopListener listener) {
        ssListeners.removeListener(listener);
    }

    protected void sendHL7v26() throws InterruptedException {
        List<ORU_R01> bundle = new ArrayList<ORU_R01>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                validationOracle.forEach((fx) -> {
                    if (fx.getNumeric().getMetric_id().startsWith("MDC_")) {
                        ORU_R01 obs;
                        try {
                            obs = hl7Observation(fx.getNumeric());
                            if (null != obs) {
                                bundle.add(obs);
                            }
                        } catch (Exception e) {
                            log.error("unable to create HL7 observation", e);
                        }

                    }
                });
            } finally {
                latch.countDown();
            }
        });
        latch.await();

        Parser parser = hl7Context.getPipeParser();
        // Now, let's encode the message and look at the output
        Connection hapiConnection = HL7Emitter.this.hl7Connection;

        bundle.forEach((x) -> {
            try {
                String encodedMessage = parser.encode(x);
                listeners.fire(new DispatchLine(encodedMessage));
                if (null != hapiConnection) {
                    Initiator initiator = hapiConnection.getInitiator();
                    Message response = initiator.sendAndReceive(x);
                    String responseString = parser.encode(response);
                    log.debug("Received Response:" + responseString);
                }
            } catch (Exception e) {
                log.error("unable to send HL7 message", e);
            }
        });
    }

    public IIdType getDeviceResource(String udi) {
        IIdType resourceId = deviceUDItoResourceId.get(udi);
        if (null == resourceId && fhirClient != null) {
            Device device = new Device();
            device.addIdentifier().setSystem(PTID_SYSTEM).setValue(udi);
            MethodOutcome outcome = fhirClient.update()
                    .resource(device)
                    .conditional()
                    .where(Device.IDENTIFIER.exactly().systemAndIdentifier(PTID_SYSTEM, udi))
                    .execute();
            resourceId = outcome.getId();
            log.info("udi " + udi + " is " + resourceId);
            deviceUDItoResourceId.put(udi, resourceId);
        }
        return resourceId;

    }

    public IIdType getPatientResource(String mrn) {
        IIdType resourceId = patientMRNtoResourceId.get(mrn);
        if (null == resourceId && fhirClient != null) {
            Bundle bundle = fhirClient
                    .search()
                    .forResource(Patient.class)
                    .where(Patient.IDENTIFIER.exactly().systemAndIdentifier(PTID_SYSTEM, mrn))
                    .returnBundle(Bundle.class)
                    .execute();
            List<Patient> patients = new ArrayList<>();
            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                if (entry.getResource() instanceof Patient) {
                    patients.add((Patient) entry.getResource());
                }
            }
            if (patients.isEmpty()) {
                log.warn("No patient in remote system with MRN=" + mrn);
                return null;
            } else {
                if (patients.size() > 1) {
                    log.warn("Duplicate resource ids for mrn=" + mrn + " using first");
                }
                resourceId = patients.get(0).getIdElement();
                patientMRNtoResourceId.put(mrn, resourceId);
            }

        }
        return resourceId;
    }

    public ORU_R01 hl7Observation(NumericFx data) throws HL7Exception, IOException {
        ORU_R01 r01 = new ORU_R01();
        // ORU is an observation
        // Event R01 is an unsolicited observation message
        // "T" for Test, "P" for Production, etc.
        r01.initQuickstart("ORU", "R01", "T");

        // Populate the MSH Segment
        MSH mshSegment = r01.getMSH();
        mshSegment.getSendingApplication().getNamespaceID().setValue("ICE");
        mshSegment.getSequenceNumber().setValue("123");

        // Populate the PID Segment
        ORU_R01_PATIENT patient = r01.getPATIENT_RESULT().getPATIENT();
        PID pid = patient.getPID();
        pid.getPatientName(0).getFamilyName().getSurname().setValue("Doe");
        pid.getPatientName(0).getGivenName().setValue("John");
        pid.getPatientIdentifierList(0).getIDNumber().setValue("123456");

        ORU_R01_ORDER_OBSERVATION orderObservation = r01.getPATIENT_RESULT().getORDER_OBSERVATION();

        orderObservation.getOBR().getObr7_ObservationDateTime().setValueToSecond(new Date());

        ORU_R01_OBSERVATION observation = orderObservation.getOBSERVATION(0);

        // Populate the first OBX
        OBX obx = observation.getOBX();
        // obx.getSetIDOBX().setValue("1");
        obx.getObservationIdentifier().getIdentifier().setValue("0002-4182");
        obx.getObservationIdentifier().getText().setValue("HR");
        obx.getObservationIdentifier().getCwe3_NameOfCodingSystem().setValue("MDIL");
        obx.getObservationSubID().setValue("0");
        obx.getUnits().getIdentifier().setValue("0004-0aa0");
        obx.getUnits().getText().setValue("bpm");
        obx.getUnits().getCwe3_NameOfCodingSystem().setValue("MDIL");
        obx.getObservationResultStatus().setValue("F");

        // The first OBX has a value type of CE. So first, we populate OBX-2
        // with "CE"...
        obx.getValueType().setValue("NM");

        // "NM" is Numeric
        NM nm = new NM(r01);
        nm.setValue(Float.toString(data.getValue()));

        obx.getObservationValue(0).setData(nm);
        return r01;
    }

    Observation fhirObservation(Validation validation) {
        NumericFx data = validation.getNumeric();

        Observation obs = new Observation();
        final String mrn = deviceUdiToPatientMRN.get(data.getUnique_device_identifier());
        if (null == mrn) {
            log.debug("No known mrn for udi=" + data.getUnique_device_identifier());
        }

        IIdType resourceId = null == mrn ? null : getPatientResource(mrn);
        if (null == resourceId) {
            log.debug("No known patient resource id for mrn=" + mrn);
        } else {
            obs.setSubject(new Reference(resourceId.toUnqualifiedVersionless()));
        }

        IIdType deviceResourceId = getDeviceResource(data.getUnique_device_identifier());
        if (null == deviceResourceId) {
            log.debug("No known device resource id for udi=" + data.getUnique_device_identifier());
        } else {
            obs.setDevice(new Reference(deviceResourceId.toUnqualifiedVersionless()));
        }

        obs.setValue(new Quantity().setValue(data.getValue()).setUnit(data.getUnit_id()).setCode(data.getMetric_id())
                .setSystem("OpenICE"));
        // obs.addIdentifier().setSystem("urn:info.openice").setValue(uuidFromSequence(sampleInfo.publication_sequence_number).toString());
        DateTimeType dt = new DateTimeType(data.getPresentation_time(), TemporalPrecisionEnum.SECOND);
        dt.setTimeZone(TimeZone.getTimeZone("UTC"));
        obs.setEffective(dt);
        obs.setStatus(validation.isValidated() ? Observation.ObservationStatus.FINAL
                : Observation.ObservationStatus.PRELIMINARY);

        return obs;
    }

    static final String METRIC_PREFIX = "MDC_";
    static final String PTID_SYSTEM = "urn:oid:2.16.840.1.113883.3.1974";

    public void sendFHIR() throws InterruptedException {
        List<Resource> bundle = new ArrayList<>();
        List<String> jsonStrings = new ArrayList<>();
        synchronized (recentUpdates) {
            recentUpdates.forEach((x) -> {
                Observation obs = fhirObservation(x);
                if (null != obs) {
                    bundle.add(obs);
                    String jsonEncoded = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(obs);
                    jsonStrings.add(jsonEncoded + "\n");
                }
            });
            log.debug("flushing {} FHIR observations", recentUpdates.size());
            recentUpdates.clear();
        }

        Platform.runLater(() -> jsonStrings.forEach((t) -> listeners.fire(new DispatchLine(t))));

        IGenericClient client = fhirClient;

        if (null != client) {
            List<IBaseResource> created = client.transaction().withResources(bundle).encodedJson().execute();
            for (IBaseResource r : created) {
                if (r != null && r.getIdElement().hasIdPart()) {
                    log.info("Created {} id={}", r.fhirType(), r.getIdElement().getIdPart());
                }
            }
        }
    }

    Set<Validation> getRecentUpdates() {
        return recentUpdates;
    }

    public void send() throws InterruptedException {
        if (Type.V26.equals(type)) {
            sendHL7v26();
        } else if (Type.FHIR_R4.equals(type)) {
            sendFHIR();
        }
    }

    @Override
    public void handleConnectivityChange(MDSEvent evt) {
        ice.MDSConnectivity c = (MDSConnectivity) evt.getSource();

        String mrnPartition = PartitionAssignmentController.findMRNPartition(c.partition);
        if (mrnPartition != null) {
            log.info("udi " + c.unique_device_identifier + " is " + mrnPartition);
            deviceUdiToPatientMRN.put(c.unique_device_identifier, PartitionAssignmentController.toMRN(mrnPartition));
        }
    }

    private ElementObserver<Validation> validationObserver;

    private void add(Validation validation) {
        if (validation.getNumeric().getMetric_id().startsWith(METRIC_PREFIX)) {
            validationObserver.attachListener(validation);
        }
    }

    private void remove(Validation validation) {
        // Must not detach what we did not attach
        if (validation.getNumeric().getMetric_id().startsWith(METRIC_PREFIX)) {
            validationObserver.detachListener(validation);
        }
    }

    @Override
    public void run() {
        try {
            send();
        } catch (InterruptedException e) {
            log.error("Sending", e);
        } catch (Throwable t) {
            log.error("Error sending FHIR data", t);
            stop();
        }

    }
}
