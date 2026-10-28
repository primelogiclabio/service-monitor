package io.primelogiclab.svcmon.hub.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.security.NetworkPolicy.Refusal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Which addresses are inside, and what a person may sign in with from where. */
class NetworkPolicyTest {

    private static NetworkPolicy policy(boolean allowOutside, List<String> internal, List<String> proxies) {
        return new NetworkPolicy(new HubProperties.Access(allowOutside, internal, proxies));
    }

    private static final NetworkPolicy DEFAULT = new NetworkPolicy(HubProperties.Access.DEFAULT);

    private static MockHttpServletRequest from(String address, boolean secure) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/status");
        r.setRemoteAddr(address);
        r.setSecure(secure);
        return r;
    }

    @Test
    void theDefaultInternalNetworkIsLoopbackAndThePrivateRanges() {
        for (String inside : List.of("127.0.0.1", "127.9.9.9", "::1", "[::1]", "10.1.2.3", "172.16.0.1", "172.31.255.255",
                "192.168.1.5", "fd12:3456::1", "fd00::1%eth0")) {
            assertTrue(DEFAULT.isInside(inside), inside);
        }
        for (String outside : List.of("203.0.113.7", "172.32.0.1", "172.15.255.255", "8.8.8.8", "2001:db8::1", "fe80::1",
                "", "localhost", "example.com", "10.0.0.1, 10.0.0.2")) {
            assertFalse(DEFAULT.isInside(outside), outside);
        }
        assertFalse(DEFAULT.isInside((String) null));
    }

    @Test
    void anIpv4MappedIpv6AddressCountsAsTheIpv4Address() {
        assertTrue(DEFAULT.isInside("::ffff:10.0.0.1"));
        assertFalse(DEFAULT.isInside("::ffff:203.0.113.7"));
    }

    @Test
    void aPublicProxyIsOutsideEvenThoughItsAddressIsInternal() {
        NetworkPolicy p = policy(false, HubProperties.Access.DEFAULT.internalNetworks(), List.of("127.0.0.2", "10.0.0.5"));
        assertFalse(p.isInside("127.0.0.2"), "nginx on this machine that forgot X-Forwarded-For");
        assertFalse(p.isInside("10.0.0.5"));
        assertTrue(p.isInside("127.0.0.1"), "an SSH tunnel from 127.0.0.1 stays inside");
        assertTrue(p.isInside("10.0.0.6"));
    }

    @Test
    void theInternalNetworkCanBeNarrowedAndThenReplacesTheDefaults() {
        NetworkPolicy p = policy(false, List.of("10.20.0.0/16", "192.168.7.9"), List.of());
        assertTrue(p.isInside("10.20.3.4"));
        assertTrue(p.isInside("192.168.7.9"));
        assertFalse(p.isInside("192.168.7.10"), "an address without a length is just itself");
        assertFalse(p.isInside("10.21.0.1"));
        assertFalse(p.isInside("127.0.0.1"), "loopback too is only inside when listed");
    }

    @Test
    void badValuesStopTheStartAndNameTheSetting() {
        for (String bad : List.of("0.0.0.0/0", "::/0", "10.0.0.0/33", "10.0.0.0/x", "intranet.example.com", "10.0.0.0/8/8",
                "", "*")) {
            var e = assertThrows(IllegalStateException.class, () -> policy(false, List.of(bad), List.of()), bad);
            assertTrue(e.getMessage().startsWith("svcmon.access.internal-networks: "), e.getMessage());
        }
        var e = assertThrows(IllegalStateException.class, () -> policy(false, List.of("10.0.0.0/8"), List.of("proxy")));
        assertTrue(e.getMessage().startsWith("svcmon.access.public-proxies: "), e.getMessage());
    }

    @Test
    void fromInsideEveryoneMayTryAndFromOutsideOnlyWhatTheSettingAllows() {
        assertNull(DEFAULT.forUser(from("10.0.0.1", false)), "plain HTTP inside is fine");
        assertNull(DEFAULT.forToken(true));

        assertEquals(Refusal.OUTSIDE_NOT_ALLOWED, DEFAULT.forUser(from("203.0.113.7", true)));
        assertEquals(Refusal.OUTSIDE_NOT_ALLOWED, DEFAULT.forToken(false));
        assertTrue(DEFAULT.refusesEveryone(from("203.0.113.7", true)));

        NetworkPolicy open = policy(true, HubProperties.Access.DEFAULT.internalNetworks(), List.of());
        assertNull(open.forUser(from("203.0.113.7", true)));
        assertEquals(Refusal.INSECURE_TRANSPORT, open.forUser(from("203.0.113.7", false)));
        assertEquals(Refusal.OUTSIDE_NOT_ALLOWED, open.forToken(false), "the CLI's tokens stay inside");
        assertFalse(open.refusesEveryone(from("203.0.113.7", false)));
        assertEquals("outside_not_allowed", Refusal.OUTSIDE_NOT_ALLOWED.code);
        assertEquals("insecure_transport", Refusal.INSECURE_TRANSPORT.code);
    }
}
