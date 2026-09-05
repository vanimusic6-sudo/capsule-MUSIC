package com.nikhil.yt.db

/** Semantic conversions needed when upgrading directly across historic Room schemas. */
internal fun legacyLibraryValue(table: String, column: String, oldColumns: Set<String>): String? {
    fun old(vararg names: String): String? = names.firstOrNull { it in oldColumns }?.let { "`$it`" }
    val migrationTimestamp = "CAST(strftime('%s', 'now') AS INTEGER) * 1000"
    return when (table to column) {
        "song" to "inLibrary" -> old("createDate", "create_date")
        "song" to "createDate" -> old("create_date")
        "song" to "modifyDate" -> old("modify_date")
        "song" to "downloadState" -> old("download_state")
        "album" to "bookmarkedAt" -> old("lastUpdateTime")
        "playlist" to "createdAt" -> old("createDate") ?: migrationTimestamp
        "playlist" to "lastUpdateTime" -> migrationTimestamp
        "playlist" to "bookmarkedAt" -> old("lastUpdateTime") ?: migrationTimestamp
        "playlist" to "browseId" -> if ("id" in oldColumns) "CASE WHEN id NOT LIKE 'LP%' THEN id ELSE NULL END" else null
        else -> null
    }
}
