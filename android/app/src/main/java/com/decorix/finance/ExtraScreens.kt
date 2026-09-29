package com.decorix.finance

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

@Composable fun UploadScreen(vm: FinanceViewModel, bank: Boolean) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    fun analyze(uri: Uri) {
        vm.perform {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        require(buffer.size() + count <= 15 * 1024 * 1024)
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toByteArray()
                } ?: throw IllegalArgumentException("file_unreadable")
            }
            preview = JSONObject(vm.repository.call(if (bank) "import/bank-statement" else "receipts/analyze", "POST",
                upload = bytes, mime = if (bank) "application/pdf" else "image/jpeg"))
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { analyze(it) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> if (success) cameraUri?.let { analyze(Uri.parse(it)) } }
    val result = preview
    if (result != null && !bank) {
        TransactionForm(vm, receipt = result); return
    }
    Page {
        Heading(stringResource(if (bank) R.string.bank_import else R.string.receipt))
        Action(if (bank) R.string.choose_pdf else R.string.gallery, !vm.busy) { picker.launch(arrayOf(if (bank) "application/pdf" else "image/*")) }
        if (!bank) OutlinedButton(onClick = {
            val directory = File(context.cacheDir, "receipts").apply { mkdirs() }
            val file = File(directory, "capture.jpg")
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            cameraUri = uri.toString(); camera.launch(uri)
        }, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text(stringResource(R.string.camera)) }
        if (vm.busy) Text(stringResource(R.string.working))
        result?.let { data ->
            Heading(stringResource(R.string.preview))
            Tile {
                Text("${stringResource(R.string.expenses)}: ${money(data.number("expenses"), vm.language)}")
                Text("${stringResource(R.string.income)}: ${money(data.number("income"), vm.language)}")
                Text("${stringResource(R.string.duplicates)}: ${data.optInt("duplicates")}")
                if (!data.optBoolean("totals_found")) Text(stringResource(R.string.total_unverified))
                if (!data.optBoolean("check_ok")) Text(stringResource(R.string.mismatch), color = MaterialTheme.colorScheme.error)
            }
            data.optJSONArray("operations")?.objects()?.forEach { op -> Tile {
                Text(op.optString("merchant").ifBlank { op.optString("description") })
                Text("${op.optString("date")} · ${money(op.number("amount"), vm.language)}")
            } }
            Action(R.string.import_action, !vm.busy && data.optBoolean("check_ok")) {
                vm.mutate("import/confirm", data = json("preview_id" to data.optString("preview_id")), done = { vm.screen = "history" })
            }
        }
    }
}

@Composable fun ProfileScreen(vm: FinanceViewModel) {
    val raw = loaded(vm, "settings")
    var server by rememberSaveable { mutableStateOf(vm.repository.baseUrl) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    Page {
        raw?.let { Heading(JSONObject(it).optString("display_name")) }
        Tile {
            Text(stringResource(R.string.language), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(vm.language == "ru", { vm.appearance(lang = "ru") }, label = { Text("Русский") })
                FilterChip(vm.language == "en", { vm.appearance(lang = "en") }, label = { Text("English") })
            }
            Text(stringResource(R.string.theme), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to R.string.system_theme, "light" to R.string.light_theme, "dark" to R.string.dark_theme).forEach { (value, title) ->
                    FilterChip(vm.theme == value, { vm.appearance(mode = value) }, label = { Text(stringResource(title)) })
                }
            }
        }
        Tile { listOf("budget" to R.string.budget, "recurring" to R.string.recurring, "products" to R.string.products,
            "shopping" to R.string.shopping, "import" to R.string.bank_import, "history" to R.string.history).forEach { (route, title) ->
            TextButton(onClick = { vm.screen = route }, Modifier.fillMaxWidth()) { Text(stringResource(title)) }
        } }
        Field(R.string.server, server, { server = it })
        OutlinedButton(onClick = {
            runCatching { vm.repository.baseUrl = server; vm.signedIn = vm.repository.signedIn }.onFailure { vm.error = "invalid_input" }
        }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
        OutlinedButton(onClick = { vm.perform { status = JSONObject(vm.repository.call("services/status")) } }, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text(stringResource(R.string.services)) }
        status?.let { s -> Tile {
            Text("Ollama: ${stringResource(if (s.optBoolean("ollama")) R.string.available else R.string.unavailable)}")
            Text("Tesseract: ${stringResource(if (s.optBoolean("tesseract")) R.string.available else R.string.unavailable)}")
        } }
        OutlinedButton(onClick = { vm.mutate("demo", done = { vm.screen = "home" }) }, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text(stringResource(R.string.demo)) }
        TextButton(onClick = { vm.logout() }, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text(stringResource(R.string.logout)) }
        Text("Finance 0.1.0", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable fun RecurringScreen(vm: FinanceViewModel) {
    val raw = loaded(vm, "recurring")
    val rows = raw?.let { JSONArray(it).objects() }.orEmpty()
    Page {
        if (rows.isEmpty() && raw != null) Empty()
        rows.forEach { row -> Tile {
            Heading(row.optString("name")); Text(money(row.number("amount"), vm.language))
            Text("${stringResource(R.string.next_date)}: ${row.optString("next_date").take(10)}")
            TextButton(onClick = { vm.mutate("recurring/hide", data = json("key" to row.optString("key"))) }, enabled = !vm.busy) { Text(stringResource(R.string.hide)) }
        } }
    }
}

@Composable fun ProductsScreen(vm: FinanceViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    val raw = loaded(vm, "products?q=" + URLEncoder.encode(search, "UTF-8"))
    val rows = raw?.let { JSONArray(it).objects() }.orEmpty()
    Page {
        Field(R.string.search, query, { query = it })
        Action(R.string.search) { search = query }
        if (rows.isEmpty() && raw != null) Empty()
        rows.forEach { row -> Tile(Modifier.clickable { selected = row }) {
            Heading(row.optString("name")); Text(money(row.number("last"), vm.language))
            Text("${stringResource(R.string.cheapest)}: ${money(row.number("cheapest"), vm.language)} · ${row.optString("cheapest_store")}")
        } }
        selected?.let { product ->
            Heading(product.optString("name"))
            product.optJSONArray("entries")?.objects()?.forEach { entry -> Tile {
                Text("${entry.optString("date").take(10)} · ${entry.optString("store")}")
                Text(money(entry.number("price"), vm.language))
            } }
        }
    }
}

@Composable fun ShoppingScreen(vm: FinanceViewModel) {
    val raw = loaded(vm, "shopping-list")
    val rows = raw?.let { JSONObject(it).optJSONArray("items")?.objects() }.orEmpty()
    Page {
        if (rows.isEmpty() && raw != null) Empty()
        rows.forEach { row -> Tile {
            Heading(row.optString("name"))
            Text("${stringResource(R.string.cheapest)}: ${money(row.number("cheapest"), vm.language)} · ${row.optString("cheapest_store")}")
            Action(R.string.bought, !vm.busy) { vm.mutate("shopping-list/bought", data = json("key" to row.optString("key"))) }
        } }
    }
}
