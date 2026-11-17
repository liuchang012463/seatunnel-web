package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MetadataPageCollectorTest {
    @Test
    void stopsWhenTheRemoteCursorRepeats() {
        var calls = new AtomicInteger();
        var rows = MetadataPageCollector.collectPages(after -> {
            calls.incrementAndGet();
            return new OpenMetadataPage<>(List.of(after == null ? "first" : "second"), 2L, "same-cursor");
        });
        assertEquals(List.of("first", "second"), rows);
        assertEquals(2, calls.get());
    }
}
