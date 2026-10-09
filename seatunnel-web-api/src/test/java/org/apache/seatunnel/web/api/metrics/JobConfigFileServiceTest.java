package org.apache.seatunnel.web.api.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JobConfigFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesRuntimeConfigWithOwnerOnlyPermissions() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));

        String originalUserDir = System.getProperty("user.dir");
        JobConfigFileService service = new JobConfigFileService();
        System.setProperty("user.dir", tempDir.toString());
        try {
            Path config = Path.of(service.writeConfig(42L, "source { Jdbc {} }"));

            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(config));
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(config.getParent()));
        } finally {
            service.cleanup(42L);
            if (originalUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", originalUserDir);
            }
        }
    }
}
