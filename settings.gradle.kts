pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "KotlinMultiplatformWorkshopWarsaw2024"

include("common")
include("workshopApi")
include("workshopSolutions")
include("server")
include("client")
include("adminClient")
include("serverAndAdminCommon")
include("testEnvironment")
include("registration")
include("serverAndClientTest")
include("bugReproducer")
