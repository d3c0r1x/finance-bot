package com.decorix.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun TransactionMutationActionButtons(
    transactionId: String,
    sourceStatus: String,
    language: String,
    canWrite: Boolean,
    busy: Boolean,
    canRepeat: Boolean,
    pendingRepeat: Boolean,
    pendingVoid: Boolean,
    onRepeat: () -> Unit,
    onVoid: () -> Unit,
    onRepeatRetry: () -> Unit = {},
    onVoidRetry: () -> Unit = {},
) {
    if (!canWrite) return
    val russian = language == "ru"
    val posted = sourceStatus == "posted"
    val showRepeat = pendingRepeat || (!pendingVoid && canRepeat)
    val showVoid = pendingVoid || (!pendingRepeat && posted)
    if (!showRepeat && !showVoid) return

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showRepeat) TextButton(
            modifier = Modifier.testTag("transaction-repeat-$transactionId"),
            enabled = !busy,
            onClick = { if (pendingRepeat) onRepeatRetry() else onRepeat() },
        ) {
            Text(if (pendingRepeat) {
                if (russian) "Повторить запрос" else "Retry request"
            } else if (russian) "Повторить" else "Repeat")
        }
        if (showVoid) TextButton(
            modifier = Modifier.testTag("transaction-void-$transactionId"),
            enabled = !busy,
            onClick = { if (pendingVoid) onVoidRetry() else onVoid() },
        ) {
            Text(if (pendingVoid) {
                if (russian) "Повторить отмену" else "Retry void"
            } else if (russian) "Отменить" else "Void")
        }
    }
}
