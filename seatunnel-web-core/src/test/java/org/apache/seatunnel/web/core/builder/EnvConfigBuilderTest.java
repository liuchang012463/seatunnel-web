package org.apache.seatunnel.web.core.builder;

import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.spi.bean.dto.config.BatchJobEnvConfig;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvConfigBuilderTest {

    @Test
    void shouldRenderReadLimitIntoEnvHocon() {
        EnvConfigBuilder builder = new EnvConfigBuilder(Collections.emptyList());
        BatchJobEnvConfig env = new BatchJobEnvConfig();
        env.setJobMode(JobMode.BATCH);
        env.setParallelism(2);
        env.setReadLimitBytesPerSecond(1048576L);
        env.setReadLimitRowsPerSecond(1000L);
        env.setPriority("MEDIUM");

        String hocon = builder.build(env);

        assertTrue(
                hocon.contains("read_limit"),
                "Expected read_limit section in env HOCON, got: " + hocon);
        assertTrue(
                hocon.contains("bytes_per_second"),
                "Expected bytes_per_second in env HOCON, got: " + hocon);
        assertTrue(hocon.contains("1048576"), "Expected 1048576 in env HOCON");
        assertTrue(
                hocon.contains("rows_per_second"),
                "Expected rows_per_second in env HOCON, got: " + hocon);
        assertTrue(hocon.contains("1000"), "Expected 1000 in env HOCON");
        assertFalse(
                hocon.contains("priority"),
                "priority must NOT be rendered into engine env HOCON, got: " + hocon);
    }

    @Test
    void shouldOmitReadLimitWhenNull() {
        EnvConfigBuilder builder = new EnvConfigBuilder(Collections.emptyList());
        BatchJobEnvConfig env = new BatchJobEnvConfig();
        env.setJobMode(JobMode.BATCH);
        env.setParallelism(1);

        String hocon = builder.build(env);
        assertFalse(
                hocon.contains("read_limit"),
                "Expected no read_limit key when fields are null, got: " + hocon);
    }
}