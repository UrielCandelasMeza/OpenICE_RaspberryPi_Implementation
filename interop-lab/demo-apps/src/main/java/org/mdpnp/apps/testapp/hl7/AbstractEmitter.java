package org.mdpnp.apps.testapp.hl7;

import ice.MDSConnectivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
// import java.util.concurrent.TimeUnit;

import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.util.Callback;

import org.mdpnp.apps.device.OnListChange;
import org.mdpnp.apps.fxbeans.ElementObserver;
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

import com.rti.dds.subscription.Subscriber;

/**
 * Clase base abstracta para emisores de datos clínicos.
 * Contiene la infraestructura común para la captura de datos DDS, observador de
 * validaciones,
 * mapa de conectividad de dispositivos (UDI a MRN), planificador periódico y
 * listeners de UI.
 */
public abstract class AbstractEmitter implements MDSListener, Runnable {

    protected static final Logger log = LoggerFactory.getLogger(AbstractEmitter.class);

    protected static final String METRIC_PREFIX = "MDC_";
    protected static final String PTID_SYSTEM = "urn:oid:2.16.840.1.113883.3.1974";

    protected final MDSHandler mdsHandler;
    protected final ScheduledExecutorService executor;
    protected final ValidationOracle validationOracle;
    protected ElementObserver<Validation> validationObserver;

    protected final Set<Validation> recentUpdates = Collections.synchronizedSet(new HashSet<>());
    protected final Map<String, String> deviceUdiToPatientMRN = Collections.synchronizedMap(new HashMap<>());
    protected volatile String selectedPatientMRN;

    protected final ListenerList<LineEmitterListener> listeners = new ListenerList<>(LineEmitterListener.class);
    protected final ListenerList<StartStopListener> ssListeners = new ListenerList<>(StartStopListener.class);

    protected ScheduledFuture<?> emit;

    public AbstractEmitter(final Subscriber subscriber, final EventLoop eventLoop,
            final ValidationOracle validationOracle) {
        executor = Executors.newSingleThreadScheduledExecutor();
        this.validationOracle = validationOracle;

        if (validationOracle != null) {
            validationObserver = attachValidationObserver(validationOracle);
            validationOracle.forEach((t) -> add(t));
        }

        if (subscriber != null && eventLoop != null) {
            this.mdsHandler = new MDSHandler(eventLoop, subscriber.get_participant());
            mdsHandler.addConnectivityListener(this);
            mdsHandler.start();
        } else {
            this.mdsHandler = null;
        }
    }

    protected ElementObserver<Validation> attachValidationObserver(ValidationOracle validationOracle) {
        ElementObserver<Validation> observer = new ElementObserver<>(
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

    protected void add(Validation validation) {
        if (validation.getNumeric().getMetric_id().startsWith(METRIC_PREFIX)) {
            if (validationObserver != null) {
                validationObserver.attachListener(validation);
            }
        }
    }

    protected void remove(Validation validation) {
        if (validation.getNumeric().getMetric_id().startsWith(METRIC_PREFIX)) {
            if (validationObserver != null) {
                validationObserver.detachListener(validation);
            }
        }
    }

    public void setSelectedPatientMRN(String mrn) {
        this.selectedPatientMRN = mrn;
        log.info("MRN seleccionado en {}: {}", getClass().getSimpleName(), mrn);
    }

    public String getSelectedPatientMRN() {
        return selectedPatientMRN;
    }

    public Set<Validation> getRecentUpdates() {
        return recentUpdates;
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

    protected void fireLine(String line) {
        listeners.fire(l -> l.newLine(line));
    }

    protected void fireStarted() {
        ssListeners.fire(l -> l.started());
    }

    protected void fireStopped() {
        ssListeners.fire(l -> l.stopped());
    }

    @Override
    public void handleConnectivityChange(MDSEvent evt) {
        ice.MDSConnectivity c = (MDSConnectivity) evt.getSource();
        String mrnPartition = PartitionAssignmentController.findMRNPartition(c.partition);
        if (mrnPartition != null) {
            log.info("udi " + c.unique_device_identifier + " is " + mrnPartition);
            deviceUdiToPatientMRN.put(c.unique_device_identifier, PartitionAssignmentController.toMRN(mrnPartition));
        } else {
            log.debug("udi {} partitions={} sin MRN", c.unique_device_identifier, c.partition);
        }
    }

    public abstract void start(String host, int port, long interval);

    public void stop() {
        if (null != emit) {
            emit.cancel(true);
            emit = null;
        }
        fireStopped();
    }

    public void shutdown() {
        stop();
        executor.shutdownNow();
        if (mdsHandler != null) {
            mdsHandler.shutdown();
        }
    }

    protected abstract void emitData(List<Validation> pendingUpdates) throws Exception;

    @Override
    public void run() {
        try {
            List<Validation> pending;
            synchronized (recentUpdates) {
                pending = new ArrayList<>(recentUpdates);
                recentUpdates.clear();
            }
            if (!pending.isEmpty()) {
                emitData(pending);
            }
        } catch (InterruptedException e) {
            log.error("Emisión interrumpida en " + getClass().getSimpleName(), e);
        } catch (Throwable t) {
            log.error("Error al emitir datos en " + getClass().getSimpleName(), t);
            stop();
        }
    }
}
