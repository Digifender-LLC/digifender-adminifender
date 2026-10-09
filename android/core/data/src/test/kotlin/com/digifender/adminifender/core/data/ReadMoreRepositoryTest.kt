package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.extraction.ExtractionScheduler
import com.digifender.adminifender.core.data.extraction.ReadMoreRepository
import com.digifender.adminifender.core.data.network.ReadMoreApi
import com.digifender.adminifender.core.data.network.ReadMoreOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadMoreRepositoryTest {
    private var answer: ReadMoreOutcome = ReadMoreOutcome.Requested(101)
    private var syncs = 0
    private var runs = 0
    private val repo = ReadMoreRepository(
        object : ReadMoreApi {
            override suspend fun readMore(attachmentId: String) = answer
        },
        object : ExtractionScheduler {
            override fun requestNow() {
                runs += 1
            }

            override fun schedulePeriodic() = Unit

            override fun cancel() = Unit
        },
    ) { syncs += 1 }

    @Test
    fun aQueuedRequestAsksForASyncAndSchedulesTheReader() = runBlocking {
        assertEquals(ReadMoreOutcome.Requested(101), repo.request(uuid(1)))
        assertEquals(1 to 1, syncs to runs)
    }

    @Test
    fun anythingElseSchedulesNothing() = runBlocking {
        for (other in listOf(ReadMoreOutcome.NothingToRead, ReadMoreOutcome.SessionExpired, ReadMoreOutcome.Unavailable("network"), ReadMoreOutcome.NotAvailable("not_eligible"))) {
            answer = other
            assertEquals(other, repo.request(uuid(1)))
        }
        assertEquals(0 to 0, syncs to runs)
    }
}
