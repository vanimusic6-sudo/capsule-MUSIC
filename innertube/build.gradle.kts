plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(libs.brotli)
    implementation(libs.newpipe.extractor)
    // Separate namespace: VIDEO fallback only, never replaces AUDIO.
    implementation("com.github.maxrave-dev:PipePipeExtractor:f8982ca9e7") {
        // PipePipe's unused full runtime duplicates NewPipe's protobuf-lite.
        // Its YouTube models use Wire; retain the existing lite runtime.
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    implementation(libs.re2j)
    implementation(libs.rhino)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
}
