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
        val certs = mutableMapOf<String, String>()
        val rawOvpnFiles = mutableListOf<Pair<String, String>>()

        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast("/")
                    when {
                        fileName.endsWith(".crt", ignoreCase = true) ||
                        fileName.endsWith(".pem", ignoreCase = true) -> {
                            certs[fileName.lowercase()] = zis.bufferedReader().readText().trim()
                        }
                        fileName.endsWith(".ovpn", ignoreCase = true) -> {
                            rawOvpnFiles.add(fileName to zis.bufferedReader().readText())
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val profiles = mutableListOf<OvpnProfileItem>()
        val seenHosts = mutableSetOf<String>()
        val remoteRegex = Regex("^remote\\s+(\\S+)\\s+(\\d+)", RegexOption.MULTILINE)
        val caRegex = Regex("^ca\\s+(\\S+)", RegexOption.MULTILINE)

        for ((fileName, rawContent) in rawOvpnFiles) {
            var content = rawContent
            val caMatch = caRegex.find(content)
            if (caMatch != null) {
                val caFile = caMatch.groupValues[1].trim().substringAfterLast("/").lowercase()
                val certBody = certs[caFile] ?: certs.values.firstOrNull()
                if (certBody != null) {
                    content = content.replace(caRegex, "")
                    content += "\n<ca>\n$certBody\n</ca>\n"
                }
            }

            val remoteMatch = remoteRegex.find(content)
            if (remoteMatch != null) {
                val host = remoteMatch.groupValues[1]
                val port = remoteMatch.groupValues[2].toIntOrNull() ?: 443
                val profileName = fileName.removeSuffix(".ovpn")
                val isTb = host.endsWith("lazerpenguin.com", ignoreCase = true)
                val key = if (isTb) host.lowercase() else "$host:$port:$profileName"
                if (seenHosts.add(key)) {
                    profiles.add(
                        OvpnProfileItem(
                            name = profileName,
                            rawConfig = content,
                            host = host,
                            port = if (isTb) 7011 else port
                        )
                    )
                }
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
