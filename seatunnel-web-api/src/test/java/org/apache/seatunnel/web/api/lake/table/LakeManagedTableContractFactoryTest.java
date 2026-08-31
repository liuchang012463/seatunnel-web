package org.apache.seatunnel.web.api.lake.table;

import org.apache.seatunnel.web.api.lake.contract.DorisTypeBase;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.source.SourceColumnSnapshot;
import org.apache.seatunnel.web.api.lake.source.SourceConstraintSnapshot;
import org.apache.seatunnel.web.api.lake.source.SourceObjectSnapshot;
import org.apache.seatunnel.web.common.enums.LakeTableModel;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableColumnDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTablePreviewDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LakeManagedTableContractFactoryTest {

    private final LakeManagedTableContractFactory factory = new LakeManagedTableContractFactory();

    @Test
    void keyDefaultsToVarcharAndValueDefaultsToStringFromFreshSource() {
        LakeManagedTablePreviewDTO request = new LakeManagedTablePreviewDTO();
        request.setTableModel(LakeTableModel.DUPLICATE);

        TargetContract contract = factory.build(source(), request);

        assertEquals(DorisTypeBase.VARCHAR, contract.getColumns().get(0).getTargetType().getBase());
        assertEquals(255, contract.getColumns().get(0).getTargetType().getLength());
        assertEquals(DorisTypeBase.STRING, contract.getColumns().get(1).getTargetType().getBase());
        assertEquals(List.of("id"), contract.getKeyColumns());
        assertEquals("id", contract.getColumns().get(0).getTargetName());
        assertEquals("payload", contract.getColumns().get(1).getTargetName());
        assertEquals("STRING", factory.fieldMappings(contract).get(1).getTargetType());
    }

    @Test
    void clientCannotUseStringAsKey() {
        LakeManagedTablePreviewDTO request = new LakeManagedTablePreviewDTO();
        LakeManagedTableColumnDTO id = new LakeManagedTableColumnDTO();
        id.setSourceField("ID");
        id.setTargetType("STRING");
        id.setKey(true);
        request.setColumns(List.of(id));

        assertThrows(IllegalArgumentException.class, () -> factory.build(source(), request));
    }

    @Test
    void targetNameAndTypeAreStructuredAndSourceFactsRemainAuthoritative() {
        LakeManagedTablePreviewDTO request = new LakeManagedTablePreviewDTO();
        LakeManagedTableColumnDTO id = new LakeManagedTableColumnDTO();
        id.setSourceField("ID");
        id.setTargetField("order_id");
        id.setTargetType("BIGINT");
        request.setColumns(List.of(id));

        TargetContract contract = factory.build(source(), request);

        assertEquals("order_id", contract.getColumns().get(0).getTargetName());
        assertEquals(DorisTypeBase.BIGINT, contract.getColumns().get(0).getTargetType().getBase());
        assertEquals(false, contract.getColumns().get(0).getNullable());
    }

    private static SourceObjectSnapshot source() {
        return new SourceObjectSnapshot(
                "om-table", "svc.db.public.orders",
                List.of(
                        new SourceColumnSnapshot("ID", 1, "BIGINT", "BIGINT", null, 19L, 0L,
                                "PRIMARY_KEY", false),
                        new SourceColumnSnapshot("PAYLOAD", 2, "JSON", "JSON", null, null, null,
                                null, true)),
                List.of(new SourceConstraintSnapshot("PRIMARY_KEY", List.of("ID"), List.of(), null)),
                "source-hash", "snapshot");
    }
}
