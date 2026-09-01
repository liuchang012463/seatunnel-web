package org.apache.seatunnel.web.api.lake.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Server-owned logical JDBC driver inventory.
 *
 * <p>The registry deliberately has no method that accepts driver details from
 * a request.  A catalog request can only refer to an adapter; URL, class and
 * checksum are read from {@link LakeProperties} and copied into the desired
 * spec by a trusted service.</p>
 */
@Component
public final class LakeJdbcDriverRegistry {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final String registryRevision;
    private final Map<LakeJdbcAdapterType, DriverRegistration> registrations;

    @Autowired
    public LakeJdbcDriverRegistry(LakeProperties properties) {
        this(properties == null ? null : properties.getJdbcCatalog());
    }

    /** Visible for unit tests and non-Spring bootstrap code. */
    public LakeJdbcDriverRegistry(LakeProperties.JdbcCatalog configuration) {
        LakeProperties.JdbcCatalog config = configuration == null
                ? new LakeProperties.JdbcCatalog() : configuration;
        this.registryRevision = trimToNull(config.getRegistryRevision());
        EnumMap<LakeJdbcAdapterType, DriverRegistration> values =
                new EnumMap<>(LakeJdbcAdapterType.class);
        values.put(LakeJdbcAdapterType.MYSQL,
                registration(LakeJdbcAdapterType.MYSQL, config.getMysql()));
        values.put(LakeJdbcAdapterType.POSTGRESQL,
                registration(LakeJdbcAdapterType.POSTGRESQL, config.getPostgresql()));
        values.put(LakeJdbcAdapterType.ORACLE,
                registration(LakeJdbcAdapterType.ORACLE, config.getOracle()));
        this.registrations = Map.copyOf(values);
    }

    /** Empty, disabled registry for callers that do not have application config. */
    public LakeJdbcDriverRegistry() {
        this((LakeProperties.JdbcCatalog) null);
    }

    public String registryRevision() {
        return registryRevision;
    }

    public Optional<DriverRegistration> find(LakeJdbcAdapterType adapter) {
        return Optional.ofNullable(registrations.get(adapter));
    }

    public Optional<DriverRegistration> find(String adapter) {
        try {
            return find(LakeJdbcAdapterType.parse(adapter));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public DriverRegistration require(LakeJdbcAdapterType adapter) {
        return find(adapter).orElseThrow(() -> new IllegalArgumentException(
                "JDBC catalog driver is not registered"));
    }

    /** Returns configuration/availability reasons without exposing driver details. */
    public DriverStatus status(LakeJdbcAdapterType adapter) {
        DriverRegistration registration = registrations.get(adapter);
        List<String> reasons = new ArrayList<>();
        if (registration == null || !registration.enabled()) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CONFIG_MISSING);
            return new DriverStatus(false, false, reasons, registration);
        }
        if (StringUtils.isBlank(registration.url())
                || StringUtils.isBlank(registration.driverClass())) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CONFIG_MISSING);
        }
        if (StringUtils.isBlank(registration.checksum())) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CHECKSUM_MISSING);
        } else if (!SHA256.matcher(registration.checksum()).matches()) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CHECKSUM_INVALID);
        }
        if (StringUtils.isBlank(registryRevision)) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_REGISTRY_REVISION_MISSING);
        }
        boolean configured = reasons.isEmpty();
        boolean available = configured && registration.verified();
        if (configured && !available) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_NOT_VERIFIED);
        }
        return new DriverStatus(configured, available, reasons, registration);
    }

    private DriverRegistration registration(
            LakeJdbcAdapterType adapter,
            LakeProperties.Driver driver) {
        LakeProperties.Driver value = driver == null ? new LakeProperties.Driver() : driver;
        return new DriverRegistration(
                adapter,
                value.isEnabled(),
                trimToNull(value.getUrl()),
                trimToNull(value.getDriverClass()),
                trimToNull(value.getChecksum()),
                registryRevision,
                value.isVerified());
    }

    private static String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    /** Immutable server-owned registration copied from application config. */
    public record DriverRegistration(
            LakeJdbcAdapterType adapter,
            boolean enabled,
            String url,
            String driverClass,
            String checksum,
            String registryRevision,
            boolean verified) {

        /** Driver inventory details are server-internal, never a safe VO. */
        @Override
        @JsonIgnore
        public String url() {
            return url;
        }

        @Override
        @JsonIgnore
        public String driverClass() {
            return driverClass;
        }

        @Override
        @JsonIgnore
        public String checksum() {
            return checksum;
        }

        @Override
        @JsonIgnore
        public String registryRevision() {
            return registryRevision;
        }
    }

    /** Safe capability facts; it contains no URL/class/checksum values. */
    public record DriverStatus(
            boolean configured,
            boolean available,
            List<String> reasonCodes,
            DriverRegistration registration) {

        public DriverStatus {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }

        public boolean isConfigured() {
            return configured;
        }

        public boolean isAvailable() {
            return available;
        }

        public List<String> getReasons() {
            return reasonCodes;
        }

        @Override
        @JsonIgnore
        public DriverRegistration registration() {
            return registration;
        }
    }
}
