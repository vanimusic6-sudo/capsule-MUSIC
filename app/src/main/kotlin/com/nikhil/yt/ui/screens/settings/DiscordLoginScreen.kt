package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.util.Locale

/**
 * Fail closed. Legacy WebView login scraped a Discord user authorization token.
 * OAuth2 does not authorize the user-token gateway RPC, therefore enabling a
 * cosmetic OAuth login would be misleading and unsafe.
 */
@Composable
fun DiscordLoginScreen(navController: NavController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Discord", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            if (Locale.getDefault().language == "ru")
                "Интеграция временно отключена ради безопасности. Старый вход получал приватный токен Discord. Мы не будем запрашивать его снова. Для восстановления нужен официальный поддерживаемый способ."
            else "This integration is temporarily disabled for security. The old login extracted a private Discord account token. Capsule will not request one again; a supported official integration is needed.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { navController.navigateUp() }) {
            Text(if (Locale.getDefault().language == "ru") "Назад" else "Back")
        }
    }
}
