package com.air.advantage.aaservice.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CanQueueConcurrencyTest {
    private val noOpSink =
        object : UartEventSink {
            override fun onPollData(
                tag: String,
                payload: ByteArray,
            ) {}

            override fun onRawCan(payload: ByteArray) {}
        }

    private val typeBytes = "17".toByteArray(Charsets.UTF_8)
    private val appStoreBytes = "MyAir5".toByteArray(Charsets.UTF_8)

    private fun engine(): UartDispatchEngine =
        UartDispatchEngine(listOf("getClock"), typeBytes, appStoreBytes, noOpSink)

    private val clockPayload = "<request>getClock</request>".toByteArray(Charsets.UTF_8)
    private val getCanPayload = "getCAN 12345".toByteArray(Charsets.UTF_8)

    private fun idsIn(frame: String): List<Int> {
        val content = frame.removePrefix("<U>").substringBefore("</U=")
        return content.split(" ").drop(1)
            .filter { it.isNotEmpty() && it.all(Char::isDigit) }
            .map { it.toInt() }
    }

    @Test
    fun `concurrent enqueue and dispatch loses no CAN ids and throws no exceptions`() {
        val e = engine()

        val producerCount = 4
        val idsPerProducer = 250
        val totalIds = producerCount * idsPerProducer

        val startLatch = CountDownLatch(1)
        val producerDone = CountDownLatch(producerCount)
        val stopConsumer = AtomicBoolean(false)
        val consumerFrames = mutableListOf<String>()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        val executor = Executors.newFixedThreadPool(producerCount + 1)

        val consumer =
            executor.submit<Unit> {
                try {
                    startLatch.await()
                    var i = 0
                    while (!stopConsumer.get() && i < 100_000) {
                        e.onPing()?.let { consumerFrames.add(String(it, Charsets.UTF_8)) }
                        if (i % 2 == 0) e.onFrame(clockPayload) else e.onFrame(getCanPayload)
                        i++
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }

        val producers =
            (0 until producerCount).map { p ->
                executor.submit<Unit> {
                    try {
                        startLatch.await()
                        val base = p * idsPerProducer
                        (1..idsPerProducer).chunked(10).forEach { chunk ->
                            e.enqueueCanIds(chunk.map { (base + it).toString() })
                            e.enqueueDirectMessage("dm-$p-${chunk.first()}")
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        producerDone.countDown()
                    }
                }
            }

        startLatch.countDown()
        assertTrue("producers timed out", producerDone.await(30, TimeUnit.SECONDS))
        stopConsumer.set(true)
        consumer.get(30, TimeUnit.SECONDS)
        producers.forEach { it.get(30, TimeUnit.SECONDS) }
        executor.shutdown()
        assertTrue("no worker threads may throw", errors.isEmpty())

        val drainFrames = mutableListOf<String>()
        repeat(2000) {
            e.onFrame(getCanPayload)
            repeat(4) {
                e.onPing()?.let { drainFrames.add(String(it, Charsets.UTF_8)) }
            }
            e.onFrame(clockPayload)
            repeat(2) {
                e.onPing()?.let { drainFrames.add(String(it, Charsets.UTF_8)) }
            }
        }

        val allSetCanFrames = (consumerFrames + drainFrames).filter { it.startsWith("<U>setCAN ") }
        val drained = allSetCanFrames.distinct().flatMap { idsIn(it) }.toSet()

        assertEquals(totalIds, drained.size)
        assertEquals((1..totalIds).toSet(), drained)
    }
}
