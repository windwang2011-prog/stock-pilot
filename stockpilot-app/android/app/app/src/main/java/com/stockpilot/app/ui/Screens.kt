package com.stockpilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stockpilot.app.core.Report
import com.stockpilot.app.core.ReportStock
import com.stockpilot.app.core.StockRef
import com.stockpilot.app.core.WatchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

private val RED = Color(0xFFC0392B)
private val GREEN = Color(0xFF15803D)
private val GRAY = Color(0xFF777777)
private val LIGHT = Color(0xFFF5F6F8)

private fun pctColor(v: Double): Color = if (v >= 0) RED else GREEN

private fun pctText(v: Double): String = (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f", v) + "%"

private fun actionColor(a: String): Color = when (a) {
    "买入" -> RED
    "卖出" -> GREEN
    else -> Color(0xFF444444)
}

@Composable
private fun Head(st: AppState) {
    Text("StockPilot", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("市场阶段：" + st.phaseLabel(), fontSize = 12.sp, color = GRAY)
        Spacer(Modifier.width(10.dp))
        Text("盯盘：" + (if (st.watchEnabled) "开启" else "关闭"), fontSize = 12.sp,
            color = if (st.watchEnabled) RED else GRAY)
    }
    if (st.status.isNotEmpty()) Text(st.status, fontSize = 11.sp, color = GRAY)
    val err = st.error
    if (err != null) Text(err, fontSize = 11.sp, color = RED)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun StockRow(
    r: WatchResult,
    actionText: String? = null,
    actionTint: Color = RED,
    onAction: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.stock.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(r.stock.code, fontSize = 11.sp, color = GRAY)
            Spacer(Modifier.weight(1f))
            Text(String.format(Locale.US, "%.2f", r.price), fontSize = 15.sp,
                fontWeight = FontWeight.Bold, color = pctColor(r.changePct))
            Spacer(Modifier.width(8.dp))
            Text(pctText(r.changePct), fontSize = 12.sp, color = pctColor(r.changePct))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("评分 " + r.signal.score, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(r.signal.action, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = actionColor(r.signal.action))
            Spacer(Modifier.width(10.dp))
            Text("主力 " + Report.fmtMoney(r.mainNet), fontSize = 11.sp, color = GRAY)
            Spacer(Modifier.weight(1f))
            if (actionText != null) {
                Text(
                    actionText,
                    fontSize = 11.sp,
                    color = actionTint,
                    modifier = Modifier.clickable(enabled = onAction != null) { onAction?.invoke() }
                )
            }
        }
        if (r.signal.sessionSummary.isNotEmpty()) {
            Text("依据：" + r.signal.sessionSummary, fontSize = 11.sp, color = GRAY,
                modifier = Modifier.padding(top = 3.dp))
        }
        val tech = r.signal.reasons.filter { !it.startsWith("【") }.take(2).joinToString("；")
        if (tech.isNotEmpty()) {
            Text("技术面：" + tech, fontSize = 11.sp, color = GRAY)
        }
    }
}

// ==================== 自选 ====================
@Composable
fun WatchScreen(st: AppState, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Head(st)

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = st.searchKw,
                onValueChange = { st.searchKw = it },
                label = { Text("代码 / 名称 / 拼音首字母", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { st.search() } }) { Text("搜索") }
        }

        if (st.searchResults.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(LIGHT).padding(8.dp)) {
                for (r in st.searchResults.take(8)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { st.addWatch(r) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(r.name, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(r.code, fontSize = 11.sp, color = GRAY)
                        Spacer(Modifier.weight(1f))
                        Text("＋ 添加", fontSize = 12.sp, color = RED)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scope.launch { st.refreshWatch() } }) {
                Text(if (st.loading) "扫描中…" else "刷新行情")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { scope.launch { st.buildReportNow() } }) { Text("生成报告") }
        }
        Spacer(Modifier.height(8.dp))

        if (st.results.isEmpty()) {
            Text("暂无数据。添加自选股后点「刷新行情」。", fontSize = 12.sp, color = GRAY)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(st.results, key = { it.stock.secid }) { r ->
                    StockRow(r, "删除", RED) { st.removeWatch(r.stock.secid) }
                }
            }
        }
    }
}

// ==================== 推荐 ====================
@Composable
fun RecommendScreen(st: AppState, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Head(st)
        Text("从当日最热门的 3 个板块中优选个股，结合日线技术面与盘前/盘中/盘后资金特征综合评分。",
            fontSize = 11.sp, color = GRAY)
        Spacer(Modifier.height(8.dp))

        Button(onClick = { scope.launch { st.refreshRecommend() } }) {
            Text(if (st.recoLoading) "分析中…" else "生成推荐")
        }
        Spacer(Modifier.height(8.dp))

        if (st.sectors.isNotEmpty()) {
            Text("热点板块：" + st.sectors.joinToString("、") { it.name + " " + pctText(it.changePct) },
                fontSize = 11.sp, color = GRAY)
            Spacer(Modifier.height(6.dp))
        }

        if (st.recos.isEmpty()) {
            Text("点击「生成推荐」开始分析。", fontSize = 12.sp, color = GRAY)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(st.recos, key = { it.stock.secid }) { r ->
                    if (st.isWatched(r.stock.secid)) {
                        StockRow(r, "已关注", GRAY, null)
                    } else {
                        StockRow(r, "＋关注", RED) { st.addWatch(r.stock) }
                    }
                }
            }
        }
    }
}

// ==================== 美股盘面 ====================
@Composable
fun UsScreen(st: AppState, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Head(st)
        Text("美股隔夜走势 → A股次日参考。含大盘指数、行业板块涨跌榜、龙头个股与中概股。",
            fontSize = 11.sp, color = GRAY)
        Spacer(Modifier.height(8.dp))

        Button(onClick = { scope.launch { st.refreshUs() } }) {
            Text(if (st.usLoading) "拉取中…" else "刷新美股盘面")
        }
        Spacer(Modifier.height(8.dp))

        if (st.usReports.isNotEmpty()) {
            Text("历史报告（点选查看）：", fontSize = 12.sp, color = GRAY)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for (r in st.usReports.take(4)) {
                    Text(
                        r.first,
                        fontSize = 11.sp,
                        color = RED,
                        modifier = Modifier.padding(end = 10.dp).clickable { st.loadUsReport(r.first) }
                    )
                }
            }
        } else {
            Text("交易日北京时间 05:30 后会自动生成并推送通知。", fontSize = 12.sp, color = GRAY)
        }

        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                if (st.usReport.isEmpty()) "报告内容将显示在这里。" else st.usReport,
                fontSize = 12.sp
            )
        }
    }
}

// ==================== 报告 ====================
@Composable
fun ReportScreen(st: AppState, scope: CoroutineScope) {
    // 进入报告页时，若尚未载入个股清单，则自动载入最近一份报告（含后台服务生成的那份）
    LaunchedEffect(Unit) {
        if (st.reports.isNotEmpty() && st.reportText.isEmpty()) {
            st.loadReport(st.reports.first().first)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Head(st)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scope.launch { st.buildReportNow() } }) { Text("生成今日报告") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { st.reports = st.store.loadReports() }) { Text("刷新列表") }
        }
        Spacer(Modifier.height(8.dp))

        if (st.reports.isNotEmpty()) {
            Text("历史报告（点选查看）：", fontSize = 12.sp, color = GRAY)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for (r in st.reports.take(4)) {
                    Text(
                        r.first,
                        fontSize = 11.sp,
                        color = RED,
                        modifier = Modifier.padding(end = 10.dp).clickable { st.loadReport(r.first) }
                    )
                }
            }
        } else {
            Text("暂无历史报告。交易日收盘后会自动生成并推送通知。", fontSize = 12.sp, color = GRAY)
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "报告内容：大盘 → 热点板块 → 龙头个股 → 推荐个股（信号连续性排行）→ 我的关注 → 投资建议",
            fontSize = 11.sp, color = GRAY
        )
        if (st.reportStocks.isNotEmpty()) {
            val left = st.reportStocks.count { !st.isWatched(it.secid) }
            Text(
                "报告涉及个股 " + st.reportStocks.size + " 只，其中 " + left + " 只未关注（点「＋关注」加入自选）",
                fontSize = 11.sp, color = RED
            )
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            for (row in st.reportStocks) {
                ReportStockRow(
                    row = row,
                    watched = st.isWatched(row.secid)
                ) {
                    st.addWatchFromReport(StockRef(row.secid, row.code, row.name))
                }
            }
            if (st.reportStocks.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("—— 报告正文 ——", fontSize = 11.sp, color = GRAY)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                if (st.reportText.isEmpty()) "报告内容将显示在这里。交易日 15:30 后会自动生成并推送通知。" else st.reportText,
                fontSize = 12.sp
            )
        }
    }
}

/** 报告中的个股行（可关注） */
@Composable
private fun ReportStockRow(row: ReportStock, watched: Boolean, onAdd: () -> Unit) {
    val px = row.price
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(row.code, fontSize = 11.sp, color = GRAY)
            Spacer(Modifier.weight(1f))
            if (px != null) {
                Text(
                    String.format(Locale.US, "%.2f", px),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = pctColor(row.changePct)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(pctText(row.changePct), fontSize = 12.sp, color = pctColor(row.changePct))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.group, fontSize = 10.sp, color = GRAY)
            Spacer(Modifier.width(8.dp))
            Text("评分 " + row.score, fontSize = 11.sp, color = GRAY)
            Spacer(Modifier.width(8.dp))
            Text(row.action, fontSize = 11.sp, color = actionColor(row.action))
            Spacer(Modifier.weight(1f))
            if (watched) {
                Text("已关注", fontSize = 11.sp, color = GRAY)
            } else {
                Text(
                    "＋关注",
                    fontSize = 12.sp,
                    color = RED,
                    modifier = Modifier.clickable { onAdd() }
                )
            }
        }
    }
}

// ==================== 设置 ====================
@Composable
fun SettingsScreen(st: AppState, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Head(st)

        Text("盯盘服务", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("交易日 9:15–15:30 在后台持续运行；收盘后 15:30 自动生成报告。", fontSize = 11.sp, color = GRAY)
        Spacer(Modifier.height(6.dp))
        Row {
            Button(onClick = { st.startService() }) { Text("启动盯盘") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { st.stopService() }) { Text("停止盯盘") }
        }
        Spacer(Modifier.height(14.dp))

        Text("扫描间隔", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (m in listOf(3, 5, 10, 15)) {
                val sel = st.scanInterval == m
                Text(
                    m.toString() + " 分钟",
                    fontSize = 12.sp,
                    color = if (sel) Color.White else Color(0xFF444444),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(if (sel) RED else LIGHT)
                        .clickable { st.setInterval(m) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("信号通知", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Switch(checked = st.notifyEnabled, onCheckedChange = { st.setNotify(it) })
        }
        Spacer(Modifier.height(14.dp))

        Text("系统授权（重要）", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("国产系统需加入电池优化白名单，否则后台可能被清理。", fontSize = 11.sp, color = GRAY)
        Spacer(Modifier.height(6.dp))
        Row {
            OutlinedButton(onClick = { st.openBatterySettings() }) { Text("电池白名单") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { st.openAppNotificationSettings() }) { Text("通知设置") }
        }
        Spacer(Modifier.height(18.dp))

        val sc = remember { SelfCheck.run() }
        Text("引擎自检", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "策略引擎自检：" + sc.first + "/" + sc.second + " 通过" + (if (sc.first == sc.second) " ✅" else " ❌"),
            fontSize = 12.sp,
            color = if (sc.first == sc.second) RED else GREEN
        )
        Spacer(Modifier.height(10.dp))

        Text("数据来源：东方财富公开接口（腾讯备用）。", fontSize = 11.sp, color = GRAY)
        Text("策略为技术指标分析，仅供参考，不构成投资建议。", fontSize = 11.sp, color = GRAY)
        Spacer(Modifier.height(20.dp))
    }
}
