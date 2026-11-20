package io.github.rvbcrs.wallmode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

data class ImmichShareRef(val origin: String, val key: String)

private val IMMICH_SHARE_PATH = Regex("^/share/([A-Za-z0-9_-]{40,200})/?$")
private val IMMICH_UUID = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)
private val IMMICH_TIME_BUCKET = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
private class ImmichHttpException(val status: Int) : IllegalStateException("Immich returned HTTP $status")

fun parseImmichShareUrl(value: String): ImmichShareRef? = runCatching {
    val uri = URI(value.trim())
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https")
    require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(scheme == "https" || isPrivateImmichHost(uri.host))
    val key = IMMICH_SHARE_PATH.matchEntire(uri.rawPath ?: "")?.groupValues?.get(1)
        ?: error("Invalid share path")
    val origin = URI(scheme, null, uri.host.lowercase(Locale.ROOT), uri.port, null, null, null).toASCIIString()
    ImmichShareRef(origin, key)
}.getOrNull()

internal fun isPrivateImmichHost(rawHost: String): Boolean {
    val host = rawHost.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
    if (host == "localhost" || host.endsWith(".local") || host == "::1") return true
    if (host.contains(':') &&
        (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:"))
    ) return true
    val parts = host.split('.').mapNotNull(String::toIntOrNull)
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 10 || parts[0] == 127 ||
        parts[0] == 192 && parts[1] == 168 ||
        parts[0] == 172 && parts[1] in 16..31
}

object ImmichPhotoSource {
    private const val SHARE_HEADER = "x-immich-share-key"
    private const val PAGE_SIZE = 250
    private const val MAX_ASSETS = 1_000
    private const val MAX_PAGES = 20
    private const val MAX_BUCKETS = 1_000
    private const val MAX_JSON_BYTES = 4 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 1280
    private const val MAX_REDIRECTS = 5

    fun loadAssetIds(shareUrl: String): Result<List<String>> = runCatching {
        val share = parseImmichShareUrl(shareUrl) ?: error("Invalid Immich share URL")
        val sharedLink = JSONObject(
            request(
                share = share,
                path = "/api/shared-links/me",
                method = "GET",
                accept = "application/json",
                expectedContentType = "application/json",
                maxBytes = MAX_JSON_BYTES
            ).toString(StandardCharsets.UTF_8)
        )
        check(sharedLink.optString("type") == "ALBUM") { "Immich link is not an album share" }
        val albumId = sharedLink.optJSONObject("album")?.optString("id").orEmpty()
        check(IMMICH_UUID.matches(albumId)) { "Immich album ID is invalid" }

        try {
            loadSearchAssetIds(share, albumId)
        } catch (error: ImmichHttpException) {
            // Immich 3 rejects metadata search with public share authentication.
            if (error.status != HttpURLConnection.HTTP_FORBIDDEN) throw error
            loadTimelineAssetIds(share, albumId)
        }
    }

    private fun loadSearchAssetIds(share: ImmichShareRef, albumId: String): List<String> {
        val ids = linkedSetOf<String>()
        var page = 1
        while (true) {
            check(page <= MAX_PAGES) { "Immich pagination exceeded its limit" }
            val body = JSONObject()
                .put("albumIds", JSONArray().put(albumId))
                .put("type", "IMAGE")
                .put("page", page)
                .put("size", PAGE_SIZE)
                .put("withExif", false)
                .put("withPeople", false)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
            val response = JSONObject(
                request(
                    share = share,
                    path = "/api/search/metadata",
                    method = "POST",
                    accept = "application/json",
                    expectedContentType = "application/json",
                    maxBytes = MAX_JSON_BYTES,
                    body = body
                ).toString(StandardCharsets.UTF_8)
            ).optJSONObject("assets") ?: error("Immich response has no assets")
            val items = response.optJSONArray("items") ?: error("Immich response has no asset list")
            for (index in 0 until items.length()) {
                val id = items.optJSONObject(index)?.optString("id").orEmpty()
                check(IMMICH_UUID.matches(id)) { "Immich returned an invalid asset ID" }
                ids += id
                if (ids.size >= MAX_ASSETS) break
            }

            if (ids.size >= MAX_ASSETS || response.isNull("nextPage")) break
            val nextPage = response.optString("nextPage").toIntOrNull()
                ?: error("Immich returned an invalid next page")
            check(nextPage > page) { "Immich pagination did not advance" }
            page = nextPage
        }
        return ids.toList()
    }

    private fun loadTimelineAssetIds(share: ImmichShareRef, albumId: String): List<String> {
        fun getJson(path: String) = request(
            share = share,
            path = path,
            method = "GET",
            accept = "application/json",
            expectedContentType = "application/json",
            maxBytes = MAX_JSON_BYTES
        ).toString(StandardCharsets.UTF_8)

        val buckets = JSONArray(getJson("/api/timeline/buckets?albumId=$albumId"))
        check(buckets.length() <= MAX_BUCKETS) { "Immich timeline exceeded its limit" }
        val ids = linkedSetOf<String>()
        for (bucketIndex in 0 until buckets.length()) {
            val bucket = buckets.getJSONObject(bucketIndex).getString("timeBucket")
            check(IMMICH_TIME_BUCKET.matches(bucket)) { "Immich returned an invalid time bucket" }
            val response = JSONObject(getJson("/api/timeline/bucket?albumId=$albumId&timeBucket=$bucket"))
            val assets = response.getJSONArray("id")
            val images = response.getJSONArray("isImage")
            val trashed = response.getJSONArray("isTrashed")
            check(assets.length() == images.length() && assets.length() == trashed.length()) {
                "Immich timeline columns have different lengths"
            }
            for (index in 0 until assets.length()) {
                val isImage = images.get(index)
                val isTrashed = trashed.get(index)
                check(isImage is Boolean && isTrashed is Boolean) { "Immich returned invalid asset flags" }
                if (!isImage || isTrashed) continue
                val id = assets.getString(index)
                check(IMMICH_UUID.matches(id)) { "Immich returned an invalid asset ID" }
                ids += id
                if (ids.size >= MAX_ASSETS) return ids.toList()
            }
        }
        return ids.toList()
    }

    fun loadThumbnail(shareUrl: String, assetId: String): Result<Bitmap> = runCatching {
        val share = parseImmichShareUrl(shareUrl) ?: error("Invalid Immich share URL")
        require(IMMICH_UUID.matches(assetId)) { "Invalid Immich asset ID" }
        val encoded = request(
            share = share,
            path = "/api/assets/$assetId/thumbnail?size=preview",
            method = "GET",
            accept = "image/*",
            expectedContentType = "image/",
            maxBytes = MAX_IMAGE_BYTES
        )
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Immich thumbnail could not be decoded" }
        BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply {
                inSampleSize = ambientImageSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ) ?: error("Immich thumbnail could not be decoded")
    }

    private fun request(
        share: ImmichShareRef,
        path: String,
        method: String,
        accept: String,
        expectedContentType: String,
        maxBytes: Int,
        body: ByteArray? = null
    ): ByteArray {
        val origin = URL(share.origin)
        var current = URL(origin, path)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "WallMode")
                setRequestProperty(SHARE_HEADER, share.key)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setFixedLengthStreamingMode(body.size)
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body) }
                val responseCode = connection.responseCode
                if (responseCode in setOf(301, 302, 303, 307, 308)) {
                    check(redirectCount < MAX_REDIRECTS) { "Too many Immich redirects" }
                    check(body == null || responseCode == 307 || responseCode == 308) {
                        "Immich POST redirect changed method"
                    }
                    val location = connection.getHeaderField("Location")
                        ?.takeIf { it.length <= 2_048 }
                        ?: error("Immich redirect has no valid location")
                    val redirected = URL(current, location)
                    check(isSameImmichOrigin(origin, redirected)) { "Immich redirect changed origin" }
                    current = redirected
                    return@repeat
                }
                if (responseCode !in 200..299) throw ImmichHttpException(responseCode)
                check(connection.contentType?.substringBefore(';')?.trim()
                    ?.startsWith(expectedContentType, ignoreCase = true) == true
                ) { "Immich returned unexpected content" }
                val contentLength = connection.contentLengthLong
                check(contentLength < 0 || contentLength <= maxBytes.toLong()) { "Immich response is too large" }
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(
                        contentLength.takeIf { it in 1L..maxBytes.toLong() }?.toInt() ?: 32 * 1024
                    )
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= maxBytes) { "Immich response is too large" }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many Immich redirects")
    }
}

internal fun isSameImmichOrigin(origin: URL, candidate: URL): Boolean {
    fun effectivePort(url: URL) = if (url.port >= 0) url.port else url.defaultPort
    return candidate.userInfo == null &&
        origin.protocol.equals(candidate.protocol, ignoreCase = true) &&
        origin.host.equals(candidate.host, ignoreCase = true) &&
        effectivePort(origin) == effectivePort(candidate)
}
