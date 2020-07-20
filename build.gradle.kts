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
    implementation("net.bramp.ffmpeg:ffmpeg:0.6.2")

    implementation("org.bytedeco.javacpp-presets:fftw-platform:3.3.8-1.4.4")

    implementation("com.google.guava:guava:29.0-jre")

    val autoServiceVersion = "1.0-rc7"
    compileOnly("com.google.auto.service:auto-service:$autoServiceVersion")
    annotationProcessor("com.google.auto.service:auto-service:$autoServiceVersion")
    val autoValueVersion = "1.7.4"
    annotationProcessor("com.google.auto.value:auto-value:$autoValueVersion")
    compileOnly("com.google.auto.value:auto-value-annotations:$autoValueVersion")

    testImplementation("junit:junit:4.13")
}
