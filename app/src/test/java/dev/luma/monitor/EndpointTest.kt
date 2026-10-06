package dev.luma.monitor

import org.junit.Assert.*
import org.junit.Test

class EndpointTest {
    @Test fun credentialScopeUsesExactOrigin() {
        val endpoint = Endpoint.parse("https://monitor.example.com/komari/")
        assertTrue(endpoint.permits("https://monitor.example.com:443/komari/api/rpc2"))
        assertFalse(endpoint.permits("https://monitor.example.com.evil.test/api/rpc2"))
        assertFalse(endpoint.permits("https://monitor.example.com:8443/api/rpc2"))
        assertFalse(endpoint.permits("http://monitor.example.com/api/rpc2"))
        assertFalse(endpoint.permits("https://user@monitor.example.com/api/rpc2"))
        assertEquals("https://monitor.example.com/komari/api/rpc2", endpoint.api("api/rpc2"))
    }

    @Test fun rejectUnsafeOrAmbiguousRoots() {
        listOf("http://host.test", "javascript:alert(1)", "https://user:pass@host.test", "https://host.test/?token=secret", "https://host.test/#login").forEach {
            assertThrows(IllegalArgumentException::class.java) { Endpoint.parse(it) }
        }
    }

    @Test fun normalizeDefaultPortAndHostCase() {
        assertEquals(Endpoint.parse("https://HOST.test:443/"), Endpoint.parse("https://host.test"))
    }
}
