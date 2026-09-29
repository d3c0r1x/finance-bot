package com.decorix.finance

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FinanceApp() }
    }
}

val categoryIds = linkedMapOf("еда" to R.string.food, "транспорт" to R.string.transport, "жилье" to R.string.housing,
    "досуг" to R.string.leisure, "одежда" to R.string.clothing, "здоровье" to R.string.health,
    "работа" to R.string.work, "техника" to R.string.tech, "долги" to R.string.debts, "прочее" to R.string.other)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.number(key: String): Double = optDouble(key, 0.0).takeIf { it.isFinite() } ?: 0.0
fun money(value: Double, language: String): String = NumberFormat.getNumberInstance(Locale.forLanguageTag(language))
    .apply { maximumFractionDigits = 2 }.format(value) + " ₽"
fun json(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { put(it.first, it.second) } }

@Composable fun FinanceApp(vm: FinanceViewModel = viewModel()) {
    val context = LocalContext.current
    val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(vm.language)) }
    val localized = remember(vm.language, context) { context.createConfigurationContext(config) }
    val dark = vm.theme == "dark" || (vm.theme == "system" && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(primary = Color(0xFFB9F66B), onPrimary = Color(0xFF152008),
        background = Color(0xFF101214), surface = Color(0xFF1A1F22), surfaceVariant = Color(0xFF263238))
    else lightColorScheme(primary = Color(0xFF0F766E), onPrimary = Color.White,
        background = Color(0xFFF6F7F9), surface = Color.White, surfaceVariant = Color(0xFFE7ECF2))
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
        MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp))) {
            Surface(Modifier.fillMaxSize(), color = colors.background) {
                if (!vm.signedIn) AuthScreen(vm) else MainShell(vm)
                if (vm.error != null) {
                    val message = when (vm.error) {
                        "network_error" -> R.string.network_error
                        "invalid_credentials", "invalid_access_token", "invalid_refresh_token", "session_revoked" -> R.string.auth_error
                        "invalid_input", "payment_exceeds_balance", "bank_totals_mismatch" -> R.string.invalid_input
                        "username_taken", "demo_requires_empty_account" -> R.string.conflict_error
                        else -> R.string.service_error
                    }
                    AlertDialog(onDismissRequest = { vm.error = null }, text = { Text(stringResource(message)) },
                        confirmButton = { TextButton(onClick = { vm.error = null }) { Text(stringResource(R.string.done)) } })
                }
            }
        }
    }
}

@Composable fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable fun Tile(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable fun Heading(text: String) { Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
@Composable fun Field(label: Int, value: String, change: (String) -> Unit, numeric: Boolean = false, secret: Boolean = false) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = true,
        shape = RoundedCornerShape(8.dp), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable fun Action(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(8.dp)) { Text(stringResource(label)) }
}
@Composable fun Empty() { Tile { Text(stringResource(R.string.empty), fontWeight = FontWeight.Bold); Text(stringResource(R.string.empty_sub), color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable fun BrandMark() {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(58.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MonitorHeart, stringResource(R.string.app_name), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable fun loaded(vm: FinanceViewModel, path: String): String? {
    var result by remember(path, vm.revision) { mutableStateOf(vm.repository.cached(path)) }
    var loading by remember(path, vm.revision) { mutableStateOf(true) }
    LaunchedEffect(path, vm.revision) {
        try { result = vm.load(path) } catch (failure: Exception) {
            vm.error = if (failure is ApiFailure) failure.code else "network_error"
            vm.signedIn = vm.repository.signedIn
        } finally { loading = false }
    }
    if (loading && result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    return result
}

@Composable fun AuthScreen(vm: FinanceViewModel) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(vm.repository.baseUrl) }
    Page {
        Spacer(Modifier.height(36.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandMark()
            Column {
                Text(stringResource(R.string.brand), fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                Text(stringResource(R.string.secure_local), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.welcome), fontSize = 46.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black)
        Text(stringResource(R.string.welcome_sub), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(vm.language == "ru", { vm.appearance(lang = "ru") }, label = { Text("RU") })
            FilterChip(vm.language == "en", { vm.appearance(lang = "en") }, label = { Text("EN") })
        }
        Tile {
            if (registering) Field(R.string.name, name, { name = it })
            Field(R.string.username, username, { username = it })
            Field(R.string.password, password, { password = it }, secret = true)
            if (registering) Field(R.string.repeat_password, repeat, { repeat = it }, secret = true)
            Action(if (registering) R.string.register else R.string.login, !vm.busy && username.length >= 3 && password.length >= 8 && (!registering || (password == repeat && name.isNotBlank()))) {
                runCatching { vm.repository.baseUrl = server }.onSuccess {
                    vm.authenticate(username, password, if (registering) name else null)
                }.onFailure { vm.error = "invalid_input" }
            }
            TextButton(onClick = { registering = !registering }) { Text(stringResource(if (registering) R.string.login else R.string.register)) }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Tile {
            Text(stringResource(R.string.server), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.server_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.server_emulator_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Field(R.string.server, server, { server = it })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MainShell(vm: FinanceViewModel) {
    val destinations = listOf(Triple("home", R.string.home, Icons.Default.Home), Triple("analytics", R.string.analytics, Icons.Default.BarChart),
        Triple("add", R.string.add, Icons.Default.AddCircle), Triple("debts", R.string.debts, Icons.Default.AccountBalanceWallet), Triple("profile", R.string.profile, Icons.Default.Person))
    val title = destinations.find { it.first == vm.screen }?.second ?: when {
        vm.screen == "history" -> R.string.history; vm.screen == "budget" -> R.string.budget
        vm.screen == "receipt" -> R.string.receipt; vm.screen == "import" -> R.string.bank_import
        vm.screen == "recurring" -> R.string.recurring; vm.screen == "products" -> R.string.products
        vm.screen == "shopping" -> R.string.shopping; else -> R.string.transaction
    }
    BackHandler(vm.screen != "home") { vm.screen = "home" }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(title), fontWeight = FontWeight.Bold) }, navigationIcon = {
        if (vm.screen !in destinations.map { it.first }) IconButton(onClick = { vm.screen = "home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    }, actions = { IconButton(onClick = { vm.revision++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) } }) },
        bottomBar = { NavigationBar { destinations.forEach { (route, label, icon) ->
            NavigationBarItem(selected = vm.screen == route, onClick = { vm.screen = route }, icon = { Icon(icon, stringResource(label)) }, label = { Text(stringResource(label), maxLines = 1) })
        } } }) { padding ->
        Column(Modifier.padding(padding)) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.offline) Text(stringResource(R.string.offline), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp))
            key(vm.screen) {
                when {
                    vm.screen == "home" -> HomeScreen(vm)
                    vm.screen == "analytics" -> AnalyticsScreen(vm)
                    vm.screen == "add" -> TransactionForm(vm)
                    vm.screen == "history" -> HistoryScreen(vm)
                    vm.screen.startsWith("tx/") -> DetailScreen(vm, vm.screen.substringAfter('/').toInt())
                    vm.screen.startsWith("edit/") -> {
                        val data = loaded(vm, "transactions/" + vm.screen.substringAfter('/'))
                        data?.let { TransactionForm(vm, JSONObject(it)) }
                    }
                    vm.screen == "debts" -> DebtsScreen(vm)
                    vm.screen == "budget" -> BudgetScreen(vm)
                    vm.screen == "profile" -> ProfileScreen(vm)
                    vm.screen == "receipt" -> UploadScreen(vm, false)
                    vm.screen == "import" -> UploadScreen(vm, true)
                    vm.screen == "products" -> ProductsScreen(vm)
                    vm.screen == "shopping" -> ShoppingScreen(vm)
                    vm.screen == "recurring" -> RecurringScreen(vm)
                }
            }
        }
    }
}

@Composable fun HomeScreen(vm: FinanceViewModel) {
    val raw = loaded(vm, "dashboard")
    val preferences = loaded(vm, "settings")
    if (preferences != null && !JSONObject(preferences).optBoolean("onboarded")) {
        Onboarding(vm, JSONObject(preferences)); return
    }
    Page {
        raw?.let { value ->
            val data = JSONObject(value)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFD9F99D)), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.expenses), color = Color(0xFF233010))
                    Text(money(data.number("expenses"), vm.language), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color(0xFF17210D))
                    Text("${stringResource(R.string.monthly_budget)}: ${money(data.number("limit"), vm.language)}", color = Color(0xFF233010))
                    LinearProgressIndicator(progress = { (data.number("expenses") / data.number("limit").coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = Color(0xFF294A13), trackColor = Color(0xFFA6C95A))
                    Text("${stringResource(R.string.daily_safe)}: ${money(data.number("daily_safe"), vm.language)}", color = Color(0xFF233010), fontWeight = FontWeight.SemiBold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(Modifier.weight(1f)) { Text(stringResource(R.string.income)); Text(money(data.number("income"), vm.language), fontWeight = FontWeight.Bold) }
                Tile(Modifier.weight(1f).clickable { vm.screen = "debts" }) { Text(stringResource(R.string.debts)); Text(money(data.number("total_debt"), vm.language), fontWeight = FontWeight.Bold) }
            }
            Tile { Text(stringResource(R.string.forecast)); Text(money(data.number("forecast"), vm.language), fontSize = 24.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Heading(stringResource(R.string.recent)); TextButton(onClick = { vm.screen = "history" }) { Text(stringResource(R.string.all)) }
            }
            val rows = data.optJSONArray("recent")?.objects().orEmpty()
            if (rows.isEmpty()) Empty() else rows.forEach { TransactionRow(vm, it) }
        }
    }
}

@Composable fun Onboarding(vm: FinanceViewModel, preferences: JSONObject) {
    var name by rememberSaveable { mutableStateOf(preferences.optString("display_name")) }
    var amount by rememberSaveable { mutableStateOf("55000") }
    Page {
        Heading(stringResource(R.string.onboarding))
        Text(stringResource(R.string.welcome_sub))
        Field(R.string.name, name, { name = it })
        Field(R.string.monthly_budget, amount, { amount = it }, numeric = true)
        Action(R.string.start, !vm.busy && name.isNotBlank() && amount.toDoubleOrNull() != null) {
            vm.perform {
                vm.repository.call("budgets", "PUT", json("total" to amount.toDouble(), "limits" to JSONObject()))
                vm.repository.call("settings", "PUT", preferences.put("display_name", name).put("onboarded", true).put("language", vm.language).put("theme", vm.theme))
                vm.revision++
            }
        }
    }
}

@Composable fun TransactionRow(vm: FinanceViewModel, row: JSONObject) {
    Tile(Modifier.clickable { vm.screen = "tx/${row.optInt("id")}" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(row.optString("description").ifBlank { stringResource(categoryIds[row.optString("category")] ?: R.string.other) }, fontWeight = FontWeight.SemiBold)
                Text(row.optString("created_at").take(10), style = MaterialTheme.typography.bodySmall)
            }
            Text((if (row.optString("tx_type") == "income") "+" else "−") + money(row.number("amount"), vm.language), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable fun HistoryScreen(vm: FinanceViewModel) {
    var type by rememberSaveable { mutableStateOf("") }
    val raw = loaded(vm, "transactions?days=36500" + if (type.isBlank()) "" else "&tx_type=$type")
    Page {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to R.string.all, "expense" to R.string.expenses, "income" to R.string.income).forEach { (key, title) ->
                FilterChip(type == key, { type = key }, label = { Text(stringResource(title)) })
            }
        }
        val rows = raw?.let { JSONArray(it).objects() }.orEmpty()
        if (rows.isEmpty() && raw != null) Empty()
        rows.forEach { TransactionRow(vm, it) }
    }
}

@Composable fun AnalyticsScreen(vm: FinanceViewModel) {
    var days by rememberSaveable { mutableStateOf(30) }
    val raw = loaded(vm, "reports/summary?days=$days")
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(7 to R.string.week, 30 to R.string.month, 90 to R.string.quarter, 365 to R.string.year).forEach { (value, title) ->
                FilterChip(days == value, { days = value }, label = { Text(stringResource(title)) })
            }
        }
        raw?.let {
            val data = JSONObject(it)
            Tile { Text(stringResource(R.string.balance)); Text(money(data.number("balance"), vm.language), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("${stringResource(R.string.expenses)}: ${money(data.number("expenses"), vm.language)}")
                Text("${stringResource(R.string.income)}: ${money(data.number("income"), vm.language)}") }
            val categories = data.optJSONObject("categories") ?: JSONObject()
            val keys = categories.keys().asSequence().toList()
            val palette = listOf(Color(0xFF8EBD42), Color(0xFF7B8CDE), Color(0xFFFFAF67), Color(0xFF61B8A6), Color(0xFFD884A8))
            Heading(stringResource(R.string.categories))
            if (keys.isNotEmpty()) Tile {
                Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                    val diameter = size.height - 20.dp.toPx()
                    var start = -90f
                    keys.forEachIndexed { index, key ->
                        val sweep = (categories.number(key) / data.number("expenses").coerceAtLeast(1.0) * 360).toFloat()
                        drawArc(palette[index % palette.size], start, sweep, false, Offset((size.width - diameter) / 2, 10.dp.toPx()), androidx.compose.ui.geometry.Size(diameter, diameter), style = Stroke(24.dp.toPx()))
                        start += sweep
                    }
                }
                keys.forEach { key -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(categoryIds[key] ?: R.string.other)); Text(money(categories.number(key), vm.language))
                } }
            } else Empty()
            val timeline = data.optJSONObject("timeline") ?: JSONObject()
            val dates = timeline.keys().asSequence().toList()
            if (dates.isNotEmpty()) Tile {
                Heading(stringResource(R.string.timeline))
                val color = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                    val maximum = dates.maxOf { timeline.number(it) }.coerceAtLeast(1.0)
                    val step = size.width / dates.size
                    dates.forEachIndexed { index, date ->
                        drawLine(color, Offset(step * (index + .5f), size.height), Offset(step * (index + .5f), (size.height * (1 - timeline.number(date) / maximum)).toFloat()), strokeWidth = (step * .65f).coerceAtMost(18.dp.toPx()), cap = StrokeCap.Round)
                    }
                }
                Text("${dates.first()} — ${dates.last()}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Action(R.string.budget) { vm.screen = "budget" }
        OutlinedButton(onClick = { vm.screen = "history" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.history)) }
    }
}
