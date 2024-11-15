plugins {
    id("io.github.reyerizo.gradle.jcstress") version "0.8.15"
}

dependencies {
    jcstressImplementation(rootProject)
}

jcstress {
    verbose = "true"
    jcstressDependency = libs.jcstress.core.get().toString()
}
