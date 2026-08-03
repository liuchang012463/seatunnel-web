package org.apache.seatunnel.plugin.alarm.email;

import org.apache.seatunnel.plugin.alarm.api.AlarmInfo;
import org.apache.seatunnel.plugin.alarm.api.AlarmData;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailAlarmChannelTest {

    @Test
    void exposesStableEmailChannelAndDynamicFields() {
        EmailAlarmChannelFactory factory = new EmailAlarmChannelFactory();

        assertEquals("EMAIL", factory.name());
        assertEquals("邮件 (Email)", factory.displayName());
        assertTrue(factory.params().stream().anyMatch(field -> "host".equals(field.getKey())));
        assertTrue(factory.params().stream().anyMatch(field -> "to".equals(field.getKey())));
        assertTrue(factory.params().stream().anyMatch(field -> "contentType".equals(field.getKey())));
    }

    @Test
    void rejectsIncompleteEmailConfigurationWithoutOpeningSmtpConnection() {
        AlarmInfo info = AlarmInfo.builder()
                .alarmParams(Map.of("host", "smtp.example.com"))
                .alarmData(AlarmData.builder().build())
                .build();

        var result = new EmailAlarmChannel().process(info);

        assertFalse(result.isSuccess());
        assertEquals("email recipient is not configured", result.getMessage());
    }
}
