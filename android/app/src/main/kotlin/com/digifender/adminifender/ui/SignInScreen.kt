package com.digifender.adminifender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R

@Composable
fun SignInScreen(state: SessionUiState, onSignIn: () -> Unit) {
    Scaffold(Modifier.fillMaxSize()) { padding ->
        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.sign_in_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.sign_in_body), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onSignIn, enabled = !state.busy) { Text(stringResource(R.string.sign_in_button)) }
            if (state.busy) CircularProgressIndicator()
            state.message?.let {
                Text(
                    stringResource(
                        when (it) {
                            SignInMessage.CANCELLED -> R.string.sign_in_cancelled
                            SignInMessage.NO_PASSKEY -> R.string.sign_in_no_passkey
                            SignInMessage.REFUSED -> R.string.sign_in_refused
                            SignInMessage.UNAVAILABLE -> R.string.sign_in_unavailable
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
