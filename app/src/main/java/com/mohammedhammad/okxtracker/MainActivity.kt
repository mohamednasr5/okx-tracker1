package com.mohammedhammad.okxtracker

import android.app.*
import android.content.*
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.core.app.NotificationCompat
import androidx.wear.compose.foundation.lazy.*
import androidx.wear.compose.material.*
import kotlinx.coroutines.*
import okhttp3.*
import org.json.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ================= COLORS =================
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

// ================= DATA =================
data class Coin(val symbol: String, val qty: Double, val avgBuy: Double)

// ================= MAIN =================
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colors = Colors(
                    primary = OKXColors.ACCENT,
                    primaryVariant = OKXColors.ACCENT2,
                    secondary = OKXColors.ACCENT,
                    secondaryVariant = OKXColors.ACCENT2,
                    background = OKXColors.BG,
                    surface = OKXColors.CARD,
                    error = OKXColors.LOSS,
                    onPrimary = Color.Black,
                    onSecondary = Color.Black,
                    onBackground = OKXColors.TEXT,
                    onSurface = OKXColors.TEXT,
                    onError = Color.White,
                    isLight = false
                )
            ) {
                AppScreen()
            }
        }
    }
}

// ================= CUSTOM TEXT FIELD =================
@Composable
fun WearTextField(
    value: String,
    onValueChange: (String) -> Unit,
    textColor: Color,
    bgColor: Color,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = textColor, fontSize = 12.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ================= UI =================
@Composable
fun AppScreen() {

    var coins by remember { mutableStateOf(listOf<Coin>()) }

    var symbol by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp)
    ) {

        item {
            Text("OKX Tracker", color = OKXColors.ACCENT, fontSize = 14.sp)
        }

        // ========= ADD =========
        item {
            WearTextField(
                value = symbol,
                onValueChange = { symbol = it },
                textColor = OKXColors.TEXT,
                bgColor = OKXColors.CARD
            )
        }

        item {
            WearTextField(
                value = qty,
                onValueChange = { qty = it },
                textColor = OKXColors.TEXT,
                bgColor = OKXColors.CARD,
                keyboardType = KeyboardType.Decimal
            )
        }

        item {
            WearTextField(
                value = price,
                onValueChange = { price = it },
                textColor = OKXColors.TEXT,
                bgColor = OKXColors.CARD,
                keyboardType = KeyboardType.Decimal
            )
        }

        item {
            Button(onClick = {
                val q = qty.toDoubleOrNull()
                val p = price.toDoubleOrNull()
                if (symbol.isNotEmpty() && q != null && p != null) {
                    coins = coins + Coin(symbol.uppercase(), q, p)
                    symbol = ""; qty = ""; price = ""
                }
            }) {
                Text("Add")
            }
        }

        // ========= LIST =========
        itemsIndexed(coins) { index, coin ->

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(6.dp)
                    .background(OKXColors.CARD, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {

                Column {

                    Text(
                        coin.symbol,
                        color = OKXColors.ACCENT,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Qty: ${coin.qty}",
                        color = OKXColors.TEXT2,
                        fontSize = 10.sp
                    )

                    Text(
                        "Buy: ${coin.avgBuy}$",
                        color = OKXColors.TEXT2,
                        fontSize = 10.sp
                    )

                    Spacer(Modifier.height(6.dp))

                    Row {
                        Button(
                            onClick = {
                                coins = coins.toMutableList().also {
                                    it.removeAt(index)
                                }
                            }
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}
