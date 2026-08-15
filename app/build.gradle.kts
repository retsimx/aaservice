import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
    id("org.jlleitschuh.gradle.ktlint")
    id("jacoco")
}

ktlint {
    version = "1.2.1"
}

android {
    namespace = "com.air.advantage.aaservice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.air.advantage.aaservice2"
        minSdk = 19
        targetSdk = 30
        versionCode = 4152
        versionName = "14.150-rebuild.2"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

tasks.withType<Test> {
    maxParallelForks = 1
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}

tasks.register<JacocoReport>("jacocoTestReport") {
    description = "Generates a Jacoco code coverage report from unit tests."
    group = "verification"
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
    val excludedClasses =
        listOf(
            "**/R.class",
            "**/R\$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/BR.*",
            "**/Hilt_*.*",
            "**/*_Hilt*.*",
            "**/*_GeneratedInjector*.*",
            "**/Dagger*.*",
            "**/hilt_aggregated_deps/**/*.*",
            "**/databinding/**/*.*",
        )
    val debugKotlinClasses =
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            exclude(excludedClasses)
        }
    val debugJavaClasses =
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) {
            exclude(excludedClasses)
        }
    classDirectories.setFrom(files(debugKotlinClasses, debugJavaClasses))
    val sourceDirs = mutableListOf(file("src/main/java"))
    if (file("src/main/kotlin").isDirectory) {
        sourceDirs.add(file("src/main/kotlin"))
    }
    sourceDirectories.setFrom(files(sourceDirs))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage")) {
            include("**/*.exec")
        },
    )
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.20"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.test.ext:junit-ktx:1.1.5")
    testImplementation("androidx.test:rules:1.5.0")
    testImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
