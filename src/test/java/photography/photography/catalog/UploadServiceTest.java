package photography.photography.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import photography.photography.api.ApiException;

class UploadServiceTest {
    @Test
    void typeIsDecidedFromMagicBytesOnly() {
        assertEquals("jpg", UploadService.extensionFor(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals("png", UploadService.extensionFor(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0}));
        assertEquals("webp", UploadService.extensionFor(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}));
        assertNull(UploadService.extensionFor("<svg onload=alert(1)>".getBytes()), "SVG/HTML must be refused");
        assertNull(UploadService.extensionFor("<?php".getBytes()));
        assertNull(UploadService.extensionFor(new byte[0]));
    }

    @Test
    void pathTraversalAndForeignNamesAreRejected() {
        UploadService s = new UploadService("/tmp/lenscape-test-uploads", 1000, "http://localhost:8080");
        for (String bad : new String[] {"../etc/passwd", "..%2F..%2Fx.jpg", "a.jpg", "00000000-0000-0000-0000-000000000000.svg", null}) {
            assertEquals(404, assertThrows(ApiException.class, () -> s.resolve(bad)).status());
        }
        assertEquals(404, assertThrows(ApiException.class, () -> s.resolve("00000000-0000-0000-0000-000000000000.jpg")).status(), "well-formed but missing");
    }
}
