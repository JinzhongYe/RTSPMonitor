package com.rtspmonitor.core
import org.junit.Assert.*
import org.junit.Test
class PolicyTest {
 @Test fun namesCannotEscapeMoviesDirectory() { assertEquals("___a_b", safeCameraName("../a:b")); assertEquals("Camera", safeCameraName("")) }
 @Test fun preservesValidCameraNames() { assertEquals("Camera_01-门口", safeCameraName("Camera_01-门口")) }
 @Test fun connectionNeverPrintsSecrets() { assertFalse(Connection("rtsp://host/a", "secret-user", "secret-pass", Transport.TCP).toString().contains("secret")) }
 @Test(expected = IllegalArgumentException::class) fun rejectsNonRtsp() { Connection("file:///etc/passwd", "", "", Transport.TCP) }
}
