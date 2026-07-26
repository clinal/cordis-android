package io.github.clinal.cordis.domain

enum class RuntimeStatus {
    MissingBootstrap,
    Stopped,
    Starting,
    Running,
    Stopping,
    Failed,
}
