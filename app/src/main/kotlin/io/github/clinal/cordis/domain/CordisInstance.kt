package io.github.clinal.cordis.domain

data class CordisInstance(
    val id: String,
    val name: String,
    val port: Int,
    val dns: String,
    val status: RuntimeStatus,
    val lastLogLines: List<String>,
)
