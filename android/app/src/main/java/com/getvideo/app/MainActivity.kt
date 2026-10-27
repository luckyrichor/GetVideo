package com.getvideo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.getvideo.app.ui.screen.ExtractScreen
import com.getvideo.app.ui.screen.LibraryScreenV2
import com.getvideo.app.ui.screen.MyScreen
import com.getvideo.app.ui.screen.QueueBar
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
        setContent {
            GetVideoTheme {
                // 从 B站/抖音「分享到 GetVideo」进来时直接落在提取页
                var shared by remember { mutableStateOf(sharedText(intent)) }
                var tab by remember { mutableStateOf(if (shared != null) Tab.EXTRACT else Tab.LIBRARY) }
                var openCategories by remember { mutableStateOf(false) }

                DisposableEffect(Unit) {
                    val listener = { newIntent: Intent ->
                        sharedText(newIntent)?.let { shared = it; tab = Tab.EXTRACT }
                        Unit
                    }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                Scaffold(
                    bottomBar = {
                        Column {
                            if (tab == Tab.LIBRARY) QueueBar()
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                            NavigationBar {
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
                                Tab.LIBRARY -> LibraryScreenV2(openCategories)
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

    private fun sharedText(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        } else null
}
