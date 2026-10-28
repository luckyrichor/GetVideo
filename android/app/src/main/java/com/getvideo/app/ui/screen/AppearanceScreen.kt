package com.getvideo.app.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getvideo.app.data.repo.BackgroundStore
import com.getvideo.app.data.repo.BuiltinBackground
import com.getvideo.app.data.repo.BuiltinKind
import com.getvideo.app.ui.components.RowDivider
import com.getvideo.app.ui.components.SectionCard
import com.getvideo.app.ui.components.SectionTitle
import com.getvideo.app.ui.components.SettingRow
import com.getvideo.app.ui.components.assetPainter
import com.getvideo.app.ui.components.uiAccentContainer
import com.getvideo.app.ui.components.uiCardColor
import com.getvideo.app.ui.components.uiHairline
import com.getvideo.app.ui.components.uiMuted

/**
 * 外观页：内置壁纸（静态 / 动图 / 视频）、自选背景、卡片不透明度。
 *
 * 背景只影响最底层，列表卡片和按钮仍然是不透明底色，所以不会影响点击和阅读。
 * 所有背景（含视频）都静音，不参与音频焦点。
 */
@Composable
fun AppearanceScreen() {
    val context = LocalContext.current
    val background by BackgroundStore.state.collectAsState()

    fun takePersist(uri: android.net.Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    // 选择图片后立刻生效，并申请持久化读取权限，重启后仍然可用。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            takePersist(uri)
            BackgroundStore.setImage(context, uri)
        }
    }
    // 视频 / 动图同样申请持久化授权，重启后不用再选一次。
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            takePersist(uri)
            BackgroundStore.setMotion(context, uri, isVideo = true)
        }
    }
    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            takePersist(uri)
            BackgroundStore.setMotion(context, uri, isVideo = false)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        SectionTitle("内置壁纸 · ${BackgroundStore.stills.size} 张")
        SectionCard(padding = PaddingValues(vertical = 12.dp)) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(BackgroundStore.stills, key = { it.key }) { item ->
                    BuiltinTile(
                        item = item,
                        selected = background.builtinId == item.key,
                        onClick = { BackgroundStore.setBuiltin(context, item.key) },
                    )
                }
            }
        }

        SectionTitle("内置动态背景 · 3 张动图 + 3 段视频")
        SectionCard(padding = PaddingValues(vertical = 12.dp)) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(BackgroundStore.motions, key = { it.key }) { item ->
                    BuiltinTile(
                        item = item,
                        selected = background.builtinId == item.key,
                        onClick = { BackgroundStore.setBuiltin(context, item.key) },
                    )
                }
            }
        }
        Text(
            "内置壁纸随安装包提供，不用联网、不用相册授权；动图和视频一律静音循环。",
            Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = uiMuted(),
        )

        SectionTitle("自己的背景")
        SectionCard {
            SettingRow(
                Icons.Rounded.Image,
                "从相册选择图片",
                subtitle = "支持任意比例的图片，自动裁切铺满",
                showChevron = false,
                onClick = { picker.launch(arrayOf("image/*")) },
            )
            RowDivider()
            AppearanceRow(
                Icons.Rounded.PlayArrow,
                "选择背景视频",
                "mp4 / webm，静音循环播放，不打断正在听的声音",
            ) { videoPicker.launch(arrayOf("video/*")) }
            RowDivider()
            AppearanceRow(
                Icons.Rounded.Image,
                "选择背景动图",
                "GIF / WebP，循环播放",
            ) { gifPicker.launch(arrayOf("image/gif", "image/webp")) }
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
        Text(
            "壁纸引擎（Wallpaper Engine）：创意工坊里「视频」类型的壁纸就是一个 mp4/webm 文件，复制到手机后用上面的「选择背景视频」即可；" +
                "「场景」类型是打包过的 scene.pkg，手机读不了，需要先在电脑上用壁纸引擎导出成视频。",
            Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = uiMuted(),
        )

        SectionTitle("卡片不透明度")
        SectionCard {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    "列表里那些白色卡片有多实。往左拖更透，背景图更明显；往右拖更白，文字更好读。",
                    fontSize = 12.sp,
                    color = uiMuted(),
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(6.dp))
                Slider(
                    value = background.cardAlpha,
                    onValueChange = { BackgroundStore.setCardAlpha(context, it) },
                    valueRange = 0.12f..1f,
                    enabled = background.enabled,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "当前 ${(background.cardAlpha * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (background.cardAlpha >= 0.97f) "完全不透明" else "可透出背景",
                        fontSize = 12.sp,
                        color = uiMuted(),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 内置壁纸的方块：缩略图 + 名字，选中时描边并打勾。 */
@Composable
private fun BuiltinTile(item: BuiltinBackground, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(96.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .size(width = 96.dp, height = 128.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(uiAccentContainer())
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else uiHairline(),
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
            Image(
                painter = assetPainter(item.thumb),
                contentDescription = item.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.kind != BuiltinKind.STILL) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier.padding(6.dp).align(Alignment.BottomStart),
                ) {
                    Text(
                        item.kindLabel,
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        color = Color.White,
                    )
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .padding(6.dp)
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(13.dp), tint = Color.White)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.label,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(start = 2.dp),
        )
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
