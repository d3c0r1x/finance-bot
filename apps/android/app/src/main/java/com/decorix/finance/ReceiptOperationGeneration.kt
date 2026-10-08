package com.decorix.finance

import java.util.concurrent.atomic.AtomicLong

internal object ReceiptOperationGeneration {
    private val generation = AtomicLong(0L)

    fun capture(): Long = generation.get()

    @Synchronized
    fun invalidate(): Long = generation.incrementAndGet()

    fun isCurrent(token: Long): Boolean = generation.get() == token

    @Synchronized
    fun <T> runIfCurrent(token: Long, action: () -> T): T? {
        if (!isCurrent(token)) return null
        return action()
    }
}
