package com.decorix.finance

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FinanceExportDownloadHandoffTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val server = MockWebServer()
    private val heldCertificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .build()
    private lateinit var downloadUrl: String
    private lateinit var receiver: BroadcastReceiver
    private lateinit var resultLatch: CountDownLatch
    private val resultBytes = AtomicReference<ByteArray?>()
    private val resultError = AtomicReference<String?>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, ExportCsvTestReceiverActivity::class.java),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/csv; charset=utf-8")
                .setBody(CSV_BYTES.toString(Charsets.UTF_8)),
        )
        check(
            context.getSharedPreferences(RECEIVER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(CERTIFICATE_KEY, Base64.encodeToString(heldCertificate.certificate.encoded, Base64.NO_WRAP))
                .commit(),
        )
        downloadUrl = "https://127.0.0.1:${server.port}/csv-handoff/export.csv?signature=synthetic"
        resultLatch = CountDownLatch(1)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                resultBytes.set(intent.getByteArrayExtra(RESULT_BYTES))
                resultError.set(intent.getStringExtra(RESULT_ERROR))
                resultLatch.countDown()
            }
        }
        InstrumentationRegistry.getInstrumentation().targetContext
            .registerReceiver(receiver, IntentFilter(RESULT_ACTION))
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            runCatching { context.unregisterReceiver(receiver) }
            context.getSharedPreferences(RECEIVER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(CERTIFICATE_KEY)
                .apply()
            server.shutdown()
        } finally {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, ExportCsvTestReceiverActivity::class.java),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
    }

    @Test
    fun systemChooserCanSelectTestReceiverAndFetchesSyntheticCsvWithoutBearer() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.openExportDownload(downloadUrl)
        }

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val receiverChoice = By.text(RECEIVER_LABEL)
        assertTrue(
            "API 27 system chooser did not expose the HTTPS CSV test receiver",
            device.wait(Until.hasObject(receiverChoice), CHOOSER_TIMEOUT_MS),
        )
        device.findObject(receiverChoice).click()

        assertTrue("test receiver did not report its download", resultLatch.await(10, TimeUnit.SECONDS))
        assertNull("test receiver failed: ${resultError.get()}", resultError.get())
        assertArrayEquals(CSV_BYTES, resultBytes.get())

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("receiver made no HTTPS request to MockWebServer", request)
        assertEquals("GET", request!!.method)
        assertEquals("/csv-handoff/export.csv", request.requestUrl?.encodedPath)
        assertEquals("synthetic", request.requestUrl?.queryParameter("signature"))
        assertNull("external download carried Core bearer credentials", request.getHeader("Authorization"))
        assertEquals(0L, request.body.size)
        assertEquals(EXPECTED_SHA256, sha256(resultBytes.get()!!))
        val uri = Uri.parse(downloadUrl)
        assertEquals("127.0.0.1", uri.host)
        assertEquals(server.port, uri.port)
    }

    @Test
    fun receiverRejectsUnexpectedPathBeforeNetworkAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val invalidUrl = "https://127.0.0.1:${server.port}/unexpected.csv?signature=synthetic"
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, ExportCsvTestReceiverActivity::class.java)
                .setData(Uri.parse(invalidUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        assertTrue("receiver did not reject an unexpected path", resultLatch.await(5, TimeUnit.SECONDS))
        assertNotNull("receiver accepted an unexpected path", resultError.get())
        assertNull("receiver made a request before validating path", server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val RECEIVER_LABEL = "CSV export test receiver"
        const val RESULT_ACTION = "com.decorix.finance.debug.test.CSV_HANDOFF_RESULT"
        const val RESULT_BYTES = "bytes"
        const val RESULT_ERROR = "error"
        const val RECEIVER_PREFS = "f52_csv_handoff_test"
        const val CERTIFICATE_KEY = "receiver_trusted_certificate"
        const val CHOOSER_TIMEOUT_MS = 5_000L
        const val EXPECTED_SHA256 = "cec39ee7052f9a1b5db515f2fe3b69fbec9c52d1421e6531cd045374c13c2a59"
        val CSV_BYTES = "date,amount,description\n2026-01-02,123.45,synthetic fixture\n".toByteArray(Charsets.UTF_8)
    }
}
