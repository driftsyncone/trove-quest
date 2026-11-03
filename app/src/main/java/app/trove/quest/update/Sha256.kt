package app.trove.quest.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
  /** Lowercase hex SHA-256 of everything left in [input] (not closed). */
  fun hex(input: InputStream): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(1 shl 16)
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  fun hex(file: File): String = file.inputStream().use(::hex)

  /** True if [file] exists and hashes to [expected] (hex, any case). */
  fun matches(file: File, expected: String): Boolean =
      file.isFile && hex(file).equals(expected.trim(), ignoreCase = true)
}
