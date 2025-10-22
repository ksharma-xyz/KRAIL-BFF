package app.krail.bff.model

import kotlinx.serialization.Serializable

@Serializable
data class MobileContext(
    val deviceId: String?,
    val deviceModel: String?,
    val osName: String?,
    val osVersion: String?,
    val appVersion: String?,
    val clientRegion: String?,
    val networkType: String?
)
