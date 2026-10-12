package com.getvideo.app.data.repo

import java.io.File
import java.util.Base64
import org.json.JSONObject

/** Device-private Basic Auth configuration; never contains an account session. */
internal class ServerAccessStore(private val file: File) {
    fun authorization(): String? = runCatching {
        JSONObject(file.readText()).getString("authorization").takeIf { auth ->
            auth.startsWith("Basic ") && !auth.contains('\n') && !auth.contains('\r') &&
                String(Base64.getDecoder().decode(auth.removePrefix("Basic ")), Charsets.UTF_8)
                    .let { it.substringBefore(':').isNotBlank() && it.contains(':') && it.substringAfter(':').isNotEmpty() }
        }
    }.getOrNull()

    fun username(): String = authorization()?.let {
        String(Base64.getDecoder().decode(it.removePrefix("Basic ")), Charsets.UTF_8).substringBefore(':')
    }.orEmpty()

    fun candidate(username: String, password: String): String {
        val user = username.trim()
        require(user.isNotEmpty() && ':' !in user && '\n' !in user && '\r' !in user) { "请输入有效的服务器用户名" }
        if (password.isEmpty()) {
            require(user == username() && authorization() != null) { "请输入服务器密码" }
            return authorization()!!
        }
        require('\n' !in password && '\r' !in password) { "密码不能包含换行" }
        return "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
    }

    fun saveValidated(username: String, password: String, verify: (String) -> Unit) {
        val auth = candidate(username, password)
        verify(auth) // Never replace working credentials when validation fails.
        file.parentFile?.mkdirs()
        val staged = File(file.path + ".new")
        try {
            staged.writeText(JSONObject().put("authorization", auth).toString())
            check(staged.setReadable(false, false) && staged.setWritable(false, false) &&
                staged.setReadable(true, true) && staged.setWritable(true, true)) { "无法保护访问配置" }
            check(staged.renameTo(file)) { "无法保存访问配置" }
        } finally { staged.delete() }
    }
}
