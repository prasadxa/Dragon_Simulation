package com.dragonsim.ar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dragonsim.ar.R

/** High-level app state the overlay renders. */
enum class OverlayState {
    /** No dragon yet — looking for the printed image. */
    Searching,

    /** Dragon spawned and image is TRACKING — overlay shows nothing. */
    Tracking,

    /** Dragon spawned but the image dropped tracking. */
    Lost,

    /** ARCore failed / device unsupported — full-screen message. */
    Unsupported,
}

private val ScrimColor = Color(0x99000000)
private val ErrorScreenColor = Color(0xFF10181F)

/**
 * Top-centre status chip on a scrim, bottom-right Reset button, and a
 * full-screen centred message for the [OverlayState.Unsupported] state.
 */
@Composable
fun StatusOverlay(
    state: OverlayState,
    errorMessage: String?,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (state == OverlayState.Unsupported) {
            // Opaque fallback screen — never leave the user on a black view.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ErrorScreenColor)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = errorMessage ?: stringResource(R.string.status_unsupported),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                // Reset doubles as "retry": it clears the error and recreates the AR session.
                Button(onClick = onReset) {
                    Text(stringResource(R.string.action_reset))
                }
                Spacer(Modifier.navigationBarsPadding().height(16.dp))
            }
        } else {
            val statusText = when (state) {
                OverlayState.Searching -> stringResource(R.string.status_searching)
                OverlayState.Lost -> stringResource(R.string.status_lost)
                OverlayState.Tracking -> null
                OverlayState.Unsupported -> null // handled above
            }
            statusText?.let {
                Text(
                    text = it,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 24.dp)
                        .background(ScrimColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Button(
                onClick = onReset,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.action_reset))
            }
        }
    }
}
