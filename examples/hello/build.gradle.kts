plugins {
    id("dev.composenative.desktop") version "0.1.0"
}

composeDesktop {
    executableName.set("hello")
    // Repositories are declared in settings.gradle.kts above, which is what a
    // build using repositoriesMode = FAIL_ON_PROJECT_REPOS has to do.
    addRepositories.set(false)
}
