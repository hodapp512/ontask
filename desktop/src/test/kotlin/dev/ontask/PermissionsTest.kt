package dev.ontask

import dev.ontask.track.PermState
import dev.ontask.track.PermissionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionsTest {
    @Test fun parsesHelperPermissions() {
        val o = Json.parseToJsonElement(
            """{"accessibility":false,"screenRecording":true,"automation":[{"bundleId":"com.google.Chrome","name":"Google Chrome","status":"granted"},{"bundleId":"com.apple.Safari","name":"Safari","status":"not_asked"}]}""",
        ).jsonObject
        val s = PermissionStatus.parse(o)
        assertEquals(PermState.NOT_ASKED, s.accessibility)
        assertEquals(PermState.GRANTED, s.screenRecording)
        assertEquals(listOf(PermState.GRANTED, PermState.NOT_ASKED), s.browsers.map { it.state })
        assertEquals(PermissionStatus.unknown, PermissionStatus.parse(null))
    }
}
