package com.mycontacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPublicKey

class RemoteConfigPublicKeyTest {
    @Test
    fun loadsPinnedP256PublicKeyAndFingerprint() {
        val key = RemoteConfigPublicKey.load()
        assertTrue(key is ECPublicKey)
        assertEquals("EC", key.algorithm)
        assertEquals(256, (key as ECPublicKey).params.curve.field.fieldSize)
        assertEquals(
            "142b34dbec6bd761d60ed26344e2aacf4c7a05a47b7d4a4b3e80d6871987d0fc",
            RemoteConfigPublicKey.fingerprintSha256()
        )
    }
}
