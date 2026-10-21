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
        // LibretroDroid (frontend libretro) est publié via JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "RetroPop"
include(":app")
