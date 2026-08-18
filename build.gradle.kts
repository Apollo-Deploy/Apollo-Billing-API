plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("io.ktor.plugin") version "3.4.2"
    id("com.gradleup.shadow") version "9.4.2"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    application
}

group = "com.apollodeploy.billing"
version = "1.0.0"

application {
    mainClass.set("com.apollodeploy.billing.BillingApplicationKt")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.addAll("-Xjsr305=strict")
        optIn.set(
            listOf(
                "kotlinx.coroutines.ExperimentalCoroutinesApi",
                "kotlinx.serialization.ExperimentalSerializationApi",
            ),
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    val ktorVersion = "3.4.2"
    val arrowVersion = "2.1.0"

    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // OpenAPI / Scalar docs
    implementation("io.github.smiley4:ktor-openapi:5.7.0")
    implementation("io.github.smiley4:schema-kenerator-core:2.7.2")
    implementation("io.github.smiley4:schema-kenerator-swagger:2.7.2")
    implementation(kotlin("reflect"))

    // Ktor Client (for Polar API calls)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // KotlinX
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Database
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.postgresql:postgresql:42.7.7")

    // Redis (Polar state fallback cache)
    implementation("io.lettuce:lettuce-core:6.5.5.RELEASE")

    // Arrow (typed errors + resilience)
    implementation("io.arrow-kt:arrow-core:$arrowVersion")
    implementation("io.arrow-kt:arrow-resilience:$arrowVersion")

    // Configuration
    implementation("com.typesafe:config:1.4.3")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.17")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // OAuth M2M SDK
    implementation("com.apollodeploy.oauth:oauth-m2m-client:1.0.1")
    implementation("com.apollodeploy.oauth:oauth-m2m-ktor:1.0.1")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("com.apollodeploy.oauth:oauth-m2m-testing:1.0.1")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("io.mockk:mockk:1.14.2")
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("org.testcontainers:testcontainers:1.20.5")
    testImplementation("org.testcontainers:postgresql:1.20.5")
}

// Load .env into the run task so the Gradle daemon environment doesn't matter.
fun loadDotEnv(): Map<String, String> {
    val envFile = rootProject.file(".env")
    if (!envFile.exists()) return emptyMap()
    return envFile
        .readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()
}

tasks.named<JavaExec>("run") {
    environment(loadDotEnv())
}

tasks.test {
    useJUnitPlatform()
    environment(loadDotEnv())
}

tasks.shadowJar {
    archiveFileName.set("app.jar")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

ktlint {
    version.set("1.5.0")
    ignoreFailures.set(false)
    filter {
        exclude { entry -> entry.file.path.contains("/build/") }
    }
}
