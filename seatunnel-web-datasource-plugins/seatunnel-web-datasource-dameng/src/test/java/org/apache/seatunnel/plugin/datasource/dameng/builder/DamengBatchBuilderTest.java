/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.plugin.datasource.dameng.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.dameng.param.DamengDataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamengBatchBuilderTest {

    private final DamengBatchBuilder builder = new DamengBatchBuilder();

    @Test
    void sinkHoconAddsDamengDialectByDefault() {
        Config config = builder.buildSinkHocon(context("targetTableName = sys_user\nautoCreateTable = true"));

        assertEquals("Dameng", config.getString("dialect"));
        // Without a live Dameng, falls back to uppercased form database for v$database match.
        assertEquals("TEST", config.getString("database"));
        assertEquals("SYSDBA.sys_user", config.getString("table"));
        assertTrue(config.getBoolean("generate_sink_sql"));
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST", config.getString("schema_save_mode"));
    }

    @Test
    void sinkHoconUppercasesAlreadyUpperDatabaseUnchanged() {
        Config config =
                builder.buildSinkHocon(
                        HoconBuildContext.builder()
                                .connectionConfig(
                                        ConfigFactory.parseString(
                                                "url = \"jdbc:dm://localhost:5236/DAMENG\"\n"
                                                        + "driver = \"dm.jdbc.driver.DmDriver\"\n"
                                                        + "user = test\n"
                                                        + "password = test\n"
                                                        + "database = DAMENG\n"
                                                        + "schemaName = SYSDBA"))
                                .nodeConfig(
                                        ConfigFactory.parseString(
                                                "targetTableName = sys_user\nautoCreateTable = true"))
                                .build());

        assertEquals("Dameng", config.getString("dialect"));
        assertEquals("DAMENG", config.getString("database"));
    }

    @Test
    void sinkHoconKeepsExplicitDialectFromExtraParams() {
        Config config =
                builder.buildSinkHocon(
                        context(
                                "targetTableName = sys_user\n"
                                        + "autoCreateTable = true\n"
                                        + "extraParams = [{ key = \"dialect\", value = \"KingBase\" }]"));

        assertEquals("KingBase", config.getString("dialect"));
    }

    @Test
    void sinkHoconUsesSelectedOwnerForSingleTable() {
        Config config = builder.buildSinkHocon(
                context(
                        "database = DAMENG\n"
                                + "schemaName = SYSDBA",
                        "targetTableName = T_JSS\n"
                                + "schemaName = XXTX\n"
                                + "autoCreateTable = false"));

        assertEquals("DAMENG", config.getString("database"));
        assertEquals("XXTX.T_JSS", config.getString("table"));
        assertEquals("ERROR_WHEN_SCHEMA_NOT_EXIST", config.getString("schema_save_mode"));
    }

    @Test
    void sinkHoconNormalizesThreePartPathToOwnerAndTable() {
        Config config = builder.buildSinkHocon(
                context(
                        "database = DAMENG\n"
                                + "schemaName = XXTX",
                        "table = DAMENG.XXTX.T_JSS\n"
                                + "autoCreateTable = false"));

        assertEquals("XXTX.T_JSS", config.getString("table"));
    }

    @Test
    void sourceHoconUsesDamengDatabaseAndSelectedOwnerInTablePath() {
        Config config = builder.buildSourceHocon(
                context(
                        "database = DAMENG\n"
                                + "schemaName = SYSDBA",
                        "table = T_JSS\n"
                                + "schemaName = XXTX"));

        assertFalse(config.hasPath("database"));
        assertEquals("DAMENG.XXTX.T_JSS", config.getString("table_path"));
    }

    @Test
    void sourceHoconTreatsTwoPartPathAsOwnerAndTable() {
        Config config = builder.buildSourceHocon(
                context(
                        "database = DAMENG\n"
                                + "schemaName = SYSDBA",
                        "table_path = XXTX.T_JSS"));

        assertEquals("DAMENG.XXTX.T_JSS", config.getString("table_path"));
    }

    @Test
    void sourceHoconRejectsStaleThreePartDatabasePath() {
        IllegalArgumentException error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> builder.buildSourceHocon(
                                context(
                                        "database = DAMENG\n"
                                                + "schemaName = SYSDBA",
                                        "table_path = XXTX.SYSDBA.T_JSS")));

        assertTrue(error.getMessage().contains("does not match"));
    }

    @Test
    void damengFormExplainsDatabaseAndOwnerSemantics() {
        List<FormFieldConfig> fields = new DamengDataSourceProcessor().generateFormFields();

        FormFieldConfig database = fields.stream()
                .filter(field -> "database".equals(field.getKey()))
                .findFirst()
                .orElseThrow();
        FormFieldConfig schema = fields.stream()
                .filter(field -> "schemaName".equals(field.getKey()))
                .findFirst()
                .orElseThrow();

        assertEquals("数据库实例", database.getLabel());
        assertEquals("模式/Owner", schema.getLabel());
    }

    @Test
    void usesBundledDamengDriverJarByDefault() {
        List<FormFieldConfig> fields = new DamengDataSourceProcessor().generateFormFields();

        assertEquals(
                "DmJdbcDriver18-8.1.2.141.jar",
                fields.stream()
                        .filter(field -> "driverLocation".equals(field.getKey()))
                        .findFirst()
                        .orElseThrow()
                        .getDefaultValue());
    }

    private HoconBuildContext context(String nodeConfig) {
        return context(
                "database = test\n"
                        + "schemaName = SYSDBA",
                nodeConfig);
    }

    private HoconBuildContext context(String connectionConfig, String nodeConfig) {
        return HoconBuildContext.builder()
                .connectionConfig(
                        ConfigFactory.parseString(
                                "url = \"jdbc:dm://localhost:5236/test\"\n"
                                        + "driver = \"dm.jdbc.driver.DmDriver\"\n"
                                        + "user = test\n"
                                        + "password = test\n"
                                        + connectionConfig))
                .nodeConfig(ConfigFactory.parseString(nodeConfig))
                .build();
    }
}
