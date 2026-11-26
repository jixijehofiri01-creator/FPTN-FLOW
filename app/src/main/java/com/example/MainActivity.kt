package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.feature.shared.FptnViewModel
import com.example.navigation.FptnAppRoot

class MainActivity : ComponentActivity() {

    private val viewModel: FptnViewModel by viewModels()
    private var incomingDeepLinkToken by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureDeepLink(intent)

        setContent {
            FptnAppRoot(
                viewModel = viewModel,
                deepLinkToken = incomingDeepLinkToken,
                onDeepLinkConsumed = { incomingDeepLinkToken = null },
                onExitApp = { finishAndRemoveTask() },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegroundChanged(true)
    }

    override fun onStop() {
        viewModel.onAppForegroundChanged(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureDeepLink(intent)
    }

    private fun captureDeepLink(intent: Intent?) {
        val data = intent?.dataString?.trim().orEmpty()
        if (data.startsWith("fptn:", ignoreCase = true) ||
            data.startsWith("fptnb:", ignoreCase = true) ||
            data.startsWith("fptn://", ignoreCase = true)
        ) {
            incomingDeepLinkToken = data
        }
    }
}
