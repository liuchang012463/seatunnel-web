package org.apache.seatunnel.plugin.datasource.sftp.param;

import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpDataSourceProcessorTest {

    @Test
    void generatesConnectionOnlyForm() {
        List<FormFieldConfig> fields = new SftpDataSourceProcessor().generateFormFields();
        List<String> keys = fields.stream().map(FormFieldConfig::getKey).toList();

        assertEquals(
                List.of(
                        "host",
                        "port",
                        "user",
                        "password",
                        "strictHostKeyChecking",
                        "knownHostsPath"),
                keys);
        assertFalse(keys.contains("database"));
        assertFalse(keys.contains("driverLocation"));
        assertTrue(fields.stream()
                .filter(field -> "port".equals(field.getKey()))
                .allMatch(field -> "22".equals(field.getDefaultValue())));
    }
}
