package com.my.kizzy.rpc

import org.junit.Assert.*
import org.junit.Test

class DiscordUserInfoTest {
    @Test fun parsesDisplayNameAndFallsBackForMissingOrNullNames() {
        assertEquals(UserInfo("capsule", "Capsule Music"),
            parseDiscordUserInfo("""{"username":"capsule","global_name":"Capsule Music"}"""))
        assertEquals(UserInfo("capsule", "capsule"), parseDiscordUserInfo("""{"username":"capsule"}"""))
        assertEquals(UserInfo("capsule", "capsule"),
            parseDiscordUserInfo("""{"username":"capsule","global_name":null}"""))
    }
    @Test fun malformedUserResponsesRemainFailures() {
        assertTrue(runCatching { parseDiscordUserInfo("""{"global_name":"Capsule"}""") }.isFailure)
        assertTrue(runCatching { parseDiscordUserInfo("not json") }.isFailure)
    }
}
