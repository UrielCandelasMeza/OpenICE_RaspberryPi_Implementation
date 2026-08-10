package org.mdpnp.apps.testapp.hl7;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import org.mdpnp.apps.fxbeans.NumericFx;
import org.mdpnp.apps.testapp.IceProperties;
import org.mdpnp.apps.testapp.validate.Validation;
import org.mdpnp.apps.testapp.validate.ValidationOracle;
import org.mdpnp.rtiapi.data.EventLoop;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

import com.rti.dds.subscription.Subscriber;

/**
 * Emisor especializado exclusivamente en el estándar HL7 FHIR R4.
 * Administra la creación y envío de recursos Observation, Patient y Device vía REST API con OAuth2.
 */
public class FhirEmitter extends AbstractEmitter {

    protected final FhirContext fhirContext;
    protected IGenericClient fhirClient;
    protected IGenericClient backendClient;
    private String patientIdentifierSystem = PTID_SYSTEM;
    private final TokenProvider tokenProvider = new TokenProvider();
    private BearerTokenAuthInterceptor bearerInterceptor;

    private final Map<String, IIdType> patientMRNtoResourceId = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, IIdType> deviceUDItoResourceId = Collections.synchronizedMap(new HashMap<>());

    public FhirEmitter(Subscriber subscriber, EventLoop eventLoop, ValidationOracle validationOracle, FhirContext fhirContext) {
        super(subscriber, eventLoop, validationOracle);
        this.fhirContext = fhirContext;
    }

    @Override
    public void start(String host, int port, long interval) {
        if (host != null && !host.isEmpty()) {
            fhirClient = fhirContext.newRestfulGenericClient(host);
            backendClient = fhirContext.newRestfulGenericClient(backendUrl());
            patientIdentifierSystem = new IceProperties("mdpnp.fhir.patient.identifier.system")
                    .getValue().orElse(PTID_SYSTEM);
            bearerInterceptor = null;
            refreshBearerToken(fhirClient);
            fireStarted();
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
        fhirClient = null;
        backendClient = null;
    }

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

    private String backendUrl() {
        return new IceProperties("mdpnp.fhir.backend.url").getValue().orElse("http://localhost:8099/fhir");
    }

    private String authorizedList() {
        return new IceProperties("mdpnp.fhir.list.auth").getValue().orElse("patient-list-example");
    }

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
            log.error("addToAuthorizedList: FALLO al agregar {} a la lista: {}", patientId.getIdPart(), e.getMessage(), e);
        }
    }

    public Observation fhirObservation(Validation validation) {
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
        DateTimeType dt = new DateTimeType(data.getPresentation_time(), TemporalPrecisionEnum.SECOND);
        dt.setTimeZone(TimeZone.getTimeZone("UTC"));
        obs.setEffective(dt);
        obs.setStatus(validation.isValidated() ? Observation.ObservationStatus.FINAL
                : Observation.ObservationStatus.PRELIMINARY);

        return obs;
    }

    @Override
    protected void emitData(List<Validation> pendingUpdates) throws Exception {
        sendFHIR(pendingUpdates);
    }

    public void sendFHIR(List<Validation> pendingUpdates) throws InterruptedException {
        IGenericClient client = fhirClient;
        if (client == null) {
            return;
        }

        refreshBearerToken(client);

        try {
            sendObservations(client, pendingUpdates);
        } catch (BaseServerResponseException e) {
            if (e.getStatusCode() == 401 || e.getStatusCode() == 403) {
                log.warn("FHIR request failed with status {}; refreshing token and retrying once", e.getStatusCode());
                tokenProvider.getAccessToken();
                refreshBearerToken(client);
                try {
                    sendObservations(client, pendingUpdates);
                } catch (BaseServerResponseException ex) {
                    log.error("Reintento fallido de envio FHIR (Status Code: {}): {}", ex.getStatusCode(),
                            ex.getResponseBody());
                } catch (Exception ex) {
                    log.error("Error inesperado en reintento de envio FHIR", ex);
                }
            } else if (e.getStatusCode() == 400) {
                log.error("Error 400 (Bad Request) al enviar observaciones al Gateway FHIR. Detalle: {}",
                        e.getResponseBody());
            } else {
                log.error("Error del servidor/Gateway FHIR (Status Code: {}): {}", e.getStatusCode(),
                        e.getResponseBody());
            }
        } catch (Exception e) {
            log.error("Error inesperado al enviar observaciones FHIR", e);
        }
    }

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

        Platform.runLater(() -> jsonStrings.forEach((t) -> fireLine(t)));

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
}
