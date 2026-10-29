plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.serble.rotationbeats"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.serble.rotationbeats"
        minSdk = 24
        targetSdk = 35
        // CI passes the run number so every published build is a strict upgrade.
        val build = System.getenv("BUILD_NUMBER")?.toIntOrNull()
        versionCode = build ?: 1
        versionName = if (build != null) "1.0.$build" else "1.0-dev"
    }

    // Release signing comes from the environment (see README). Without it, release builds fall
    // back to the debug key so they can still be side-loaded locally.
    val keystore = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Optional: point at pre-downloaded Robolectric android-all jars (offline CI / sandboxes).
            System.getenv("ROBOLECTRIC_DEPS_DIR")?.let { dir ->
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", dir)
            }
            // Rendering screenshots needs ~350 MB of Robolectric jars, so it is opt-in.
            if (System.getenv("SCREENSHOTS") == null) it.exclude("**/ScreenshotTest*")
            it.systemProperty("shots.dir", layout.buildDirectory.dir("shots").get().asFile.absolutePath)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
