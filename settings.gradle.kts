plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "KotlinMultiplatformWorkshopWarsaw2024"

include("common")
include("server")
include("client")
