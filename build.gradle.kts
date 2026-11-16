// Top-level build file. Module setup lives in build-logic convention plugins.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

// Security floors for old transitive dependencies that youtubedl-android 0.18.1 pins
// (jackson 2.11.1, commons-io 2.5, commons-compress 1.12 — from 2016/2020). The versions
// below are the first clean of every advisory in range that affects this app:
// - jackson 2.18.11: CVE-2020-36518, CVE-2022-42003/42004 and the 2026 batch (fixed <= 2.18.11)
// - jackson-core 2.18.11: CVE-2025-52999 (deeply nested JSON StackOverflowError)
// - commons-io 2.16.1: CVE-2021-29425, CVE-2024-47554
// - commons-compress 1.27.1: CVE-2021-36090, CVE-2024-25710
// Added on every resolvable classpath (configurations appear only once a module's plugins
// are applied, hence configureEach) so they hold for every variant, test and platform.
subprojects {
    val depHandler = dependencies
    val securityFloors = listOf(
        "com.fasterxml.jackson.core:jackson-databind:2.18.11",
        "com.fasterxml.jackson.core:jackson-core:2.18.11",
        "com.fasterxml.jackson.core:jackson-annotations:2.18.11",
        "commons-io:commons-io:2.16.1",
        "org.apache.commons:commons-compress:1.27.1",
    )
    configurations.configureEach {
        // Some plugin-owned configurations (KGP source-set classpaths) reject external
        // constraints; skip those, the resolvable classpaths carry the floor.
        runCatching { securityFloors.forEach { depHandler.constraints.add(name, it) } }
    }
}
