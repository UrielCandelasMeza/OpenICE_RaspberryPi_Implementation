package org.mdpnp.devices.fhir;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;
import ice.Numeric;

import org.mdpnp.devices.DeviceClock;
import org.mdpnp.devices.connected.AbstractConnectedDevice;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rosetta.MDC_DIM_DIMLESS;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author mfeinberg
 */
public abstract class FhirDevice extends AbstractConnectedDevice {

    private static final Logger log = LoggerFactory.getLogger(FhirDevice.class);

    public FhirDevice(Subscriber subscriber, Publisher publisher, EventLoop eventLoop) {
        super(subscriber, publisher, eventLoop);
    }

    protected abstract Set<Observation> getObservations(DeviceClock.Reading t) throws Exception;

    protected abstract long getSampleRateMs();

    protected DeviceClock.Reading getDeviceClockReading() {
        DeviceClock clock = getClockProvider();
        return clock.instant();
    }

    public static Observation createObservation(String metricId, Number value, Date asOf) {

        Observation obs = new Observation();
        obs.setValue(new Quantity().setValue(value.doubleValue()).setUnit(MDC_DIM_DIMLESS.VALUE).setCode(metricId).setSystem("OpenICE"));
        DateTimeType dt = new DateTimeType(asOf, TemporalPrecisionEnum.SECOND);
        dt.setTimeZone(TimeZone.getTimeZone("UTC"));
        obs.setEffective(dt);
        obs.setStatus(Observation.ObservationStatus.PRELIMINARY);

        return obs;
    }


    private final class DataPublisher implements Runnable {

        public DataPublisher() {
        }

        @Override
        public void run() {

            DeviceClock.Reading t = getDeviceClockReading();

            Set<Observation> data;
            try {
                data = getObservations(t);
            } catch (Exception ex) {
                log.warn("Provider failed to returned invalid list of observations for " + t, ex);
                data = Collections.emptySet();
            }

            if (data == null) {
                log.warn("Provider returned invalid (null) list of observations for " + t);
                data = Collections.emptySet();
            }
            publishObservations(t, data);
        }
    }

    private void publishObservations(DeviceClock.Reading t, Set<Observation> data) {

        log.info("Publishing " + data.size() + " observations");
        for (Observation obs : data) {
            try {
                ObservationConverter.NumericObservation ice = observationConvertor.observationOnIce(obs);
                numericSample(ice.holder, (float)ice.value, ice.time);
            } catch (Exception ex) {
                log.error("Failed to convert/publish observation " + obs, ex);
            }
        }
    }

    ObservationConverter observationConvertor  = new ObservationConverter() {
        InstanceHolder<Numeric> getInstanceHolderForCode(String code) {
            return FhirDevice.this.getInstanceHolderForCode(code);
        }
    };

    static abstract class ObservationConverter {

        static class NumericObservation {

            public NumericObservation(InstanceHolder<Numeric> holder, double value, DeviceClock.Reading time) {
                this.holder = holder;
                this.value = value;
                this.time = time;
            }

            final InstanceHolder<Numeric> holder;
            final double value;
            final DeviceClock.Reading time;
        }

        NumericObservation observationOnIce(Observation obs) {

            Quantity qty = (Quantity) obs.getValue();
            double value = qty.getValue().doubleValue();

            String code = qty.getCode();
            InstanceHolder<Numeric> holder = getInstanceHolderForCode(code);

            log.info("Converting observation:" + code + "=" + value);

            DateTimeType dt = (DateTimeType) obs.getEffective();
            Date d = dt.getValue();
            DeviceClock.Reading clockReading = new DeviceClock.ReadingImpl(d.getTime());

            return new NumericObservation(holder, value, clockReading);
        }

        abstract InstanceHolder<Numeric> getInstanceHolderForCode(String code);
    }

    private synchronized InstanceHolder<Numeric> getInstanceHolderForCode(String code) {

        if(holders.containsKey(code))
            return holders.get(code);

        InstanceHolder<Numeric> holder = createNumericInstance(code, "");
        holders.put(code, holder);
        return holder;
    }

    private Map<String, InstanceHolder<Numeric>> holders = new HashMap<>();

    private ScheduledFuture<?> task;

    public boolean connect(String address) {
        ScheduledExecutorService exec = getExecutor();
        connect(exec);
        return true;
    }

    public void connect(ScheduledExecutorService executor) {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        long updatePriod = getSampleRateMs();
        long now = System.currentTimeMillis();
        task = executor.scheduleAtFixedRate(new DataPublisher(), updatePriod - now % updatePriod, updatePriod, TimeUnit.MILLISECONDS);
    }

    public void disconnect() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }
}
