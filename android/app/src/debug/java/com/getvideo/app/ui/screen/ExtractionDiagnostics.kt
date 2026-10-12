package com.getvideo.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.getvideo.app.data.repo.DiagnosticsStore

@Composable
fun ExtractionDiagnostics() {
    val context = LocalContext.current
    val lines = DiagnosticsStore.lines
    if (lines.isEmpty()) return
    HorizontalDivider()
    Text("提取诊断（测试版）", style = MaterialTheme.typography.titleSmall)
    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    TextButton(onClick = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GetVideo 提取诊断", lines.joinToString("\n")))
    }) { Text("复制诊断") }
}
