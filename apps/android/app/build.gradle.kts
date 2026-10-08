import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun endpointBuildConfigValue(property: String, defaultValue: String): String {
    val value = providers.gradleProperty(property).orElse(defaultValue).get()
    require('\n' !in value && '\r' !in value) { "$property must be a single-line URL" }
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

val validateReleaseEndpoints = tasks.register("validateReleaseEndpoints") {
    doLast {
        listOf("financeApiBaseUrl", "financeOidcIssuer").forEach { property ->
            val value = providers.gradleProperty(property).orNull
                ?: throw GradleException("Release requires -P$property=https://...")
            val uri = runCatching { URI(value) }.getOrElse {
                throw GradleException("$property must be an absolute public HTTPS URL")
            }
            val host = uri.host?.trim('[', ']')?.lowercase()
            val octets = host?.split('.')?.mapNotNull(String::toIntOrNull).orEmpty()
            val privateIpv4 = octets.size == 4 && (octets[0] == 0 || octets[0] == 10 ||
                octets[0] == 127 || octets[0] == 192 && octets[1] == 168 ||
                octets[0] == 172 && octets[1] in 16..31 ||
                octets[0] == 169 && octets[1] == 254 ||
                octets[0] == 100 && octets[1] in 64..127)
            val privateIpv6 = host?.let {
                it == "::" || it == "::1" || it.startsWith("fc") || it.startsWith("fd") || it.startsWith("fe80:")
            } == true
            if (uri.scheme != "https" || host.isNullOrBlank() || uri.userInfo != null ||
                host == "localhost" || privateIpv4 || privateIpv6 ||
                property == "financeApiBaseUrl" && !uri.path.isNullOrEmpty() && uri.path != "/") {
                throw GradleException("$property must be an absolute public HTTPS URL")
            }
        }
        logger.lifecycle("Release endpoints validated: HTTPS API and OIDC issuer configured")
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseEndpoints)
}

tasks.withType<Test>().configureEach {
    systemProperty("expectedFinanceApiBaseUrl",
        providers.gradleProperty("financeApiBaseUrl").orElse("http://10.0.2.2:8080").get())
    systemProperty("expectedFinanceOidcIssuer",
        providers.gradleProperty("financeOidcIssuer").orElse("http://localhost:8081/realms/finance").get())
}

android {
    namespace = "com.decorix.finance"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.decorix.finance"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", endpointBuildConfigValue("financeApiBaseUrl", "http://10.0.2.2:8080"))
        buildConfigField("String", "OIDC_REALM_URL", endpointBuildConfigValue("financeOidcIssuer", "http://localhost:8081/realms/finance"))
        buildConfigField("String", "OIDC_CLIENT_ID", "\"finance-android\"")
        manifestPlaceholders["appAuthRedirectScheme"] = "com.decorix.finance"
    }
    buildTypes {
        getByName("debug") {
            // Keep local test installs beside an existing Finance installation and its user data.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("net.openid:appauth:0.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
