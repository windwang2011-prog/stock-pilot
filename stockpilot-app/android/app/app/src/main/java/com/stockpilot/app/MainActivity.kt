package com.stockpilot.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stockpilot.app.ui.AppState
import com.stockpilot.app.ui.RecommendScreen
import com.stockpilot.app.ui.ReportScreen
import com.stockpilot.app.ui.SettingsScreen
import com.stockpilot.app.ui.WatchScreen
import com.stockpilot.app.service.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * StockPilot 主界面
 *  Tab1 自选：添加/删除自选，实时行情 + 评分 + 动作 + 依据
 *  Tab2 推荐：热门板块优选个股（盘前/盘中/盘后综合）
 *  Tab3 报告：盘后汇总报告（自动生成 + 历史查阅）
 *  Tab4 设置：盯盘服务开关、扫描间隔、通知、系统授权
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        askNotificationPermission()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val st = remember { AppState(applicationContext) }
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(Unit) {
                        if (st.watchlist.isNotEmpty()) st.refreshWatch()
                    }
                    AppScaffold(st, scope)
                }
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}

@Composable
private fun AppScaffold(st: AppState, scope: CoroutineScope) {
    val tabs = listOf("自选", "推荐", "报告", "设置")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            for (i in tabs.indices) {
                val sel = st.tab == i
                Text(
                    tabs[i],
                    color = if (sel) Color.White else Color(0xFF444444),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(if (sel) Color(0xFFC0392B) else Color(0xFFF0F1F3))
                        .clickable { st.tab = i }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
            Spacer(Modifier.width(0.dp))
        }
        when (st.tab) {
            0 -> WatchScreen(st, scope)
            1 -> RecommendScreen(st, scope)
            2 -> ReportScreen(st, scope)
            else -> SettingsScreen(st, scope)
        }
    }
}
