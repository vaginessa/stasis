package stasis.client.encryption.secrets

import org.apache.pekko.util.ByteString
import at.favre.lib.hkdf.HKDF
import stasis.shared.model.users.User
import stasis.shared.secrets.SecretsConfig

final case class UserHashedEncryptionPassword(
  user: User.Id,
  private val hashedPassword: ByteString
)(implicit target: SecretsConfig)
    extends Secret {
  def toEncryptionSecret: UserEncryptionSecret = {
    val salt = user.toBytes
    val keyInfo = ByteString(s"${user.toString}-encryption-key")
    val ivInfo = ByteString(s"${user.toString}-encryption-iv")

    val hkdf = HKDF.fromHmacSha512()

    val prk = hkdf.extract(salt, hashedPassword.toArray)

    val key = hkdf.expand(prk, keyInfo.toArray, target.encryption.deviceSecret.keySize)
    val iv = hkdf.expand(prk, ivInfo.toArray, target.encryption.deviceSecret.ivSize)

    UserEncryptionSecret(user = user, key = ByteString(key), iv = ByteString(iv))
  }
}

object UserHashedEncryptionPassword {
  def apply(
    user: User.Id,
    hashedPassword: ByteString
  )(implicit target: SecretsConfig): UserHashedEncryptionPassword =
    new UserHashedEncryptionPassword(user, hashedPassword)
}
