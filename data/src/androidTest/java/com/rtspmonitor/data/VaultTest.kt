package com.rtspmonitor.data
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rtspmonitor.core.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class) class VaultTest {
 @Test fun credentialsRoundTripAndTamperingFails() {
  val vault=CredentialVault(); val original=Connection("rtsp://127.0.0.1/test", "user-secret", "password-secret", Transport.UDP)
  val encrypted=vault.encrypt(original)
  assertFalse(encrypted.contains("password-secret")); assertFalse(encrypted.contains("127.0.0.1"))
  val restored=vault.decrypt(encrypted); assertEquals(original.password,restored.password); assertEquals(original.url,restored.url); assertEquals(Transport.UDP,restored.transport)
  val bytes=android.util.Base64.decode(encrypted,android.util.Base64.NO_WRAP); bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
  assertTrue(runCatching { vault.decrypt(android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)) }.isFailure)
 }
}
