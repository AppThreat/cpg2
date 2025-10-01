package io.shiftleft.passes
import io.shiftleft.SerializedCpg
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.utils.ExecutionContextProvider

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, LinkedBlockingQueue, ThreadFactory}
import scala.annotation.nowarn
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutorService, Future}

/* ConcurrentWriterCpgPass is a possible replacement for ParallelCpgPass and NewStylePass.
 *
 * Instead of returning an Iterator, generateParts() returns an Array. This means that the entire collection
 * of parts must live on the heap at the same time; on the other hand, there are no possible issues with iterator invalidation,
 * e.g. when running over all METHOD nodes and deleting some of them.
 *
 * Changes are applied sequentially, in the same order as the output of `runOnParts`, as opposed to `ParallelCpgPass`,
 * where the ordering of change application is non-deterministic. For this reason, ConcurrentWriterCpgPass only accepts a single KeyPool.
 *
 * However, as opposed to NewStylePass, changes are not buffered and applied in one go; instead, they are applied as the respective
 * `runOnPart` finishes, concurrently with other `runOnPart` invocations.
 *
 * Compared to NewStylePass, this avoids excessive peak memory consumption. On the other hand, `runOnPart` sees the CPG
 * in an intermediate state: No promises are made about which previous changes are already applied; and changes are
 * applied concurrently, such that all reads from the graph are potential race conditions. Furthermore, this variant has
 * higher constant overhead per part than NewStylePass, i.e. is better suited to passes that create few large diffs.
 *
 *
 * Initialization and cleanup of external resources or large datastructures can be done in the `init()` and `finish()`
 * methods. This may be better than using the constructor or GC, because e.g. SCPG chains of passes construct
 * passes eagerly, and releases them only when the entire chain has run.
 * */
object ConcurrentWriterCpgPass:
    private val cores                 = Runtime.getRuntime.availableProcessors()
    private val writerQueueCapacity   = Math.max(2, (0.75 * cores).toInt)
    private val producerQueueCapacity = Math.max(4, (1.5 * cores).toInt)
    private val writerBatchSize       = 4
end ConcurrentWriterCpgPass

abstract class ConcurrentWriterCpgPass[T <: AnyRef](
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

    /** WARNING: runOnPart is executed in parallel to committing of graph modifications. The upshot
      * is that it is unsafe to read ANY data from cpg, on pain of bad race conditions
      *
      * Only use ConcurrentWriterCpgPass if you are _very_ sure that you avoid races.
      *
      * E.g. adding a CFG edge to node X races with reading an AST edge of node X.
      */
    override def createApplySerializeAndStore(
      serializedCpg: SerializedCpg,
      inverse: Boolean = false,
      prefix: String = ""
    ): Unit =
        import ConcurrentWriterCpgPass.producerQueueCapacity
        var nParts = 0
        var nDiff  = 0
        nDiffT = -1
        init()
        val parts = generateParts()
        nParts = parts.length
        val partIter        = parts.iterator
        val completionQueue = mutable.ArrayDeque[Future[overflowdb.BatchedUpdate.DiffGraph]]()
        val writer          = new Writer()
        val writerThread    = new Thread(writer)
        writerThread.setName("AppThreat cpg2 Writer")
        writerThread.start()
        implicit val ec: ExecutionContext = this.cpuOptimizedExecutionContext
        try
            var done = false
            while !done && writer.raisedException.isEmpty do
                if writer.raisedException.isDefined then
                    throw writer.raisedException.get
                if completionQueue.size < producerQueueCapacity && partIter.hasNext then
                    val next = partIter.next()
                    completionQueue.append(Future.apply {
                        val builder = new DiffGraphBuilder
                        runOnPart(builder, next.asInstanceOf[T])
                        builder.build()
                    })
                else if completionQueue.nonEmpty then
                    val future = completionQueue.removeHead()
                    val res    = Await.result(future, Duration.Inf)
                    nDiff += res.size
                    writer.queue.put(Some(res))
                else
                    done = true
        finally
            try
                if writer.raisedException.isEmpty then writer.queue.put(None)
                writerThread.join()
                if writer.raisedException.isDefined then
                    throw new RuntimeException(
                      "Failure in ConcurrentWriterCpgPass",
                      writer.raisedException.get
                    )
            finally finish()
        end try
    end createApplySerializeAndStore

    private class Writer extends Runnable:
        val queue = new LinkedBlockingQueue[Option[overflowdb.BatchedUpdate.DiffGraph]](
          ConcurrentWriterCpgPass.writerQueueCapacity
        )

        @volatile var raisedException: Option[Exception] = None

        override def run(): Unit =
            try
                nDiffT = 0
                val batchBuffer = new java.util.ArrayList[overflowdb.BatchedUpdate.DiffGraph](
                  ConcurrentWriterCpgPass.writerBatchSize
                )
                while true do
                    queue.take() match
                        case None =>
                            flushBatch(batchBuffer)
                            return
                        case Some(diffGraph) =>
                            batchBuffer.add(diffGraph)
                            if batchBuffer.size() >= ConcurrentWriterCpgPass.writerBatchSize then
                                flushBatch(batchBuffer)
            catch
                case exception: InterruptedException => Thread.currentThread().interrupt()
                case exc: Exception =>
                    raisedException = Some(exc)
                    queue.clear()
                    throw exc

        private def flushBatch(batch: java.util.ArrayList[overflowdb.BatchedUpdate.DiffGraph])
          : Unit =
            if batch.size() > 0 then
                batch.forEach { diffGraph =>
                    nDiffT += overflowdb.BatchedUpdate
                        .applyDiff(cpg.graph, diffGraph, keyPool.orNull, null)
                        .transitiveModifications()
                }
                batch.clear()
    end Writer
end ConcurrentWriterCpgPass
