package org.krost.unidrive.onedrive.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DriveResponse(
    @SerialName("id") val id: String? = null,
    @SerialName("driveType") val driveType: String? = null,
    @SerialName("owner") val owner: IdentitySet? = null,
    @SerialName("quota") val quota: Quota? = null,
)

@Serializable
data class DriveItemCollectionResponse(
    @SerialName("value") val value: List<DriveItem> = emptyList(),
    @SerialName("@odata.nextLink") val nextLink: String? = null,
    @SerialName("@odata.deltaLink") val deltaLink: String? = null,
)

@Serializable
data class ErrorResponse(
    @SerialName("error") val error: ODataError? = null,
)

@Serializable
data class ODataError(
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("innerError") val innerError: InnerError? = null,
)

@Serializable
data class InnerError(
    @SerialName("date") val date: String? = null,
    @SerialName("request-id") val requestId: String? = null,
    @SerialName("client-request-id") val clientRequestId: String? = null,
)

@Serializable
data class UploadSession(
    @SerialName("uploadUrl") val uploadUrl: String? = null,
    @SerialName("expirationDateTime") val expirationDateTime: String? = null,
    @SerialName("nextExpectedRanges") val nextExpectedRanges: List<String>? = null,
)

@Serializable
data class SharingLinkResponse(
    @SerialName("link") val link: SharingLink? = null,
)

@Serializable
data class SharingLink(
    @SerialName("type") val type: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("webUrl") val webUrl: String? = null,
)

@Serializable
data class SubscriptionResponse(
    @SerialName("id") val id: String? = null,
    @SerialName("resource") val resource: String? = null,
    @SerialName("applicationId") val applicationId: String? = null,
    @SerialName("changeType") val changeType: String? = null,
    @SerialName("notificationUrl") val notificationUrl: String? = null,
    @SerialName("expirationDateTime") val expirationDateTime: String? = null,
) {
    fun toSubscription(): Subscription? = id?.let { Subscription(it, resource, expirationDateTime) }
}

data class Subscription(
    val id: String,
    val resource: String?,
    val expirationDateTime: String?,
)

@Serializable
data class PermissionCollectionResponse(
    @SerialName("value") val value: List<Permission> = emptyList(),
)

@Serializable
data class Permission(
    @SerialName("id") val id: String? = null,
    @SerialName("link") val link: SharingLink? = null,
    @SerialName("hasPassword") val hasPassword: Boolean = false,
    @SerialName("expirationDateTime") val expirationDateTime: String? = null,
)
