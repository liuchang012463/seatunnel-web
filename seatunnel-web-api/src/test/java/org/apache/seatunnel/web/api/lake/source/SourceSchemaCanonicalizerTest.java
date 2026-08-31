package org.apache.seatunnel.web.api.lake.source;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableConstraint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceSchemaCanonicalizerTest {

    @Test
    void structuralHashIgnoresGovernanceAndProfileFieldsAndCollectionOrder() {
        OpenMetadataTable table = table();
        String first = SourceSchemaCanonicalizer.canonicalHash(table);

        table.setDescription("a different description");
        table.setTags(List.of("secret-tag"));
        table.setDomains(List.of("domain"));
        table.setProfile(new org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableProfile());
        table.setColumns(List.of(table.getColumns().get(1), table.getColumns().get(0)));
        table.setTableConstraints(List.of(table.getTableConstraints().get(1), table.getTableConstraints().get(0)));

        assertEquals(first, SourceSchemaCanonicalizer.canonicalHash(table));
        assertTrue(SourceSchemaCanonicalizer.snapshot(table).snapshotJson().contains("omEntityId"));
    }

    @Test
    void structuralHashChangesWhenTypeOrNullabilityChanges() {
        OpenMetadataTable table = table();
        String first = SourceSchemaCanonicalizer.canonicalHash(table);
        table.getColumns().get(1).setDataType("BIGINT");
        assertNotEquals(first, SourceSchemaCanonicalizer.canonicalHash(table));
    }

    private static OpenMetadataTable table() {
        OpenMetadataTable table = new OpenMetadataTable();
        table.setId("table-1");
        table.setFullyQualifiedName("st_ds_7.orders.public.orders");
        table.setServiceFullyQualifiedName("st_ds_7");

        OpenMetadataColumn id = new OpenMetadataColumn();
        id.setName("id");
        id.setOrdinalPosition(1);
        id.setDataType("int");
        id.setDataTypeDisplay("INT");
        id.setConstraint("PRIMARY_KEY");
        OpenMetadataColumn payload = new OpenMetadataColumn();
        payload.setName("payload");
        payload.setOrdinalPosition(2);
        payload.setDataType("varchar");
        payload.setDataTypeDisplay("VARCHAR(20)");
        payload.setConstraint("NULL");
        table.setColumns(List.of(id, payload));

        OpenMetadataTableConstraint primary = new OpenMetadataTableConstraint();
        primary.setConstraintType("PRIMARY_KEY");
        primary.setColumns(List.of("id"));
        OpenMetadataTableConstraint unique = new OpenMetadataTableConstraint();
        unique.setConstraintType("UNIQUE");
        unique.setColumns(List.of("payload"));
        table.setTableConstraints(List.of(primary, unique));
        return table;
    }
}
