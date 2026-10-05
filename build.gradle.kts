plugins {
    `java-library`
    `maven-publish`
}

group = "com.codeskop.sdk"
version = "0.1.0"

repositories { mavenCentral() }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    withSourcesJar()
    withJavadocJar()
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "codeskop-server"
            pom {
                name.set("Codeskop server SDK for the JVM")
                description.set("Errors, incoming requests and outgoing API calls from Java and Kotlin backends (Servlet, Spring Boot, OkHttp).")
                url.set("https://www.codeskop.com/docs/server/java")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
            }
        }
    }
}
