package com.example

import com.example.data.local.TokenSecretCipher
import com.example.domain.root.SafeRootRulePlanner
import com.example.vpn.dns.TunnelDohResolver
import com.example.vpn.root.RootTunFdHelper
import com.example.vpn.service.PowerLockCoordinator
import com.example.vpn.service.PowerWakeLockHandle
import com.example.vpn.tunnel.FingerprintTrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.cert.CertificateException

/**
 * Targeted security, input-validation, and regression tests added during the independent audit.
 */
class SecurityAndConcurrencyAuditTest {

    @Test
    fun safeRootRulePlanner_sanitizeIpv4_rejectsShellInjectionAndMalformedInputs() {
        assertEquals("198.51.100.42", SafeRootRulePlanner.sanitizeIpv4OrNull(" 198.51.100.42 "))
        assertEquals("10.10.0.1", SafeRootRulePlanner.sanitizeIpv4OrNull("10.10.0.1"))
        assertNull(SafeRootRulePlanner.sanitizeIpv4OrNull(""))
        assertNull(SafeRootRulePlanner.sanitizeIpv4OrNull(null))
        assertNull(SafeRootRulePlanner.sanitizeIpv4OrNull("1.2.3.256"))
        assertNull(SafeRootRulePlanner.sanitizeIpv4OrNull("1.2.3"))
        assertNull(SafeRootRulePlanner.sanitizeIpv4OrNull("1.2.3.4; rm -rf /"))
        assertNull(SafeRootRulePlanner.sanitizeIpv4OrNull("1.2.3.4$(id)"))
        assertNull(SafeRootRulePlanner.sanitizeIpv4OrNull("1.2.3.4`id`"))
        assertNull(SafeRootRulePlanner.sanitizeIpv4OrNull("1.2.3.4\nid"))
        assertNull(SafeRootRulePlanner.sanitizeIpv4OrNull("example.com"))
    }

    @Test
    fun safeRootRulePlanner_buildApplyCommands_rejectsInjectedServerIpAndNegativeUid() {
        try {
            SafeRootRulePlanner.buildApplyCommands(
                appUid = 10123,
                vpnServerIpv4 = "1.2.3.4; touch /data/local/tmp/pwned",
                tunInterfaceName = "fptn0",
                smartRfEnabled = true,
            )
            fail("Expected IllegalArgumentException for shell-injected vpnServerIpv4")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        try {
            SafeRootRulePlanner.buildApplyCommands(
                appUid = -1,
                vpnServerIpv4 = "198.51.100.10",
                tunInterfaceName = "fptn0",
                smartRfEnabled = true,
            )
            fail("Expected IllegalArgumentException for negative appUid")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun safeRootRulePlanner_buildApplyCommands_sanitizesMalformedDnsAndClientIp() {
        val cmds = SafeRootRulePlanner.buildApplyCommands(
            appUid = 10123,
            vpnServerIpv4 = "198.51.100.10",
            tunInterfaceName = "fptn0;rm -rf /",
            clientTunIpv4 = "10.0.0.1;id",
            dnsServerIpv4 = "8.8.8.8 && id",
            smartRfEnabled = true,
            excludedUids = setOf(-5, 10200),
            systemBypassUids = setOf(-1, 1073),
        )
        val joined = cmds.joinToString("\n")
        assertFalse("Injected shell command must not appear in generated rules", joined.contains("rm -rf"))
        assertFalse("Injected id command must not appear in generated rules", joined.contains(";id"))
        assertFalse("Injected DNS command must not appear in generated rules", joined.contains("&& id"))
        assertFalse("Negative UID must be ignored", joined.contains("-5"))
        assertTrue("Fallback client IPv4 should be used", joined.contains("10.10.0.1"))
        assertTrue("Valid excluded UID should be kept", joined.contains("10200"))
    }

    @Test
    fun rootTunFdHelper_validators_rejectCommandInjectionAndClampMtu() {
        assertEquals("fptn0", RootTunFdHelper.validateTunName("fptn0"))
        assertEquals("tun_vpn-1", RootTunFdHelper.validateTunName("tun_vpn-1"))
        assertNull(RootTunFdHelper.validateTunName("fptn0;id"))
        assertNull(RootTunFdHelper.validateTunName("0invalid"))
        assertNull(RootTunFdHelper.validateTunName("this_interface_name_is_too_long"))
        assertNull(RootTunFdHelper.validateTunName(""))

        assertEquals("10.10.0.1", RootTunFdHelper.validateIpv4("10.10.0.1"))
        assertNull(RootTunFdHelper.validateIpv4("10.10.0.1;id"))
        assertNull(RootTunFdHelper.validateIpv4("999.0.0.1"))

        assertEquals(576, RootTunFdHelper.clampMtu("100"))
        assertEquals(9000, RootTunFdHelper.clampMtu("65535"))
        assertEquals(1400, RootTunFdHelper.clampMtu("1400"))
        assertEquals(1500, RootTunFdHelper.clampMtu("invalid;id"))
    }

    @Test
    fun fingerprintTrustManager_rejectsBlankOrMalformedMd5InsteadOfBypassing() {
        val invalidPins = listOf(
            "",
            "   ",
            "short",
            "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
            "0123456789abcdef0123456789abcde", // 31 chars
        )
        for (pin in invalidPins) {
            val tm = FingerprintTrustManager(pin)
            try {
                tm.checkServerTrusted(emptyArray(), "RSA")
                fail("Expected CertificateException for invalid MD5 pin '$pin'")
            } catch (_: CertificateException) {
                // expected
            }
        }
    }

    @Test
    fun tunnelDohResolver_trySynthesizeIpv4TcpRst_handlesMalformedShortPacketsSafely() {
        assertNull(TunnelDohResolver.trySynthesizeIpv4TcpRst(ByteArray(0), 0))
        assertNull(TunnelDohResolver.trySynthesizeIpv4TcpRst(ByteArray(10), 10))
        // Non-IPv4 version byte (IPv6 = 0x60)
        val ipv6 = ByteArray(48).apply { this[0] = 0x60.toByte() }
        assertNull(TunnelDohResolver.trySynthesizeIpv4TcpRst(ipv6, ipv6.size))
    }

    @Test
    fun tokenSecretCipher_encryptsWithRandomIvAndDecryptsLegacyPlaintext() {
        val secret = "fptnb:super_secret_password_12345"
        val enc1 = TokenSecretCipher.encrypt(secret)
        val enc2 = TokenSecretCipher.encrypt(secret)

        assertTrue("Encrypted value should carry enc:v1 prefix", TokenSecretCipher.isEncrypted(enc1))
        assertFalse("Ciphertext must not expose plaintext", enc1.contains("super_secret"))
        assertNotEquals("Randomized IV must produce distinct ciphertexts", enc1, enc2)
        assertEquals(secret, TokenSecretCipher.decrypt(enc1))
        assertEquals(secret, TokenSecretCipher.decrypt(enc2))
        // Legacy unencrypted row remains readable
        assertEquals("legacy_plain_token", TokenSecretCipher.decrypt("legacy_plain_token"))
    }

    @Test
    fun powerLockCoordinator_preventsStaleReconnectFromReleasingNewFailoverLock() {
        var held = false
        var releaseCount = 0
        val fakeHandle = object : PowerWakeLockHandle {
            override val isHeld: Boolean get() = held
            override fun acquire(timeoutMs: Long) {
                held = true
            }
            override fun release() {
                if (held) {
                    held = false
                    releaseCount++
                }
            }
        }

        val coordinator = PowerLockCoordinator(fakeHandle)
        val reconnectToken = coordinator.acquire(15_000L)
        assertTrue(held)

        // Auto-Failover hands off to beginConnect(), which acquires a newer generation token
        val failoverConnectToken = coordinator.acquire(12_000L)
        assertTrue(held)

        // Old triggerSoftReconnect finally block runs AFTER beginConnect() acquired its lock
        assertFalse(
            "Stale reconnect token must not release the newer failover session's WakeLock",
            coordinator.releaseIfOwner(reconnectToken),
        )
        assertTrue("WakeLock must remain held for active failover handshake", held)
        assertEquals(0, releaseCount)

        // Current failover owner finishes and releases cleanly
        assertTrue(coordinator.releaseIfOwner(failoverConnectToken))
        assertFalse(held)
        assertEquals(1, releaseCount)
    }
}
