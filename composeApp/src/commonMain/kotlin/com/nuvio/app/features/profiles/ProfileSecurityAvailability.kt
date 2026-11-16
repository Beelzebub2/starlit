package com.nuvio.app.features.profiles

import com.nuvio.app.core.auth.AuthState

internal fun canManageProfilePin(isNew: Boolean, authState: AuthState): Boolean =
    !isNew && (authState as? AuthState.Authenticated)?.isAnonymous == false
