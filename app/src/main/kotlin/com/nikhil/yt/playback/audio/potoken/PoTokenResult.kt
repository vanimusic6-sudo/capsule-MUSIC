package com.nikhil.yt.playback.audio.potoken

data class PoTokenResult(
    val playerRequestPoToken: String,
    val streamingDataPoToken: String,
) {
    companion object {
        /**
         * BotGuard's two tokens have intentionally non-obvious scopes in InnerTubeX:
         * the token sent with the /player request is bound to visitor/session data,
         * while the token appended to the GoogleVideo streaming URL is bound to the
         * concrete video id. Keep that mapping in one named factory so the two
         * same-typed strings cannot be casually swapped.
         *
         * This mirrors InnerTubeX's PoTokenBinding contract:
         * VISITOR_DATA -> playerRequestPoToken, VIDEO_ID -> streamingDataPoToken.
         */
        internal fun fromBotGuard(
            videoIdPoToken: String,
            visitorDataPoToken: String,
        ) =
            PoTokenResult(
                playerRequestPoToken = visitorDataPoToken,
                streamingDataPoToken = videoIdPoToken,
            )
    }
}
