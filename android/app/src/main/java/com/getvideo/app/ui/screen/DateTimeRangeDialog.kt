package com.getvideo.app.ui.screen

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

internal fun displayDateTime(value: Long, seconds: Boolean = false): String =
    DateTimeFormatter.ofPattern(if (seconds) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

@Composable
internal fun DateTimeRangeDialog(start: Long?, end: Long?, onClose: () -> Unit, onApply: (Long?, Long?) -> Unit) {
    val context = LocalContext.current
    var from by remember { mutableStateOf(start) }
    var until by remember { mutableStateOf(end) }
    var error by remember { mutableStateOf<String?>(null) }
    fun calendar(value: Long?, isEnd: Boolean) = Calendar.getInstance().apply {
        if (value != null) timeInMillis = value
        else { set(Calendar.HOUR_OF_DAY, if (isEnd) 23 else 0); set(Calendar.MINUTE, if (isEnd) 59 else 0) }
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    fun selectDate(isEnd: Boolean) {
        val c = calendar(if (isEnd) until else from, isEnd)
        DatePickerDialog(context, { _, year, month, day ->
            c.set(year, month, day)
            if (isEnd) until = c.timeInMillis else from = c.timeInMillis
            error = null
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun selectTime(isEnd: Boolean) {
        val c = calendar(if (isEnd) until else from, isEnd)
        TimePickerDialog(context, { _, hour, minute ->
            c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, minute)
            if (isEnd) until = c.timeInMillis else from = c.timeInMillis
            error = null
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("按加入时间筛选") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("开始时间", style = MaterialTheme.typography.labelLarge)
        Text(from?.let { displayDateTime(it) } ?: "不限")
        Row { OutlinedButton(onClick = { selectDate(false) }) { Text("开始日期") }; Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = { selectTime(false) }) { Text("开始时分") } }
        Text("截止时间", style = MaterialTheme.typography.labelLarge)
        Text(until?.let { displayDateTime(it) } ?: "不限")
        Row { OutlinedButton(onClick = { selectDate(true) }) { Text("截止日期") }; Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = { selectTime(true) }) { Text("截止时分") } }
        AreaButton(onClick = { from = null; until = null; error = null }) { Text("清除时间范围") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { AreaButton(onClick = {
        if (from != null && until != null && from!! > until!!) error = "开始时间不能晚于截止时间"
        else onApply(from, until)
    }) { Text("应用") } }, dismissButton = { AreaButton(onClick = onClose) { Text("取消") } })
}
