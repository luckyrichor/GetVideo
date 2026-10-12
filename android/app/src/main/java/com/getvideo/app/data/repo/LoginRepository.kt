package com.getvideo.app.data.repo

import android.content.Context
import com.getvideo.app.data.remote.CookieImportRequest
import com.getvideo.app.data.remote.CredentialDto
import com.getvideo.app.data.remote.LoginPollResponse
import com.getvideo.app.data.remote.LoginStartRequest
import com.getvideo.app.data.remote.LoginStartResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LoginRepository(context: Context, private val owner: AccountStore.Account) {

    private val settings = Settings(context)
    private suspend fun api(): com.getvideo.app.data.remote.GetVideoApi {
        val server = settings.serverUrlNow()
        check(accountResponseIsCurrent(owner, AccountStore.forServer(server), server)) { "账号或服务器已切换，请重试" }
        return ApiProvider.api(owner.serverUrl, owner)
    }

    suspend fun start(platform: String): Result<LoginStartResponse> = runCatching {
        withContext(Dispatchers.IO) {
            api()
                .loginStart(LoginStartRequest(platform = platform))
        }
    }

    suspend fun poll(sessionId: String): Result<LoginPollResponse> = runCatching {
        withContext(Dispatchers.IO) {
            api().loginPoll(sessionId)
        }
    }

    suspend fun importCookies(platform: String, cookies: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            api().importCookies(
                CookieImportRequest(platform = platform, cookies = cookies)
            )
            Unit
        }
    }

    suspend fun credentials(): Result<List<CredentialDto>> = runCatching {
        withContext(Dispatchers.IO) {
            api().credentials()
        }
    }

    suspend fun revoke(platform: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            api().revoke(platform)
            Unit
        }
    }

    /** 二维码图片的完整地址，交给 Coil 加载。 */
    suspend fun qrImageUrl(path: String): String =
        owner.serverUrl.trimEnd('/') + path
}
