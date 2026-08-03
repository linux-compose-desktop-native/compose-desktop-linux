
plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
}

kotlin {
    linuxX64("linux") {
        binaries {
            executable {
                baseName = "compose-desktop-demo"
                entryPoint = "dev.composenative.demo.main"
                linkerOpts(NativeToolchain.linkerOpts)
            }
        }
    }

    sourceSets {
        linuxMain.dependencies {
            // api, so the demo can make the OpenGL calls its ExternalGlTexture needs
            implementation(project(":library"))
        }
    }
}

tasks.register<Exec>("runDemo") {
    group = "application"
    description = "Builds and runs the demo application."
    dependsOn("linkDebugExecutableLinux")
    commandLine(
        layout.buildDirectory.file("bin/linux/debugExecutable/compose-desktop-demo.kexe")
            .get().asFile.absolutePath
    )
}
