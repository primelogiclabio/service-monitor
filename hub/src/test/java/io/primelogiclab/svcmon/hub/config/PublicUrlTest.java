package io.primelogiclab.svcmon.hub.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PublicUrlTest {

    @Test
    void unsetMeansNoPublicAddress() {
        assertNull(PublicUrl.normalize(null));
        assertNull(PublicUrl.normalize(""));
        assertNull(PublicUrl.normalize("   "));
    }

    @Test
    void anHttpOrHttpsAddressIsKeptWithoutItsTrailingSlash() {
        assertEquals("https://monitor.example.test", PublicUrl.normalize("https://monitor.example.test"));
        assertEquals("https://monitor.example.test", PublicUrl.normalize(" https://monitor.example.test/ "));
        assertEquals("http://hub.internal:8080", PublicUrl.normalize("http://hub.internal:8080/"));
        assertEquals("https://example.test/monitor", PublicUrl.normalize("https://example.test/monitor/"));
    }

    @Test
    void anythingElseFailsLoudlyAndNamesTheSetting() {
        for (String bad : new String[] {"monitor.example.test", "ftp://monitor.example.test", "javascript:alert(1)",
                "https://user:pw@monitor.example.test", "https://monitor.example.test/?a=b", "https://monitor.example.test/#x",
                "https:///only-a-path", "https://monitor example.test", "https://monitor.example.test/\"onmouseover=x",
                "https://" + "a".repeat(300) + ".test"}) {
            String message = assertThrows(IllegalArgumentException.class, () -> PublicUrl.normalize(bad), bad).getMessage();
            assertTrue(message.startsWith("svcmon.public-url"), message);
        }
    }
}
