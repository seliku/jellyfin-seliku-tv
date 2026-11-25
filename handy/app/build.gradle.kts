import java.time.Duration
import java.time.LocalDateTime

plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
	id("org.jetbrains.kotlin.plugin.compose")
	id("org.jetbrains.kotlin.plugin.serialization")
}

// Versionsnummer = Minuten seit 2026 (wie die TV-App): jeder Bau ist grösser als der vorige
val minuten = Duration.between(LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.now()).toMinutes().toInt()

android {
	namespace = "ch.seliku.handy"
	compileSdk = 36
	defaultConfig {
		applicationId = "ch.seliku.jellyfin.handy"
		minSdk = 26
		targetSdk = 36
		versionCode = minuten
		versionName = "1.0.$minuten"
		buildConfigField("String", "UPDATE_REPO", "\"seliku/jellyfin-seliku-tv\"")
	}
	signingConfigs {
		create("seliku") {
			storeFile = file(project.findProperty("seliku.handy.store") ?: "/root/bau/schluessel/seliku-handy.jks")
			storePassword = project.findProperty("seliku.handy.passwort") as String?
			keyAlias = "seliku-handy"
			keyPassword = project.findProperty("seliku.handy.passwort") as String?
		}
	}
	buildTypes {
		release {
			isMinifyEnabled = false
			signingConfig = signingConfigs.getByName("seliku")
		}
		debug { signingConfig = signingConfigs.getByName("seliku") }
	}
	buildFeatures { compose = true; buildConfig = true }
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
		isCoreLibraryDesugaringEnabled = true
	}
	kotlin { jvmToolchain(21) }
	packaging { resources { excludes += setOf("META-INF/*") } }
}

dependencies {
	coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
	implementation("org.jellyfin.sdk:jellyfin-core:1.7.1")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
	implementation("androidx.core:core-ktx:1.17.0")
	implementation("androidx.activity:activity-compose:1.11.0")
	implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
	implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
	implementation("androidx.lifecycle:lifecycle-process:2.9.4")
	implementation("androidx.compose.ui:ui:1.9.4")
	implementation("androidx.compose.foundation:foundation:1.9.4")
	implementation("androidx.compose.material3:material3:1.4.0")
	implementation("androidx.compose.material:material-icons-extended:1.7.8")
	implementation("androidx.navigation:navigation-compose:2.9.5")
	implementation("io.coil-kt.coil3:coil-compose:3.3.0")
	implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
	implementation("androidx.media3:media3-exoplayer:1.8.0")
	implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
	implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
	implementation("androidx.media3:media3-ui:1.8.0")
	implementation("androidx.media3:media3-session:1.8.0")
	implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1")
	implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
