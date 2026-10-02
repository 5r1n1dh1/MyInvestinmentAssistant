plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt) apply false
}

tasks.register("checkAll") {
    group = "verification"
    description = "Runs the pure-JVM test suites that need no Android SDK."
    dependsOn(":domain:test", ":data:test")
}