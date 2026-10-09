package com.digifender.adminifender.core.data.extraction

/** Asks the platform to run [DeviceExtractor] when conditions allow. WorkManager is not an exact-time service (lld.md#text-extraction-and-ocr). */
interface ExtractionScheduler {
    /** Run soon: after a sync or when the owner signs in. Asking twice is one run. */
    fun requestNow()

    /** The slow cadence that picks up files once the phone is on power and network again. */
    fun schedulePeriodic()

    /** Sign-out and session end: running work is cancelled, which hands the file back. */
    fun cancel()
}
