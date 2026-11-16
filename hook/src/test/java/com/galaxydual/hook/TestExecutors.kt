// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
package com.galaxydual.hook

import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// T-F10: shared test executor for the busy-worker / stuck-breaker suites.
// submit() always throws RejectedExecutionException (worker permanently busy);
// still a ThreadPoolExecutor so gate.queueDepth()/activeCount() introspect a
// real (empty, zero-queue) queue instead of the -1 custom-executor sentinel.
internal class AlwaysRejectingExecutor :
    ThreadPoolExecutor(0, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue()) {
    override fun <T> submit(task: Callable<T>): Future<T> =
        throw RejectedExecutionException("busy")
    override fun <T> submit(task: Runnable, result: T): Future<T> =
        throw RejectedExecutionException("busy")
    override fun submit(task: Runnable): Future<*> =
        throw RejectedExecutionException("busy")
}
