package com.nuvio.app.features.profiles

import com.nuvio.app.core.auth.AuthState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileSecurityAvailabilityTest {
    @Test
    fun profilePinControlsOnlyRequireAnExistingSignedInProfile() {
        val signedIn = AuthState.Authenticated(userId = "account-1", email = null, isAnonymous = false)
        assertTrue(canManageProfilePin(isNew = false, authState = signedIn))
        assertFalse(canManageProfilePin(isNew = true, authState = signedIn))
        assertFalse(canManageProfilePin(isNew = false, authState = AuthState.Unauthenticated))
        assertFalse(canManageProfilePin(isNew = false, authState = AuthState.Loading))
        assertFalse(canManageProfilePin(
            isNew = false, authState = AuthState.Authenticated(userId = "guest", email = null, isAnonymous = true),
        ))
    }
}
