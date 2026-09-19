/**
 * Capsule MUSIC
 * Which artists the home feed reasons from.
 * GPL-3.0
 */

package com.nikhil.yt.viewmodels

import com.nikhil.yt.db.entities.Artist

/**
 * The artists to build the "more like" rows from: listened to first, followed to fill.
 *
 * Most played alone was the whole bug. On a fresh install nothing has been played, so the list was
 * empty and the feed had nothing to reason from — which meant the artists someone picked in the
 * welcome flow were subscribed and then changed nothing at all. Following an artist is a weaker
 * statement than listening to them for a fortnight, so it does not outrank it; it fills what is
 * left, which is everything on the first day and very little after that.
 *
 * Duplicates are dropped by id rather than trusted not to occur: an artist who is both followed
 * and played would otherwise take two of the three seeds and make the feed narrower than it is.
 */
internal fun feedArtistSeeds(
    played: List<Artist>,
    followed: List<Artist>,
    limit: Int,
): List<Artist> {
    if (limit <= 0) return emptyList()
    return (played + followed)
        .distinctBy { it.id }
        .take(limit)
}
