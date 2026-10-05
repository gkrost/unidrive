rootProject.name = "unidrive"

// Maven Central rate-limits by client IP, and GitHub-hosted CI runners share
// egress ranges — a build can fail with `429 Too Many Requests` from
// repo.maven.apache.org (Gradle retries with backoff, but a rate-limit window
// outlasts the default budget). Gradle only falls through to the next
// repository on a NOT-FOUND response, never on an HTTP error, so the complete
// Central mirrors below must be tried BEFORE mavenCentral: they answer with
// the artifact and Central is only reached on a mirror miss (404), while
// mavenCentral itself stays the last-resort fallback for mirror outages or
// sync lag. Dependency lockfiles pin exact versions, so a mirror serving the
// locked artifact is interchangeable with Central.
//
// Google's storage mirror is an official, continuously-synced full copy of
// Central; Aliyun/Tencent/Huawei are further full mirrors kept as additional
// fallbacks. The same list lives in allprojects.repositories in the root
// build script — keep the two in sync.
//
// Plugin markers exist only on the Portal and the Portal itself is not the
// rate-limit source, so it stays first; the mirrors catch what the Portal
// redirects to Central (e.g. kotlin-gradle-plugins-bom).
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://repo.huaweicloud.com/repository/maven/")
        mavenCentral()
    }
}

include(
    "app:core", "app:engine-core", "app:sync", "app:sync-tracking", "app:hydration", "app:cli", "app:cli-spi", "app:config",
    "providers:internxt", "providers:onedrive", "providers:localfs",
)
