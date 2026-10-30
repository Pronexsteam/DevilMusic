pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral(); maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\..*") } } } }
rootProject.name = "DevilMusic"
include(":app", ":core")
project(":core").projectDir = file("core")
