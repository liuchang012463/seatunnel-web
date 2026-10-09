package org.apache.seatunnel.web.engine.client.driver;

import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineDriverJarPublisherTest {

    private static final Long CLIENT_ID = 1L;

    private final SeaTunnelRestClient restClient = mock(SeaTunnelRestClient.class);

    private final EngineDriverJarPublisher publisher = new EngineDriverJarPublisher(restClient);

    @Test
    void uploadsLocalJarAndUsesTheEnginePath(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("Vastbase.jar");
        Files.write(jar, "jar-bytes".getBytes(StandardCharsets.UTF_8));
        when(restClient.uploadDriverJar(eq(CLIENT_ID), any(), eq("Vastbase.jar")))
                .thenReturn("/opt/seatunnel/driver-jars/abc-Vastbase.jar");

        String published =
                publisher.publish(
                        CLIENT_ID,
                        "source {\n"
                                + "  Jdbc {\n"
                                + "    driver = \"org.postgresql.Driver\"\n"
                                + "    driver_location = \""
                                + jar
                                + "\"\n"
                                + "  }\n"
                                + "}\n");

        assertTrue(published.contains("driver_location = \"/opt/seatunnel/driver-jars/abc-Vastbase.jar\""));
        assertFalse(published.contains(jar.toString()));
    }

    @Test
    void keepsValuesThatAreNotLocalFiles(@TempDir Path dir) {
        String enginePath = "/opt/seatunnel/driver-jars/abc-Vastbase.jar";
        String config = "sink { Jdbc { driver_location = \"" + enginePath + "\" } }";

        assertEquals(config, publisher.publish(CLIENT_ID, config));
        verify(restClient, never()).uploadDriverJar(any(), any(), any());
    }

    @Test
    void uploadsEveryJarOfAMultiJarValue(@TempDir Path dir) throws Exception {
        Path first = dir.resolve("a.jar");
        Path second = dir.resolve("b.jar");
        Files.write(first, "a".getBytes(StandardCharsets.UTF_8));
        Files.write(second, "b".getBytes(StandardCharsets.UTF_8));
        when(restClient.uploadDriverJar(eq(CLIENT_ID), any(), eq("a.jar"))).thenReturn("/engine/a.jar");
        when(restClient.uploadDriverJar(eq(CLIENT_ID), any(), eq("b.jar"))).thenReturn("/engine/b.jar");

        String published =
                publisher.publish(
                        CLIENT_ID,
                        "source { Jdbc { driver_location = \"" + first + ";" + second + "\" } }");

        assertTrue(published.contains("driver_location = \"/engine/a.jar;/engine/b.jar\""));
    }

    @Test
    void leavesConfigWithoutDriverLocationUntouched() {
        String config = "source { Jdbc { url = \"jdbc:mysql://localhost:3306/demo\" } }";

        assertEquals(config, publisher.publish(CLIENT_ID, config));
        verify(restClient, never()).uploadDriverJar(any(), any(), any());
    }

    @Test
    void publishesAnUnquotedDriverLocationOfAScriptJob(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("Vastbase.jar");
        Files.write(jar, "jar-bytes".getBytes(StandardCharsets.UTF_8));
        when(restClient.uploadDriverJar(eq(CLIENT_ID), any(), eq("Vastbase.jar")))
                .thenReturn("/opt/seatunnel/driver-jars/abc-Vastbase.jar");

        String published =
                publisher.publish(
                        CLIENT_ID, "source { Jdbc { driver_location = " + jar + " } }");

        assertTrue(
                published.contains(
                        "driver_location = /opt/seatunnel/driver-jars/abc-Vastbase.jar"));
    }

    @Test
    void publishesAnUnquotedMultiJarDriverLocation(@TempDir Path dir) throws Exception {
        Path first = dir.resolve("a.jar");
        Path second = dir.resolve("b.jar");
        Files.write(first, "a".getBytes(StandardCharsets.UTF_8));
        Files.write(second, "b".getBytes(StandardCharsets.UTF_8));
        when(restClient.uploadDriverJar(eq(CLIENT_ID), any(), eq("a.jar"))).thenReturn("/engine/a.jar");
        when(restClient.uploadDriverJar(eq(CLIENT_ID), any(), eq("b.jar"))).thenReturn("/engine/b.jar");

        String published =
                publisher.publish(
                        CLIENT_ID,
                        "source { Jdbc { driver_location = " + first + ";" + second + " } }");

        assertTrue(published.contains("driver_location = /engine/a.jar;/engine/b.jar"));
    }
}
