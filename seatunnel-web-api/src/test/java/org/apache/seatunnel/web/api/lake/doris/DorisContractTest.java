package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.contract.DorisTypeBase;
import org.apache.seatunnel.web.api.lake.contract.TargetColumn;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetContractCanonicalizer;
import org.apache.seatunnel.web.api.lake.contract.TargetContractValidator;
import org.apache.seatunnel.web.api.lake.contract.TargetDistribution;
import org.apache.seatunnel.web.api.lake.contract.TargetPartition;
import org.apache.seatunnel.web.api.lake.contract.TargetType;
import org.apache.seatunnel.web.common.enums.LakeTableModel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DorisContractTest {

    @Test
    void readsThe4_1_2ShowCreateFixtureAndNormalisesDefaults() throws IOException {
        String ddl = readResource("/lake/doris/auto_range_show_create_4_1_2.sql");
        DorisContractReader reader = new DorisContractReader();

        TargetContract contract = reader.read(ddl);

        assertEquals(2, contract.getVersion());
        assertEquals(LakeTableModel.DUPLICATE, contract.getTableModel());
        assertEquals(List.of("id", "event_time"), contract.getKeyColumns());
        assertEquals("STRING", contract.getColumns().get(2).getTargetType().getBase().name());
        assertEquals(TargetPartition.autoRange("event_time", "DAY"), contract.getPartition());
        assertEquals(TargetDistribution.hash(List.of("id")), contract.getDistribution());
        assertEquals("5", reader.readProperties(ddl).get("partition.retention_count"));
        assertFalse(reader.readTableProperties(ddl).containsKey("_auto_bucket"));
    }

    @Test
    void builderCoversDuplicateUniqueAutoRangeAndAutoBucket() {
        TargetContract duplicate = contract(LakeTableModel.DUPLICATE,
                TargetPartition.autoRange("event_time", "day"), TargetDistribution.random());
        String duplicateDdl = new DorisDdlBuilder().build("lake_db", "events", duplicate,
                Map.of("partition.retention_count", "7", "_auto_bucket", "true"));
        assertTrue(duplicateDdl.contains("DUPLICATE KEY(`id`)"));
        assertTrue(duplicateDdl.contains("AUTO PARTITION BY RANGE (date_trunc(`event_time`, 'day'))"));
        assertTrue(duplicateDdl.contains("DISTRIBUTED BY RANDOM BUCKETS AUTO"));
        assertTrue(duplicateDdl.contains("\"partition.retention_count\" = \"7\""));

        TargetContract unique = contract(LakeTableModel.UNIQUE, TargetPartition.disabled(),
                TargetDistribution.hash(List.of("id")));
        String uniqueDdl = new DorisDdlBuilder().buildCreateTable("lake_db", "events_unique", unique);
        assertTrue(uniqueDdl.contains("UNIQUE KEY(`id`)"));
        assertTrue(uniqueDdl.contains("DISTRIBUTED BY HASH(`id`) BUCKETS AUTO"));
    }

    @Test
    void canonicalHashIgnoresTextAndDatetimeZeroAliases() {
        TargetContract stringContract = contractWithTypes(DorisTypeBase.STRING, new TargetType(DorisTypeBase.DATETIME));
        TargetContract aliasContract = contractWithTypes(DorisTypeBase.TEXT,
                new TargetType(DorisTypeBase.DATETIME, null, null, 0));

        assertEquals(TargetContractCanonicalizer.canonicalJson(stringContract),
                TargetContractCanonicalizer.canonicalJson(aliasContract));
        assertEquals(TargetContractCanonicalizer.sha256(stringContract),
                TargetContractCanonicalizer.sha256(aliasContract));
    }

    @Test
    void validatorRejectsStringKeyAndUnsafeProperties() {
        TargetContract invalidKey = new TargetContract(LakeTableModel.DUPLICATE,
                List.of(new TargetColumn("id", 1, "id", new TargetType(DorisTypeBase.STRING),
                        false, true, 1)),
                List.of("id"), TargetPartition.disabled(), TargetDistribution.random());
        assertThrows(IllegalArgumentException.class, () -> TargetContractValidator.validate(invalidKey));
        assertThrows(IllegalArgumentException.class,
                () -> new DorisDdlBuilder().build("lake_db", "events", contract(
                        LakeTableModel.DUPLICATE, TargetPartition.disabled(), TargetDistribution.random()),
                        Map.of("unknown_property", "x")));

        TargetContract uniquePartitionOutsideKey = contract(LakeTableModel.UNIQUE,
                TargetPartition.autoRange("event_time", "DAY"), TargetDistribution.hash(List.of("id")));
        assertThrows(IllegalArgumentException.class,
                () -> TargetContractValidator.validate(uniquePartitionOutsideKey));
    }

    @Test
    void capabilityDecisionReturnsStableReasonsForEveryFailedCheck() {
        DorisCapability result = new DorisCapabilityResolver().resolve(
                new DorisCapabilityChecks(false, false, false, false, false, false));

        assertFalse(result.isPhysicalSupported());
        assertFalse(result.isLogicalSupported());
        assertEquals(List.of(
                DorisCapabilityReason.ADAPTER_MISSING,
                DorisCapabilityReason.DRIVER_CONFIG_MISSING,
                DorisCapabilityReason.DRIVER_CHECKSUM_MISSING,
                DorisCapabilityReason.SOURCE_CONFIG_INCOMPLETE,
                DorisCapabilityReason.LAKE_DORIS_UNREACHABLE,
                DorisCapabilityReason.SOURCE_NETWORK_UNREACHABLE), result.getReasons());
    }

    @Test
    void capabilityIsEnabledOnlyWhenAllSixChecksPass() {
        DorisCapability result = DorisCapabilityResolver.evaluate(
                new DorisCapabilityChecks(true, true, true, true, true, true));
        assertTrue(result.isPhysicalSupported());
        assertTrue(result.isLogicalSupported());
        assertTrue(result.getReasons().isEmpty());
    }

    private static TargetContract contract(LakeTableModel model, TargetPartition partition,
                                            TargetDistribution distribution) {
        return new TargetContract(model, List.of(
                new TargetColumn("ID", 1, "id", TargetType.varchar(255), false, true, 1),
                new TargetColumn("EVENT_TIME", 2, "event_time", new TargetType(DorisTypeBase.DATETIME),
                        false, false, 2),
                new TargetColumn("PAYLOAD", 3, "payload", new TargetType(DorisTypeBase.STRING),
                        true, false, 3)), List.of("id"), partition, distribution);
    }

    private static TargetContract contractWithTypes(DorisTypeBase payloadType, TargetType eventType) {
        return new TargetContract(LakeTableModel.DUPLICATE, List.of(
                new TargetColumn("id", 1, "id", TargetType.varchar(20), false, true, 1),
                new TargetColumn("payload", 2, "payload", new TargetType(payloadType), true, false, 2),
                new TargetColumn("event_time", 3, "event_time", eventType, false, false, 3)),
                List.of("id"), TargetPartition.disabled(), TargetDistribution.random());
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream input = DorisContractTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing test resource " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
