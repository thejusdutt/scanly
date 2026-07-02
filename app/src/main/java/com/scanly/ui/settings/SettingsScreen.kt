package com.scanly.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.scanly.BuildConfig
import com.scanly.R
import com.scanly.platform.TipState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val tipState by vm.tipState.collectAsState(initial = TipState.Unavailable)
    val context = LocalContext.current
    val isFoss = BuildConfig.FLAVOR == "foss"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            // --- Privacy ---
            SectionHeader("Privacy")
            ListItem(
                leadingContent = { Icon(Icons.Default.Lock, null) },
                headlineContent = { Text(stringResource(R.string.privacy_title)) },
                supportingContent = {
                    Text(
                        if (isFoss) stringResource(R.string.privacy_offline_foss)
                        else stringResource(R.string.privacy_offline_gplay),
                    )
                },
            )

            // --- Cloud (no direct integrations by design; SAF reaches any provider) ---
            SectionHeader("Cloud")
            ListItem(
                leadingContent = { Icon(Icons.Default.Cloud, null) },
                headlineContent = { Text("No cloud accounts, by design") },
                supportingContent = {
                    Text(
                        "Export → Save to device writes straight into Google Drive, " +
                            "Dropbox or Nextcloud via your file apps. Scanly never talks " +
                            "to any cloud service itself.",
                    )
                },
            )

            // --- Support ---
            SectionHeader("Support")
            when (val s = tipState) {
                is TipState.Available -> ListItem(
                    leadingContent = { Icon(Icons.Default.Favorite, null) },
                    headlineContent = { Text("Leave a tip (${s.price})") },
                    supportingContent = { Text("Optional. Everything works without it.") },
                    trailingContent = {
                        Button(onClick = { (context as? Activity)?.let(vm::tip) }) { Text("Tip") }
                    },
                )
                TipState.Tipped -> ListItem(
                    leadingContent = { Icon(Icons.Default.Favorite, null) },
                    headlineContent = { Text("Thank you for the tip ❤") },
                )
                TipState.Unavailable -> if (isFoss) ListItem(
                    headlineContent = { Text("This is the FOSS build") },
                    supportingContent = { Text("No ads, no tracking, no internet permission.") },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
