package com.mohammedhammad.okxtracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ═══════════════════════════════
// DATA MODELS
// ═══════════════════════════════
data class Coin(val symbol: String, val qty: Double, val avgBuy: Double)
data class CoinTicker(val price: Double, val change24h: Double)
data class PortfolioItem(
    val coin: Coin,
    val ticker: CoinTicker?,
    val pnlUsd: Double?,
    val pnlPct: Double?,
    val valueUsd: Double?
)

// ═══════════════════════════════
// COLORS
// ═══════════════════════════════
object OKXColors {
    val BG = Color(0xFF070b14)
    val CARD = Color(0xFF111c2e)
    val CARD2 = Color(0xFF162035)
    val ACCENT = Color(0xFF00e5b8)
    val ACCENT2 = Color(0xFF0088ff)
    val PROFIT = Color(0xFF00d68f)
    val LOSS = Color(0xFFff3d5a)
    val GOLD = Color(0xFFfbbf24)
    val TEXT = Color(0xFFe2eeff)
    val TEXT2 = Color(0xFF7a95c0)
    val TEXT3 = Color(0xFF3d5278)
}

// ═══════════════════════════════
// MAIN ACTIVITY
// ═══════════════════════════════
class MainActivity : ComponentActivity() {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val prefs by lazy { getSharedPreferences("okx_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupNotificationChannel()
        setContent {
            WearAppTheme {
                OKXTrackerScreen(
                    http = http,
                    prefs = prefs,
                    vibrate = ::doVibrate,
                    notify = ::sendAlert
                )
            }
        }
    }

    private fun doVibrate() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 100, 60, 150, 60, 300), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 60, 150, 60, 300), -1))
            }
        } catch (_: Exception) {}
    }

    private fun setupNotificationChannel() {
        val ch = NotificationChannel(
            "okx_profit",
            "تنبيه الربح",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "إشعار عند تحقيق هدف الربح" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun sendAlert(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, "okx_profit")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt() and 0xFFFF, n)
    }
}

// ═══════════════════════════════
// WEAR APP THEME
// ═══════════════════════════════
@Composable
fun WearAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = OKXColors.ACCENT,
            primaryVariant = OKXColors.ACCENT2,
            secondary = OKXColors.ACCENT,
            secondaryVariant = OKXColors.ACCENT2,
            error = OKXColors.LOSS,
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onError = Color.White,
            background = OKXColors.BG,
            onBackground = OKXColors.TEXT,
            surface = OKXColors.CARD,
            onSurface = OKXColors.TEXT,
        ),
        content = content
    )
}

// ═══════════════════════════════
// MAIN SCREEN
// ═══════════════════════════════
@Composable
fun OKXTrackerScreen(
    http: OkHttpClient,
    prefs: SharedPreferences,
    vibrate: () -> Unit,
    notify: (String, String) -> Unit
) {
    var screen by remember { mutableStateOf("portfolio") }
    var coins by remember { mutableStateOf(Prefs.loadCoins(prefs)) }
    var egpRate by remember { mutableStateOf(prefs.getFloat("egp", 50f).toDouble()) }
    var alertAt by remember { mutableStateOf(prefs.getFloat("alertAt", 10f).toDouble()) }
    var portfolio by remember { mutableStateOf<List<PortfolioItem>>(emptyList()) }
    var lastUpdate by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var alertFiredLvl by remember { mutableStateOf(prefs.getFloat("alertLvl", 0f).toDouble()) }
    var bannerMsg by remember { mutableStateOf("") }
    var editIdx by remember { mutableStateOf(-1) }

    var fSymbol by remember { mutableStateOf("") }
    var fQty by remember { mutableStateOf("") }
    var fAvg by remember { mutableStateOf("") }
    var fError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (coins.isEmpty()) {
            portfolio = emptyList()
            return
        }
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                val req = Request.Builder()
                    .url("https://www.okx.com/api/v5/market/tickers?instType=SPOT")
                    .build()
                val resp = http.newCall(req).execute()
                val json = resp.body?.string() ?: return@launch
                val arr = JSONObject(json).getJSONArray("data")
                val map = mutableMapOf<String, CoinTicker>()
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    val id = t.getString("instId")
                    val last = t.optDouble("last", 0.0)
                    val open = t.optDouble("sodUtc8", 0.0).let { if (it == 0.0) last else it }
                    val ch = if (open > 0) (last - open) / open * 100 else 0.0
                    map[id] = CoinTicker(last, ch)
                }

                val items = coins.map { c ->
                    val tk = map["${c.symbol}-USDT"]
                    val valu = tk?.let { it.price * c.qty }
                    val cost = c.avgBuy * c.qty
                    val pnl = valu?.let { it - cost }
                    val pct = pnl?.let { if (cost > 0) it / cost * 100 else 0.0 }
                    PortfolioItem(c, tk, pnl, pct, valu)
                }
                val totalPnl = items.sumOf { it.pnlUsd ?: 0.0 }

                withContext(Dispatchers.Main) {
                    portfolio = items
                    isLoading = false
                    lastUpdate = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                    if (totalPnl > 0 && alertAt > 0) {
                        val level = (totalPnl / alertAt).toInt()
                        if (level > 0 && level.toDouble() > alertFiredLvl) {
                            alertFiredLvl = level.toDouble()
                            prefs.edit().putFloat("alertLvl", level.toFloat()).apply()
                            val earned = level * alertAt
                            val earnedEGP = (earned * egpRate).toInt()
                            vibrate()
                            notify("💰 مبروك! ربح جديد 🎉", "ربحت \$${fNum(earned)} ≈ $earnedEGP جنيه 🚀")
                            bannerMsg = "🎉 مبروك! ربحت \$${fNum(earned)}"
                        }
                    }
                    if (totalPnl <= 0) {
                        alertFiredLvl = 0.0
                        prefs.edit().putFloat("alertLvl", 0f).apply()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    lastUpdate = "خطأ في الاتصال"
                }
            }
        }
    }

    LaunchedEffect(coins) {
        refresh()
        while (true) {
            delay(30_000)
            refresh()
        }
    }

    Box(Modifier.fillMaxSize().background(OKXColors.BG)) {
        when (screen) {
            "portfolio" -> {
                val totVal = portfolio.sumOf { it.valueUsd ?: 0.0 }
                val totCost = coins.sumOf { it.avgBuy * it.qty }
                val totPnl = totVal - totCost
                val totPct = if (totCost > 0) totPnl / totCost * 100 else 0.0
                val pColor = if (totPnl >= 0) OKXColors.PROFIT else OKXColors.LOSS

                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 32.dp, bottom = 24.dp, start = 6.dp, end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("₿ OKX Tracker", color = OKXColors.ACCENT, fontSize = 14.sp, fontWeight = FontWeight.Black)
                            Text("م. محمد حماد", color = OKXColors.TEXT3, fontSize = 9.sp)
                        }
                    }
                    if (bannerMsg.isNotEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().background(Color(0x3300d68f), RoundedCornerShape(10.dp)).clickable { bannerMsg = "" }.padding(10.dp), contentAlignment = Alignment.Center) {
                                Text(bannerMsg, color = OKXColors.PROFIT, fontSize = 12.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (coins.isNotEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().background(if (totPnl >= 0) Color(0x2200d68f) else Color(0x22ff3d5a), RoundedCornerShape(14.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${if (totPnl >= 0) "+" else ""}${fNum(totPnl)}\$", color = pColor, fontSize = 26.sp, fontWeight = FontWeight.Black)
                                    Text("${if (totPnl >= 0) "+" else ""}${fNum(totPnl * egpRate)} جنيه", color = pColor.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(3.dp))
                                    Text("${if (totPct >= 0) "▲" else "▼"} ${String.format("%.2f", kotlin.math.abs(totPct))}%", color = pColor, fontSize = 12.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text("القيمة: \$${fNum(totVal)}", color = OKXColors.TEXT2, fontSize = 10.sp)
                                    Text("التكلفة: \$${fNum(totCost)}", color = OKXColors.TEXT3, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                    items(portfolio) { item ->
                        CoinRow(item = item, egpRate = egpRate)
                    }
                    if (coins.isEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📭", fontSize = 32.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("لا توجد عملات
اضغط ⚙️ للإضافة", color = OKXColors.TEXT3, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (isLoading) "⏳ جاري التحديث..." else if (lastUpdate.isNotEmpty()) "🕐 $lastUpdate" else "", color = OKXColors.TEXT3, fontSize = 9.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CompactButton(onClick = { refresh() }, colors = ButtonDefaults.buttonColors(backgroundColor = OKXColors.CARD2)) { Text("🔄", fontSize = 14.sp) }
                                CompactButton(onClick = { screen = "settings" }, colors = ButtonDefaults.buttonColors(backgroundColor = OKXColors.CARD2)) { Text("⚙️", fontSize = 14.sp) }
                            }
                        }
                    }
                }
            }
            "settings" -> {
                if (editIdx >= 0 && editIdx < coins.size) {
                    val c = coins[editIdx]
                    if (fQty.isEmpty() && fAvg.isEmpty()) {
                        fQty = c.qty.toString()
                        fAvg = c.avgBuy.toString()
                    }
                    EditCoinScreen(
                        symbol = c.symbol, fQty = fQty, onQtyChange = { fQty = it }, fAvg = fAvg, onAvgChange = { fAvg = it }, error = fError,
                        onSave = {
                            val q = fQty.toDoubleOrNull(); val a = fAvg.toDoubleOrNull()
                            if (q == null || q <= 0) { fError = "الكمية غير صحيحة"; return@EditCoinScreen }
                            if (a == null || a <= 0) { fError = "السعر غير صحيح"; return@EditCoinScreen }
                            val updated = coins.toMutableList(); updated[editIdx] = Coin(c.symbol, q, a)
                            coins = updated; Prefs.saveCoins(prefs, coins); fQty = ""; fAvg = ""; fError = ""; editIdx = -1; refresh()
                        },
                        onDelete = {
                            val updated = coins.toMutableList(); updated.removeAt(editIdx)
                            coins = updated; Prefs.saveCoins(prefs, coins); fQty = ""; fAvg = ""; fError = ""; editIdx = -1; refresh()
                        },
                        onCancel = { fQty = ""; fAvg = ""; fError = ""; editIdx = -1 }
                    )
                } else {
                    SettingsScreen(
                        coins = coins, egpRate = egpRate, alertAt = alertAt, fSymbol = fSymbol, onSymbolChange = { fSymbol = it.uppercase().trim() },
                        fQty = fQty, onQtyChange = { fQty = it }, fAvg = fAvg, onAvgChange = { fAvg = it }, fError = fError,
                        onEgpChange = { v -> egpRate = v; prefs.edit().putFloat("egp", v.toFloat()).apply() },
                        onAlertChange = { v -> alertAt = v; prefs.edit().putFloat("alertAt", v.toFloat()).apply(); alertFiredLvl = 0.0; prefs.edit().putFloat("alertLvl", 0f).apply() },
                        onAdd = {
                            val sym = fSymbol.trim().uppercase(); val q = fQty.toDoubleOrNull(); val a = fAvg.toDoubleOrNull()
                            when {
                                sym.isEmpty() -> fError = "أدخل رمز العملة"
                                q == null || q <= 0 -> fError = "الكمية غير صحيحة"
                                a == null || a <= 0 -> fError = "السعر غير صحيح"
                                else -> {
                                    val updated = coins.toMutableList()
                                    val ex = updated.indexOfFirst { it.symbol == sym }
                                    if (ex >= 0) updated[ex] = Coin(sym, q, a) else updated.add(Coin(sym, q, a))
                                    coins = updated; Prefs.saveCoins(prefs, coins); fSymbol = ""; fQty = ""; fAvg = ""; fError = ""; refresh()
                                }
                            }
                        },
                        onEdit = { idx -> editIdx = idx; fQty = coins[idx].qty.toString(); fAvg = coins[idx].avgBuy.toString() },
                        onDelete = { idx -> val updated = coins.toMutableList(); updated.removeAt(idx); coins = updated; Prefs.saveCoins(prefs, coins); refresh() },
                        onBack = { screen = "portfolio" }
                    )
                }
            }
        }
    }
}

@Composable
fun CoinRow(item: PortfolioItem, egpRate: Double) {
    val pnl = item.pnlUsd ?: 0.0
    val pColor = if (pnl >= 0) OKXColors.PROFIT else OKXColors.LOSS
    val bgColor = if (pnl >= 0) Color(0x1400d68f) else Color(0x14ff3d5a)
    val ch24 = item.ticker?.change24h ?: 0.0
    Box(Modifier.fillMaxWidth().background(OKXColors.CARD, RoundedCornerShape(10.dp))) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(pColor, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)))
        Column(Modifier.padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(item.coin.symbol, color = OKXColors.ACCENT, fontSize = 13.sp, fontWeight = FontWeight.Black)
                Text(if (item.ticker != null) "\$${String.format("%.4f", item.ticker.price)}" else "---", color = if (ch24 >= 0) OKXColors.PROFIT else OKXColors.LOSS, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ك: ${item.coin.qty}", color = OKXColors.TEXT3, fontSize = 9.sp)
                Text("${if (ch24 >= 0) "▲" else "▼"} ${String.format("%.2f", kotlin.math.abs(ch24))}%", color = if (ch24 >= 0) OKXColors.PROFIT else OKXColors.LOSS, fontSize = 9.sp)
            }
            Box(Modifier.fillMaxWidth().background(bgColor, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${if (pnl >= 0) "+" else ""}${fNum(pnl)}\$ / ${fNum(pnl * egpRate)} ج", color = pColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${if ((item.pnlPct ?: 0.0) >= 0) "+" else ""}${String.format("%.2f", item.pnlPct ?: 0.0)}%", color = pColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    coins: List<Coin>, egpRate: Double, alertAt: Double, fSymbol: String, onSymbolChange: (String) -> Unit,
    fQty: String, onQtyChange: (String) -> Unit, fAvg: String, onAvgChange: (String) -> Unit, fError: String,
    onEgpChange: (Double) -> Unit, onAlertChange: (Double) -> Unit, onAdd: () -> Unit, onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit, onBack: () -> Unit
) {
    var egpInput by remember { mutableStateOf(egpRate.toString()) }
    var alertInput by remember { mutableStateOf(alertAt.toString()) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 24.dp, start = 6.dp, end = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("⚙️ الإعدادات", color = OKXColors.ACCENT, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        item {
            SettingCard(label = "💱 سعر الدولار (جنيه)") {
                BasicTextField(
                    value = egpInput,
                    onValueChange = { egpInput = it; it.toDoubleOrNull()?.let { v -> if (v > 0) onEgpChange(v) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().background(OKXColors.CARD2, RoundedCornerShape(4.dp)).padding(8.dp),
                    textStyle = TextStyle(color = OKXColors.GOLD, fontSize = 12.sp)
                )
            }
        }
        item {
            SettingCard(label = "🔔 تنبيه كل (\$)") {
                BasicTextField(
                    value = alertInput,
                    onValueChange = { alertInput = it; it.toDoubleOrNull()?.let { v -> if (v > 0) onAlertChange(v) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().background(OKXColors.CARD2, RoundedCornerShape(4.dp)).padding(8.dp),
                    textStyle = TextStyle(color = OKXColors.ACCENT, fontSize = 12.sp)
                )
                Text("صوت + اهتزاز + إشعار عند كل مضاعف", color = OKXColors.TEXT3, fontSize = 9.sp)
            }
        }
        item { Text("🪙 عملاتي (${coins.size})", color = OKXColors.ACCENT, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        itemsIndexed(coins) { idx, c ->
            Row(Modifier.fillMaxWidth().background(OKXColors.CARD, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.symbol, color = OKXColors.ACCENT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("ك: ${c.qty} | شراء: \$${c.avgBuy}", color = OKXColors.TEXT3, fontSize = 9.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(28.dp).background(Color(0x220088ff), RoundedCornerShape(6.dp)).clickable { onEdit(idx) }, contentAlignment = Alignment.Center) { Text("✏️", fontSize = 12.sp) }
                    Box(Modifier.size(28.dp).background(Color(0x22ff3d5a), RoundedCornerShape(6.dp)).clickable { onDelete(idx) }, contentAlignment = Alignment.Center) { Text("🗑️", fontSize = 12.sp) }
                }
            }
        }
        item { Text("➕ إضافة عملة جديدة", color = OKXColors.ACCENT, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        item {
            Column(Modifier.fillMaxWidth().background(OKXColors.CARD2, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicTextField(value = fSymbol, onValueChange = onSymbolChange, modifier = Modifier.fillMaxWidth().background(OKXColors.CARD, RoundedCornerShape(4.dp)).padding(8.dp), textStyle = TextStyle(color = OKXColors.TEXT))
                BasicTextField(value = fQty, onValueChange = onQtyChange, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().background(OKXColors.CARD, RoundedCornerShape(4.dp)).padding(8.dp), textStyle = TextStyle(color = OKXColors.TEXT))
                BasicTextField(value = fAvg, onValueChange = onAvgChange, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().background(OKXColors.CARD, RoundedCornerShape(4.dp)).padding(8.dp), textStyle = TextStyle(color = OKXColors.TEXT))
                if (fError.isNotEmpty()) { Text(fError, color = OKXColors.LOSS, fontSize = 10.sp) }
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(backgroundColor = OKXColors.ACCENT)) { Text("➕ إضافة", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black) }
            }
        }
        item { Button(onClick = onBack, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(backgroundColor = OKXColors.CARD)) { Text("◀ رجوع", color = OKXColors.TEXT, fontSize = 12.sp) } }
    }
}

@Composable
fun EditCoinScreen(symbol: String, fQty: String, onQtyChange: (String) -> Unit, fAvg: String, onAvgChange: (String) -> Unit, error: String, onSave: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 32.dp, bottom = 24.dp, start = 6.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("✏️ تعديل $symbol", color = OKXColors.ACCENT, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        item {
            Column(Modifier.fillMaxWidth().background(OKXColors.CARD, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicTextField(value = fQty, onValueChange = onQtyChange, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().background(OKXColors.CARD2, RoundedCornerShape(4.dp)).padding(8.dp), textStyle = TextStyle(color = OKXColors.TEXT))
                BasicTextField(value = fAvg, onValueChange = onAvgChange, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().background(OKXColors.CARD2, RoundedCornerShape(4.dp)).padding(8.dp), textStyle = TextStyle(color = OKXColors.TEXT))
                if (error.isNotEmpty()) { Text(error, color = OKXColors.LOSS, fontSize = 10.sp) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onSave, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(backgroundColor = OKXColors.ACCENT)) { Text("💾 حفظ", color = Color.Black, fontSize = 12.sp) }
                Button(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(backgroundColor = OKXColors.LOSS)) { Text("🗑️ حذف", color = Color.White, fontSize = 12.sp) }
            }
        }
        item { Button(onClick = onCancel, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(backgroundColor = OKXColors.CARD)) { Text("✕ إلغاء", color = OKXColors.TEXT, fontSize = 12.sp) } }
    }
}

@Composable
fun SettingCard(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(OKXColors.CARD, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = OKXColors.TEXT2, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

fun fNum(n: Double): String {
    val a = kotlin.math.abs(n)
    return when {
        a >= 1_000_000 -> String.format("%.2fM", n / 1_000_000)
        a >= 1_000 -> String.format("%.1fK", n / 1_000)
        a >= 100 -> String.format("%.1f", n)
        a >= 1 -> String.format("%.2f", n)
        else -> String.format("%.4f", n)
    }
}

object Prefs {
    fun loadCoins(prefs: SharedPreferences): List<Coin> = try {
        val json = prefs.getString("coins", "[]") ?: "[]"
        val arr = JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Coin(o.getString("symbol"), o.getDouble("qty"), o.getDouble("avgBuy"))
        }
    } catch (_: Exception) { emptyList() }

    fun saveCoins(prefs: SharedPreferences, coins: List<Coin>) {
        val arr = JSONArray()
        coins.forEach { arr.put(JSONObject().apply { put("symbol", it.symbol); put("qty", it.qty); put("avgBuy", it.avgBuy) }) }
        prefs.edit().putString("coins", arr.toString()).apply()
    }
}
