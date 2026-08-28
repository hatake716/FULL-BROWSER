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

// X サーバ (Termux:X11 lorie)。external/termux-x11 (コミット 50ac80fb + LDFA パッチ) を
// :embedded-x11 モジュールが生成オーバーレイ経由でビルドする (LDFA と同じ方式)。
include(":embedded-x11")
include(":shell-loader-stub")
project(":shell-loader-stub").projectDir = file("../external/termux-x11/shell-loader/stub")
