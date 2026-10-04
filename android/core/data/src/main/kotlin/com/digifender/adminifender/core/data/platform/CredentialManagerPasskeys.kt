package com.digifender.adminifender.core.data.platform

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import com.digifender.adminifender.core.data.session.PasskeyResult

/**
 * Credential Manager passkey prompt. The server's options carry no allowCredentials, so the platform offers every
 * passkey for the relying party. Assertion contents are passed straight to the server and never logged.
 */
class CredentialManagerPasskeys(private val activity: Activity) : PasskeyAuthenticator {
    override suspend fun assert(requestJson: String): PasskeyResult = try {
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
        when (val credential = CredentialManager.create(activity).getCredential(activity, request).credential) {
            is PublicKeyCredential -> PasskeyResult.Assertion(credential.authenticationResponseJson)
            else -> PasskeyResult.Failed("unexpected_credential")
        }
    } catch (_: GetCredentialCancellationException) {
        PasskeyResult.Cancelled
    } catch (_: NoCredentialException) {
        PasskeyResult.NoCredential
    } catch (_: GetCredentialException) {
        PasskeyResult.Failed("credential_unavailable")
    }
}
