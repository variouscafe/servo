import java.util.regex.Pattern

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
}

android {
    // 폰 전송 가능 크기로: 압축 패키징(설치 시 추출) — 274M → ~100M
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    // local.properties ndk.dir(29.0)와 불일치하면 AGP 심볼 스트립 태스크가
    // CXX1104로 스킵돼 미스트립 .so가 통째로 패키징된다(0.1.4 사고).
    ndkVersion = "29.0.13113456"

    namespace = "org.servo.servoshell"

    defaultConfig {
        applicationId = "org.servo.servoshell"
        minSdk = libs.versions.android.sdk.min.get().toInt()
        targetSdk = 34
        // namsan 버전 규칙(2026-10-28 지시): 산출물은 namsan-X.Y.Z.apk이고
        // versionName과 일치. 수정이 생기면 수정 내용에 맞게 버전을 올리고
        // versionCode도 함께 올린다(날짜 기반 스킴 유지: yyyyMMdd + 금일 빌드 차례).
        versionCode = 2026110501
        versionName = "0.7.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    val signingKeyInfo = getSigningKeyInfo()

    if (signingKeyInfo != null) {
        signingConfigs {
            register("release") {
                storeFile = signingKeyInfo["storeFile"] as File
                storePassword = signingKeyInfo["storePassword"] as String
                keyAlias = signingKeyInfo["keyAlias"] as String
                keyPassword = signingKeyInfo["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {}

        release {
            signingConfig =
                signingConfigs.getByName(if (signingKeyInfo != null) "release" else "debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }

        // Custom build types

        val debug = getByName("debug")
        val release = getByName("release")

        register("armv7Debug") {
            initWith(debug)
            ndk { abiFilters.add(getNDKAbi("armv7")) }
        }
        register("armv7Release") {
            initWith(release)
            ndk { abiFilters.add(getNDKAbi("armv7")) }
        }
        register("arm64Debug") {
            initWith(debug)
            ndk { abiFilters.add(getNDKAbi("arm64")) }
        }
        register("arm64Release") {
            initWith(release)
            ndk { abiFilters.add(getNDKAbi("arm64")) }
        }
        register("x86Debug") {
            initWith(debug)
            ndk { abiFilters.add(getNDKAbi("x86")) }
        }
        register("x86Release") {
            initWith(release)
            ndk { abiFilters.add(getNDKAbi("x86")) }
        }
        register("x64Debug") {
            initWith(debug)
            ndk { abiFilters.add(getNDKAbi("x64")) }
        }
        register("x64Release") {
            initWith(release)
            ndk { abiFilters.add(getNDKAbi("x64")) }
        }
    }
}

// Ignore default "debug" and "release" build types
androidComponents {
    beforeVariants {
        if (it.buildType == "release" || it.buildType == "debug") {
            it.enable = false
        }
    }
}

project.afterEvaluate {
    android.applicationVariants.forEach { variant ->
        val pattern = Pattern.compile("^([\\w\\d]+)(Debug|Release)")
        val matcher = pattern.matcher(variant.name)
        if (!matcher.find()) {
            throw GradleException("Invalid variant name for output: " + variant.name)
        }
        val arch = matcher.group(1)
        val debug = variant.name.contains("Debug")
        val finalFolder = getTargetDir(debug, arch)
        // namsan 규칙: 아티팩트명 = namsan-<versionName>.apk (WebView판과 동일 계약)
        val finalFile = File(finalFolder, "namsan-${android.defaultConfig.versionName}.apk")
        variant.outputs.forEach { output ->
            val copyAndRenameAPKTask =
                project.task<Copy>("copyAndRename${variant.name.capitalize()}APK") {
                    from(output.outputFile.parent)
                    into(finalFolder)
                    include(output.outputFile.name)
                    rename(output.outputFile.name, finalFile.name)
                }
            variant.assembleProvider.get().finalizedBy(copyAndRenameAPKTask)
        }
    }
}

dependencies {
    if (findProject(":servoview-local") != null) {
        implementation(project(":servoview-local"))
    } else {
        implementation(project(":servoview"))
    }
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3.compose)
    implementation(libs.androidx.material3.compose.adaptive)
    implementation(libs.androidx.preference)
    // namsan push bridge: NotificationCompat + 릴레이 통신 + FCM 수신
    implementation(libs.androidx.core)
    implementation(libs.okhttp)
    implementation(libs.firebase.messaging)
    // namsan: Rust 코어 레지스트리(UniFFI/JNA)
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.androidx.compose.material.icons)
}
