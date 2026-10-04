package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.AdminifenderApi
import com.digifender.adminifender.core.data.network.BootstrapPageDto
import com.digifender.adminifender.core.data.network.ChangesPageDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.SendResult

/** Scripted backend: queued answers per call, and a log of every request made. */
class FakeApi : AdminifenderApi {
    val changePages = ArrayDeque<ReadResult<ChangesPageDto>>()
    val bootstrapPages = ArrayDeque<ReadResult<BootstrapPageDto>>()
    val actionResults = ArrayDeque<SendResult>()
    val log = mutableListOf<String>()
    val sentKeys = mutableListOf<String>()

    override suspend fun changes(after: Long, limit: Int): ReadResult<ChangesPageDto> {
        log += "changes:$after"
        return changePages.removeFirstOrNull() ?: ReadResult.Ok(ChangesPageDto(emptyList(), after, false))
    }

    override suspend fun bootstrap(cursor: String?, limit: Int): ReadResult<BootstrapPageDto> {
        log += "bootstrap:${cursor ?: "-"}"
        return bootstrapPages.removeFirst()
    }

    override suspend fun concernAction(concernId: String, idempotencyKey: String, expectedRevision: Int, action: ConcernAction): SendResult {
        log += "action:$concernId:$expectedRevision:${action.kind.wire}"
        sentKeys += idempotencyKey
        return actionResults.removeFirst()
    }
}
