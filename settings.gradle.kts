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

rootProject.name = "FitTrack"

include(":app")

include(":core:model")
include(":core:training")
include(":core:database")
include(":core:network")
include(":core:designsystem")

include(":feature:onboarding")
include(":feature:plans")
include(":feature:workout")
include(":feature:history")
include(":feature:coach")
include(":feature:settings")

include(":platform:reminders")

