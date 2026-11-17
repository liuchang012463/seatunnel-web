package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LakeDorisConnectionParamsTest {

    @Test
    void removesOnlyTheDefaultDatabaseWhilePreservingHostsAndDriverOptions() {
        Map<String, String> cases = Map.of(
                "jdbc:mysql://fe:9030/ods", "jdbc:mysql://fe:9030/",
                "jdbc:mysql://fe:9030/ods?useSSL=false", "jdbc:mysql://fe:9030/?useSSL=false",
                "jdbc:mysql://fe:9030?useSSL=false", "jdbc:mysql://fe:9030/?useSSL=false",
                "jdbc:mysql://fe:9030", "jdbc:mysql://fe:9030/",
                "jdbc:mysql://fe:9030/?path=/tmp/driver", "jdbc:mysql://fe:9030/?path=/tmp/driver",
                "jdbc:mysql:loadbalance://fe1:9030,fe2:9030/ods?useSSL=false",
                "jdbc:mysql:loadbalance://fe1:9030,fe2:9030/?useSSL=false");
        cases.forEach((url, expected) -> {
            BaseConnectionParam param = new BaseConnectionParam() { };
            param.setUrl(url);
            param.setDatabase("ods");
            param.setUser("lake");
            param.setPassword("test-password");

            LakeDorisConnectionParams.withoutDefaultDatabase(param);

            assertEquals(expected, param.getUrl());
            assertEquals("", param.getDatabase());
            assertEquals("lake", param.getUser());
            assertEquals("test-password", param.getPassword());
        });
    }
}
