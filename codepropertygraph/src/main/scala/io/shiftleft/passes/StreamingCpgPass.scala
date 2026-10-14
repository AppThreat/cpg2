package io.shiftleft.passes

import io.shiftleft.SerializedCpg
import io.shiftleft.codepropertygraph.Cpg

import java.util.concurrent.{Executors, LinkedBlockingQueue, Semaphore}
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService, Future}

object StreamingCpgPass:
    import PassConfig.{cores, intProp}

    /** Tuning knobs, overridable via system properties so that large-graph runs can be tuned
      * without recompiling. Any unset, non-numeric or non-positive value falls back to the default.
      *   - `odb.streamingpass.writerQueueCapacity`: bounded handoff queue between producers and the
      *     single writer thread.
      *   - `odb.streamingpass.producerQueueCapacity`: max number of in-flight producer tasks
      *     (backpressure).
      *   - `odb.streamingpass.writerBatchSize`: how many DiffGraphs the writer accumulates before
      *     applying them, amortising writer wake-ups on large graphs.
      */
    private[passes] def writerQueueCapacity: Int =
        intProp("odb.streamingpass.writerQueueCapacity", Math.max(2, (0.5 * cores).toInt))
    private[passes] def producerQueueCapacity: Int =
        intProp("odb.streamingpass.producerQueueCapacity", Math.max(4, (0.7 * cores).toInt))
    private[passes] def writerBatchSize: Int =
        intProp("odb.streamingpass.writerBatchSize", 32)
end StreamingCpgPass

/** A replacement for ConcurrentWriterCpgPass with significantly lower memory usage.
  *
  * Parts run concurrently, and each DiffGraph goes to the writer as soon as it is ready. The writer
  * applies them in part order, holding one that finishes early until the parts before it are
  * applied, so node ids (and everything derived from their order) are the same on every run. A
  * part's slot is freed only once its DiffGraph is applied, so at most `producerQueueCapacity`
  * DiffGraphs are running or waiting at any time.
  */
abstract class StreamingCpgPass[T <: AnyRef](
  cpg: Cpg,
  @nowarn outName: String = "",
  keyPool: Option[KeyPool] = None
) extends NewStyleCpgPassBase[T]:

    @volatile private var nDiffT = -1

    protected lazy val cpuOptimizedExecutionContext: ExecutionContextExecutorService =
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        ExecutionContext.fromExecutorService(executor)

    override def finish(): Unit =
        try
            if cpuOptimizedExecutionContext ne null then cpuOptimizedExecutionContext.shutdownNow()
        catch
            case _: Exception =>
        finally
            super.finish()

    override def createApplySerializeAndStore(
      serializedCpg: SerializedCpg,
      inverse: Boolean = false,
      prefix: String = ""
    ): Unit =
        val producerQueueCapacity = StreamingCpgPass.producerQueueCapacity

        nDiffT = -1
        init()
        val parts  = generateParts()
        val nParts = parts.length

        if nParts == 0 then return

        val semaphore    = new Semaphore(producerQueueCapacity)
        val writer       = new Writer(semaphore)
        val writerThread = new Thread(writer)
        writerThread.setName("StreamingCpgPass Writer")
        writerThread.start()
        implicit val ec: ExecutionContext = this.cpuOptimizedExecutionContext

        // The writer frees a slot once it has applied a part, so a writer that died frees none.
        def acquireSlot(): Unit =
            while !semaphore.tryAcquire(1, 100, java.util.concurrent.TimeUnit.MILLISECONDS) do
                if writer.raisedException.isDefined || !writerThread.isAlive then
                    throw new RuntimeException(
                      "Writer thread failed while waiting for workers",
                      writer.raisedException.orNull
                    )

        try
            var index = 0
            while index < nParts && writer.raisedException.isEmpty do
                acquireSlot()
                val part      = parts(index)
                val partIndex = index
                Future {
                    try
                        val builder = new DiffGraphBuilder
                        runOnPart(builder, part.asInstanceOf[T])
                        val diff = builder.build()
                        if writer.raisedException.isEmpty then
                            writer.queue.put(Some((partIndex, diff)))
                    catch
                        case t: Throwable =>
                            val e = t match
                                case exception: Exception => exception
                                case _                    => new RuntimeException(t)
                            writer.raisedException = Some(e)
                            semaphore.release()
                            try writer.queue.offer(None)
                            catch case _: Exception => ()
                }
                index += 1
            end while
            // every part has been applied once all the slots are free again
            (0 until producerQueueCapacity).foreach(_ => acquireSlot())
        finally
            try
                if writer.raisedException.isEmpty then writer.queue.put(None)
                writerThread.join()
                if writer.raisedException.isDefined then
                    throw new RuntimeException(
                      "Failure in StreamingCpgPass",
                      writer.raisedException.get
                    )
            finally finish()
        end try
    end createApplySerializeAndStore

    private class Writer(slots: Semaphore) extends Runnable:
        private val batchSize = StreamingCpgPass.writerBatchSize
        val queue = new LinkedBlockingQueue[Option[(Int, overflowdb.BatchedUpdate.DiffGraph)]](
          StreamingCpgPass.writerQueueCapacity
        )

        @volatile var raisedException: Option[Exception] = None

        override def run(): Unit =
            try
                nDiffT = 0
                val batchBuffer = new java.util.ArrayList[overflowdb.BatchedUpdate.DiffGraph](
                  batchSize
                )
                // DiffGraphs of parts that finished before the part due next, by part index
                val waiting = scala.collection.mutable.HashMap
                    .empty[Int, overflowdb.BatchedUpdate.DiffGraph]
                var due = 0
                while true do
                    if raisedException.isDefined then return

                    queue.take() match
                        case None =>
                            flushBatch(batchBuffer)
                            return
                        case Some((index, diffGraph)) =>
                            waiting.put(index, diffGraph)
                            while waiting.contains(due) do
                                batchBuffer.add(waiting.remove(due).get)
                                due += 1
                            // A part keeps its slot until it is applied, so a batch held back
                            // while nothing else is ready could leave every producer waiting.
                            if batchBuffer.size() >= batchSize || queue.isEmpty then
                                flushBatch(batchBuffer)
            catch
                case exception: InterruptedException => Thread.currentThread().interrupt()
                case t: Throwable =>
                    val e = t match
                        case exception: Exception => exception
                        case _                    => new RuntimeException(t)
                    raisedException = Some(e)
                    queue.clear()

        private def flushBatch(batch: java.util.ArrayList[overflowdb.BatchedUpdate.DiffGraph])
          : Unit =
            if batch.size() > 0 then
                try
                    batch.forEach { diffGraph =>
                        val applied = overflowdb.BatchedUpdate
                            .applyDiff(cpg.graph, diffGraph, keyPool.orNull, null)
                            .transitiveModifications()
                        nDiffT += applied
                        slots.release()
                    }
                finally
                    batch.clear()
    end Writer
end StreamingCpgPass
