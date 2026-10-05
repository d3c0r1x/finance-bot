package com.decorix.finance

import android.net.Uri
import net.openid.appauth.connectivity.ConnectionBuilder
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Allows the local Keycloak HTTP endpoint in debug; production connections remain HTTPS-only. */
internal object DevelopmentConnectionBuilder : ConnectionBuilder {
    internal fun isAllowedForDevelopmentHttp(uri: URI): Boolean =
        uri.scheme == "http" && uri.host == "localhost" && uri.port == 8081

    override fun openConnection(uri: Uri): HttpURLConnection {
        if (uri.scheme == "https") return DefaultConnectionBuilder.INSTANCE.openConnection(uri)
        if (!BuildConfig.DEBUG || !isAllowedForDevelopmentHttp(URI(uri.toString()))) {
            throw IOException("Only HTTPS connections are permitted outside local Keycloak debug")
        }
        return URL(uri.toString()).openConnection() as HttpURLConnection
    }
}
