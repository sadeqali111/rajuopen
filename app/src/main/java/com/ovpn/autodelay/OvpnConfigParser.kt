package com.ovpn.autodelay

import java.io.InputStream
import java.util.zip.ZipInputStream

data class OvpnProfileItem(
    val name: String,
    val rawConfig: String,
    val host: String,
    val port: Int,
    var realDelayMs: Long = Long.MAX_VALUE
)

object OvpnConfigParser {

    fun parseFromZip(zipStream: InputStream): List<OvpnProfileItem> {
        val profiles = mutableListOf<OvpnProfileItem>()
        val seenHosts = mutableSetOf<String>()
        val remoteRegex = Regex("^remote\\s+(\\S+)\\s+(\\d+)", RegexOption.MULTILINE)

        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".ovpn", ignoreCase = true)) {
                    val content = zis.bufferedReader().readText()
                    val remoteMatch = remoteRegex.find(content)
                    if (remoteMatch != null) {
                        val host = remoteMatch.groupValues[1]
                        val port = remoteMatch.groupValues[2].toIntOrNull() ?: 443
                        val fileName = entry.name.substringAfterLast("/").removeSuffix(".ovpn")
                        val key = if (host.endsWith("lazerpenguin.com", ignoreCase = true)) host.lowercase() else "$host:$port:$fileName"
                        if (seenHosts.add(key)) {
                            profiles.add(
                                OvpnProfileItem(
                                    name = fileName,
                                    rawConfig = content,
                                    host = host,
                                    port = if (host.endsWith("lazerpenguin.com", ignoreCase = true)) 7011 else port
                                )
                            )
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return profiles
    }

    fun rewriteForLocalRelay(original: String, localPort: Int): String {
        val lines = original.lines().filterNot { line ->
            val t = line.trim().lowercase()
            t.startsWith("remote ") || t.startsWith("proto ") || t.startsWith("dev ")
        }
        val header = listOf(
            "dev tun",
            "proto tcp-client",
            "remote 127.0.0.1 $localPort"
        )
        var result = (header + lines).joinToString("\n")
        if (!result.contains("data-ciphers-fallback")) {
            result += "\ndata-ciphers-fallback AES-256-CBC\n"
        }
        return result
    }
}
