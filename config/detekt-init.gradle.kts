// detekt static analysis — applied via `gradle --init-script` so CI (and local
// runs) can analyse every module WITHOUT modifying the service build files.
//
// Local use:
//   gradle --init-script config/detekt-init.gradle.kts detekt
//
// detekt is ADVISORY for now (the CI step uses continue-on-error). Once the team
// reviews the first findings and commits a `config/detekt/detekt.yml` + baseline,
// move detekt into the root build.gradle.kts and make it a hard gate.
initscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.7")
    }
}

allprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure(io.gitlab.arturbosch.detekt.extensions.DetektExtension::class.java) {
        buildUponDefaultConfig = true
        parallel = true
        // Reports are uploaded as a CI artifact; failures don't block (advisory).
        ignoreFailures = true
    }
}
