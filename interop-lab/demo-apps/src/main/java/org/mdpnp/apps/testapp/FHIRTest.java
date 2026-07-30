package org.mdpnp.apps.testapp;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Patient;

public class FHIRTest {
    public static void main(String[] args) {
        FhirContext ctx = FhirContext.forR4();
        String serverBase = "https://hapi.fhir.org/baseR4";

        IGenericClient client = ctx.newRestfulGenericClient(serverBase);
        Patient patient = new Patient();
        patient.addIdentifier().setSystem("urn:system").setValue("12345");
        patient.addName().setFamily("Smith").addGiven("John");

        MethodOutcome outcome = client.create().resource(patient).prettyPrint().encodedJson().execute();

        IIdType id = outcome.getId();
        System.out.println("Got ID: " + id.getValue());
    }
}
