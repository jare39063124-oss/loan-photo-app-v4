import org.gradle.api.tasks.wrapper.Wrapper

// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
}

// Disable distribution URL validation (network restricted in this environment)
tasks.named<Wrapper>("wrapper") {
    validateDistributionUrl = false
}
