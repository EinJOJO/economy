plugins {
    `java-library`
    `maven-publish`
}

group = "it.einjojo"
version = "1.0-SNAPSHOT" // Der artifactId wird standardmäßig der Projektname sein

val junitVersion = "5.10.2"
val assertjVersion = "3.25.3"
val mockitoVersion = "5.11.0"
val testcontainersVersion = "1.19.7"
val hikariVersion = "5.1.0"
val slf4jVersion = "2.0.9"
val jedisVersion = "5.2.0"
val postgresDriverVersion = "42.7.3"
val gsonVersion = "2.10.1"
val awaitilityVersion = "4.2.1"

repositories {
    mavenCentral()
}

dependencies {
    api("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("redis.clients:jedis:$jedisVersion")
    implementation("com.google.code.gson:gson:$gsonVersion")
    implementation("org.postgresql:postgresql:$postgresDriverVersion")

    // --- Testabhängigkeiten ---
    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")

    // Testcontainers für Integrationstests
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion") // JUnit 5 Integration
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    // Redis-Container wird nicht explizit im Code verwendet, aber für Vollständigkeit hinzugefügt
    // testImplementation("org.testcontainers:redis:$testcontainersVersion")

    // HikariCP für ConnectionProvider-Implementierung in Tests
    testImplementation("com.zaxxer:HikariCP:$hikariVersion")

    // SLF4J Simple Logger für Tests (oder Logback, etc.)
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")

    // Awaitility für robustere asynchrone Tests
    testImplementation("org.awaitility:awaitility:$awaitilityVersion")
}

java {

    withJavadocJar()
    withSourcesJar()

    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        // showStandardStreams = true // Bei Bedarf für Debugging aktivieren
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("Economy")
                description.set("Basic thread safe economy.")
                url.set("https://einjojo.it/work/economy")


                // Füge Entwicklerinformationen hinzu
                developers {
                    developer {
                        id.set("einjojo")
                        name.set("Johannes")
                        email.set("johannes@einjojo.it")
                    }
                }
            }
        }
    }
    repositories {

    }
}

