package com.getvideo.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 纯展示层的基础样式常量与容器，集中管理页面视觉，不含任何业务逻辑。
 */

@Composable
fun uiIsDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** 页面底色：启用了自定义背景时让出这一层，让底图透上来。 */
@Composable
fun uiPageBackground(): Color =
    if (LocalBackgroundEnabled.current) Color.Transparent
    else if (uiIsDark()) Color(0xFF121016) else Color(0xFFF5F3FA)

/**
 * 不透明页面底色：即使启用了背景图也挡住底图。
 * 文字密集的页面（如助手页）需要它，否则字会叠在背景图上看不清。
 */
@Composable
fun uiSolidPage(): Color = if (uiIsDark()) Color(0xFF121016) else Color(0xFFF5F3FA)

/** 当前卡片不透明度，外观页滑杆可调。 */
@Composable
fun uiCardAlpha(): Float = LocalCardAlpha.current.coerceIn(0.12f, 1f)

@Composable
fun uiCardColor(): Color =
    if (!LocalBackgroundEnabled.current) {
        if (uiIsDark()) Color(0xFF1D1A24) else Color.White
    } else {
        // 有背景图时卡片改成半透明，让底图能透上来；具体多透由外观页滑杆决定。
        if (uiIsDark()) {
            Color(0xFF1D1A24).copy(alpha = (uiCardAlpha() + 0.16f).coerceAtMost(0.97f))
        } else {
            Color.White.copy(alpha = uiCardAlpha())
        }
    }

@Composable
fun uiHairline(): Color =
    if (!LocalBackgroundEnabled.current) {
        if (uiIsDark()) Color(0xFF312C3B) else Color(0xFFEAE6F2)
    } else {
        if (uiIsDark()) {
            Color(0xFF312C3B).copy(alpha = (uiCardAlpha() + 0.10f).coerceAtMost(0.9f))
        } else {
            Color.White.copy(alpha = (uiCardAlpha() + 0.18f).coerceAtMost(0.95f))
        }
    }

@Composable
fun uiMuted(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)

/** 描边按钮的配色：给按钮一层实底，压在背景图上也能看清字。 */
@Composable
fun solidButtonColors(): androidx.compose.material3.ButtonColors = ButtonDefaults.outlinedButtonColors(
    containerColor = uiCardColor(),
    contentColor = MaterialTheme.colorScheme.onSurface,
)

/** 绿色用于“已建立”状态，红色沿用主题 error。 */
@Composable
fun uiSuccess(): Color = if (uiIsDark()) Color(0xFF7BD8A6) else Color(0xFF1C7F4B)

@Composable
fun uiSuccessContainer(): Color =
    if (uiIsDark()) Color(0xFF17331F) else Color(0xFFE7F5EC)

@Composable
fun uiAccentContainer(): Color =
    if (!LocalBackgroundEnabled.current) {
        if (uiIsDark()) Color(0xFF2A2340) else Color(0xFFF0EBFB)
    } else {
        if (uiIsDark()) {
            Color(0xFF2A2340).copy(alpha = (uiCardAlpha() + 0.06f).coerceAtMost(0.9f))
        } else {
            Color(0xFFF0EBFB).copy(alpha = (uiCardAlpha() - 0.06f).coerceAtLeast(0.2f))
        }
    }

/** 页面级背景 + 内容内边距。 */
@Composable
fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .background(uiPageBackground())
            .padding(horizontal = 16.dp),
        content = content,
    )
}

/** 卡片容器：白底、大圆角、细边，替代原先裸露的描边按钮。 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(vertical = 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = uiCardColor(),
        border = BorderStroke(1.dp, uiHairline()),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** 分组小标题，弱化处理，避免和内容抢视觉重量。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 6.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        color = uiMuted(),
    )
}

/** 设置项行：左图标 + 标题/副标题 + 右侧说明与箭头。 */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    tone: Color? = null,
    showChevron: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(uiAccentContainer()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(19.dp), tint = tone ?: MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = uiMuted())
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = uiMuted())
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }
    }
}

/** 卡片内的分隔线，左侧留出图标宽度，视觉更贴合现代设置页。 */
@Composable
fun RowDivider(startIndent: Dp = 60.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent, end = 14.dp)
            .height(1.dp)
            .background(uiHairline()),
    )
}

/** 状态胶囊：用于“已登录 / 已绑定 / 未配置”。 */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = uiSuccessContainer(),
    content: Color = uiSuccess(),
    dot: Boolean = true,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(content))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, fontWeight = FontWeight.Medium)
    }
}

/** 圆形徽标：用于头像与平台标识。 */
@Composable
fun RoundBadge(
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    background: Brush? = null,
    background2: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = CircleShape
    Box(
        modifier
            .size(size)
            .clip(shape)
            .then(if (background != null) Modifier.background(background, shape) else Modifier.background(background2 ?: uiAccentContainer(), shape)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 顶部渐变色块，用于账号身份卡。 */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dark = uiIsDark()
    val start = if (dark) Color(0xFF2B2340) else Color(0xFFEDE6FF)
    val end = if (dark) Color(0xFF1E1A2B) else Color(0xFFF7F2FF)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, uiHairline()),
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(start, end)))
                .padding(18.dp),
            content = content,
        )
    }
}

/** 内联提示条，替代原先直接甩在页面上的裸文本。 */
@Composable
fun InlineBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tone.copy(alpha = if (uiIsDark()) 0.16f else 0.08f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = tone)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = tone, modifier = Modifier.weight(1f))
        }
    }
}

/** 说明性小字，行高放宽提升可读性。 */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        lineHeight = 18.sp,
        color = uiMuted(),
    )
}

/** 二级页顶部返回栏。 */
@Composable
fun SubPageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(uiCardColor())
                .border(1.dp, uiHairline(), CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.ArrowBack,
                "返回",
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** 二级页外壳：固定返回栏 + 可滚动内容区，避免内容溢出到底部导航栏下。 */
@Composable
fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(uiPageBackground())) {
        Box(Modifier.padding(horizontal = 16.dp)) { SubPageHeader(title, onBack) }
        Box(Modifier.weight(1f).padding(horizontal = 16.dp), content = content)
    }
}

/** 平台徽标底色。 */
object PlatformColors {
    val bilibili = Color(0xFFFB7299)
    val douyin = Color(0xFF1C1B20)
}
