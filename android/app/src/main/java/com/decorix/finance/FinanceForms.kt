package com.decorix.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.LocalDate

@Composable fun TransactionForm(vm: FinanceViewModel, existing: JSONObject? = null, receipt: JSONObject? = null, onSaved: () -> Unit = { vm.screen = "home" }) {
    var amount by rememberSaveable { mutableStateOf((existing ?: receipt)?.let { it.optString(if (receipt != null) "total" else "amount", "") } ?: "") }
    var description by rememberSaveable { mutableStateOf((existing ?: receipt)?.optString(if (receipt != null) "store" else "description", "") ?: "") }
    var category by rememberSaveable { mutableStateOf((existing ?: receipt)?.optString("category", "еда")?.takeIf { it in categoryIds } ?: "еда") }
    var date by rememberSaveable { mutableStateOf(existing?.optString("created_at")?.take(10) ?: LocalDate.now().toString()) }
    var type by rememberSaveable { mutableStateOf(existing?.optString("tx_type") ?: "expense") }
    var text by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val receiptItems = remember { receipt?.optJSONArray("items")?.objects().orEmpty().map { JSONObject(it.toString()) }.toMutableList() }
    Page {
        if (existing == null && receipt == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(type == "expense", { type = "expense" }, label = { Text(stringResource(R.string.expense)) })
                FilterChip(type == "income", { type = "income" }, label = { Text(stringResource(R.string.income)) })
            }
            Tile {
                Field(R.string.free_text, text, { text = it })
                OutlinedButton(onClick = { vm.perform {
                    val result = JSONObject(vm.repository.call("transactions/parse", "POST", json("text" to text)))
                    amount = result.optString("amount", "")
                    description = result.optString("description", text)
                    category = result.optString("category", "прочее").takeIf { it in categoryIds } ?: "прочее"
                    type = result.optString("tx_type", "expense").takeIf { it in listOf("expense", "income") } ?: "expense"
                } }, enabled = !vm.busy && text.isNotBlank()) { Text(stringResource(R.string.parse)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.screen = "receipt" }, Modifier.weight(1f)) { Text(stringResource(R.string.receipt)) }
                OutlinedButton(onClick = { vm.screen = "import" }, Modifier.weight(1f)) { Text(stringResource(R.string.bank_import)) }
            }
        }
        if (receipt != null) {
            Heading(stringResource(R.string.preview))
            if (receipt.optBoolean("items_mismatch") || receipt.optBoolean("total_estimated")) Text(stringResource(R.string.mismatch), color = MaterialTheme.colorScheme.error)
        }
        Tile {
            Field(R.string.amount, amount, { amount = it.replace(',', '.') }, numeric = true)
            Field(R.string.description, description, { description = it })
            Box {
                OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) { Text("${stringResource(R.string.category)}: ${stringResource(categoryIds[category] ?: R.string.other)}") }
                DropdownMenu(expanded, { expanded = false }) { categoryIds.forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { category = key; expanded = false })
                } }
            }
            Field(R.string.date, date, { date = it })
        }
        if (receipt != null) {
            Heading(stringResource(R.string.items))
            receiptItems.forEachIndexed { index, item ->
                key(index) { ReceiptItemEditor(item) }
            }
        }
        Action(R.string.save, !vm.busy && (amount.toDoubleOrNull() ?: 0.0) > 0 && runCatching { LocalDate.parse(date) }.isSuccess) {
            val body = json("amount" to amount.toDouble(), "category" to category, "description" to description,
                "tx_type" to type, "created_at" to date + "T12:00:00", "debt_target" to existing?.optString("debt_target"))
            if (receipt != null) {
                body.put("confirmation_id", receipt.optString("confirmation_id")).put("items", org.json.JSONArray(receiptItems))
                vm.mutate("receipts/confirm", data = body, done = onSaved)
            } else vm.mutate(if (existing == null) "transactions" else "transactions/${existing.optInt("id")}",
                if (existing == null) "POST" else "PUT", body, onSaved)
        }
    }
}

@Composable fun ReceiptItemEditor(item: JSONObject) {
    var name by remember { mutableStateOf(item.optString("name")) }
    var qty by remember { mutableStateOf(item.optString("qty", "1")) }
    var price by remember { mutableStateOf(item.optString("price", "0")) }
    var sum by remember { mutableStateOf(item.optString("sum", "0")) }
    Tile {
        Field(R.string.item_name, name, { name = it; item.put("name", it) })
        Field(R.string.quantity, qty, { qty = it; item.put("qty", it.replace(',', '.')) }, numeric = true)
        Field(R.string.price, price, { price = it; item.put("price", it.replace(',', '.')) }, numeric = true)
        Field(R.string.item_sum, sum, { sum = it; item.put("sum", it.replace(',', '.')) }, numeric = true)
    }
}

@Composable fun DetailScreen(vm: FinanceViewModel, txid: Int) {
    val raw = loaded(vm, "transactions/$txid") ?: return
    val data = JSONObject(raw)
    var delete by remember { mutableStateOf(false) }
    Page {
        Tile {
            Heading(data.optString("description").ifBlank { stringResource(R.string.transaction) })
            Text(money(data.number("amount"), vm.language), fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(categoryIds[data.optString("category")] ?: R.string.other))
            Text(data.optString("created_at"))
        }
        data.optJSONArray("items")?.objects()?.forEach { item ->
            Tile { Text(item.optString("name")); Text(money(item.number("sum"), vm.language))
                if (!item.isNull("verdict")) Text(item.optString("verdict"))
                if (!item.isNull("advice")) Text(item.optString("advice"))
                if (!item.isNull("verdict_source")) Text(item.optString("verdict_source"), style = MaterialTheme.typography.labelSmall)
            }
        }
        Action(R.string.edit) { vm.screen = "edit/$txid" }
        OutlinedButton(onClick = { vm.mutate("transactions/$txid/repeat", done = { vm.screen = "history" }) }, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text(stringResource(R.string.repeat)) }
        TextButton(onClick = { delete = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, text = { Text(stringResource(R.string.delete_confirm)) },
        confirmButton = { TextButton(onClick = { delete = false; vm.mutate("transactions/$txid", "DELETE", done = { vm.screen = "history" }) }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable fun BudgetScreen(vm: FinanceViewModel) {
    val raw = loaded(vm, "budgets") ?: return
    val data = remember(raw) { JSONObject(raw) }
    var total by remember(raw) { mutableStateOf(data.optString("total")) }
    var weekly by remember(raw) { mutableStateOf(data.optString("weekly_food")) }
    val limits = remember(raw) { mutableStateMapOf<String, String>().apply { categoryIds.keys.forEach { put(it, data.optJSONObject("limits")?.optString(it, "0") ?: "0") } } }
    var proposal by remember { mutableStateOf<JSONObject?>(null) }
    Page {
        Field(R.string.monthly_budget, total, { total = it.replace(',', '.') }, true)
        Field(R.string.weekly_food, weekly, { weekly = it.replace(',', '.') }, true)
        categoryIds.forEach { (key, label) -> Field(label, limits[key].orEmpty(), { limits[key] = it.replace(',', '.') }, true) }
        Action(R.string.save, !vm.busy) {
            runCatching {
                json("total" to total.toDouble(), "weekly_food" to weekly.toDouble(), "limits" to JSONObject().apply { limits.forEach { (k,v) -> put(k, v.toDouble()) } })
            }.onSuccess { vm.mutate("budgets", "PUT", it, done = { vm.screen = "analytics" }) }.onFailure { vm.error = "invalid_input" }
        }
        OutlinedButton(onClick = { vm.perform {
            val result = JSONObject(vm.repository.call("budgets/suggest", "POST"))
            if (result.optString("status") != "ok") vm.error = result.optString("status") else proposal = result.optJSONObject("proposal")
        } }, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text(stringResource(R.string.suggest_budget)) }
        proposal?.let { p -> Tile {
            Heading(stringResource(R.string.preview))
            val suggested = p.optJSONObject("limits") ?: p.optJSONObject("categories") ?: JSONObject()
            suggested.keys().forEach { key -> Text("${stringResource(categoryIds[key] ?: R.string.other)}: ${suggested.optString(key)}") }
            if (p.has("comment")) Text(p.optString("comment"))
            Action(R.string.apply) {
                suggested.keys().forEach { key -> if (key in categoryIds) limits[key] = suggested.optString(key) }
                if (p.has("total")) total = p.optString("total")
                proposal = null
            }
        } }
    }
}

@Composable fun DebtsScreen(vm: FinanceViewModel) {
    val raw = loaded(vm, "debts")
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var payment by remember { mutableStateOf<JSONObject?>(null) }
    Page {
        Action(R.string.add) { editing = JSONObject() }
        val rows = raw?.let { org.json.JSONArray(it).objects() }.orEmpty()
        if (rows.isEmpty() && raw != null) Empty()
        rows.forEach { row ->
            Tile {
                Heading(row.optString("name")); Text(money(row.number("current_amount"), vm.language), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("${stringResource(R.string.interest)}: ${row.number("interest_rate")}")
                Text(if (row.isNull("payoff_months")) stringResource(R.string.no_forecast) else "${stringResource(R.string.payoff)}: ${row.optInt("payoff_months")}")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { payment = row }, enabled = row.number("current_amount") > 0) { Text(stringResource(R.string.pay)) }
                    TextButton(onClick = { editing = row }) { Text(stringResource(R.string.edit)) }
                }
            }
        }
    }
    editing?.let { row -> DebtEditor(vm, row) { editing = null } }
    payment?.let { row ->
        var amount by remember(row) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { payment = null }, title = { Text(stringResource(R.string.pay)) },
            text = { Field(R.string.amount, amount, { amount = it.replace(',', '.') }, true) },
            confirmButton = { TextButton(onClick = {
                val value = amount.toDoubleOrNull()
                if (value == null || value <= 0) vm.error = "invalid_input"
                else vm.mutate("debts/${row.optString("id")}/payment", data = json("amount" to value), done = { payment = null })
            }, enabled = !vm.busy) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { payment = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable fun DebtEditor(vm: FinanceViewModel, row: JSONObject, close: () -> Unit) {
    var name by remember { mutableStateOf(row.optString("name")) }
    var amount by remember { mutableStateOf(row.optString("current_amount", "")) }
    var interest by remember { mutableStateOf(row.optString("interest_rate", "0")) }
    var minimum by remember { mutableStateOf(row.optString("min_payment", "0")) }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.debts)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(R.string.debt_name, name, { name = it }); Field(R.string.amount, amount, { amount = it.replace(',', '.') }, true)
            Field(R.string.interest, interest, { interest = it.replace(',', '.') }, true); Field(R.string.min_payment, minimum, { minimum = it.replace(',', '.') }, true)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { json("name" to name, "current_amount" to amount.toDouble(), "interest_rate" to interest.toDouble(), "min_payment" to minimum.toDouble()) }
            .onSuccess { vm.mutate(if (row.has("id")) "debts/${row.optString("id")}" else "debts", if (row.has("id")) "PUT" else "POST", it, close) }
            .onFailure { vm.error = "invalid_input" }
    }, enabled = !vm.busy) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } })
}
