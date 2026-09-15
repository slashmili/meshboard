pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "meshboard-native"
if (providers.gradleProperty("meshboard.android").orNull == "true") include(":androidApp")
