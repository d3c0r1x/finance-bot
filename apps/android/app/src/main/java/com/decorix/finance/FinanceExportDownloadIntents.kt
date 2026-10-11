package com.decorix.finance

import android.content.Intent
import android.net.Uri

internal object FinanceExportDownloadIntents {
    fun externalView(url: String): Intent? = runCatching {
        FinanceExportModels.requireSafeDownloadUrl(url)
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }.getOrNull()
}
