pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "FULL-BROWSER"
include(":app")
// X サーバ (Termux:X11 lorie) を library module として取り込む場合はここで include する:
// include(":lorie"); project(":lorie").projectDir = file("../external/termux-x11/lorie")
