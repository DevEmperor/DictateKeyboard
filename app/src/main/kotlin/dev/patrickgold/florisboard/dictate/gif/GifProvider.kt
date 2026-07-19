/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.gif

import kotlinx.serialization.Serializable

/**
 * A single GIF search result in a provider-agnostic shape. Concrete providers (e.g.
 * [KlipyGifProvider]) map their API responses onto this so the UI never depends on a specific
 * backend — swapping providers later is a one-file change. Serializable so recently used GIFs can
 * be persisted in [GifHistory].
 */
@Serializable
data class GifItem(
    /** Provider-specific id/slug, used for optional share/usage ranking pings. */
    val id: String,
    val title: String?,
    /** A small animated preview (GIF or WebP) shown in the results grid. */
    val previewUrl: String,
    /** The full-quality `image/gif` URL that gets downloaded and inserted into the editor. */
    val fullGifUrl: String,
    /** Intrinsic size of the preview, used to lay the grid out with the correct aspect ratio. */
    val width: Int,
    val height: Int,
)

/** One page of GIF results plus whether another page can be requested. */
data class GifPage(
    val items: List<GifItem>,
    val hasNext: Boolean,
) {
    companion object {
        val Empty = GifPage(emptyList(), hasNext = false)
    }
}

/**
 * Abstraction over an online GIF search backend. Implementations must be safe to call from a
 * coroutine (they perform network I/O internally on [kotlinx.coroutines.Dispatchers.IO]).
 */
interface GifProvider {
    /** Whether the provider has everything it needs to run (e.g. a non-blank API key). */
    val isConfigured: Boolean

    /** Trending GIFs, [page] is 1-based. Throws on network/HTTP/parse errors. */
    suspend fun trending(page: Int): GifPage

    /** Search GIFs for [query], [page] is 1-based. Throws on network/HTTP/parse errors. */
    suspend fun search(query: String, page: Int): GifPage

    /**
     * Best-effort "this GIF was used/shared" ping to improve ranking. Never throws — failures are
     * swallowed, since this is purely advisory.
     */
    suspend fun registerShare(id: String)

    companion object {
        const val PER_PAGE = 24
    }
}
