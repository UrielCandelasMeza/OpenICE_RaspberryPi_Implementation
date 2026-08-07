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
import org.mdpnp.apps.testapp.IceProperties;
import org.mdpnp.apps.testapp.patient.EMRFacade;
// import org.mdpnp.apps.testapp.patient.PatientInfo;
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
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Device;
//import org.hl7.fhir.r4.model.Identifier;
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

/**
 * Emisor de datos clínicos que se encarga de transmitir observaciones
 * fisiológicas en tiempo real
 * recibidas desde dispositivos médicos (vía RTI DDS) hacia sistemas externos de
 * historia clínica electrónica (EMR).
 * Soporta la transmisión en formato de mensajería estándar HL7 v2.6 (ORU^R01)
 * mediante sockets TCP,
 * o en formato FHIR R4 (Resources: Observation, Patient, Device) mediante una
 * API RESTful con soporte para OAuth2.
 */
public class HL7Emitter implements MDSListener, Runnable {
    /**
     * Protocolos/formatos de comunicación soportados por el emisor.
     */
    public enum Type {
        /** Formato estándar HL7 FHIR R4. */
        FHIR_R4,
        /** Formato estándar HL7 v2.6 ORU_R01. */
        V26,
    }

    /** Logger de la clase para eventos de transmisión de datos. */
    protected static final Logger log = LoggerFactory.getLogger(HL7Emitter.class);

    /** Contexto de HAPI para procesamiento de mensajes HL7 v2. */
    private final HapiContext hl7Context;
    /** Contexto de HAPI FHIR para serialización y llamadas REST. */
    protected final FhirContext fhirContext;
    /** Manejador de conectividad e información de dispositivos MDS. */
    protected final MDSHandler mdsHandler;

    /** Conexión activa para la transmisión HL7 v2.6. */
    protected Connection hl7Connection;
    /** Cliente FHIR que interactúa con el Gateway seguro (puerto 8080). */
    protected IGenericClient fhirClient;
    /**
     * Cliente FHIR directo al backend sin autenticación (puerto 8099) para
     * configurar recursos base.
     */
    protected IGenericClient backendClient;
    /** Sistema de identificación del paciente configurado para los MRN. */
    private String patientIdentifierSystem = PTID_SYSTEM;
    // private final EMRFacade emr;
    /**
     * Planificador de hilos para la emisión periódica a intervalos configurados.
     */
    protected final ScheduledExecutorService executor;

    /** Proveedor del token de acceso OAuth2 para el Gateway. */
    private final TokenProvider tokenProvider = new TokenProvider();
    /**
     * Interceptor HTTP para inyectar la cabecera Bearer Token en solicitudes FHIR.
     */
    private BearerTokenAuthInterceptor bearerInterceptor;

    /**
     * Mapa sincronizado que asocia el identificador UDI de un dispositivo con el
     * MRN del paciente activo.
     */
    private final Map<String, String> deviceUdiToPatientMRN = Collections
            .synchronizedMap(new HashMap<String, String>());
    /**
     * Mapa sincronizado que asocia el MRN del paciente con su correspondiente ID de
     * recurso en el backend FHIR.
     */
    private final Map<String, IIdType> patientMRNtoResourceId = Collections.synchronizedMap(new HashMap<>());
    /**
     * MRN del paciente seleccionado actualmente a través de la interfaz gráfica.
     */
    private volatile String selectedPatientMRN;

    /**
     * Mapa sincronizado que asocia el UDI del dispositivo con su correspondiente ID
     * de recurso Device en el backend.
     */
    private final Map<String, IIdType> deviceUDItoResourceId = Collections.synchronizedMap(new HashMap<>());
    /**
     * Conjunto sincronizado de datos clínicos validados que han cambiado y están
     * listos para emitirse.
     */
    private final Set<Validation> recentUpdates = Collections.synchronizedSet(new HashSet<>());

    /**
     * Lista de listeners para despachar líneas de texto de salida y visualizarlas.
     */
    private final ListenerList<LineEmitterListener> listeners = new ListenerList<LineEmitterListener>(
            LineEmitterListener.class);
    /**
     * Lista de listeners para notificar cambios en el estado de inicio/detención
     * del emisor.
     */
    private final ListenerList<StartStopListener> ssListeners = new ListenerList<StartStopListener>(
            StartStopListener.class);

    /**
     * Constructor principal de {@code HL7Emitter}.
     *
     * @param subscriber       Suscriptor de DDS utilizado para recibir datos de
     *                         dispositivos.
     * @param eventLoop        Bucle de eventos para procesar cambios en DDS.
     * @param validationOracle Oráculo de validación de datos clínicos.
     * @param fhirContext      Contexto FHIR de HAPI para la serialización y
     *                         clientes REST.
     * @param emr              Fachada para consultar información de pacientes.
     */
    public HL7Emitter(final Subscriber subscriber, final EventLoop eventLoop,
            final ValidationOracle validationOracle,
            final FhirContext fhirContext,
            final EMRFacade emr) {

        // this.emr = emr;
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

    /**
     * Asocia un observador al oráculo de validación para monitorear los cambios
     * en las marcas de tiempo de presentación de los datos numéricos y encolarlos
     * para su posterior emisión.
     *
     * @param validationOracle El oráculo de validación a observar.
     * @return El observador de elementos creado.
     */
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

    /**
     * Inicia el proceso de emisión periódica de datos clínicos. Dependiendo del
     * tipo
     * seleccionado, establece la conexión con el servidor TCP HL7 v2.6 o inicializa
     * los clientes para el Gateway FHIR R4.
     *
     * @param host     Dirección IP o dominio del servidor de destino.
     * @param port     Puerto de comunicación del servidor.
     * @param type     El protocolo de emisión (V26 o FHIR_R4).
     * @param interval Intervalo de emisión periódica en milisegundos.
     */
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
                backendClient = fhirContext.newRestfulGenericClient(backendUrl());
                patientIdentifierSystem = new IceProperties("mdpnp.fhir.patient.identifier.system")
                        .getValue().orElse(PTID_SYSTEM);
                bearerInterceptor = null;
                refreshBearerToken(fhirClient);
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

    /**
     * Refresca e inyecta el token de acceso OAuth2 Bearer en el cliente FHIR.
     * Si no se dispone de un interceptor, registra uno nuevo.
     *
     * @param client Cliente FHIR genérico en el que se actualizará el token.
     */
    private void refreshBearerToken(IGenericClient client) {
        String token = tokenProvider.getAccessToken();
        if (bearerInterceptor == null) {
            bearerInterceptor = new BearerTokenAuthInterceptor(token == null ? "" : token);
            client.registerInterceptor(bearerInterceptor);
        } else if (token != null && !token.isEmpty()) {
            bearerInterceptor.setToken(token);
        }
        System.out.println("Authorization header que se enviara: Bearer "
                + (token == null || token.isEmpty() ? "(SIN TOKEN)" : token));
        if (token == null || token.isEmpty()) {
            log.warn("No valid token available; FHIR requests will be sent without Authorization header");
        }
    }

    /**
     * Establece el número de registro médico (MRN) del paciente seleccionado en la
     * interfaz de usuario.
     * Este MRN se usará de manera prioritaria al asociar observaciones con
     * pacientes.
     *
     * @param mrn Registro médico del paciente.
     */
    public void setSelectedPatientMRN(String mrn) {
        this.selectedPatientMRN = mrn;
        log.info("MRN seleccionado desde UI: {}", mrn);
    }

    /**
     * Detiene la tarea de emisión periódica y cierra las conexiones abiertas
     * (HL7/FHIR).
     */
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

    /**
     * Realiza un apagado total del emisor, terminando el planificador de hilos
     * y el manejador del servicio de datos de dispositivos (MDSHandler).
     */
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

    /**
     * Obtiene el oráculo de validación asociado al emisor.
     *
     * @return El oráculo de validación de tipo {@link ValidationOracle}.
     */
    public ValidationOracle getValidationOracle() {
        return validationOracle;
    }

    /**
     * Agrega un listener para recibir las líneas de salida generadas por el emisor
     * (mensajes codificados).
     *
     * @param listener El listener a agregar.
     */
    public void addLineEmitterListener(LineEmitterListener listener) {
        listeners.addListener(listener);
    }

    /**
     * Remueve un listener de líneas generadas.
     *
     * @param listener El listener a remover.
     */
    public void removeLineEmitterListener(LineEmitterListener listener) {
        listeners.removeListener(listener);
    }

    /**
     * Agrega un listener para monitorear el estado de inicio y detención del
     * emisor.
     *
     * @param listener El listener a agregar.
     */
    public void addStartStopListener(StartStopListener listener) {
        ssListeners.addListener(listener);
    }

    /**
     * Remueve un listener de estado de inicio/detención.
     *
     * @param listener El listener a remover.
     */
    public void removeStartStopListener(StartStopListener listener) {
        ssListeners.removeListener(listener);
    }

    /**
     * Recopila las observaciones del oráculo de validación, las empaqueta en
     * mensajes HL7 v2.6 ORU^R01 y las envía por socket TCP al servidor configurado.
     *
     * @throws InterruptedException Si el hilo es interrumpido durante el proceso.
     */
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

    /**
     * Obtiene el recurso de tipo Device desde el backend FHIR correspondiente al
     * UDI especificado.
     * Si no existe en el backend, lo crea vinculándolo con el ID del paciente
     * provisto.
     *
     * @param udi               Identificador único de dispositivo.
     * @param patientResourceId ID del recurso de paciente asociado en el backend.
     * @return El identificador del recurso Device en FHIR.
     */
    public IIdType getDeviceResource(String udi, IIdType patientResourceId) {
        IIdType resourceId = deviceUDItoResourceId.get(udi);
        if (null == resourceId && backendClient != null) {
            Device device = new Device();
            device.addIdentifier().setSystem(PTID_SYSTEM).setValue(udi);
            if (null != patientResourceId) {
                device.setPatient(new Reference(patientResourceId.toUnqualifiedVersionless()));
            }
            MethodOutcome outcome = backendClient.update()
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

    /**
     * Resuelve y obtiene el identificador de recurso de paciente (Patient) en el
     * backend FHIR
     * para el MRN especificado. Si no existe, realiza un PUT condicional para
     * crearlo en el backend
     * y lo agrega a la lista de pacientes autorizados (List Resource).
     *
     * @param mrn Registro médico del paciente.
     * @return El identificador del recurso Patient en FHIR.
     */
    public IIdType getPatientResource(String mrn) {
        IIdType resourceId = patientMRNtoResourceId.get(mrn);
        if (null == resourceId && backendClient != null) {
            try {
                Bundle bundle = backendClient
                        .search()
                        .forResource(Patient.class)
                        .where(Patient.IDENTIFIER.exactly().systemAndIdentifier(patientIdentifierSystem, mrn))
                        .returnBundle(Bundle.class)
                        .execute();
                List<Patient> patients = new ArrayList<>();
                for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                    if (entry.getResource() instanceof Patient) {
                        patients.add((Patient) entry.getResource());
                    }
                }
                if (patients.isEmpty()) {
                    log.info("Patient con MRN={} no existe en backend, creando...", mrn);
                    Patient patient = new Patient();
                    patient.addIdentifier().setSystem(patientIdentifierSystem).setValue(mrn);
                    patient.addName().setFamily("Unknown").addGiven("Patient");
                    MethodOutcome outcome = backendClient.update()
                            .resource(patient)
                            .conditional()
                            .where(Patient.IDENTIFIER.exactly().systemAndIdentifier(patientIdentifierSystem, mrn))
                            .execute();
                    resourceId = outcome.getId();
                    log.info("Patient creado en backend: {} MRN={}", resourceId.getIdPart(), mrn);
                    patientMRNtoResourceId.put(mrn, resourceId);
                } else {
                    if (patients.size() > 1) {
                        log.warn("Duplicate resource ids for mrn=" + mrn + " using first");
                    }
                    resourceId = patients.get(0).getIdElement();
                    patientMRNtoResourceId.put(mrn, resourceId);
                }
                addToAuthorizedList(resourceId);
            } catch (BaseServerResponseException e) {
                log.warn("Failed to resolve patient for MRN=" + mrn + ": " + e.getMessage());
            }
        }
        return resourceId;
    }

    private String backendUrl() {
        return new IceProperties("mdpnp.fhir.backend.url").getValue().orElse("http://localhost:8099/fhir");
    }

    private String authorizedList() {
        return new IceProperties("mdpnp.fhir.list.auth").getValue().orElse("patient-list-example");
    };

    /**
     * Agrega el recurso del paciente especificado al recurso List FHIR de pacientes
     * autorizados
     * (patient-list-example) en el backend, si es que no se encuentra ya registrado
     * en ella.
     * Esto es requerido para cumplir con las políticas de ListAccessChecker.
     *
     * @param patientId El identificador de recurso del paciente a autorizar.
     */
    private void addToAuthorizedList(IIdType patientId) {
        if (backendClient == null) {
            log.error("addToAuthorizedList: backendClient es null, no se puede agregar paciente a lista");
            return;
        }
        try {
            String ref = patientId.toUnqualifiedVersionless().getValue();
            log.info("addToAuthorizedList: verificando si {} esta en la lista {}...", ref, authorizedList());

            org.hl7.fhir.r4.model.ListResource fhirList = backendClient.read()
                    .resource(org.hl7.fhir.r4.model.ListResource.class)
                    .withId(authorizedList())
                    .execute();

            boolean alreadyInList = false;
            for (org.hl7.fhir.r4.model.ListResource.ListEntryComponent entry : fhirList.getEntry()) {
                String entryRef = entry.getItem() != null ? entry.getItem().getReference() : null;
                log.debug("  entry in list: {}", entryRef);
                if (ref.equals(entryRef)) {
                    alreadyInList = true;
                    break;
                }
            }

            if (!alreadyInList) {
                log.info("addToAuthorizedList: {} NO esta en la lista, agregando...", ref);
                org.hl7.fhir.r4.model.ListResource.ListEntryComponent newEntry = fhirList.addEntry();
                newEntry.getItem().setReference(ref);
                MethodOutcome outcome = backendClient.update().resource(fhirList).execute();
                log.info("addToAuthorizedList: {} agregado a la lista, outcome={}", ref, outcome.getId());
            } else {
                log.info("addToAuthorizedList: {} ya esta en la lista", ref);
            }
        } catch (Exception e) {
            log.error("addToAuthorizedList: FALLO al agregar {} a la lista: {}", patientId.getIdPart(), e.getMessage(),
                    e);
        }
    }

    /**
     * Construye y llena una estructura de mensaje HL7 v2.6 ORU_R01 conteniendo
     * un segmento MSH, PID y OBX a partir de un objeto NumericFx.
     *
     * @param data El dato numérico del dispositivo.
     * @return El mensaje {@link ORU_R01} estructurado.
     * @throws HL7Exception Si ocurre un error de parsing o estructuración HL7.
     * @throws IOException  Si ocurre un error de entrada/salida.
     */
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

    /**
     * Mapea un objeto de validación clínica a un recurso de tipo
     * {@link Observation} de FHIR R4,
     * asociando las referencias correspondientes al paciente (Subject) y al
     * dispositivo (Device).
     *
     * @param validation El objeto de validación conteniendo el dato numérico.
     * @return El recurso FHIR {@link Observation} generado.
     */
    Observation fhirObservation(Validation validation) {
        NumericFx data = validation.getNumeric();
        Observation obs = new Observation();
        String udi = data.getUnique_device_identifier();

        String mrn = selectedPatientMRN;
        if (mrn == null) {
            mrn = deviceUdiToPatientMRN.get(udi);
        }
        if (mrn == null) {
            log.debug("No known mrn for udi=" + udi);
        }

        IIdType resourceId = null == mrn ? null : getPatientResource(mrn);
        if (null == resourceId) {
            log.warn("Observacion sin subject: udi={} mrn={} no resolvio Patient (identifierSystem={})",
                    udi, mrn, patientIdentifierSystem);
        } else {
            obs.setSubject(new Reference(resourceId.toUnqualifiedVersionless()));
        }

        IIdType deviceResourceId = getDeviceResource(data.getUnique_device_identifier(), resourceId);
        if (null != deviceResourceId) {
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

    /**
     * Obtiene las observaciones acumuladas y pendientes de procesar, refresca el
     * token de acceso
     * y las transmite al Gateway FHIR. Reintenta la transmisión una vez si falla
     * por token expirado (401/403) y captura errores como el 400 (Bad Request) para
     * su posterior tratamiento sin detener el flujo de ejecución.
     *
     * @throws InterruptedException Si el hilo es interrumpido durante el envío.
     */
    public void sendFHIR() throws InterruptedException {
        IGenericClient client = fhirClient;

        if (client == null) {
            return;
        }

        refreshBearerToken(client);

        List<Validation> pending;
        synchronized (recentUpdates) {
            pending = new ArrayList<>(recentUpdates);
            recentUpdates.clear();
        }
        if (pending.isEmpty()) {
            return;
        }

        try {
            sendObservations(client, pending);
        } catch (BaseServerResponseException e) {
            if (e.getStatusCode() == 401 || e.getStatusCode() == 403) {
                log.warn("FHIR request failed with status {}; refreshing token and retrying once", e.getStatusCode());
                tokenProvider.getAccessToken();
                refreshBearerToken(client);
                try {
                    sendObservations(client, pending);
                } catch (BaseServerResponseException ex) {
                    log.error("Reintento fallido de envio FHIR (Status Code: {}): {}", ex.getStatusCode(),
                            ex.getResponseBody());
                } catch (Exception ex) {
                    log.error("Error inesperado en reintento de envio FHIR", ex);
                }
            } else if (e.getStatusCode() == 400) {
                log.error("Error 400 (Bad Request) al enviar observaciones al Gateway FHIR. Detalle: {}",
                        e.getResponseBody());
                // TODO: Aquí puedes agregar tu lógica de negocio específica para el error 400
            } else {
                log.error("Error del servidor/Gateway FHIR (Status Code: {}): {}", e.getStatusCode(),
                        e.getResponseBody());
            }
        } catch (Exception e) {
            log.error("Error inesperado al enviar observaciones FHIR", e);
        }
    }

    /**
     * Envía un lote de observaciones validadas al Gateway FHIR encapsuladas dentro
     * de un
     * Bundle de transacción. Solo se envían al Gateway aquellas observaciones que
     * tienen
     * un paciente asociado (subject).
     *
     * @param client  Cliente FHIR utilizado para transmitir la transacción.
     * @param pending Lista de validaciones/observaciones pendientes.
     */
    private void sendObservations(IGenericClient client, List<Validation> pending) {
        System.out.println("Enviando " + pending.size() + " observaciones FHIR...");
        List<Resource> bundle = new ArrayList<>();
        List<String> jsonStrings = new ArrayList<>();
        for (Validation x : pending) {
            Observation obs = fhirObservation(x);
            String jsonEncoded = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(obs);
            jsonStrings.add(jsonEncoded + "\n");
            if (obs.hasSubject()) {
                bundle.add(obs);
            } else {
                log.info("Observacion sin subject, no se envia al gateway: udi={}",
                        obs.hasDevice() ? obs.getDevice().getReference() : "unknown");
            }
        }

        Platform.runLater(() -> jsonStrings.forEach((t) -> listeners.fire(new DispatchLine(t))));

        if (bundle.isEmpty()) {
            System.out.println("Ninguna observacion tiene subject; no se envia al gateway.");
            return;
        }

        System.out.println("Enviando " + bundle.size() + " observaciones con subject al gateway FHIR...");

        List<IBaseResource> created = client.transaction().withResources(bundle).encodedJson().execute();
        for (IBaseResource r : created) {
            if (r != null && r.getIdElement().hasIdPart()) {
                log.info("Created {} id={}", r.fhirType(), r.getIdElement().getIdPart());
            }
        }
    }

    Set<Validation> getRecentUpdates() {
        return recentUpdates;
    }

    /**
     * Canaliza el envío de datos invocando la función correspondiente de acuerdo
     * al protocolo/formato activo (HL7 v2.6 o FHIR R4).
     *
     * @throws InterruptedException Si el hilo es interrumpido.
     */
    public void send() throws InterruptedException {
        if (Type.V26.equals(type)) {
            sendHL7v26();
        } else if (Type.FHIR_R4.equals(type)) {
            sendFHIR();
        }
    }

    /**
     * Escucha y reacciona a los eventos de cambio de conectividad de los
     * dispositivos (MDS),
     * extrayendo el MRN del paciente desde la partición activa asignada en DDS.
     *
     * @param evt Evento de conectividad MDSEvent.
     */
    @Override
    public void handleConnectivityChange(MDSEvent evt) {
        ice.MDSConnectivity c = (MDSConnectivity) evt.getSource();

        String mrnPartition = PartitionAssignmentController.findMRNPartition(c.partition);
        if (mrnPartition != null) {
            log.info("udi " + c.unique_device_identifier + " is " + mrnPartition);
            deviceUdiToPatientMRN.put(c.unique_device_identifier, PartitionAssignmentController.toMRN(mrnPartition));
        } else {
            log.debug("udi {} partitions={} sin MRN (se usa MRN del ComboBox si esta seleccionado)",
                    c.unique_device_identifier, c.partition);
        }
    }

    private ElementObserver<Validation> validationObserver;

    /**
     * Registra y asocia la validación provista al observador si corresponde
     * a una métrica de dispositivo clínico (con prefijo MDC_).
     *
     * @param validation La validación a agregar.
     */
    private void add(Validation validation) {
        if (validation.getNumeric().getMetric_id().startsWith(METRIC_PREFIX)) {
            validationObserver.attachListener(validation);
        }
    }

    /**
     * Remueve y desasocia la validación del observador de cambios.
     *
     * @param validation La validación a remover.
     */
    private void remove(Validation validation) {
        // Must not detach what we did not attach
        if (validation.getNumeric().getMetric_id().startsWith(METRIC_PREFIX)) {
            validationObserver.detachListener(validation);
        }
    }

    /**
     * Hilo ejecutor de la tarea periódica (Runnable). Invoca el método
     * {@code send()}
     * para realizar el ciclo de emisión y maneja cualquier excepción ocurrida.
     */
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
