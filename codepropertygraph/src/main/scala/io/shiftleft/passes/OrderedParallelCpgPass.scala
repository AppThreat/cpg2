package io.shiftleft.passes

import io.shiftleft.SerializedCpg
import io.shiftleft.codepropertygraph.Cpg

import java.util.concurrent.{ExecutorCompletionService, Executors, LinkedBlockingQueue, TimeUnit}
import scala.annotation.nowarn
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

object OrderedParallelCpgPass:
    private val cores           = Runtime.getRuntime.availableProcessors()
    private val queueCapacity   = Math.max(2, (0.5 * cores).toInt)
    private val writerBatchSize = Math.min(4, (0.7 * cores).toInt)

abstract class OrderedParallelCpgPass[T <: AnyRef](
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
        import OrderedParallelCpgPass.queueCapacity

        nDiffT = -1
        init()
        val parts  = generateParts()
        val nParts = parts.length

        if nParts == 0 then return

        val writer       = new Writer()
        val writerThread = new Thread(writer)
        writerThread.setName("OrderedParallelCpgPass Writer")
        writerThread.start()

        val completionService =
            new ExecutorCompletionService[(Int, Option[overflowdb.BatchedUpdate.DiffGraph])](
              cpuOptimizedExecutionContext
            )

        val reorderBuffer = mutable.Map.empty[Int, Option[overflowdb.BatchedUpdate.DiffGraph]]

        var submitIndex = 0
        var writeIndex  = 0
        var activeTasks = 0

        val partIter = parts.iterator

        try
            def submitNext(): Unit =
                if partIter.hasNext && writer.raisedException.isEmpty then
                    val part         = partIter.next()
                    val currentIndex = submitIndex
                    submitIndex += 1

                    completionService.submit(() =>
                        try
                            val builder = new DiffGraphBuilder
                            runOnPart(builder, part.asInstanceOf[T])
                            (currentIndex, Some(builder.build()))
                        catch
                            case t: Throwable => throw t
                    )
                    activeTasks += 1

            while activeTasks < queueCapacity && partIter.hasNext do
                submitNext()

            while activeTasks > 0 do
                val future = completionService.take()
                activeTasks -= 1

                try
                    val (index, result) = future.get()
                    reorderBuffer.put(index, result)
                    var nextReady = reorderBuffer.remove(writeIndex)
                    while nextReady.isDefined && writer.raisedException.isEmpty do
                        var queued = false
                        while !queued && writer.raisedException.isEmpty do
                            queued = writer.queue.offer(nextReady.get, 100, TimeUnit.MILLISECONDS)

                        if queued then
                            writeIndex += 1
                            nextReady = reorderBuffer.remove(writeIndex)

                catch
                    case e: java.util.concurrent.ExecutionException =>
                        val cause = e.getCause
                        val ex = cause match
                            case exception: Exception => exception
                            case _                    => new RuntimeException(cause)
                        writer.raisedException = Some(ex)
                        try writer.queue.offer(None)
                        catch case _: Exception => ()
                    case t: Throwable =>
                        writer.raisedException = Some(new RuntimeException(t))
                        try writer.queue.offer(None)
                        catch case _: Exception => ()
                end try
                if writer.raisedException.isEmpty then
                    submitNext()
            end while
        finally
            try
                if writer.raisedException.isEmpty then writer.queue.put(None)
                writerThread.join()
                if writer.raisedException.isDefined then
                    throw new RuntimeException(
                      "Failure in OrderedParallelCpgPass",
                      writer.raisedException.get
                    )
            finally finish()
        end try
    end createApplySerializeAndStore

    private class Writer extends Runnable:
        val queue = new LinkedBlockingQueue[Option[overflowdb.BatchedUpdate.DiffGraph]](
          OrderedParallelCpgPass.queueCapacity
        )

        @volatile var raisedException: Option[Exception] = None

        override def run(): Unit =
            try
                nDiffT = 0
                val batchBuffer = new java.util.ArrayList[overflowdb.BatchedUpdate.DiffGraph](
                  OrderedParallelCpgPass.writerBatchSize
                )
                while true do
                    if raisedException.isDefined then return

                    queue.take() match
                        case None =>
                            flushBatch(batchBuffer)
                            return
                        case Some(diffGraph) =>
                            batchBuffer.add(diffGraph)
                            if batchBuffer.size() >= OrderedParallelCpgPass.writerBatchSize then
                                flushBatch(batchBuffer)
            catch
                case exception: InterruptedException => Thread.currentThread().interrupt()
                case exc: Exception =>
                    raisedException = Some(exc)
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
end OrderedParallelCpgPass
