package org.apache.seatunnel.web.api.lake.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.seatunnel.web.api.lake.LakeJdbcDriverLoader;
import org.apache.seatunnel.web.dao.entity.LakeJdbcDriver;
import org.apache.seatunnel.web.dao.repository.LakeJdbcDriverDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.io.InputStream;

/** Reads logical JDBC driver registrations from the local Web database. */
@Component
public final class LakeJdbcDriverRegistry {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern MD5 = Pattern.compile("[0-9a-fA-F]{32}");

    private final LakeJdbcDriverDao driverDao;
    private final Map<LakeJdbcAdapterType, DriverRegistration> staticRegistrations;

    @Autowired
    public LakeJdbcDriverRegistry(LakeJdbcDriverDao driverDao) {
        this.driverDao = driverDao;
        this.staticRegistrations = Map.of();
    }

    /** Compatibility constructor for old embedders/tests; no longer used by Spring. */
    public LakeJdbcDriverRegistry(org.apache.seatunnel.web.api.lake.LakeProperties.JdbcCatalog configuration) {
        this.driverDao = null;
        org.apache.seatunnel.web.api.lake.LakeProperties.JdbcCatalog config = configuration == null
                ? new org.apache.seatunnel.web.api.lake.LakeProperties.JdbcCatalog() : configuration;
        EnumMap<LakeJdbcAdapterType, DriverRegistration> values = new EnumMap<>(LakeJdbcAdapterType.class);
        values.put(LakeJdbcAdapterType.MYSQL, registration(LakeJdbcAdapterType.MYSQL, config.getMysql(), config.getRegistryRevision()));
        values.put(LakeJdbcAdapterType.POSTGRESQL, registration(LakeJdbcAdapterType.POSTGRESQL, config.getPostgresql(), config.getRegistryRevision()));
        values.put(LakeJdbcAdapterType.ORACLE, registration(LakeJdbcAdapterType.ORACLE, config.getOracle(), config.getRegistryRevision()));
        this.staticRegistrations = Map.copyOf(values);
    }

    /** Compatibility overload for callers that still pass the old properties object. */
    @Deprecated
    public LakeJdbcDriverRegistry(org.apache.seatunnel.web.api.lake.LakeProperties properties) {
        this(properties == null ? null : properties.getJdbcCatalog());
    }

    public LakeJdbcDriverRegistry() {
        this((org.apache.seatunnel.web.api.lake.LakeProperties.JdbcCatalog) null);
    }

    public String registryRevision() {
        if (driverDao == null) {
            return staticRegistrations.values().stream()
                    .map(DriverRegistration::registryRevision)
                    .filter(StringUtils::isNotBlank)
                    .findFirst().orElse(null);
        }
        return "db";
    }

    public Optional<DriverRegistration> find(LakeJdbcAdapterType adapter) {
        if (adapter == null) {
            return Optional.empty();
        }
        if (driverDao == null) {
            return Optional.ofNullable(staticRegistrations.get(adapter));
        }
        LakeJdbcDriver driver = driverDao.queryByAdapter(adapter.name());
        return Optional.ofNullable(driver).map(item -> registration(adapter, item));
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

    public DriverStatus status(LakeJdbcAdapterType adapter) {
        DriverRegistration registration = find(adapter).orElse(null);
        List<String> reasons = new ArrayList<>();
        if (registration == null || !registration.enabled()) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CONFIG_MISSING);
            return new DriverStatus(false, false, reasons, registration);
        }
        if (StringUtils.isBlank(registration.url()) && StringUtils.isBlank(registration.driverLocation())) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CONFIG_MISSING);
        }
        if (StringUtils.isBlank(registration.driverClass())) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CONFIG_MISSING);
        }
        if (StringUtils.isBlank(registration.checksum())) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CHECKSUM_MISSING);
        } else if (!SHA256.matcher(registration.checksum()).matches()) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CHECKSUM_INVALID);
        } else if (StringUtils.isBlank(registration.url())
                && StringUtils.isNotBlank(registration.driverLocation())
                && !checksumMatchesLocalArtifact(registration)) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CHECKSUM_INVALID);
        }
        if (StringUtils.isNotBlank(registration.dorisMd5())
                && !MD5.matcher(registration.dorisMd5()).matches()) {
            reasons.add(LakeCatalogCapabilityReason.DORIS_DRIVER_MD5_INVALID);
        }
        boolean configured = reasons.isEmpty();
        boolean available = configured && registration.verified();
        if (configured && !available) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_NOT_VERIFIED);
        }
        return new DriverStatus(configured, available, reasons, registration);
    }

    private static boolean checksumMatchesLocalArtifact(DriverRegistration registration) {
        try {
            java.nio.file.Path path = LakeJdbcDriverLoader.resolveLocalPath(registration.driverLocation());
            if (!Files.isRegularFile(path)) {
                return false;
            }
            try (InputStream input = Files.newInputStream(path)) {
                return registration.checksum().equalsIgnoreCase(DigestUtils.sha256Hex(input));
            }
        } catch (Exception exception) {
            return false;
        }
    }

    private static DriverRegistration registration(LakeJdbcAdapterType adapter,
                                                   org.apache.seatunnel.web.api.lake.LakeProperties.Driver driver,
                                                   String revision) {
        org.apache.seatunnel.web.api.lake.LakeProperties.Driver value = driver == null
                ? new org.apache.seatunnel.web.api.lake.LakeProperties.Driver() : driver;
        return new DriverRegistration(adapter, value.isEnabled(), value.getUrl(), value.getDriverClass(),
                value.getChecksum(), StringUtils.defaultIfBlank(revision, "config"), value.isVerified(),
                value.getDorisMd5(), null);
    }

    private static DriverRegistration registration(LakeJdbcAdapterType adapter, LakeJdbcDriver driver) {
        return new DriverRegistration(adapter, Boolean.TRUE.equals(driver.getEnabled()),
                null, driver.getDriverClass(), driver.getSha256(), "db", Boolean.TRUE.equals(driver.getVerified()),
                driver.getDorisMd5(), driver.getDriverLocation());
    }

    public record DriverRegistration(
            LakeJdbcAdapterType adapter,
            boolean enabled,
            String url,
            String driverClass,
            String checksum,
            String registryRevision,
            boolean verified,
            String dorisMd5,
            String driverLocation) {

        public DriverRegistration(LakeJdbcAdapterType adapter, boolean enabled, String url,
                                  String driverClass, String checksum, String registryRevision,
                                  boolean verified) {
            this(adapter, enabled, url, driverClass, checksum, registryRevision, verified, null, null);
        }

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
        public String dorisMd5() {
            return dorisMd5;
        }

        @Override
        @JsonIgnore
        public String registryRevision() {
            return registryRevision;
        }

        @Override
        @JsonIgnore
        public String driverLocation() {
            return driverLocation;
        }
    }

    public record DriverStatus(boolean configured, boolean available,
                               List<String> reasonCodes, DriverRegistration registration) {
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
