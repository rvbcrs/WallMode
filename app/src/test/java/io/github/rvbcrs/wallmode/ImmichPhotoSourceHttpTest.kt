package io.github.rvbcrs.wallmode

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ImmichPhotoSourceHttpTest {
    private val albumId = id(1)
    private val key = "s".repeat(64)

    @Test
    fun forbiddenSearchFallsBackToTimelineAndFiltersVideosTrashAndDuplicates() {
        withServer(
            buckets = """[{"timeBucket":"2026-07-01"},{"timeBucket":"2026-06-01"}]""",
            bucket = { query ->
                if (query.endsWith("2026-07-01")) {
                    columns(listOf(id(2), id(3), id(4)), listOf(true, false, true), listOf(false, false, true))
                } else {
                    columns(listOf(id(2), id(5)), listOf(true, true), listOf(false, false))
                }
            }
        ) { share, requests ->
            assertEquals(listOf(id(2), id(5)), ImmichPhotoSource.loadAssetIds(share).getOrThrow())
            assertEquals(listOf(
                "GET /api/shared-links/me",
                "POST /api/search/metadata",
                "GET /api/timeline/buckets?albumId=$albumId",
                "GET /api/timeline/bucket?albumId=$albumId&timeBucket=2026-07-01",
                "GET /api/timeline/bucket?albumId=$albumId&timeBucket=2026-06-01"
            ), requests)
        }
    }

    @Test
    fun successfulSearchKeepsExistingPaginationWithoutUsingTimeline() {
        withServer(searchStatus = 200) { share, requests ->
            assertEquals(listOf(id(2), id(3)), ImmichPhotoSource.loadAssetIds(share).getOrThrow())
            assertEquals(listOf("GET /api/shared-links/me", "POST /api/search/metadata", "POST /api/search/metadata"), requests)
        }
    }

    @Test
    fun otherHttpFailuresDoNotTriggerTimelineFallback() {
        withServer(searchStatus = 401) { share, requests ->
            val error = ImmichPhotoSource.loadAssetIds(share).exceptionOrNull()
            assertEquals("Immich returned HTTP 401", error?.message)
            assertTrue(key !in error.toString())
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun emptyTimelineIsAnEmptyAlbum() {
        withServer(buckets = "[]") { share, requests ->
            assertEquals(emptyList<String>(), ImmichPhotoSource.loadAssetIds(share).getOrThrow())
            assertEquals(3, requests.size)
        }
    }

    @Test
    fun malformedTimelineColumnsAndIdsAreRejected() {
        listOf(
            """{"id":["${id(2)}"],"isImage":[],"isTrashed":[false]}""",
            """{"id":["${id(2)}"],"isImage":[true]}""",
            """{"id":["${id(2)}"],"isImage":[true],"isTrashed":[null]}""",
            """{"id":["invalid"],"isImage":[true],"isTrashed":[false]}"""
        ).forEach { invalid ->
            withServer(bucket = { invalid }) { share, _ ->
                assertTrue(ImmichPhotoSource.loadAssetIds(share).isFailure)
            }
        }
    }

    @Test
    fun assetLimitStopsBeforeRequestingAnotherBucket() {
        val assets = (2..1002).map(::id)
        withServer(
            buckets = """[{"timeBucket":"2026-07-01"},{"timeBucket":"2026-06-01"}]""",
            bucket = { columns(assets, assets.map { true }, assets.map { false }) }
        ) { share, requests ->
            assertEquals(assets.take(1000), ImmichPhotoSource.loadAssetIds(share).getOrThrow())
            assertEquals(4, requests.size)
        }
    }

    @Test
    fun invalidBucketCannotInjectQueryParameters() {
        withServer(buckets = """[{"timeBucket":"2026-07-01&albumId=other"}]""") { share, requests ->
            assertTrue(ImmichPhotoSource.loadAssetIds(share).isFailure)
            assertEquals(3, requests.size)
        }
    }

    private fun withServer(
        searchStatus: Int = 403,
        buckets: String = """[{"timeBucket":"2026-07-01"}]""",
        bucket: (String) -> String = { columns(emptyList(), emptyList(), emptyList()) },
        checkResult: (String, List<String>) -> Unit
    ) {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val query = session.queryParameterString.orEmpty()
                requests += "${session.method} ${session.uri}${if (query.isEmpty()) "" else "?$query"}"
                val authenticated = session.headers["x-immich-share-key"] == key
                var status = if (authenticated) 200 else 401
                val body = when (session.uri) {
                    "/api/shared-links/me" -> """{"type":"ALBUM","album":{"id":"$albumId"}}"""
                    "/api/search/metadata" -> {
                        status = if (authenticated) searchStatus else 401
                        val files = mutableMapOf<String, String>()
                        session.parseBody(files)
                        val page = JSONObject(files.getValue("postData")).getInt("page")
                        """{"assets":{"items":[{"id":"${id(page + 1)}"}],"nextPage":${if (page == 1) "2" else "null"}}}"""
                    }
                    "/api/timeline/buckets" -> buckets
                    "/api/timeline/bucket" -> bucket(query)
                    else -> { status = 404; "{}" }
                }
                return newFixedLengthResponse(Response.Status.lookup(status), "application/json", body)
            }
        }
        server.start()
        try {
            checkResult("http://127.0.0.1:${server.listeningPort}/share/$key", requests)
        } finally {
            server.stop()
        }
    }

    private fun id(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"

    private fun columns(ids: List<String>, images: List<Boolean>, trashed: List<Boolean>) = JSONObject()
        .put("id", JSONArray(ids)).put("isImage", JSONArray(images)).put("isTrashed", JSONArray(trashed)).toString()
}
