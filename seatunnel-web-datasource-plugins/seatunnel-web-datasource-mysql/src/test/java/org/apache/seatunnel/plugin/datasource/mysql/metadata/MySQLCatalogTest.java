package org.apache.seatunnel.plugin.datasource.mysql.metadata;

import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.mysql.param.MySQLConnectionParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MySQLCatalogTest {

    @Test
    void quotesMixedCaseReservedAndEmbeddedBacktickIdentifiers() {
        MySQLConnectionParam param = new MySQLConnectionParam();
        param.setDatabase("analytics");
        MySQLCatalog catalog = new MySQLCatalog(param, null);

        assertEquals("`analytics`.`Select`", catalog.buildTableReference(
                TablePath.of("analytics", null, "Select")));
        assertEquals("`analytics`.`Order``Details`", catalog.buildTableReference(
                TablePath.of("analytics", null, "Order`Details")));
    }
}
