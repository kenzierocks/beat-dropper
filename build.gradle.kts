plugins {
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

application.mainClassName = "net.octyl.beatdropper.BeatDrop"

dependencies {
    implementation("org.slf4j:slf4j-api:1.7.25")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("ch.qos.logback:logback-core:1.2.3")

    implementation("net.sf.jopt-simple:jopt-simple:5.0.4")

    implementation("com.techshroom:jsr305-plus:0.0.1")

    implementation("com.flowpowered:flow-math:1.0.3")

	implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")

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
    compileOnly("com.google.auto.service:auto-service:$autoServiceVersion")
    annotationProcessor("com.google.auto.service:auto-service:$autoServiceVersion")
    val autoValueVersion = "1.7.4"
    annotationProcessor("com.google.auto.value:auto-value:$autoValueVersion")
    compileOnly("com.google.auto.value:auto-value-annotations:$autoValueVersion")

    testImplementation("junit:junit:4.13")
}
