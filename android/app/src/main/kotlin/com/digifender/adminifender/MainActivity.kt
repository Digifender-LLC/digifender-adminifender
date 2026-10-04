package com.digifender.adminifender

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.digifender.adminifender.push.BRIEFING_DESTINATION
import com.digifender.adminifender.push.EXTRA_DESTINATION
import com.digifender.adminifender.ui.AppRoot
import com.digifender.adminifender.ui.theme.AdminifenderTheme
import kotlinx.coroutines.flow.MutableStateFlow

private val CONCERN_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** Notification extras are untrusted: only a well-formed ID or the briefing marker passes, never content. */
fun validDestination(value: String?): String? = value?.takeIf { it == BRIEFING_DESTINATION || CONCERN_ID.matches(it) }

private fun destinationOf(intent: Intent?): String? = validDestination(intent?.getStringExtra(EXTRA_DESTINATION))

class MainActivity : ComponentActivity() {
    private val destination = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) destination.value = destinationOf(intent)
        val container = (application as AdminifenderApplication).container
        setContent { AdminifenderTheme { AppRoot(container, this, destination) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destinationOf(intent)?.let { destination.value = it }
    }
}
