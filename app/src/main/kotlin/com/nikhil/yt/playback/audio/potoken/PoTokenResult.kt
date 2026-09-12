package com.nikhil.yt.playback.audio.potoken

data class PoTokenResult(
    val playerRequestPoToken: String,
    val streamingDataPoToken: String,
) {
    companion object {
        /**
         * BotGuard binds the /player token to the video id, while the GVS /
         * streaming token is bound to visitorData. Keep that mapping in one
         * named factory so the two same-typed strings cannot be casually swapped.
         */
        internal fun fromBotGuard(
            videoIdPoToken: String,
            visitorDataPoToken: String,
        ) =
            PoTokenResult(
                playerRequestPoToken = videoIdPoToken,
                streamingDataPoToken = visitorDataPoToken,
            )
    }
}
