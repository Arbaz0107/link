package com.linkvault.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linkvault.app.R
import com.linkvault.app.ui.theme.Neon
import kotlinx.coroutines.delay

object Brand {
    const val FOUNDER = "Arbaz Mahudawala"
}

/**
 * Short branded screen shown right after Android's own splash when the app is opened from the
 * launcher. Android 12+ only lets the system splash show an icon, not text, so the founder's name
 * lives here. Tap anywhere to skip.
 */
@Composable
fun BrandSplash(onFinished: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        show = true
        delay(900)
        onFinished()
    }
    Box(
        Modifier.fillMaxSize().background(Neon.ink)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onFinished() },
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(visible = show, enter = fadeIn(tween(550)) + scaleIn(tween(550), initialScale = 0.92f)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(176.dp))
                Text(
                    "LinkVault", style = MaterialTheme.typography.headlineMedium, color = Color.White,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(28.dp))
                // Letter spacing adds a gap after the last letter too, which pushes the word left of centre.
                // The start padding of the same size cancels it out so FOUNDER sits exactly in the middle.
                Text(
                    "FOUNDER", fontSize = 11.sp, letterSpacing = 3.sp, color = Neon.lime,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(start = 3.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    Brand.FOUNDER, style = MaterialTheme.typography.titleLarge, color = Color.White,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
