pluginManagement {
	repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
	repositories { mavenCentral(); google() }
}
rootProject.name = "seliku-handy"
include(":app")
