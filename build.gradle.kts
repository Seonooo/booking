plugins {
	java
	id("org.springframework.boot") version "3.5.14"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.booking"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
	}
}

dependencies {
	// --- Web / API ---
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// --- Data ---
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	runtimeOnly("com.mysql:mysql-connector-j")

	// --- Migration (CONVENTIONS-FILE.md §6) ---
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-mysql")

	// --- Resilience4j (ADR-007) ---
	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

	// --- ShedLock (ADR-010 outbox poller / ADR-011 reconciliation worker) ---
	implementation("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0")

	// --- Lombok ---
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")

	// --- Test (ADR-013) ---
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:mysql")
	testImplementation("com.redis:testcontainers-redis:2.2.2")
	testImplementation("org.wiremock:wiremock-standalone:3.10.0")
	testImplementation("org.awaitility:awaitility")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
