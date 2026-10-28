package com.getvideo.app.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface GetVideoApi {
    @GET("v2/recreations/capabilities") suspend fun recreationCapabilities(): RecreationCapabilities
    @GET("v2/recreations") suspend fun recreationProjects(): List<RecreationProjectDto>
    @POST("v2/recreations/upload") suspend fun recreationUpload(
        @Query("request_key") key: String, @Query("title") title: String,
        @Query("analyze_audio") analyzeAudio: Boolean, @Body video: okhttp3.RequestBody,
    ): RecreationProjectDto
    @POST("v2/recreations/from-track/{trackId}") suspend fun recreationFromTrack(@Path("trackId") trackId: Long, @Body body: RecreationKeyBody): RecreationProjectDto
    @GET("v2/recreations/{id}") suspend fun recreationProject(@Path("id") id: String): RecreationProjectDto
    @POST("v2/recreations/{id}/analyze") suspend fun recreationAnalyze(@Path("id") id: String, @Body body: RecreationAnalyzeBody): RecreationProjectDto
    @PUT("v2/recreations/{id}/description") suspend fun recreationEdit(@Path("id") id: String, @Body body: RecreationDescriptionEdit): RecreationProjectDto
    @POST("v2/recreations/{id}/generate") suspend fun recreationGenerate(@Path("id") id: String, @Body body: RecreationGenerateBody): RecreationProjectDto
    @POST("v2/recreations/{id}/runs/{runId}/cancel") suspend fun recreationCancel(@Path("id") id: String, @Path("runId") runId: String): RecreationProjectDto
    @POST("v2/recreations/{id}/runs/{runId}/save") suspend fun recreationSave(@Path("id") id: String, @Path("runId") runId: String): TrackDto

    @GET("account/platforms") suspend fun platformLoginCapabilities(): List<PlatformLoginCapability>
    @GET("account/platform-bindings") suspend fun platformAccountBindings(): List<PlatformAccountBinding>
    @POST("account/platform-auth/start") suspend fun platformAuthStart(@Body body: PlatformAuthStart): PlatformAuthTicket
    @POST("account/platform-auth/status") suspend fun platformAuthStatus(@Body body: PlatformAuthProof): PlatformAuthStatus
    @POST("account/platform-auth/image") suspend fun platformAuthImage(@Body body: PlatformAuthProof): okhttp3.ResponseBody
    @POST("account/platform-auth/cancel") suspend fun platformAuthCancel(@Body body: PlatformAuthProof): Map<String, Boolean>
    @POST("account/platform-auth/complete") suspend fun platformAuthComplete(@Body body: PlatformAuthProof): PlatformAuthCompletion
    @POST("account/platform-bindings/{platform}/unbind") suspend fun platformAccountUnbind(@Path("platform") platform: String, @Body body: Map<String, String>): Map<String, Boolean>

    @POST("v2/migration/claim") suspend fun claimMigration(@Body body: MigrationClaim): MigrationResult
    @POST("account/register") suspend fun accountRegister(@Body body: AccountCredentials): AccountSession
    @POST("account/login") suspend fun accountLogin(@Body body: AccountCredentials): AccountSession
    @GET("account/me") suspend fun accountMe(): AccountIdentity
    @POST("account/logout") suspend fun accountLogout(): Map<String, Boolean>
    @POST("agent/v2/chat") suspend fun extractionAgent(@Body body: ExtractionAgentRequest): ExtractionAgentResponse
    @GET("agent/v2/conversations") suspend fun agentConversations(): List<AgentConversationDto>
    @GET("agent/v2/conversations/{id}") suspend fun agentHistory(@Path("id") id: String): AgentHistoryDto
    @DELETE("agent/v2/conversations/{id}") suspend fun agentClear(@Path("id") id: String): Map<String, Boolean>
    @POST("agent/v2/actions/{id}/claim") suspend fun agentClaim(@Path("id") id: String, @Body body: AgentClaim): AgentActionDto
    @POST("agent/v2/actions/{id}/result") suspend fun agentResult(@Path("id") id: String, @Body body: AgentToolResult): AgentActionDto


    @POST("v2/diagnostics/extractions")
    suspend fun uploadExtractionFailure(@Body body: ExtractionFailureReport): ExtractionFailureAck

    @GET("v2/diagnostics/extractions")
    suspend fun extractionFailures(@Query("limit") limit: Int = 50): List<ExtractionFailureDetail>

    @GET("v2/diagnostics/extractions/{report_id}")
    suspend fun extractionFailure(@Path("report_id") reportId: String): ExtractionFailureDetail

    @POST("agent/chat")
    suspend fun agentChat(@Body body: AgentChatRequest): AgentChatResponse

    @GET("v2/memories/{userId}")
    suspend fun agentMemories(@Path("userId") userId: String): List<AgentMemoryOut>

    @POST("v2/memories")
    suspend fun agentMemoryCreate(@Body body: AgentMemoryRequest): AgentMemoryOut

    @PUT("v2/memories/{userId}/{memoryId}")
    suspend fun agentMemoryUpdate(
        @Path("userId") userId: String,
        @Path("memoryId") memoryId: Long,
        @Body body: AgentMemoryUpdate,
    ): AgentMemoryOut

    @DELETE("v2/memories/{userId}/{memoryId}")
    suspend fun agentMemoryDelete(
        @Path("userId") userId: String,
        @Path("memoryId") memoryId: Long,
    ): Map<String, Boolean>

    @POST("v2/extract")
    suspend fun extract(@Body body: ExtractRequest): ExtractResponse

    @GET("v2/tracks")
    suspend fun tracks(
        @Query("q") query: String? = null,
        @Query("platform") platform: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): List<TrackDto>

    @GET("v2/sync")
    suspend fun sync(
        @Query("cursor") cursor: Long,
        @Query("limit") limit: Int = 200,
    ): SyncResponse

    @POST("v2/platforms/qr")
    suspend fun loginStart(@Body body: LoginStartRequest): LoginStartResponse

    @GET("v2/platforms/qr/{sessionId}")
    suspend fun loginPoll(@Path("sessionId") sessionId: String): LoginPollResponse

    @POST("v2/platforms/cookies")
    suspend fun importCookies(@Body body: CookieImportRequest): Map<String, String>

    @GET("v2/platforms/credentials")
    suspend fun credentials(): List<CredentialDto>

    @DELETE("v2/platforms/credentials/{platform}")
    suspend fun revoke(
        @Path("platform") platform: String,
    ): Map<String, Boolean>
}
