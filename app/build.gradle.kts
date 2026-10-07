import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// release 签名：凭据放仓库根目录的 keystore.properties（已 gitignore），没有就只出未签名包
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "io.github.mangome.camftp"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mangome.camftp"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    // Apache FtpServer / MINA 各自带 META-INF/DEPENDENCIES、LICENSE、NOTICE，会和在一起炸 mergeJavaResource
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    testOptions {
        // slf4j-android 会调 android.util.Log，单测里让它返回默认值而不是抛 "not mocked"
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.ktx)

    // FTP 引擎：prim-ftpd 同款，Android 上已验证可用
    implementation(libs.ftpserver.core) {
        exclude(group = "org.springframework", module = "spring-context")
        exclude(group = "org.slf4j", module = "jcl-over-slf4j")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "log4j", module = "log4j")
    }
    // 与 ftpserver 1.2.1 锁定的 slf4j-api 1.7.36 对齐；输出走 logcat
    implementation(libs.slf4j.android)

    testImplementation(libs.junit)
    testImplementation(libs.commons.net)
    // 单测里把 slf4j-android 换成打 stdout 的实现，否则服务端日志静默（android.util.Log 在单测里是空实现）
    testImplementation(libs.slf4j.simple)
}

// slf4j 1.7 只允许一个绑定；单测运行时把 slf4j-android 踢出去，让 slf4j-simple 生效
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "org.slf4j", module = "slf4j-android")
    }
}
