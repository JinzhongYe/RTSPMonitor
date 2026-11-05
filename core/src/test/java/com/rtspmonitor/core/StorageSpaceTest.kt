package com.rtspmonitor.core
import org.junit.Test
class StorageSpaceTest {
 @Test fun acceptsSpaceAboveReserve() { requireRecordingSpace(129L*1024*1024) }
 @Test(expected=InsufficientStorageException::class) fun reserveCannotBeConsumed() { requireRecordingSpace(128L*1024*1024) }
 @Test(expected=InsufficientStorageException::class) fun publicationNeedsSecondCopySpace() { requireRecordingSpace(200L*1024*1024,100L*1024*1024) }
 @Test(expected=InsufficientStorageException::class) fun oversizedPublicationCannotOverflow() { requireRecordingSpace(1,Long.MAX_VALUE) }
}
