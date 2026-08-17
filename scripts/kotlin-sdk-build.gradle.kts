import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm") version "__KOTLIN_VERSION__"
    kotlin("plugin.serialization") version "__KOTLIN_VERSION__"
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "__GROUP_ID__"
version = "__SDK_VERSION__"

repositories {
    mavenCentral()
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("__KOTLIN_VERSION__")
            because("Align all Kotlin stdlib/reflect artifacts to the compiler version")
        }
    }
}

dependencies {
    api(kotlin("stdlib"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("io.ktor:ktor-client-core:3.5.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-client-cio:3.5.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin {
    jvmToolchain(__JVM_TOOLCHAIN__)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    coordinates("__GROUP_ID__", "__ARTIFACT_ID__", project.version.toString())
    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("Apollo Billing SDK")
        description.set("Server-to-server SDK for Apollo Deploy billing, entitlement, checkout, usage, and customer billing operations.")

        providers.environmentVariable("POM_URL").orNull?.let { url.set(it) }

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
            }
        }

        developers {
            developer {
                providers.environmentVariable("POM_DEVELOPER_ID").orNull?.let { id.set(it) }
                providers.environmentVariable("POM_DEVELOPER_NAME").orNull?.let { name.set(it) }
                providers.environmentVariable("POM_DEVELOPER_EMAIL").orNull?.takeIf { it.isNotBlank() }?.let { email.set(it) }
            }
        }

        scm {
            providers.environmentVariable("POM_SCM_URL").orNull?.let { url.set(it) }
            providers.environmentVariable("POM_SCM_CONNECTION").orNull?.let { connection.set(it) }
            providers.environmentVariable("POM_SCM_DEVELOPER_CONNECTION").orNull?.let { developerConnection.set(it) }
        }
    }
}
