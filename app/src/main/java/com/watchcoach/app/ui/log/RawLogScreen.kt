package com.watchcoach.app.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchcoach.app.WatchCoachApplication
import com.watchcoach.app.ui.components.EmptyState
import com.watchcoach.app.ui.components.WatchCoachTopBar
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawLogScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val logger = (context.applicationContext as WatchCoachApplication).packetLogger
    val lines by logger.lines.collectAsStateWithLifecycle()
    val verbose by logger.verbose.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    Scaffold(
        topBar = { WatchCoachTopBar(title = "Raw log", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                "TX/RX packets as hex. Empty all-zero 34F2 frames and repeated live-HR lines " +
                    "are collapsed unless Verbose raw log is on. The shared file matches this view.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = verbose,
                    onCheckedChange = { logger.setVerbose(it) },
                    modifier = Modifier.semantics { contentDescription = "Verbose raw log" },
                )
                Text("Verbose raw log", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        logger.flush()
                        copyLog(context, logger.snapshotText(), "Log copied")
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Copy") }
                OutlinedButton(onClick = {
                    logger.flush()
                    copyLog(context, logger.summaryPlusLastLines(100), "Summary + last 100 lines copied")
                }) { Text("Copy summary + last 100 lines") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = {
                    logger.flush()
                    shareLog(context, logger.logFile, logger.snapshotText())
                }) { Text("Share") }
                OutlinedButton(onClick = { logger.clear() }) { Text("Clear") }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (lines.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Log is empty",
                            body = "Tap Sync on the Dashboard, or open the scanner and copy UUIDs. " +
                                "If a sync fails, the reason is written here. " +
                                "Turn on Verbose raw log only when you need every frame.",
                        )
                    }
                }
                items(lines, key = { it.id }) { line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun copyLog(context: Context, text: String, toast: String = "Log copied") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Watch Coach log", text.ifBlank { "(empty)" }))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

private fun shareLog(context: Context, file: File, fallback: String) {
    try {
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            file.writeText(fallback.ifBlank { "(empty log)\n" })
        }
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Watch Coach BLE log")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share log"))
    } catch (t: Throwable) {
        copyLog(context, fallback)
        Toast.makeText(context, "Share failed (${t.message}); log copied instead", Toast.LENGTH_LONG).show()
    }
}
