package org.mdpnp.apps.testapp.patient;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.FEMALE;
import static org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.MALE;

/**
 * @author mfeinberg
 */
class FhirEMRImpl extends EMRFacade {

    private static final Logger log = LoggerFactory.getLogger(FhirEMRImpl.class);

    private static final String HL7_ICE_URN_OID = "urn:oid:2.16.840.1.113883.3.1974";
    private FhirContext fhirContext;
    private String fhirURL;

    public FhirEMRImpl(Executor executor) {
        super(executor);
    }

    public FhirEMRImpl() {
        super(NOOP_HANDLER);
    }

    public String getUrl() {
        return fhirURL;
    }

    public void setUrl(String url) {
        fhirURL = url;
    }

    public FhirContext getFhirContext() {
        return fhirContext;
    }

    public void setFhirContext(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    public static boolean isServerThere(String u) throws Exception {
        try {
            URI uri = URI.create(u + "/metadata");
            URL url = uri.toURL();

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            // This will throw if server is not there.
            InputStream is = conn.getInputStream();
            return is != null;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public void deleteDevicePatientAssociation(DevicePatientAssociation assoc) {
        // NO-OP
    }

    @Override
    public DevicePatientAssociation updateDevicePatientAssociation(DevicePatientAssociation assoc) {
        // NO-OP
        return assoc;
    }

    @Override
    public List<PatientInfo> fetchAllPatients() {

        IGenericClient fhirClient = getFhirClient();

        Bundle bundle = fhirClient
                .search()
                .forResource(Patient.class)
                .returnBundle(Bundle.class)
                .execute();

        final List<PatientInfo> toRet = new ArrayList<>();

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (!(entry.getResource() instanceof Patient))
                continue;
            Patient p = (Patient) entry.getResource();
            Identifier id = p.getIdentifierFirstRep();
            if (id == null || !HL7_ICE_URN_OID.equals(id.getSystem()))
                continue;
            String mrn = id.getValue();

            for (HumanName n : p.getName()) {
                if (n.getUse() == HumanName.NameUse.OFFICIAL || n.getUse() == null) {
                    String lName = n.getFamily();
                    String fName = n.getGivenAsSingleString();
                    Date bDay = p.getBirthDate();
                    Enumerations.AdministrativeGender g = p.getGender();
                    if (lName != null && fName != null && bDay != null && g != null) {
                        PatientInfo pi = new PatientInfo(mrn, fName, lName, fromFhire(g), bDay);
                        toRet.add(pi);
                        break;
                    }
                }
            }
        }

        return toRet;
    }

    public boolean createPatient(final PatientInfo p) {
        boolean ok = super.createPatient(p);
        MethodOutcome mo = createPatientImpl(p);
        log.info("Created new patient; id=" + mo.getId());
        return ok && mo.getCreated();
    }

    public boolean deletePatient(PatientInfo p) {
        return false;
    }

    MethodOutcome createPatientImpl(final PatientInfo p) {

        IGenericClient fhirClient = getFhirClient();

        String mrnId = p.getMrn();

        Patient patient = new Patient();
        patient.addIdentifier().setUse(Identifier.IdentifierUse.OFFICIAL).setSystem(HL7_ICE_URN_OID).setValue(mrnId);
        HumanName name = patient.addName();
        name.setFamily(p.getLastName());
        name.addGiven(p.getFirstName());
        patient.setGender(toFhire(p.getGender()));
        patient.setBirthDate(p.getDob());

        MethodOutcome outcome = fhirClient.update()
                .resource(patient)
                .conditional()
                .where(Patient.IDENTIFIER.exactly().systemAndIdentifier(HL7_ICE_URN_OID, mrnId))
                .execute();

        return outcome;
    }

    IGenericClient getFhirClient() {
        return fhirContext.newRestfulGenericClient(fhirURL);
    }

    static Enumerations.AdministrativeGender toFhire(PatientInfo.Gender g) {
        switch (g) {
            default:
            case M:
                return MALE;
            case F:
                return FEMALE;
        }
    }

    static PatientInfo.Gender fromFhire(String g) {
        return fromFhire(Enumerations.AdministrativeGender.fromCode(g));
    }

    static PatientInfo.Gender fromFhire(Enumerations.AdministrativeGender g) {
        switch (g) {
            default:
                throw new IllegalArgumentException("Unknown conversion " + g);
            case MALE:
                return PatientInfo.Gender.M;
            case FEMALE:
                return PatientInfo.Gender.F;
        }
    }
}
