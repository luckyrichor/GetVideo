package com.getvideo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.AutoAwesome
import com.getvideo.app.ui.screen.RecreationScreen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.getvideo.app.ui.screen.ExtractScreen
import com.getvideo.app.ui.screen.LibraryScreenV2
import com.getvideo.app.ui.screen.MyScreen
import com.getvideo.app.ui.screen.QueueBar
import com.getvideo.app.ui.components.BackgroundLayer
import com.getvideo.app.ui.components.LocalBackgroundEnabled
import com.getvideo.app.ui.theme.GetVideoTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    LIBRARY("视频库", Icons.Default.LibraryMusic),
    EXTRACT("提取", Icons.Default.Link),
    AI("AI 重建", Icons.Default.AutoAwesome),
    MY("我的", Icons.Default.Person),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.getvideo.app.data.repo.BackgroundStore.init(this)
        setContent {
            GetVideoTheme {
                val background by com.getvideo.app.data.repo.BackgroundStore.state.collectAsState()
                // 从 B站/抖音「分享到 GetVideo」进来时直接落在提取页
                var shared by remember { mutableStateOf(sharedText(intent)) }
                var tab by remember { mutableStateOf(if (shared != null) Tab.EXTRACT else Tab.LIBRARY) }
                var openCategories by remember { mutableStateOf(false) }
                // 只看背景：把列表和底栏都收起来，只留一张壁纸。
                var bgOnly by remember { mutableStateOf(false) }
                BackHandler(enabled = bgOnly) { bgOnly = false }

                DisposableEffect(Unit) {
                    val listener = { newIntent: Intent ->
                        sharedText(newIntent)?.let { shared = it; tab = Tab.EXTRACT }
                        Unit
                    }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                // 背景图铺在最底层，页面自己让出底色，卡片保持不透明以保证可读性。
                CompositionLocalProvider(LocalBackgroundEnabled provides background.enabled) {
                    Box(Modifier.fillMaxSize()) {
                        BackgroundLayer()
                        if (bgOnly) {
                            BackgroundOnlyOverlay(
                                hasBackground = background.enabled,
                                onExit = { bgOnly = false },
                            )
                        } else {
                        Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        Column {
                            if (tab == Tab.LIBRARY) QueueBar()
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                            NavigationBar(
                                containerColor = if (background.enabled) {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                                } else {
                                    NavigationBarDefaults.containerColor
                                },
                            ) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t; if (t == Tab.LIBRARY) openCategories = false },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label) },
                                )
                            }
                            }
                        }
                    }
                ) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding), color = Color.Transparent) {
                        Box(Modifier.fillMaxSize()) {
                            when (tab) {
                                Tab.LIBRARY -> LibraryScreenV2(openCategories, onBackgroundOnly = { bgOnly = true })
                                Tab.EXTRACT -> ExtractScreen(prefill = shared, onSharedConsumed = { shared = null })
                                Tab.AI -> RecreationScreen()
                                Tab.MY -> MyScreen()
                            }
                            // 底部渐隐，避免内容被导航栏硬切；不接收点击，不影响任何按钮。
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                                MaterialTheme.colorScheme.surface,
                                            )
                                        )
                                    )
                            )
                        }
                    }
                }
                        }
                    }
                }
            }
        }
    }

    private fun sharedText(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        } else null
}

/**
 * 只看背景模式：除了一张壁纸和一个退出按钮，其它一律不画。
 * 按钮用不透明卡片垫底，任何图上都看得清、点得到。
 */
@Composable
private fun BackgroundOnlyOverlay(hasBackground: Boolean, onExit: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!hasBackground) {
            Text(
                "还没有设置背景图 · 可在「我的 → 外观」里选一张",
                Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            shadowElevation = 3.dp,
        ) {
            Row(
                Modifier.clickable(onClick = onExit).padding(horizontal = 20.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text("退出背景", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
