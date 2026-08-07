package org.mdpnp.apps.testapp.hl7;

import java.util.Date;
// import java.util.List;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Observation;
// import org.hl7.fhir.r4.model.Patient;

public class Fhir {
    public static void main(String[] args) {
        FhirContext fhirContext = FhirContext.forR4();
        IGenericClient fhirClient = fhirContext.newRestfulGenericClient("https://hapi.fhir.org/baseR4");

        Bundle bundle = fhirClient
                .search()
                .forResource(Observation.class)
                .where(Observation.SUBJECT.hasId("Patient/1"))
                .count(20000)
                .returnBundle(Bundle.class)
                .execute();

        Date epoch = new Date(0);

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() instanceof Observation) {
                Observation o = (Observation) entry.getResource();
                if (o.getEffective() instanceof DateTimeType
                        && ((DateTimeType) o.getEffective()).getValue().before(epoch)) {
                    System.out.println(o.getId() + " " + o.getEffective());
                    fhirClient.delete().resourceById(o.getIdElement()).execute();
                }
            }
        }
    }
}
