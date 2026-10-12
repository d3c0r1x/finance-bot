package com.decorix.finance

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class ExportCsvTestReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.data?.toString()
        Thread {
            try {
                requireNotNull(url) { "chooser delivered no URL" }
                val uri = Uri.parse(url)
                require(
                    uri.scheme == "https" &&
                        uri.host == "127.0.0.1" &&
                        uri.encodedPath == "/csv-handoff/export.csv",
                ) { "unsupported CSV download URL" }
                val encodedCertificate = requireNotNull(
                    getSharedPreferences(RECEIVER_PREFS, MODE_PRIVATE).getString(CERTIFICATE_KEY, null),
                ) { "test certificate was not configured" }
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(
                        ByteArrayInputStream(Base64.decode(encodedCertificate, Base64.DEFAULT)),
                    ) as X509Certificate
                val trusted = HandshakeCertificates.Builder()
                    .addTrustedCertificate(certificate)
                    .build()
                val client = OkHttpClient.Builder()
                    .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
                    .build()
                val response = client.newCall(Request.Builder().url(url).get().build()).execute()
                val bytes = requireNotNull(response.body) { "HTTPS response had no body" }.bytes()
                require(response.isSuccessful) { "HTTP ${response.code}" }
                report(bytes = bytes)
            } catch (error: Exception) {
                report(error = error.javaClass.simpleName + ": " + error.message.orEmpty())
            } finally {
                runOnUiThread { finish() }
            }
        }.start()
    }

    private fun report(bytes: ByteArray? = null, error: String? = null) {
        sendBroadcast(
            Intent(RESULT_ACTION)
                .setPackage(TARGET_PACKAGE)
                .putExtra(RESULT_BYTES, bytes)
                .putExtra(RESULT_ERROR, error),
        )
    }

    private companion object {
        const val RECEIVER_PREFS = "f52_csv_handoff_test"
        const val CERTIFICATE_KEY = "receiver_trusted_certificate"
        const val RESULT_ACTION = "com.decorix.finance.debug.test.CSV_HANDOFF_RESULT"
        const val TARGET_PACKAGE = "com.decorix.finance.debug"
        const val RESULT_BYTES = "bytes"
        const val RESULT_ERROR = "error"
    }
}
