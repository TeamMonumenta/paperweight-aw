plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)
    shade(libs.tiny.remapper)

    implementation(libs.bundles.kotson)
    implementation(libs.tiny.remapper)
    compileOnly(libs.aw)
    implementation(variantOf(libs.diffpatch) { classifier("all") }) {
        isTransitive = false
    }
    compileOnly(libs.bundles.cadix)
}

gradlePlugin {
    plugins.all {
        description = "Gradle plugin for developing Paper plugins using server internals"
        implementationClass = "io.papermc.paperweight.userdev.PaperweightUser"
    }
}
