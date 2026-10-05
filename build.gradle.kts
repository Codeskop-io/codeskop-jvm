plugins {
    `java-library`
    // Same publishing setup as the mobile SDK: Central Portal, signed, credentials and the
    // in-memory signing key come from ~/.gradle/gradle.properties.
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "com.codeskop.sdk"
version = "0.1.0"

repositories { mavenCentral() }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}

dependencies {
    // All optional: integrations activate only when the host app has these on its classpath.
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(platform("org.junit:junit-bom:5.11.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
    testImplementation("org.eclipse.jetty.ee10:jetty-ee10-servlet:12.0.14")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.springframework.boot:spring-boot-starter-web:3.3.4")
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.3.4")
}

// Tests (Spring Boot 3) run on Java 17+; the library itself targets Java 11.
tasks.named<JavaCompile>("compileTestJava") { options.release.set(17) }
listOf("testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) { attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17) } }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.javadoc { (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet") }

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("com.codeskop.sdk", "codeskop-server", version.toString())

    pom {
        name.set("Codeskop server SDK for the JVM")
        description.set("Errors, incoming requests and outgoing API calls from Java and Kotlin backends (Servlet, Spring Boot, OkHttp).")
        inceptionYear.set("2026")
        url.set("https://github.com/Codeskop-io/codeskop-jvm")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("codeskop")
                name.set("Codeskop")
                url.set("https://codeskop.com")
            }
        }
        scm {
            url.set("https://github.com/Codeskop-io/codeskop-jvm")
            connection.set("scm:git:git://github.com/Codeskop-io/codeskop-jvm.git")
            developerConnection.set("scm:git:ssh://git@github.com/Codeskop-io/codeskop-jvm.git")
        }
    }
}
