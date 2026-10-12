pluginManagement {
    repositories {
        // 国内直连 google() 经常超时，镜像放前面
        if (System.getenv("CI") != "true") {
            maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != "true") {
            maven("https://mirrors.tencent.com/nexus/repository/maven-public/")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "GetVideo"
include(":app")
