package org.apache.seatunnel.web.core.fileupload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuiltInMinioPropertiesTest {

    @Test
    void doesNotRepeatBucketNameInObjectPath() {
        BuiltInMinioProperties properties = new BuiltInMinioProperties();
        properties.setBucket("seatunnel-web-upload");
        properties.setRootPrefix("seatunnel-web-upload");

        assertEquals("", properties.getRootPrefix());
        assertEquals(
                "22922596990880/5d8522db37b9436382146ce30d5fd9f2",
                properties.objectKeyPrefix(22922596990880L, "5d8522db37b9436382146ce30d5fd9f2"));
        assertEquals(
                "/22922596990880/5d8522db37b9436382146ce30d5fd9f2",
                properties.objectPath(22922596990880L, "5d8522db37b9436382146ce30d5fd9f2"));
    }

    @Test
    void keepsAnExplicitDistinctRootPrefix() {
        BuiltInMinioProperties properties = new BuiltInMinioProperties();
        properties.setBucket("seatunnel-web-upload");
        properties.setRootPrefix("tenant-a");

        assertEquals("tenant-a", properties.getRootPrefix());
        assertEquals("tenant-a/12/session", properties.objectKeyPrefix(12L, "session"));
    }
}
