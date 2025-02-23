// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.io

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.rules.TempDirectory
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

class ResizableMappedFileTest {
  @get: Rule
  val tempDir = TempDirectory()

  @get: Rule
  val disposable = DisposableRule()

  @Test
  fun testCacheMisses() {
    val fileCount = (StorageLockContext.getCacheMaxSize() / PagedFileStorage.MB + 10).toInt()
    val pageSize = PagedFileStorage.MB
    Assert.assertTrue(fileCount * pageSize > StorageLockContext.getCacheMaxSize())

    val directory = tempDir.newDirectory("resizable-mapped-files").toPath()
    val resizableMappedFiles = (0..fileCount).map {
      val file = ResizeableMappedFile(
        directory.resolve("map$it"),
        PagedFileStorage.MB,
        null,
        PagedFileStorage.MB,
        true
      )

      Disposer.register(disposable.disposable, Disposable {
        try {
          file.close()
        }
        catch (_: Exception) {

        }
      })

      file
    }

    StorageLockContext.forceDirectMemoryCache()
    // ensure we don't cache anything
    StorageLockContext.assertNoBuffersLocked()

    // fill the cache
    for (i in 0..fileCount) {
      if (i % 100 == 0) {
        println("$i of $fileCount")
      }
      resizableMappedFiles[i].write { putInt(0L, 239) }
    }

    // ensure we don't cache anything
    StorageLockContext.assertNoBuffersLocked()
    var stats = StorageLockContext.getStatistics()

    for (i in 0..fileCount) {
      if (i % 100 == 0) {
        println("$i of $fileCount")
      }
      resizableMappedFiles[i].write { putInt(0L, 239) }

      val statsAfterOp = StorageLockContext.getStatistics()

      val pageLoadDiff = statsAfterOp.pageLoad - stats.pageLoad
      val pageMissDiff = statsAfterOp.pageMiss - stats.pageMiss
      val pageHitDiff = statsAfterOp.pageHit - stats.pageHit
      val pageFastCacheHit = statsAfterOp.pageFastCacheHit - stats.pageFastCacheHit

      Assert.assertEquals(0, pageLoadDiff)
      Assert.assertEquals(1, pageMissDiff)
      Assert.assertEquals(0, pageHitDiff)
      Assert.assertEquals(0, pageFastCacheHit)

      stats = statsAfterOp
    }
  }

  private fun ResizeableMappedFile.write(op: ResizeableMappedFile.() -> Unit) {
    lockWrite()
    try {
      op()
    }
    finally {
      unlockWrite()
    }
  }
}