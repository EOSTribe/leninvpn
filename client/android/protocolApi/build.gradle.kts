plugins {
    id(libs.plugins.android.library.get().pluginId)
    id(libs.plugins.kotlin.android.get().pluginId)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "org.russia.leninvpn.protocol"
}

dependencies {
    compileOnly(project(":utils"))
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines)
}
