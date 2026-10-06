plugins { id("com.android.application") }

val releaseKeystore = providers.environmentVariable("KEYSTORE_FILE").orNull

android {
    namespace = "dev.luma.monitor"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.luma.monitor"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = providers.environmentVariable("LUMA_VERSION_NAME").orElse("0.1.1").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                    ?: error("Missing KEYSTORE_PASSWORD for release signing")
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                    ?: error("Missing KEY_ALIAS for release signing")
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                    ?: error("Missing KEY_PASSWORD for release signing")
            }
        }
    }
    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.17")
}
