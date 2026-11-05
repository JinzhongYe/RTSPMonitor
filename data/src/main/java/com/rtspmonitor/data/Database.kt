package com.rtspmonitor.data
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.rtspmonitor.core.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

@Entity data class Camera(@PrimaryKey val id: String, val name: String, val encryptedConnection: String)
@Entity data class Recording(@PrimaryKey val uri: String, val cameraId: String, val name: String, val createdAt: Long)
@Dao interface MonitorDao {
 @Query("SELECT * FROM Camera ORDER BY name") fun cameras(): Flow<List<Camera>>
 @Query("SELECT * FROM Camera WHERE id = :id") suspend fun camera(id: String): Camera?
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(camera: Camera)
 @Delete suspend fun delete(camera: Camera)
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(recording: Recording)
 @Query("SELECT * FROM Recording ORDER BY createdAt DESC") fun recordings(): Flow<List<Recording>>
 @Delete suspend fun delete(recording: Recording)
}
@Database(entities = [Camera::class, Recording::class], version = 1, exportSchema = false)
abstract class MonitorDatabase: RoomDatabase() { abstract fun dao(): MonitorDao }
class CredentialVault {
 private fun key(): SecretKey {
  val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  (store.getKey("rtsp-credentials-v1", null) as? SecretKey)?.let { return it }
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder("rtsp-credentials-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 fun encrypt(c: Connection): String {
  val payload = JSONObject().put("url", c.url).put("user", c.username).put("password", c.password).put("transport", c.transport.name).toString()
  val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
  return Base64.encodeToString(cipher.iv + cipher.doFinal(payload.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
 }
 fun decrypt(value: String): Connection {
  val bytes = Base64.decode(value, Base64.NO_WRAP)
  val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
  val p = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
  return Connection(p.getString("url"), p.getString("user"), p.getString("password"), Transport.valueOf(p.getString("transport")))
 }
}
