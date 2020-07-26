import com.techshroom.inciseblue.commonLib

plugins {
    val kotlinVersion = "1.3.72"
    kotlin("jvm") version kotlinVersion
    kotlin("kapt") version kotlinVersion
    id("com.techshroom.incise-blue") version "0.5.7"
    application
}

inciseBlue {
    util {
        javaVersion = JavaVersion.VERSION_14
    }
    license()

    lwjgl {
        lwjglVersion = "3.2.3"
        addDependency("", true)
        addDependency("jemalloc", true)
    }
}

kotlin.target.compilations.configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn")
    }
}

kapt {
    correctErrorTypes = true
}

application.mainClassName = "net.octyl.beatdropper.BeatDrop"

dependencies {
    implementation(platform(kotlin("bom")))
    implementation(kotlin("stdlib-jdk8"))

    commonLib("org.jetbrains.kotlinx", "kotlinx-coroutines", "1.3.8") {
        implementation(lib("core"))
        implementation(lib("jdk8"))
    }

    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("ch.qos.logback:logback-core:1.2.3")

    implementation("net.sf.jopt-simple:jopt-simple:5.0.4")

    implementation("com.techshroom:jsr305-plus:0.0.1")

    implementation("com.flowpowered:flow-math:1.0.3")

    val javacppPresets = mapOf(
        "ffmpeg" to "4.2.2",
        "fftw" to "3.3.8",
        "javacpp" to null
    )
    val javacppVersion = "1.5.3"
    // take desktop platforms, 64 bit
    val wantedPlatforms = listOf("linux", "macosx", "windows").map { "$it-x86_64" }
    for ((name, version) in javacppPresets) {
        val fullVersion = when (version) {
            null -> javacppVersion
            else -> "$version-$javacppVersion"
        }
        implementation("org.bytedeco:$name:$fullVersion")
        for (platform in wantedPlatforms) {
            implementation("org.bytedeco:$name:$fullVersion:$platform")
            implementation("org.bytedeco:$name:$fullVersion:$platform")
        }
    }

    implementation("com.google.guava:guava:29.0-jre")

    val autoServiceVersion = "1.0-rc7"
    compileOnly("com.google.auto.service:auto-service-annotations:$autoServiceVersion")
    kapt("com.google.auto.service:auto-service:$autoServiceVersion")

    testImplementation("junit:junit:4.13")
}
