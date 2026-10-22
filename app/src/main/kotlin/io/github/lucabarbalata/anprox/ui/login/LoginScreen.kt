package io.github.lucabarbalata.anprox.ui.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.auth.TfaKind
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.asString
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun LoginScreen(
    serverId: String,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = koinViewModel(key = "login-$serverId") { parametersOf(serverId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onLoggedIn() }

    AnProxScaffold(title = stringResource(R.string.login_title), onBack = onBack, modifier = Modifier.imePadding()) { inner ->
        Box(
            Modifier
                .fillMaxWidth()
                .padding(inner)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Hero(state.serverName, state.product.displayName)

                state.error?.let { error ->
                    if (state.certificateUntrusted) {
                        ErrorBanner(
                            message = error.asString(),
                            tone = Tone.WARNING,
                            actionLabel = stringResource(R.string.login_allow_insecure),
                            onAction = viewModel::allowInsecureAndRetry,
                        )
                    } else {
                        ErrorBanner(message = error.asString())
                    }
                }

                AnimatedContent(targetState = state.step, label = "loginStep") { step ->
                    when (step) {
                        LoginStep.PASSWORD -> PasswordStep(state, viewModel)
                        LoginStep.SECOND_FACTOR -> SecondFactorStep(state, viewModel)
                    }
                }
            }
        }
    }

    state.openId?.let { start ->
        OpenIdSignInDialog(
            start = start,
            title = stringResource(R.string.login_openid_button, state.selectedRealm?.let { it.comment?.takeIf(String::isNotBlank) ?: it.realm }.orEmpty()),
            allowInsecure = state.allowInsecure,
            onReturned = viewModel::openIdReturned,
            onFailed = viewModel::openIdFailed,
            onDismiss = viewModel::cancelOpenId,
        )
    }
}

@Composable
private fun Hero(serverName: String, product: String) {
    val c = AnProxTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(c.accent),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Lock, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(30.dp)) }
        Text(serverName, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, textAlign = TextAlign.Center)
        Text(product, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    }
}

@Composable
private fun PasswordStep(state: LoginState, viewModel: LoginViewModel) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val openIdName = state.selectedRealm?.let { it.comment?.takeIf(String::isNotBlank) ?: it.realm }.orEmpty()
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // An OpenID Connect realm names the account itself, after sign-in at its provider.
            if (!state.isOpenIdRealm) {
                GlassTextField(
                    value = state.username,
                    onValueChange = viewModel::setUsername,
                    label = stringResource(R.string.login_username),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                )
            }
            RealmPicker(state, viewModel::setRealm)
            if (state.isOpenIdRealm) {
                Text(stringResource(R.string.login_openid_body, openIdName), style = MaterialTheme.typography.bodySmall, color = AnProxTheme.colors.textSecondary)
            } else {
                GlassTextField(
                    value = state.password,
                    onValueChange = viewModel::setPassword,
                    label = stringResource(R.string.login_password),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.submitPassword() }),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password),
                            )
                        }
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.login_remember),
                    description = stringResource(R.string.login_remember_description),
                    checked = state.remember,
                    onCheckedChange = viewModel::setRemember,
                )
            }
            AnProxButton(
                text = if (state.isOpenIdRealm) stringResource(R.string.login_openid_button, openIdName) else stringResource(R.string.action_sign_in),
                onClick = viewModel::submitPassword,
                loading = state.busy,
                enabled = state.isOpenIdRealm || (state.password.isNotEmpty() && state.username.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.error != null && !state.realmsLoading && state.realms.isEmpty()) {
                AnProxButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { viewModel.retry() },
                    style = ButtonStyle.SECONDARY,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RealmPicker(state: LoginState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GlassTextField(
            value = state.selectedRealm?.label ?: state.realm,
            onValueChange = {},
            label = stringResource(R.string.login_realm),
            enabled = false,
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
        )
        // Transparent overlay so the disabled field opens the menu when tapped.
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = state.realms.isNotEmpty(), onClickLabel = stringResource(R.string.login_choose_realm)) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.realms.forEach { realm ->
                DropdownMenuItem(
                    text = { Text(realm.label) },
                    onClick = {
                        onSelect(realm.realm)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SecondFactorStep(state: LoginState, viewModel: LoginViewModel) {
    val c = AnProxTheme.colors
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.login_tfa_title), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text(stringResource(R.string.login_tfa_body), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            if (state.tfaKinds.size > 1) {
                val labels = state.tfaKinds.associateWith { tfaLabel(it) }
                SegmentedPicker(
                    options = state.tfaKinds,
                    selected = state.tfaKind,
                    label = { kind -> labels.getValue(kind) },
                    onSelect = viewModel::setTfaKind,
                )
            }
            GlassTextField(
                value = state.code,
                onValueChange = viewModel::setCode,
                label = tfaLabel(state.tfaKind),
                supportingText = if (state.tfaKind == TfaKind.RECOVERY && state.recoveryKeysLeft != null) {
                    stringResource(R.string.login_recovery_left, state.recoveryKeysLeft)
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (state.tfaKind == TfaKind.TOTP) KeyboardType.NumberPassword else KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.submitCode() }),
            )
            AnProxButton(
                text = stringResource(R.string.login_verify),
                onClick = viewModel::submitCode,
                loading = state.busy,
                enabled = state.code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = viewModel::backToPassword, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.action_back))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun tfaLabel(kind: TfaKind): String = stringResource(
    when (kind) {
        TfaKind.TOTP -> R.string.login_tfa_totp
        TfaKind.RECOVERY -> R.string.login_tfa_recovery
        TfaKind.YUBICO -> R.string.login_tfa_yubico
        TfaKind.WEBAUTHN -> R.string.login_tfa_webauthn
    },
)
