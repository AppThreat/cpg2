package io.shiftleft.passes

import io.shiftleft.SerializedCpg
import io.shiftleft.codepropertygraph.Cpg

import java.util.concurrent.{Executors, LinkedBlockingQueue, Semaphore}
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService, Future}

object StreamingCpgPass:
    private val cores                 = Runtime.getRuntime.availableProcessors()
    private val writerQueueCapacity   = Math.max(2, (0.5 * cores).toInt)
    private val producerQueueCapacity = Math.max(4, (0.7 * cores).toInt)
    private val writerBatchSize       = 4

/** A replacement for ConcurrentWriterCpgPass that trades deterministic Node IDs for significantly
  * lower memory usage.
  *
  * It streams DiffGraphs to the writer as soon as they are ready, preventing large graphs from
  * accumulating in memory while waiting for previous tasks.
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
        import StreamingCpgPass.producerQueueCapacity

        nDiffT = -1
        init()
        val parts  = generateParts()
        val nParts = parts.length

        if nParts == 0 then return

        val writer       = new Writer()
        val writerThread = new Thread(writer)
        writerThread.setName("StreamingCpgPass Writer")
        writerThread.start()
        val semaphore                     = new Semaphore(producerQueueCapacity)
        implicit val ec: ExecutionContext = this.cpuOptimizedExecutionContext

        try
            val partIter = parts.iterator
            while partIter.hasNext && writer.raisedException.isEmpty do
                semaphore.acquire()
                val part = partIter.next()
                Future {
                    try
                        val builder = new DiffGraphBuilder
                        runOnPart(builder, part.asInstanceOf[T])
                        val diff = builder.build()
                        if writer.raisedException.isEmpty then
                            writer.queue.put(Some(diff))
                    catch
                        case t: Throwable =>
                            val e = t match
                                case exception: Exception => exception
                                case _                    => new RuntimeException(t)
                            writer.raisedException = Some(e)
                            try writer.queue.offer(None)
                            catch case _: Exception => ()
                    finally
                        semaphore.release()
                }
            end while
            var permitsAcquired = 0
            while permitsAcquired < producerQueueCapacity do
                if writer.raisedException.isDefined || !writerThread.isAlive then
                    throw new RuntimeException(
                      "Writer thread failed while waiting for workers",
                      writer.raisedException.orNull
                    )
                if semaphore.tryAcquire(1, 100, java.util.concurrent.TimeUnit.MILLISECONDS) then
                    permitsAcquired += 1
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

    private class Writer extends Runnable:
        val queue = new LinkedBlockingQueue[Option[overflowdb.BatchedUpdate.DiffGraph]](
          StreamingCpgPass.writerQueueCapacity
        )

        @volatile var raisedException: Option[Exception] = None

        override def run(): Unit =
            try
                nDiffT = 0
                val batchBuffer = new java.util.ArrayList[overflowdb.BatchedUpdate.DiffGraph](
                  StreamingCpgPass.writerBatchSize
                )
                while true do
                    if raisedException.isDefined then return

                    queue.take() match
                        case None =>
                            flushBatch(batchBuffer)
                            return
                        case Some(diffGraph) =>
                            batchBuffer.add(diffGraph)
                            if batchBuffer.size() >= StreamingCpgPass.writerBatchSize then
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
                        nDiffT += overflowdb.BatchedUpdate
                            .applyDiff(cpg.graph, diffGraph, keyPool.orNull, null)
                            .transitiveModifications()
                    }
                finally
                    batch.clear()
    end Writer
end StreamingCpgPass
