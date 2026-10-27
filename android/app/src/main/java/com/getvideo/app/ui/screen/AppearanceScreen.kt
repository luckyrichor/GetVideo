package com.getvideo.app.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getvideo.app.data.repo.BackgroundStore
import com.getvideo.app.ui.components.BackgroundPreview
import com.getvideo.app.ui.components.RowDivider
import com.getvideo.app.ui.components.SectionCard
import com.getvideo.app.ui.components.SectionTitle
import com.getvideo.app.ui.components.SettingRow
import com.getvideo.app.ui.components.uiAccentContainer
import com.getvideo.app.ui.components.uiCardColor
import com.getvideo.app.ui.components.uiMuted

/**
 * 外观页：选一张图当整个 App 的背景，并调节压暗程度。
 *
 * 图片只影响最底层，列表卡片和按钮仍然是不透明底色，所以不会影响点击和阅读。
 */
@Composable
fun AppearanceScreen() {
    val context = LocalContext.current
    val background by BackgroundStore.state.collectAsState()
    // 选择图片后立刻生效，并申请持久化读取权限，重启后仍然可用。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            BackgroundStore.setImage(context, uri)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SectionTitle("预览")
        SectionCard(padding = PaddingValues(10.dp)) {
            BackgroundPreview(
                background,
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }

        SectionTitle("背景图片")
        SectionCard {
            SettingRow(
                Icons.Rounded.Image,
                "从相册选择",
                subtitle = "支持任意比例的图片，自动裁切铺满",
                showChevron = false,
                onClick = { picker.launch(arrayOf("image/*")) },
            )
            BackgroundStore.builtins.forEach { (label, asset) ->
                RowDivider()
                BuiltinRow(
                    label = label,
                    selected = background.builtin == asset,
                    onClick = { BackgroundStore.setBuiltin(context, asset) },
                )
            }
            if (background.enabled) {
                RowDivider()
                SettingRow(
                    Icons.Rounded.DeleteOutline,
                    "恢复默认背景",
                    tone = MaterialTheme.colorScheme.error,
                    showChevron = false,
                    onClick = { BackgroundStore.clear(context) },
                )
            }
        }

        SectionTitle("压暗程度")
        SectionCard {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    "压暗多一些，文字更清楚；压暗少一些，图片更明显。",
                    fontSize = 12.sp,
                    color = uiMuted(),
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(6.dp))
                Slider(
                    value = background.scrim,
                    onValueChange = { BackgroundStore.setScrim(context, it) },
                    valueRange = 0.06f..0.9f,
                    enabled = background.enabled,
                )
                Text(
                    "当前 ${(background.scrim * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BuiltinRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(uiAccentContainer()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Image, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        if (selected) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = Color.White)
            }
        }
    }
}

/** 卡片内的一行外观项，保持与「我的」页同一套留白。 */
@Composable
private fun AppearanceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(uiCardColor()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = uiMuted())
            }
        }
    }
}
