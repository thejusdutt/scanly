package com.scanly.ui.settings

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.scanly.BuildConfig
import com.scanly.R
import com.scanly.data.prefs.ThemeMode
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
            // --- Scanning ---
            SectionHeader("Scanning")
            val reviewEachScan by vm.reviewEachScan.collectAsState()
            ListItem(
                leadingContent = { Icon(Icons.Default.FactCheck, null) },
                headlineContent = { Text("Review each scan") },
                supportingContent = {
                    Text("Pause after every capture so you can keep or retake the shot before it's saved")
                },
                trailingContent = {
                    Switch(checked = reviewEachScan, onCheckedChange = vm::setReviewEachScan)
                },
            )
            val autoCapture by vm.autoCapture.collectAsState()
            ListItem(
                leadingContent = { Icon(Icons.Default.MotionPhotosAuto, null) },
                headlineContent = { Text("Auto-capture") },
                supportingContent = {
                    Text("Fire the shutter automatically once a document is held steady")
                },
                trailingContent = {
                    Switch(checked = autoCapture, onCheckedChange = vm::setAutoCapture)
                },
            )

            // --- Appearance ---
            SectionHeader("Appearance")
            val themeMode by vm.themeMode.collectAsState()
            ListItem(
                leadingContent = { Icon(Icons.Default.BrightnessMedium, null) },
                headlineContent = { Text("Theme") },
                supportingContent = {
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index, count = ThemeMode.entries.size,
                                ),
                            ) {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "System"
                                        ThemeMode.LIGHT -> "Light"
                                        ThemeMode.DARK -> "Dark"
                                    },
                                )
                            }
                        }
                    }
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dynamicColor by vm.dynamicColor.collectAsState()
                ListItem(
                    leadingContent = { Icon(Icons.Default.Palette, null) },
                    headlineContent = { Text("Dynamic colors") },
                    supportingContent = { Text("Use colors from your wallpaper") },
                    trailingContent = {
                        Switch(checked = dynamicColor, onCheckedChange = vm::setDynamicColor)
                    },
                )
            }

            // --- Security ---
            SectionHeader("Security")
            val appLock by vm.appLockEnabled.collectAsState()
            ListItem(
                leadingContent = { Icon(Icons.Default.Fingerprint, null) },
                headlineContent = { Text("App lock") },
                supportingContent = {
                    Text("Require your screen lock (biometric or PIN) to open Scanly")
                },
                trailingContent = {
                    Switch(checked = appLock, onCheckedChange = vm::setAppLock)
                },
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.Shield, null) },
                headlineContent = { Text("Encryption at rest") },
                supportingContent = {
                    Text(
                        "The document database — names, tags and all recognized text — is " +
                            "encrypted with SQLCipher using a random key held in this " +
                            "device's hardware keystore. Always on.",
                    )
                },
            )

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

            Text(
                "Scanly ${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 24.dp),
            )
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
