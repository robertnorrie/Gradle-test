plugins {
    java
    distribution
    application

    pmd
    checkstyle
    idea

    id("com.diffplug.spotless") version "6.12.0"
}

group = "de.tum.in"
version = "1.0"

base {
    archivesName.set("pet")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    withSourcesJar()
    withJavadocJar()
}

var defaultEncoding = "UTF-8"
tasks.withType<JavaCompile> { options.encoding = defaultEncoding }
tasks.withType<Javadoc> { options.encoding = defaultEncoding }
tasks.withType<Test> { systemProperty("file.encoding", "UTF-8") }

tasks.test {
    useJUnitPlatform()
    systemProperty("java.library.path", file("${project.buildDir}/lib"))
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

repositories {
    mavenCentral()
}

val modelsProject = project(":lib:models")
dependencies {
    implementation(modelsProject)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.12.7.1")
}

spotless {
    java {
        palantirJavaFormat()
    }
    groovyGradle {
        greclipse()
    }
}

// PMD
// https://docs.gradle.org/current/dsl/org.gradle.api.plugins.quality.Pmd.html

pmd {
    toolVersion = "6.51.0" // https://pmd.github.io/
    reportsDir = file("${project.buildDir}/reports/pmd")
    ruleSetFiles = files("${project.rootDir}/config/pmd-rules.xml")
    ruleSets = listOf() // We specify all rules in rules.xml
    isConsoleOutput = false
    isIgnoreFailures = false
}

tasks.withType<Pmd> {
    reports {
        xml.required.set(false)
        html.required.set(true)
    }
}

// Checkstyle
// https://docs.gradle.org/current/dsl/org.gradle.api.plugins.quality.Checkstyle.html
checkstyle {
    toolVersion = "10.4" // http://checkstyle.sourceforge.net/releasenotes.html
    configFile = file("${project.rootDir}/config/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
    isShowViolations = false // Don"t litter console
}
tasks.checkstyleMain {
    configProperties = mapOf("suppression-file" to "${project.rootDir}/config/checkstyle-main-suppression.xml")
}
tasks.checkstyleTest {
    configProperties = mapOf("suppression-file" to "${project.rootDir}/config/checkstyle-test-suppression.xml")
}

application {
    mainClass.set("de.tum.in.pet.Main")
}

val startScripts = tasks.getByName("startScripts", CreateStartScripts::class)

tasks.register("extractTemplate") {
    file(rootProject.buildDir).mkdirs()
    file("${rootProject.buildDir}/template-unix.txt").writeText(
        (startScripts.unixStartScriptGenerator as TemplateBasedScriptGenerator).template.asString()
    )
}

startScripts.doFirst {
    (startScripts.unixStartScriptGenerator as TemplateBasedScriptGenerator).template =
        project.resources.text.fromFile("${modelsProject.projectDir}/config/template-unix.txt")
}
startScripts.doLast { startScripts.windowsScript.delete() }
