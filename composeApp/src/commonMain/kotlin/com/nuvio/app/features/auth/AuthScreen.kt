package com.nuvio.app.features.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.LocalTelevisionUi
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.DeviceLinkAuthFailure
import com.nuvio.app.core.auth.DeviceLinkAuthRepository
import com.nuvio.app.core.auth.DeviceLinkAuthState
import com.nuvio.app.core.auth.GoogleOAuthAvailability
import com.nuvio.app.core.auth.PasskeyRepository
import com.nuvio.app.core.auth.PasswordRecoveryRepository
import com.nuvio.app.core.auth.PasswordRecoveryRequestResult
import com.nuvio.app.core.auth.rememberPasskeyCeremony
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogHint
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.DialogTextField
import com.nuvio.app.core.ui.GoogleBrandMark
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.AppBrandWordmark
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.auth_forgot_password
import nuvio.composeapp.generated.resources.auth_google_sign_in
import nuvio.composeapp.generated.resources.auth_google_sign_up
import nuvio.composeapp.generated.resources.auth_hide_password
import nuvio.composeapp.generated.resources.auth_option_code
import nuvio.composeapp.generated.resources.auth_option_google
import nuvio.composeapp.generated.resources.auth_option_passkey
import nuvio.composeapp.generated.resources.auth_password_recovery_description
import nuvio.composeapp.generated.resources.auth_password_recovery_failed
import nuvio.composeapp.generated.resources.auth_password_recovery_invalid_email
import nuvio.composeapp.generated.resources.auth_password_recovery_send
import nuvio.composeapp.generated.resources.auth_password_recovery_sent
import nuvio.composeapp.generated.resources.auth_password_recovery_title
import nuvio.composeapp.generated.resources.auth_password_recovery_unavailable
import nuvio.composeapp.generated.resources.auth_phone_code_hint
import nuvio.composeapp.generated.resources.auth_phone_description
import nuvio.composeapp.generated.resources.auth_phone_new_code
import nuvio.composeapp.generated.resources.auth_phone_qr_description
import nuvio.composeapp.generated.resources.auth_phone_show_code
import nuvio.composeapp.generated.resources.auth_phone_title
import nuvio.composeapp.generated.resources.auth_request_account
import nuvio.composeapp.generated.resources.auth_request_account_subtitle
import nuvio.composeapp.generated.resources.auth_request_password_minimum
import nuvio.composeapp.generated.resources.auth_request_return_to_sign_in
import nuvio.composeapp.generated.resources.auth_request_submitted_detail
import nuvio.composeapp.generated.resources.auth_request_submitted_subtitle
import nuvio.composeapp.generated.resources.auth_request_submitted_title
import nuvio.composeapp.generated.resources.auth_show_password
import nuvio.composeapp.generated.resources.auth_sign_in_with_passkey
import nuvio.composeapp.generated.resources.auth_use_email
import nuvio.composeapp.generated.resources.compose_auth_continue_without_account
import nuvio.composeapp.generated.resources.compose_auth_create_account
import nuvio.composeapp.generated.resources.compose_auth_email
import nuvio.composeapp.generated.resources.compose_auth_link_cancel
import nuvio.composeapp.generated.resources.compose_auth_link_code_expired
import nuvio.composeapp.generated.resources.compose_auth_link_creating_code
import nuvio.composeapp.generated.resources.compose_auth_link_failed
import nuvio.composeapp.generated.resources.compose_auth_link_open
import nuvio.composeapp.generated.resources.compose_auth_link_open_failed
import nuvio.composeapp.generated.resources.compose_auth_link_signing_in
import nuvio.composeapp.generated.resources.compose_auth_link_waiting
import nuvio.composeapp.generated.resources.compose_auth_or_separator
import nuvio.composeapp.generated.resources.compose_auth_password
import nuvio.composeapp.generated.resources.compose_auth_sign_in
import nuvio.composeapp.generated.resources.compose_auth_sign_in_subtitle
import nuvio.composeapp.generated.resources.compose_auth_sign_up_subtitle
import nuvio.composeapp.generated.resources.compose_auth_store_locally
import nuvio.composeapp.generated.resources.compose_auth_terms_link
import nuvio.composeapp.generated.resources.compose_auth_terms_prefix
import nuvio.composeapp.generated.resources.compose_auth_welcome_back
import org.jetbrains.compose.resources.stringResource

// Sign-in palette: a near-black night sky with one cyan accent taken from the Starlit mark.
internal val AuthTextPrimary = Color(0xFFF5F7F8)
internal val AuthTextSecondary = Color(0xFFA4A9B2)
internal val AuthFieldBackground = Color(0xFF111118)
internal val AuthFieldBorder = Color(0xFF2A2A35)
private val AuthTextMuted = Color(0xFF80858F)
private val AuthGround = Color(0xFF09090D)
private val AuthCardBackground = Color(0xFF111118)
private val AuthInsetBackground = Color(0xFF0B0B11)
private val AuthLine = Color(0xFF22222C)
private val AuthTabSelected = Color(0xFF262632)
internal val AuthAccent = Color(0xFF45D6EB)
private val AuthPrimaryButtonBackground = Color(0xFFF5F7F8)
private val AuthPrimaryButtonText = Color(0xFF0B0B10)
private val AuthQrTile = Color(0xFFF5F7F8)

/** Everything the sign-in layouts render; one snapshot per composition. */
private data class AuthUiState(
    val isSignUp: Boolean,
    val isStarlitServer: Boolean,
    val isRequestPending: Boolean,
    val passwordTooShort: Boolean,
    val email: String,
    val password: String,
    val passwordVisible: Boolean,
    val isLoading: Boolean,
    val authError: String?,
    val deviceLinkAuthState: DeviceLinkAuthState,
    val deviceLinkEnabled: Boolean,
    val googleEnabled: Boolean,
    val passkeyEnabled: Boolean,
) {
    val passkeyOffered: Boolean get() = !isSignUp && passkeyEnabled
    val deviceLinkOffered: Boolean get() = !isSignUp && deviceLinkEnabled
}

private class AuthUiActions(
    val onEmailChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onPasswordVisibilityToggle: () -> Unit,
    val onSubmit: () -> Unit,
    val onSelectMode: (signUp: Boolean) -> Unit,
    val onReturnToSignIn: () -> Unit,
    val onContinueWithoutAccount: () -> Unit,
    val onStartDeviceLink: () -> Unit,
    val onGoogleSignIn: () -> Unit,
    val onPasskeySignIn: () -> Unit,
    val onCancelDeviceLink: () -> Unit,
    val onEmailBoundsChange: (Rect) -> Unit,
    val onPasswordBoundsChange: (Rect) -> Unit,
)

private fun largeAuthScale(screenWidth: Dp): Float =
    (screenWidth.value / 1280f).coerceIn(1f, 1.3f)

@Composable
fun AuthScreen(
    modifier: Modifier = Modifier,
) {
    val authError by AuthRepository.error.collectAsStateWithLifecycle()
    val deviceLinkAuthState by DeviceLinkAuthRepository.state.collectAsStateWithLifecycle()
    val serverConnectionState by ServerConnectionController.state.collectAsStateWithLifecycle()
    val backendUrl = serverConnectionState.activeServer.backendUrl
    val isStarlitServer = !serverConnectionState.activeServer.supportsNuvioAccountFeatures
    val passkeyCeremony = rememberPasskeyCeremony()
    var serverPasskeysEnabled by remember(backendUrl) { mutableStateOf(false) }
    LaunchedEffect(backendUrl) {
        serverPasskeysEnabled = passkeyCeremony != null && PasskeyRepository.isEnabledOnActiveServer()
    }
    val passkeyEnabled = isStarlitServer && passkeyCeremony != null && serverPasskeysEnabled &&
        PasskeyRepository.canUseOnActiveServer()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val television = LocalTelevisionUi.current
    var googleEnabled by remember(backendUrl) { mutableStateOf(false) }
    LaunchedEffect(backendUrl) { googleEnabled = GoogleOAuthAvailability.isEnabled() }
    var isSignUp by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords must never enter a saved-instance-state bundle.
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    // A sign-in coroutine belongs to the current screen instance. Restoring its
    // busy flag after activity recreation can leave every sign-in action disabled
    // even though the original request was cancelled with the old composition.
    var isLoading by remember { mutableStateOf(false) }
    var isRequestPending by rememberSaveable(backendUrl) { mutableStateOf(false) }
    var passwordTooShort by rememberSaveable(backendUrl) { mutableStateOf(false) }
    var lastBackendUrl by remember { mutableStateOf(backendUrl) }
    var emailFieldBounds by remember { mutableStateOf<Rect?>(null) }
    var passwordFieldBounds by remember { mutableStateOf<Rect?>(null) }
    var showServerSheet by rememberSaveable { mutableStateOf(false) }
    var showOfficialServerDialog by rememberSaveable { mutableStateOf(false) }

    fun submitAuth() {
        if (email.isBlank() || isLoading || isRequestPending) return
        val minimumPasswordLength = if (isSignUp && isStarlitServer) 12 else 6
        if (password.length < minimumPasswordLength) {
            if (isSignUp && isStarlitServer) passwordTooShort = true
            return
        }
        val submittingSignUp = isSignUp
        val submittingStarlitSignup = submittingSignUp && isStarlitServer
        val submittingServerUrl = backendUrl
        val submittingEmail = email
        val submittingPassword = password
        DeviceLinkAuthRepository.cancel()
        isLoading = true
        focusManager.clearFocus(force = true)
        scope.launch {
            try {
                val result = if (submittingSignUp) {
                    AuthRepository.signUpWithEmail(submittingEmail, submittingPassword)
                } else {
                    AuthRepository.signInWithEmail(submittingEmail, submittingPassword)
                }
                if (submittingStarlitSignup && result.isSuccess &&
                    AuthRepository.notice.value != null &&
                    submittingServerUrl == ServerConnectionController.state.value.activeServer.backendUrl
                ) {
                    isRequestPending = true
                    password = ""
                    passwordVisible = false
                }
            } finally {
                isLoading = false
            }
        }
    }

    fun startPasskeySignIn() {
        val ceremony = passkeyCeremony ?: return
        if (isLoading || isSignUp || isRequestPending || !passkeyEnabled) return
        DeviceLinkAuthRepository.cancel()
        focusManager.clearFocus(force = true)
        isLoading = true
        scope.launch {
            try {
                AuthRepository.signInWithPasskey(ceremony)
            } finally {
                isLoading = false
            }
        }
    }

    fun signInWithGoogle() {
        if (isLoading || isRequestPending || !googleEnabled) return
        DeviceLinkAuthRepository.cancel()
        focusManager.clearFocus(force = true)
        isLoading = true
        scope.launch {
            try {
                AuthRepository.signInWithGoogle()
            } finally {
                isLoading = false
            }
        }
    }

    fun selectAuthMode(signUp: Boolean) {
        if (isLoading || isSignUp == signUp) return
        DeviceLinkAuthRepository.cancel()
        isSignUp = signUp
        passwordTooShort = false
        AuthRepository.clearError()
    }

    fun returnToSignIn() {
        isRequestPending = false
        isSignUp = false
        password = ""
        passwordTooShort = false
        AuthRepository.clearError()
    }

    fun startDeviceLink() {
        if (isLoading) return
        focusManager.clearFocus(force = true)
        AuthRepository.clearError()
        DeviceLinkAuthRepository.start()
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(backendUrl) {
        DeviceLinkAuthRepository.cancel()
        if (lastBackendUrl != backendUrl) {
            lastBackendUrl = backendUrl
            AuthRepository.clearError()
        }
        if (!serverConnectionState.activeServer.isCustom) {
            showOfficialServerDialog = false
        }
    }

    DisposableEffect(Unit) {
        onDispose(DeviceLinkAuthRepository::cancel)
    }

    val ui = AuthUiState(
        isSignUp = isSignUp,
        isStarlitServer = isStarlitServer,
        isRequestPending = isRequestPending,
        passwordTooShort = passwordTooShort,
        email = email,
        password = password,
        passwordVisible = passwordVisible,
        isLoading = isLoading,
        authError = authError,
        deviceLinkAuthState = deviceLinkAuthState,
        deviceLinkEnabled = serverConnectionState.activeServer.capabilities.tvLogin,
        googleEnabled = googleEnabled,
        passkeyEnabled = passkeyEnabled,
    )
    val actions = AuthUiActions(
        onEmailChange = {
            email = it
            AuthRepository.clearError()
        },
        onPasswordChange = {
            password = it
            passwordTooShort = false
            AuthRepository.clearError()
        },
        onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
        onSubmit = ::submitAuth,
        onSelectMode = ::selectAuthMode,
        onReturnToSignIn = ::returnToSignIn,
        onContinueWithoutAccount = {
            focusManager.clearFocus(force = true)
            DeviceLinkAuthRepository.cancel()
            AuthRepository.signInAnonymously()
        },
        onStartDeviceLink = ::startDeviceLink,
        onGoogleSignIn = ::signInWithGoogle,
        onPasskeySignIn = ::startPasskeySignIn,
        onCancelDeviceLink = DeviceLinkAuthRepository::cancel,
        onEmailBoundsChange = { emailFieldBounds = it },
        onPasswordBoundsChange = { passwordFieldBounds = it },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AuthGround)
            .pointerInput(emailFieldBounds, passwordFieldBounds) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val tappedTextField = listOfNotNull(emailFieldBounds, passwordFieldBounds)
                        .any { bounds -> bounds.contains(down.position) }
                    if (!tappedTextField) {
                        focusManager.clearFocus(force = true)
                    }
                }
            },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .authNightSky(),
        ) {
            when {
                television -> AuthTelevisionLayout(
                    ui = ui,
                    actions = actions,
                    backendUrl = backendUrl,
                )
                maxWidth >= 900.dp -> AuthLargeLayout(
                    ui = ui,
                    actions = actions,
                    scale = largeAuthScale(maxWidth),
                )
                else -> AuthMobileLayout(
                    ui = ui,
                    actions = actions,
                    topInset = statusBarTop,
                    bottomInset = navigationBarBottom,
                )
            }
        }
        if (AppFeaturePolicy.customServerConnectionsEnabled) {
            ServerConnectionMenu(
                activeServer = serverConnectionState.activeServer,
                onUseOfficial = {
                    ServerConnectionController.resetDiscovery()
                    showOfficialServerDialog = true
                },
                onConnectCustom = {
                    ServerConnectionController.resetDiscovery()
                    showServerSheet = true
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        end = if (television) 56.dp else 16.dp,
                        top = when {
                            television -> 32.dp
                            else -> statusBarTop + 12.dp
                        },
                    ),
            )
        }
    }
    if (
        AppFeaturePolicy.customServerConnectionsEnabled &&
        showServerSheet &&
        serverConnectionState.discoveredServer == null
    ) {
        ServerConnectionSheet(
            state = serverConnectionState,
            onDiscover = ServerConnectionController::discover,
            onDismiss = {
                showServerSheet = false
                ServerConnectionController.resetDiscovery()
            },
        )
    }
    serverConnectionState.discoveredServer?.let { server ->
        if (AppFeaturePolicy.customServerConnectionsEnabled) {
            ServerTrustDialog(
                server = server,
                isSwitching = serverConnectionState.isSwitching,
                switchFailure = serverConnectionState.switchFailure,
                onConfirm = ServerConnectionController::connectDiscovered,
                onDismiss = {
                    showServerSheet = false
                    ServerConnectionController.resetDiscovery()
                },
            )
        }
    }
    if (AppFeaturePolicy.customServerConnectionsEnabled && showOfficialServerDialog) {
        OfficialServerDialog(
            isSwitching = serverConnectionState.isSwitching,
            switchFailure = serverConnectionState.switchFailure,
            onConfirm = ServerConnectionController::useOfficial,
            onDismiss = {
                showOfficialServerDialog = false
                ServerConnectionController.resetDiscovery()
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Layouts
// ---------------------------------------------------------------------------------------------

/** Phones and narrow windows: one centred column, alternatives as compact tiles. */
@Composable
private fun AuthMobileLayout(
    ui: AuthUiState,
    actions: AuthUiActions,
    topInset: Dp,
    bottomInset: Dp,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight)
                    .padding(
                        start = 24.dp,
                        end = 24.dp,
                        // Leaves room for the server pill in the top corner.
                        top = topInset + 64.dp,
                        bottom = bottomInset + 24.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppBrandWordmark(
                        contentDescription = null,
                        modifier = Modifier.height(34.dp),
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    AuthHeading(
                        ui = ui,
                        headingSize = 30f,
                        subtitleSize = 15f,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    if (!ui.isRequestPending) {
                        AuthModeTabs(ui = ui, scale = 1f, onSelect = actions.onSelectMode)
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                    AuthForm(
                        ui = ui,
                        actions = actions,
                        scale = 1f,
                        compact = true,
                        offerDeviceLink = true,
                    )
                }
                if (!ui.isRequestPending) {
                    AuthContinueWithoutAccount(
                        enabled = !ui.isLoading,
                        scale = 1f,
                        inline = false,
                        onClick = actions.onContinueWithoutAccount,
                        modifier = Modifier.padding(top = 28.dp),
                    )
                }
            }
        }
    }
}

/** Desktop and tablets: a form card, plus a phone sign-in card when the server supports links. */
@Composable
private fun AuthLargeLayout(
    ui: AuthUiState,
    actions: AuthUiActions,
    scale: Float,
) {
    val showPhoneCard = ui.deviceLinkOffered && !ui.isRequestPending
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp * scale, vertical = 22.dp * scale)
                .height(40.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppBrandWordmark(
                contentDescription = null,
                modifier = Modifier.height(28.dp * scale),
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val viewportHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewportHeight)
                        .padding(horizontal = 24.dp, vertical = 16.dp * scale),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = (if (showPhoneCard) 920.dp else 480.dp) * scale)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AuthHeading(
                            ui = ui,
                            headingSize = 40f * scale,
                            subtitleSize = 16f * scale,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(32.dp * scale))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(20.dp * scale),
                        ) {
                            AuthCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                scale = scale,
                            ) {
                                if (!ui.isRequestPending) {
                                    AuthModeTabs(ui = ui, scale = scale, onSelect = actions.onSelectMode)
                                    Spacer(modifier = Modifier.height(22.dp * scale))
                                }
                                AuthForm(
                                    ui = ui,
                                    actions = actions,
                                    scale = scale,
                                    compact = false,
                                    offerDeviceLink = false,
                                )
                            }
                            if (showPhoneCard) {
                                AuthPhoneCard(
                                    state = ui.deviceLinkAuthState,
                                    enabled = !ui.isLoading,
                                    scale = scale,
                                    qrSize = 196.dp * scale,
                                    allowOpenLink = true,
                                    onStart = actions.onStartDeviceLink,
                                    onCancel = actions.onCancelDeviceLink,
                                    modifier = Modifier
                                        .width(340.dp * scale)
                                        .fillMaxHeight(),
                                )
                            }
                        }
                        if (!ui.isRequestPending) {
                            AuthContinueWithoutAccount(
                                enabled = !ui.isLoading,
                                scale = scale,
                                inline = false,
                                onClick = actions.onContinueWithoutAccount,
                                modifier = Modifier.padding(top = 24.dp * scale),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Android TV: brand and context on the left, the active sign-in surface on the right. When the
 * account server supports link sign-in the phone QR code leads, because typing with a remote is
 * the slowest way in.
 */
@Composable
private fun AuthTelevisionLayout(
    ui: AuthUiState,
    actions: AuthUiActions,
    backendUrl: String,
) {
    val phoneAvailable = ui.deviceLinkOffered && !ui.isRequestPending
    var showForm by rememberSaveable(phoneAvailable) { mutableStateOf(!phoneAvailable) }
    val showPhone = phoneAvailable && !showForm

    LaunchedEffect(showPhone, backendUrl) {
        if (showPhone) {
            // Let the screen-level reset for a new server run first.
            yield()
            if (DeviceLinkAuthRepository.state.value == DeviceLinkAuthState.Idle) {
                actions.onStartDeviceLink()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            AppBrandWordmark(
                contentDescription = null,
                modifier = Modifier.height(26.dp),
            )
            Column {
                AuthHeading(
                    ui = ui,
                    headingSize = 38f,
                    subtitleSize = 16f,
                    textAlign = TextAlign.Start,
                    phoneHeading = showPhone,
                )
                if (phoneAvailable) {
                    Spacer(modifier = Modifier.height(28.dp))
                    AuthSecondaryButton(
                        text = stringResource(
                            if (showForm) Res.string.auth_phone_title else Res.string.auth_use_email,
                        ),
                        enabled = !ui.isLoading,
                        height = 48.dp,
                        scale = 1f,
                        fillWidth = false,
                        icon = if (showForm) Icons.Rounded.QrCode2 else Icons.Rounded.Email,
                        onClick = {
                            if (!showForm) actions.onCancelDeviceLink()
                            showForm = !showForm
                        },
                        modifier = Modifier.testTag("auth-tv-switch-method"),
                    )
                }
            }
            if (!ui.isRequestPending) {
                AuthContinueWithoutAccount(
                    enabled = !ui.isLoading,
                    scale = 1f,
                    inline = false,
                    alignStart = true,
                    onClick = actions.onContinueWithoutAccount,
                )
            } else {
                Spacer(modifier = Modifier.height(1.dp))
            }
        }
        Box(
            modifier = Modifier
                .width(420.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            if (showPhone) {
                AuthPhoneCard(
                    state = ui.deviceLinkAuthState,
                    enabled = !ui.isLoading,
                    scale = 1f,
                    qrSize = 196.dp,
                    allowOpenLink = false,
                    showIntro = false,
                    onStart = actions.onStartDeviceLink,
                    onCancel = actions.onCancelDeviceLink,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                AuthCard(
                    modifier = Modifier.fillMaxWidth(),
                    scale = 1f,
                    scrollable = true,
                ) {
                    if (!ui.isRequestPending) {
                        AuthModeTabs(ui = ui, scale = 1f, onSelect = actions.onSelectMode)
                        Spacer(modifier = Modifier.height(18.dp))
                    }
                    AuthForm(
                        ui = ui,
                        actions = actions,
                        scale = 1f,
                        compact = false,
                        offerDeviceLink = false,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

@Composable
private fun AuthHeading(
    ui: AuthUiState,
    headingSize: Float,
    subtitleSize: Float,
    textAlign: TextAlign,
    phoneHeading: Boolean = false,
) {
    val heading = when {
        ui.isRequestPending -> stringResource(Res.string.auth_request_submitted_title)
        phoneHeading -> stringResource(Res.string.auth_phone_title)
        ui.isSignUp && ui.isStarlitServer -> stringResource(Res.string.auth_request_account)
        ui.isSignUp -> stringResource(Res.string.compose_auth_create_account)
        else -> stringResource(Res.string.compose_auth_welcome_back)
    }
    val subtitle = when {
        ui.isRequestPending -> stringResource(Res.string.auth_request_submitted_subtitle)
        phoneHeading -> stringResource(Res.string.auth_phone_description)
        ui.isSignUp && ui.isStarlitServer -> stringResource(Res.string.auth_request_account_subtitle)
        ui.isSignUp -> stringResource(Res.string.compose_auth_sign_up_subtitle)
        else -> stringResource(Res.string.compose_auth_sign_in_subtitle)
    }
    val horizontal = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedContent(
            targetState = heading,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "authHeading",
        ) { title ->
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = headingSize.sp,
                    lineHeight = (headingSize * 1.18f).sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.4f).sp,
                ),
                color = AuthTextPrimary,
                textAlign = textAlign,
            )
        }
        AnimatedContent(
            targetState = subtitle,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "authSubtitle",
        ) { description ->
            Text(
                text = description,
                modifier = Modifier.widthIn(max = 520.dp),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = subtitleSize.sp,
                    lineHeight = (subtitleSize * 1.45f).sp,
                ),
                color = AuthTextSecondary,
                textAlign = textAlign,
            )
        }
    }
}

/** "Sign in | Create account" switch; replaces the old toggle link under the button. */
@Composable
private fun AuthModeTabs(
    ui: AuthUiState,
    scale: Float,
    onSelect: (signUp: Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp * scale)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AuthInsetBackground, shape)
            .border(1.dp, AuthLine, shape)
            .padding(4.dp * scale)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp * scale),
    ) {
        AuthModeTab(
            text = stringResource(Res.string.compose_auth_sign_in),
            selected = !ui.isSignUp,
            enabled = !ui.isLoading,
            scale = scale,
            onClick = { onSelect(false) },
            modifier = Modifier.testTag("auth-mode-sign-in"),
        )
        AuthModeTab(
            text = stringResource(
                if (ui.isStarlitServer) Res.string.auth_request_account else Res.string.compose_auth_create_account,
            ),
            selected = ui.isSignUp,
            enabled = !ui.isLoading,
            scale = scale,
            onClick = { onSelect(true) },
            modifier = Modifier.testTag("auth-mode-sign-up"),
        )
    }
}

@Composable
private fun RowScope.AuthModeTab(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp * scale)
    Box(
        modifier = modifier
            .weight(1f)
            .height(44.dp * scale)
            .clip(shape)
            .background(if (selected) AuthTabSelected else Color.Transparent)
            .authFocusRing(shape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = (15f * scale).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = if (selected) AuthTextPrimary else AuthTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AuthForm(
    ui: AuthUiState,
    actions: AuthUiActions,
    scale: Float,
    compact: Boolean,
    offerDeviceLink: Boolean,
) {
    if (ui.isRequestPending) {
        AuthPendingRequestContent(
            scale = scale,
            onReturnToSignIn = actions.onReturnToSignIn,
        )
        return
    }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    if (showRecoveryDialog) {
        PasswordRecoveryDialog(
            initialEmail = ui.email,
            onDismiss = { showRecoveryDialog = false },
        )
    }
    val uriHandler = LocalUriHandler.current
    val showForgotPassword = !ui.isSignUp && ui.isStarlitServer &&
        PasswordRecoveryRepository.isAvailableOnActiveServer()
    val forgotPassword: @Composable () -> Unit = {
        AuthLinkButton(
            text = stringResource(Res.string.auth_forgot_password),
            enabled = !ui.isLoading,
            scale = scale,
            onClick = { showRecoveryDialog = true },
            modifier = Modifier.testTag("auth-forgot-password"),
        )
    }
    val fieldHeight = (if (compact) 52.dp else 50.dp) * scale

    Column(modifier = Modifier.fillMaxWidth()) {
        AuthTextField(
            label = stringResource(Res.string.compose_auth_email),
            value = ui.email,
            onValueChange = actions.onEmailChange,
            icon = Icons.Rounded.Email,
            fieldHeight = fieldHeight,
            scale = scale,
            onBoundsChange = actions.onEmailBoundsChange,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )

        Spacer(modifier = Modifier.height(14.dp * scale))

        AuthTextField(
            label = stringResource(Res.string.compose_auth_password),
            value = ui.password,
            onValueChange = actions.onPasswordChange,
            icon = Icons.Rounded.Lock,
            fieldHeight = fieldHeight,
            scale = scale,
            onBoundsChange = actions.onPasswordBoundsChange,
            isPassword = true,
            passwordVisible = ui.passwordVisible,
            onPasswordVisibilityToggle = actions.onPasswordVisibilityToggle,
            labelAction = if (showForgotPassword && !compact) forgotPassword else null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { actions.onSubmit() },
            ),
        )

        if (showForgotPassword && compact) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                forgotPassword()
            }
        }

        if (ui.isSignUp && ui.isStarlitServer) {
            Spacer(modifier = Modifier.height(8.dp * scale))
            Text(
                text = stringResource(Res.string.auth_request_password_minimum),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (13f * scale).sp,
                    lineHeight = (18f * scale).sp,
                ),
                color = if (ui.passwordTooShort) MaterialTheme.colorScheme.error else AuthTextMuted,
            )
        }

        ui.authError?.let { errorText ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (13f * scale).sp,
                    lineHeight = (18f * scale).sp,
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }

        val authNotice by AuthRepository.notice.collectAsStateWithLifecycle()
        authNotice?.takeIf { ui.authError == null }?.let { noticeText ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = noticeText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (13f * scale).sp,
                    lineHeight = (18f * scale).sp,
                ),
                color = AuthTextSecondary,
            )
        }

        Spacer(modifier = Modifier.height(20.dp * scale))

        AuthPrimaryButton(
            text = when {
                ui.isSignUp && ui.isStarlitServer -> stringResource(Res.string.auth_request_account)
                ui.isSignUp -> stringResource(Res.string.compose_auth_create_account)
                else -> stringResource(Res.string.compose_auth_sign_in)
            },
            isLoading = ui.isLoading,
            enabled = !ui.isLoading,
            height = (if (compact) 52.dp else 50.dp) * scale,
            scale = scale,
            onClick = actions.onSubmit,
        )

        if (ui.isSignUp && !ui.isStarlitServer) {
            Spacer(modifier = Modifier.height(12.dp * scale))
            AuthTermsAcknowledgement(
                scale = scale,
                onTermsClick = { uriHandler.openUri("https://nuvio.tv/terms") },
            )
        }

        val offerCode = offerDeviceLink && ui.deviceLinkOffered
        if (ui.passkeyOffered || ui.googleEnabled || offerCode) {
            Spacer(modifier = Modifier.height(22.dp * scale))
            AuthDivider(scale = scale)
            Spacer(modifier = Modifier.height(18.dp * scale))
            if (compact) {
                AuthCompactOptions(ui = ui, actions = actions, offerCode = offerCode)
            } else {
                AuthWideOptions(ui = ui, actions = actions, scale = scale)
            }
            if (offerCode && ui.deviceLinkAuthState != DeviceLinkAuthState.Idle) {
                Spacer(modifier = Modifier.height(14.dp * scale))
                DeviceLinkAuthSection(
                    state = ui.deviceLinkAuthState,
                    enabled = !ui.isLoading,
                    height = 48.dp * scale,
                    scale = scale,
                    onStart = actions.onStartDeviceLink,
                    onCancel = actions.onCancelDeviceLink,
                )
            }
        }
    }
}

/** Phone row of equal tiles: passkey, Google and link code side by side. */
@Composable
private fun AuthCompactOptions(
    ui: AuthUiState,
    actions: AuthUiActions,
    offerCode: Boolean,
) {
    val googleLabel = stringResource(
        if (ui.isSignUp) Res.string.auth_google_sign_up else Res.string.auth_google_sign_in,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (ui.passkeyOffered) {
            AuthOptionTile(
                label = stringResource(Res.string.auth_option_passkey),
                description = stringResource(Res.string.auth_sign_in_with_passkey),
                enabled = !ui.isLoading,
                onClick = actions.onPasskeySignIn,
                modifier = Modifier.testTag("auth-passkey"),
            ) {
                Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = AuthAccent, modifier = Modifier.size(22.dp))
            }
        }
        if (ui.googleEnabled) {
            AuthOptionTile(
                label = stringResource(Res.string.auth_option_google),
                description = googleLabel,
                enabled = !ui.isLoading,
                onClick = actions.onGoogleSignIn,
                modifier = Modifier.testTag("auth-google-circle"),
            ) {
                GoogleBrandMark(modifier = Modifier.size(20.dp))
            }
        }
        if (offerCode) {
            AuthOptionTile(
                label = stringResource(Res.string.auth_option_code),
                description = stringResource(Res.string.auth_option_code),
                enabled = !ui.isLoading && ui.deviceLinkAuthState == DeviceLinkAuthState.Idle,
                onClick = actions.onStartDeviceLink,
                modifier = Modifier.testTag("auth-device-link"),
            ) {
                Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = Color(0xFFC59BF6), modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Desktop and TV: labelled buttons for passkey and Google. */
@Composable
private fun AuthWideOptions(
    ui: AuthUiState,
    actions: AuthUiActions,
    scale: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp * scale),
    ) {
        if (ui.passkeyOffered) {
            AuthSecondaryButton(
                text = stringResource(Res.string.auth_sign_in_with_passkey),
                enabled = !ui.isLoading,
                height = 48.dp * scale,
                scale = scale,
                icon = Icons.Rounded.Fingerprint,
                iconTint = AuthAccent,
                textSize = 14f,
                onClick = actions.onPasskeySignIn,
                modifier = Modifier
                    .weight(1f)
                    .testTag("auth-passkey"),
            )
        }
        if (ui.googleEnabled) {
            AuthSecondaryButton(
                text = stringResource(
                    if (ui.isSignUp) Res.string.auth_google_sign_up else Res.string.auth_google_sign_in,
                ),
                enabled = !ui.isLoading,
                height = 48.dp * scale,
                scale = scale,
                leading = { GoogleBrandMark(modifier = Modifier.size(18.dp * scale)) },
                textSize = 14f,
                onClick = actions.onGoogleSignIn,
                modifier = Modifier
                    .weight(1f)
                    .testTag("auth-google-circle"),
            )
        }
    }
}

@Composable
private fun RowScope.AuthOptionTile(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .weight(1f)
            .height(68.dp)
            .clip(shape)
            .background(AuthCardBackground)
            .border(1.dp, AuthFieldBorder, shape)
            .authFocusRing(shape)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = Color(0xFFE4E6EA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/**
 * The phone sign-in card: a QR code for the link page plus the typed code. Used beside the form on
 * desktop and as the leading surface on Android TV.
 */
@Composable
private fun AuthPhoneCard(
    state: DeviceLinkAuthState,
    enabled: Boolean,
    scale: Float,
    qrSize: Dp,
    allowOpenLink: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    showIntro: Boolean = true,
) {
    val uriHandler = LocalUriHandler.current
    var openFailed by remember(state) { mutableStateOf(false) }
    AuthCard(
        modifier = modifier.testTag("auth-phone-card"),
        scale = scale,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showIntro) {
            Box(
                modifier = Modifier
                    .size(44.dp * scale)
                    .background(AuthAccent.copy(alpha = 0.10f), RoundedCornerShape(12.dp * scale)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Smartphone,
                    contentDescription = null,
                    tint = AuthAccent,
                    modifier = Modifier.size(22.dp * scale),
                )
            }
            Spacer(modifier = Modifier.height(14.dp * scale))
            Text(
                text = stringResource(Res.string.auth_phone_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (19f * scale).sp,
                    lineHeight = (26f * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = AuthTextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(6.dp * scale))
            Text(
                text = stringResource(Res.string.auth_phone_description),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (14f * scale).sp,
                    lineHeight = (20f * scale).sp,
                ),
                color = AuthTextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp * scale))
        }

        when (state) {
            DeviceLinkAuthState.Idle -> {
                Spacer(modifier = Modifier.weight(1f, fill = false))
                AuthSecondaryButton(
                    text = stringResource(Res.string.auth_phone_show_code),
                    enabled = enabled,
                    height = 48.dp * scale,
                    scale = scale,
                    icon = Icons.Rounded.QrCode2,
                    onClick = onStart,
                    modifier = Modifier.testTag("auth-device-link"),
                )
            }
            DeviceLinkAuthState.Starting -> {
                Box(
                    modifier = Modifier.size(qrSize + 24.dp * scale),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NuvioLoadingIndicator(color = AuthAccent, size = 28.dp * scale)
                        Spacer(modifier = Modifier.height(12.dp * scale))
                        Text(
                            text = stringResource(Res.string.compose_auth_link_creating_code),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = (13f * scale).sp),
                            color = AuthTextSecondary,
                        )
                    }
                }
            }
            is DeviceLinkAuthState.Waiting -> {
                val matrix = rememberAuthQr(state.verificationUrl)
                if (matrix != null) {
                    Box(
                        modifier = Modifier
                            .size(qrSize + 24.dp * scale)
                            .background(AuthQrTile, RoundedCornerShape(16.dp * scale))
                            .padding(12.dp * scale),
                    ) {
                        AuthQrCanvas(
                            matrix = matrix,
                            contentDescription = stringResource(Res.string.auth_phone_qr_description),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp * scale))
                }
                Text(
                    text = stringResource(Res.string.auth_phone_code_hint),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = (13f * scale).sp),
                    color = AuthTextMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp * scale))
                Text(
                    text = state.code,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = (28f * scale).sp,
                        lineHeight = (36f * scale).sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (3f * scale).sp,
                    ),
                    color = AuthTextPrimary,
                    modifier = Modifier.testTag("auth-device-link-code"),
                )
                Spacer(modifier = Modifier.height(10.dp * scale))
                AuthWaitingStatus(
                    text = stringResource(
                        if (state.isCompleting) Res.string.compose_auth_link_signing_in
                        else Res.string.compose_auth_link_waiting,
                    ),
                    scale = scale,
                )
                if (openFailed) {
                    Spacer(modifier = Modifier.height(8.dp * scale))
                    Text(
                        text = stringResource(Res.string.compose_auth_link_open_failed),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = (13f * scale).sp),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                if (!state.isCompleting) {
                    Spacer(modifier = Modifier.height(12.dp * scale))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp * scale),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (allowOpenLink) {
                            AuthLinkButton(
                                text = stringResource(Res.string.compose_auth_link_open),
                                enabled = enabled,
                                scale = scale,
                                onClick = {
                                    openFailed = runCatching { uriHandler.openUri(state.verificationUrl) }.isFailure
                                },
                            )
                        }
                        AuthLinkButton(
                            text = stringResource(Res.string.compose_auth_link_cancel),
                            enabled = true,
                            scale = scale,
                            color = AuthTextSecondary,
                            onClick = onCancel,
                        )
                    }
                }
            }
            is DeviceLinkAuthState.Failed -> {
                Text(
                    text = stringResource(
                        if (state.reason == DeviceLinkAuthFailure.Expired) Res.string.compose_auth_link_code_expired
                        else Res.string.compose_auth_link_failed,
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = (14f * scale).sp),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(14.dp * scale))
                AuthSecondaryButton(
                    text = stringResource(Res.string.auth_phone_new_code),
                    enabled = enabled,
                    height = 48.dp * scale,
                    scale = scale,
                    icon = Icons.Rounded.Refresh,
                    onClick = onStart,
                    modifier = Modifier.testTag("auth-device-link-retry"),
                )
            }
        }
    }
}

@Composable
private fun AuthWaitingStatus(text: String, scale: Float) {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp * scale),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp * scale)
                .alpha(pulse)
                .background(AuthAccent, CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = (13f * scale).sp),
            color = AuthTextSecondary,
        )
    }
}

@Composable
private fun AuthContinueWithoutAccount(
    enabled: Boolean,
    scale: Float,
    inline: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alignStart: Boolean = false,
) {
    val button: @Composable () -> Unit = {
        val shape = RoundedCornerShape(12.dp * scale)
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp * scale)
                .clip(shape)
                .authFocusRing(shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp * scale)
                .testTag("auth-continue-without-account"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp * scale),
        ) {
            Text(
                text = stringResource(Res.string.compose_auth_continue_without_account),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = (15f * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = AuthTextPrimary,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = AuthTextPrimary,
                modifier = Modifier.size(16.dp * scale),
            )
        }
    }
    val caption: @Composable () -> Unit = {
        Text(
            text = stringResource(Res.string.compose_auth_store_locally),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = (13f * scale).sp,
                lineHeight = (18f * scale).sp,
            ),
            color = AuthTextMuted,
            textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
            modifier = if (alignStart) Modifier.padding(start = 10.dp * scale) else Modifier,
        )
    }
    if (inline) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp * scale),
        ) {
            button()
            caption()
        }
    } else {
        Column(
            modifier = modifier,
            horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            button()
            caption()
        }
    }
}

@Composable
private fun AuthCard(
    modifier: Modifier = Modifier,
    scale: Float,
    scrollable: Boolean = false,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp * scale)
    Column(
        modifier = modifier
            .clip(shape)
            .background(AuthCardBackground.copy(alpha = 0.94f))
            .border(1.dp, AuthLine, shape)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 32.dp * scale, vertical = 30.dp * scale),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

@Composable
private fun PasswordRecoveryDialog(initialEmail: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(initialEmail) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PasswordRecoveryRequestResult?>(null) }
    val sent = result == PasswordRecoveryRequestResult.SENT
    val tokens = MaterialTheme.nuvio

    fun submit() {
        if (sending || sent) return
        sending = true
        scope.launch {
            try {
                result = PasswordRecoveryRepository.requestPasswordReset(address)
            } finally {
                sending = false
            }
        }
    }

    DialogSurface(
        onDismissRequest = { if (!sending) onDismiss() },
        title = stringResource(Res.string.auth_password_recovery_title),
        message = if (sent) null else stringResource(Res.string.auth_password_recovery_description),
    ) {
        if (sent) {
            // After sending, the dialog becomes a confirmation so the next step is obvious.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(tokens.shapes.compactCard)
                    .background(tokens.colors.accent.copy(alpha = tokens.opacity.selected))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.MarkEmailRead, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(Res.string.auth_password_recovery_sent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                )
            }
        } else {
            val failure = when (result) {
                PasswordRecoveryRequestResult.INVALID_EMAIL -> Res.string.auth_password_recovery_invalid_email
                PasswordRecoveryRequestResult.UNAVAILABLE -> Res.string.auth_password_recovery_unavailable
                PasswordRecoveryRequestResult.FAILED -> Res.string.auth_password_recovery_failed
                else -> null
            }
            DialogTextField(
                value = address,
                onValueChange = { address = it; result = null },
                label = stringResource(Res.string.compose_auth_email),
                enabled = !sending,
                isError = failure != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.testTag("auth-password-recovery-email"),
            )
            failure?.let { DialogHint(stringResource(it), isError = true) }
        }
        DialogButtons {
            if (!sent) {
                DialogButton(
                    text = stringResource(Res.string.action_cancel),
                    onClick = onDismiss,
                    enabled = !sending,
                )
            }
            DialogButton(
                text = stringResource(if (sent) Res.string.action_done else Res.string.auth_password_recovery_send),
                onClick = { if (sent) onDismiss() else submit() },
                style = DialogButtonStyle.Primary,
                enabled = sent || address.isNotBlank(),
                loading = sending,
                modifier = Modifier.testTag("auth-password-recovery-submit"),
            )
        }
    }
}

@Composable
private fun AuthPendingRequestContent(
    scale: Float,
    onReturnToSignIn: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.auth_request_submitted_detail),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = (15f * scale).sp,
                lineHeight = (22f * scale).sp,
            ),
            color = AuthTextSecondary,
        )
        Spacer(modifier = Modifier.height(22.dp * scale))
        AuthPrimaryButton(
            text = stringResource(Res.string.auth_request_return_to_sign_in),
            isLoading = false,
            enabled = true,
            height = 52.dp * scale,
            scale = scale,
            onClick = onReturnToSignIn,
        )
    }
}

@Composable
private fun AuthTermsAcknowledgement(
    scale: Float,
    onTermsClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.compose_auth_terms_prefix),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = (13f * scale).sp,
                lineHeight = (18f * scale).sp,
            ),
            color = AuthTextMuted,
        )
        Spacer(modifier = Modifier.width(4.dp * scale))
        AuthLinkButton(
            text = stringResource(Res.string.compose_auth_terms_link),
            enabled = true,
            scale = scale,
            onClick = onTermsClick,
        )
    }
}

@Composable
private fun AuthTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector,
    fieldHeight: Dp,
    scale: Float,
    onBoundsChange: (Rect) -> Unit,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onPasswordVisibilityToggle: () -> Unit = {},
    labelAction: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val shape = RoundedCornerShape(14.dp * scale)
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp * scale),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 20.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = (13f * scale).sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = AuthTextSecondary,
            )
            labelAction?.invoke()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(fieldHeight)
                .onGloballyPositioned { coordinates -> onBoundsChange(coordinates.boundsInRoot()) }
                .background(AuthFieldBackground, shape)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) AuthAccent else AuthFieldBorder,
                    shape = shape,
                )
                .onFocusChanged { focused = it.hasFocus }
                .padding(start = 16.dp * scale, end = if (isPassword) 4.dp * scale else 16.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp * scale),
                tint = AuthTextMuted,
            )
            Spacer(modifier = Modifier.width(12.dp * scale))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { contentDescription = label },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = AuthTextPrimary,
                    fontSize = (16f * scale).sp,
                    lineHeight = (22f * scale).sp,
                ),
                cursorBrush = SolidColor(AuthAccent),
                visualTransformation = if (isPassword && !passwordVisible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        innerTextField()
                    }
                },
            )
            if (isPassword) {
                val toggleShape = RoundedCornerShape(10.dp * scale)
                val toggleLabel = stringResource(
                    if (passwordVisible) Res.string.auth_hide_password else Res.string.auth_show_password,
                )
                Box(
                    modifier = Modifier
                        .size(44.dp * scale)
                        .clip(toggleShape)
                        .clickable(role = Role.Button, onClick = onPasswordVisibilityToggle)
                        .semantics { contentDescription = toggleLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp * scale),
                        tint = AuthTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AuthPrimaryButton(
    text: String,
    isLoading: Boolean,
    enabled: Boolean,
    height: Dp,
    scale: Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp * scale)
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .authFocusRing(shape, width = 3.dp),
        enabled = enabled,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AuthPrimaryButtonBackground,
            contentColor = AuthPrimaryButtonText,
            disabledContainerColor = if (isLoading) {
                AuthPrimaryButtonBackground
            } else {
                AuthPrimaryButtonBackground.copy(alpha = 0.45f)
            },
            disabledContentColor = AuthPrimaryButtonText.copy(alpha = 0.55f),
        ),
    ) {
        if (isLoading) {
            NuvioLoadingIndicator(
                modifier = Modifier.size(20.dp * scale),
                color = AuthPrimaryButtonText,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (16f * scale).sp,
                    lineHeight = (22f * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun AuthSecondaryButton(
    text: String,
    enabled: Boolean,
    height: Dp,
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color = AuthTextPrimary,
    leading: (@Composable () -> Unit)? = null,
    textSize: Float = 15f,
) {
    val shape = RoundedCornerShape(14.dp * scale)
    Button(
        onClick = onClick,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .authFocusRing(shape),
        enabled = enabled,
        shape = shape,
        border = BorderStroke(1.dp, AuthFieldBorder),
        contentPadding = PaddingValues(horizontal = 16.dp * scale),
        colors = ButtonDefaults.buttonColors(
            containerColor = AuthInsetBackground,
            contentColor = Color(0xFFE4E6EA),
            disabledContainerColor = AuthInsetBackground.copy(alpha = 0.5f),
            disabledContentColor = AuthTextPrimary.copy(alpha = 0.5f),
        ),
    ) {
        when {
            leading != null -> {
                leading()
                Spacer(modifier = Modifier.width(10.dp * scale))
            }
            icon != null -> {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp * scale))
                Spacer(modifier = Modifier.width(10.dp * scale))
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (textSize * scale).sp,
                lineHeight = (textSize * 1.4f * scale).sp,
                fontWeight = FontWeight.Medium,
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Text-only action in the accent color (forgot password, terms, open link). */
@Composable
private fun AuthLinkButton(
    text: String,
    enabled: Boolean,
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AuthAccent,
) {
    val shape = RoundedCornerShape(8.dp * scale)
    Box(
        modifier = modifier
            .heightIn(min = 32.dp * scale)
            .clip(shape)
            .authFocusRing(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp * scale),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = (13.5f * scale).sp,
                fontWeight = FontWeight.Medium,
            ),
            color = if (enabled) color else color.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun AuthDivider(scale: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(AuthLine),
        )
        Text(
            text = stringResource(Res.string.compose_auth_or_separator).trim(),
            modifier = Modifier.padding(horizontal = 14.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = (13f * scale).sp,
                lineHeight = (18f * scale).sp,
            ),
            color = AuthTextMuted,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(AuthLine),
        )
    }
}

/** Cyan outline while focused, so keyboard and TV remote users can see where they are. */
internal fun Modifier.authFocusRing(shape: Shape, width: Dp = 2.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.hasFocus }
        .border(width, if (focused) AuthAccent else Color.Transparent, shape)
}

private data class AuthStar(val x: Float, val y: Float, val radiusDp: Float, val alpha: Float, val tint: Color)

private val AuthStars = listOf(
    AuthStar(0.08f, 0.10f, 1.1f, 0.55f, Color.White),
    AuthStar(0.21f, 0.18f, 0.7f, 0.70f, Color.White),
    AuthStar(0.33f, 0.06f, 1.0f, 0.45f, Color(0xFF9FE9F5)),
    AuthStar(0.47f, 0.13f, 0.6f, 0.60f, Color.White),
    AuthStar(0.64f, 0.05f, 0.8f, 0.40f, Color(0xFFD7B6FA)),
    AuthStar(0.76f, 0.15f, 1.0f, 0.45f, Color.White),
    AuthStar(0.89f, 0.08f, 0.7f, 0.75f, Color.White),
    AuthStar(0.94f, 0.24f, 1.0f, 0.30f, Color.White),
    AuthStar(0.05f, 0.30f, 0.6f, 0.45f, Color.White),
    AuthStar(0.58f, 0.27f, 0.6f, 0.35f, Color(0xFF9FE9F5)),
)

/** Near-black ground with one soft glow from the Starlit mark's colours and a few still stars. */
private fun Modifier.authNightSky(): Modifier = drawWithCache {
    val radius = maxOf(size.width * 0.6f, 360.dp.toPx())
    val glow = Brush.radialGradient(
        colorStops = arrayOf(
            0f to Color(0xFF3965D4).copy(alpha = 0.26f),
            0.6f to Color(0xFFB250EF).copy(alpha = 0.08f),
            1f to Color.Transparent,
        ),
        center = Offset(size.width * 0.5f, -radius * 0.35f),
        radius = radius,
    )
    onDrawBehind {
        drawRect(color = AuthGround)
        drawRect(brush = glow)
        AuthStars.forEach { star ->
            drawCircle(
                color = star.tint.copy(alpha = star.alpha),
                radius = star.radiusDp.dp.toPx(),
                center = Offset(star.x * size.width, star.y * size.height),
            )
        }
    }
}

private fun LayoutCoordinates.boundsInRoot(): Rect {
    val position = positionInRoot()
    return Rect(
        left = position.x,
        top = position.y,
        right = position.x + size.width,
        bottom = position.y + size.height,
    )
}
