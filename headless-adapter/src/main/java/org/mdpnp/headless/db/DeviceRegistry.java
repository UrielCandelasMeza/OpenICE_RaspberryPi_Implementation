package org.mdpnp.headless.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);
    private final ConnectionPool pool;
    private final ConcurrentMap<String, DeviceInfo> cache = new ConcurrentHashMap<>();

    private static final String UPSERT_SQL =
            "INSERT INTO devices (device_id, manufacturer, model, serial_number, connection_type, build_info, operating_system) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (device_id) DO UPDATE SET " +
            "  manufacturer = EXCLUDED.manufacturer, " +
            "  model = EXCLUDED.model, " +
            "  serial_number = COALESCE(NULLIF(EXCLUDED.serial_number, ''), devices.serial_number), " +
            "  build_info = COALESCE(NULLIF(EXCLUDED.build_info, ''), devices.build_info), " +
            "  operating_system = COALESCE(NULLIF(EXCLUDED.operating_system, ''), devices.operating_system)";

    public DeviceRegistry(ConnectionPool pool) {
        this.pool = pool;
    }

    public void registerFromIdentity(String deviceId, String manufacturer, String model,
                                     String serialNumber, String buildInfo, String os) {
        DeviceInfo info = new DeviceInfo(deviceId, manufacturer, model, serialNumber, "unknown", buildInfo, os);
        cache.put(deviceId, info);
        upsert(info);
    }

    public void setConnectionType(String deviceId, String connectionType) {
        DeviceInfo existing = cache.get(deviceId);
        if (existing == null) {
            DeviceInfo info = new DeviceInfo(deviceId, "", "", "", connectionType, "", "");
            cache.put(deviceId, info);
            upsert(info);
        } else if (!connectionType.equals(existing.connectionType)) {
            DeviceInfo updated = new DeviceInfo(existing.deviceId, existing.manufacturer, existing.model,
                    existing.serialNumber, connectionType, existing.buildInfo, existing.operatingSystem);
            cache.put(deviceId, updated);
            upsert(updated);
        }
    }

    public boolean isRegistered(String deviceId) {
        return cache.containsKey(deviceId);
    }

    private void upsert(DeviceInfo info) {
        if (!pool.isAvailable()) return;
        try (Connection c = pool.getConnection();
             PreparedStatement st = c.prepareStatement(UPSERT_SQL)) {
            st.setString(1, info.deviceId);
            st.setString(2, info.manufacturer);
            st.setString(3, info.model);
            st.setString(4, info.serialNumber);
            st.setString(5, info.connectionType);
            st.setString(6, info.buildInfo);
            st.setString(7, info.operatingSystem);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to upsert device {}: {}", info.deviceId, e.getMessage());
        }
    }

    public static class DeviceInfo {
        public final String deviceId;
        public final String manufacturer;
        public final String model;
        public final String serialNumber;
        public final String connectionType;
        public final String buildInfo;
        public final String operatingSystem;

        public DeviceInfo(String deviceId, String manufacturer, String model,
                          String serialNumber, String connectionType,
                          String buildInfo, String operatingSystem) {
            this.deviceId = deviceId;
            this.manufacturer = manufacturer;
            this.model = model;
            this.serialNumber = serialNumber;
            this.connectionType = connectionType;
            this.buildInfo = buildInfo;
            this.operatingSystem = operatingSystem;
        }
    }
}
