package com.getvideo.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.getvideo.app.ui.components.uiAccentContainer
import com.getvideo.app.ui.components.uiCardColor
import com.getvideo.app.ui.components.uiHairline
import com.getvideo.app.ui.components.uiMuted

/**
 * 统一弹层：底部把手 + 标题区 + 可滚动内容 + 全宽主按钮。
 * 只做外观，不含任何业务逻辑，调用方仍把原来的回调原样传进来。
 */
@Composable
internal fun MusicSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    badge: String? = null,
    header: (@Composable () -> Unit)? = null,
    confirmText: String = "完成",
    confirmEnabled: Boolean = true,
    scroll: Boolean = true,
    onConfirm: () -> Unit = onDismiss,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 24.dp),
            shape = RoundedCornerShape(26.dp),
            color = uiCardColor(),
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.padding(bottom = 4.dp)) {
                // 顶部把手，提示这是一个可以被下拉关掉的浮层。
                Box(Modifier.fillMaxWidth().padding(top = 9.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 38.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(uiHairline()),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title,
                                fontSize = 19.sp,
                                lineHeight = 25.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (badge != null) {
                                Spacer(Modifier.width(8.dp))
                                Surface(color = uiAccentContainer(), shape = CircleShape) {
                                    Text(
                                        badge,
                                        Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        if (subtitle != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = uiMuted())
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.Close, "关闭", Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
                if (header != null) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) { header() }
                    Spacer(Modifier.height(10.dp))
                }
                Box(Modifier.weight(1f, fill = false).heightIn(max = 560.dp)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content,
                    )
                    // 底部淡出，提示下面还有内容，避免最后一行被按钮硬切。
                    if (scroll) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(20.dp)
                                .background(Brush.verticalGradient(listOf(uiCardColor().copy(alpha = 0f), uiCardColor()))),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Surface(
                    onClick = { if (confirmEnabled) onConfirm() },
                    shape = RoundedCornerShape(16.dp),
                    color = if (confirmEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(50.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            confirmText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (confirmEnabled) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 弹层内的分组卡片：标题弱化，行与行之间用极细分隔线。 */
@Composable
internal fun SheetGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                Modifier.padding(start = 6.dp, bottom = 6.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
                color = uiMuted(),
            )
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = uiAccentContainer().copy(alpha = 0.5f),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

/** 分组内的一行：图标底托 + 标题（+ 说明）+ 右侧状态/箭头。 */
@Composable
internal fun SheetRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    status: String? = null,
    subtitle: String? = null,
    featured: Boolean = false,
    destructive: Boolean = false,
    chevron: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (destructive) colors.error else colors.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (featured) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when {
                        destructive -> colors.error.copy(alpha = 0.10f)
                        featured -> colors.primary
                        else -> uiCardColor()
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null, Modifier.size(18.dp),
                tint = if (featured && !destructive) colors.onPrimary else tint,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = if (featured) FontWeight.SemiBold else FontWeight.Normal,
                color = if (destructive) colors.error else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(subtitle, fontSize = 11.sp, lineHeight = 15.sp, color = uiMuted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (status != null) {
            Text(
                status,
                Modifier.widthIn(max = 110.dp),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (featured) colors.primary else uiMuted(),
            )
        }
        if (chevron && status == null && !destructive) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ChevronRight, null, Modifier.size(17.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

/** 分组内的分隔线，左侧留出图标宽度。 */
@Composable
internal fun SheetDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 12.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
    )
}

/** 类别卡片：左侧图标底托、名称与统计、右侧播放与更多。 */
@Composable
internal fun CategoryCard(
    name: String,
    countText: String,
    icon: ImageVector,
    container: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = uiCardColor(),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        onClick = onOpen,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(uiAccentContainer()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = colors.primary)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(countText, fontSize = 12.sp, color = uiMuted(), maxLines = 1)
            }
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(uiAccentContainer()).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(playAllIcon(), "播放$name", Modifier.size(19.dp), tint = colors.primary)
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(moreIcon(), "更多", Modifier.size(19.dp), tint = colors.onSurfaceVariant.copy(alpha = 0.75f))
            }
        }
    }
}

/** 轻量胶囊动作，用来替代页面顶部那排描边按钮。 */
@Composable
internal fun SheetPill(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = when {
            !enabled -> colors.surfaceVariant.copy(alpha = 0.6f)
            primary -> colors.primary
            else -> uiCardColor()
        },
        tonalElevation = 0.dp,
        shadowElevation = if (primary && enabled) 0.dp else 1.dp,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon, null, Modifier.size(16.dp),
                    tint = when {
                        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                        primary -> colors.onPrimary
                        else -> colors.primary
                    },
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                    primary -> colors.onPrimary
                    else -> colors.onSurface
                },
            )
        }
    }
}

/** 勾选行：整行可点，代替裸 Checkbox + 文本。 */
@Composable
internal fun SelectRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) colors.primary.copy(alpha = 0.09f) else uiCardColor())
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            title,
            Modifier.weight(1f).padding(end = 10.dp, top = 10.dp, bottom = 10.dp),
            fontSize = 14.sp,
            lineHeight = 19.sp,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun playAllIcon(): ImageVector = Icons.Default.PlayArrow

@Composable
private fun moreIcon(): ImageVector = Icons.Default.MoreVert
