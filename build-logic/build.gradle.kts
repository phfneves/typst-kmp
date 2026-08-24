plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

gradlePlugin {
    plugins {
        register("cargo") {
            id = "io.github.phfneves.typst.cargo"
            implementationClass = "io.github.phfneves.typst.gradle.CargoPlugin"
        }
    }
}
