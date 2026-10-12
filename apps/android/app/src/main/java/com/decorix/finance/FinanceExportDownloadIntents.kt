package com.decorix.finance

import android.content.Intent
import android.net.Uri

internal object FinanceExportDownloadIntents {
    fun externalView(url: String): Intent? = runCatching {
        FinanceExportModels.requireSafeDownloadUrl(url)
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }.getOrNull()

    fun launchChooser(url: String, russian: Boolean, launch: (Intent) -> Unit): Boolean {
        val externalView = externalView(url) ?: return false
        launch(Intent.createChooser(externalView, if (russian) "Скачать CSV" else "Download CSV"))
        return true
    }
}
