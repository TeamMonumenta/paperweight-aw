plugins {
    `config-kotlin`
    `config-publish`
}

dependencies {
    shade(projects.paperweightLib)
    implementation(libs.bundles.kotson)
}

gradlePlugin {
    plugins.all {
        description = "Gradle plugin for developing Paper derivatives"
        implementationClass = "io.papermc.paperweight.patcher.PaperweightPatcher"
    }
}
