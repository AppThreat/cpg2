package io.shiftleft.passes

import com.google.protobuf.GeneratedMessageV3
import io.shiftleft.SerializedCpg
import io.shiftleft.codepropertygraph.Cpg
import overflowdb.BatchedUpdate

import java.util.function.{BiConsumer, Supplier}
import scala.annotation.nowarn
import scala.concurrent.duration.DurationLong
import scala.util.{Failure, Success, Try}
import scala.reflect.ClassTag

/* CpgPass
 *
 * Base class of a program which receives a CPG as input for the purpose of modifying it.
 * */

abstract class CpgPass(cpg: Cpg, outName: String = "", keyPool: Option[KeyPool] = None)
    extends ForkJoinParallelCpgPass[AnyRef](cpg, outName, keyPool):

    def run(builder: overflowdb.BatchedUpdate.DiffGraphBuilder): Unit

    final override def generateParts(): Array[? <: AnyRef] = Array[AnyRef](null)

    final override def runOnPart(
      builder: overflowdb.BatchedUpdate.DiffGraphBuilder,
      part: AnyRef
    ): Unit =
        run(builder)

@deprecated abstract class SimpleCpgPass(
  cpg: Cpg,
  outName: String = "",
  keyPool: Option[KeyPool] = None
) extends CpgPass(cpg, outName, keyPool)

/* ForkJoinParallelCpgPass is a possible replacement for CpgPass and ParallelCpgPass.
 *
 * Instead of returning an Iterator, generateParts() returns an Array. This means that the entire collection
 * of parts must live on the heap at the same time; on the other hand, there are no possible issues with iterator invalidation,
 * e.g. when running over all METHOD nodes and deleting some of them.
 *
 * Instead of streaming writes as ParallelCpgPass or ConcurrentWriterCpgPass do, all `runOnPart` invocations read the initial state
 * of the graph. Then all changes (accumulated in the DiffGraphBuilders) are merged into a single change, and applied in one go.
 *
 * In other words, the parallelism follows the fork/join parallel map-reduce (java: collect, scala: aggregate) model.
 * The effect is identical as if one were to sequentially run `runOnParts` on all output elements of `generateParts()`
 * in sequential order, with the same builder.
 *
 * This simplifies semantics and makes it easy to reason about possible races.
 *
 * For large codebases, the pass automatically processes parts in chunks to reduce peak memory consumption.
 * The chunk size can be configured via the maxChunkSize parameter (default: 1000).
 *
 * Note that ForkJoinParallelCpgPass never writes intermediate results, but chunked processing helps manage memory.
 * Consider ConcurrentWriterCpgPass when this is still a problem.
 *
 * Initialization and cleanup of external resources or large datastructures can be done in the `init()` and `finish()`
 * methods. This may be better than using the constructor or GC, because e.g. SCPG chains of passes construct
 * passes eagerly, and releases them only when the entire chain has run.
 * */
abstract class ForkJoinParallelCpgPass[T <: AnyRef](
  cpg: Cpg,
  @nowarn outName: String = "",
  keyPool: Option[KeyPool] = None,
  maxChunkSize: Int = 1000,
  continueOnError: Boolean = false
) extends NewStyleCpgPassBase[T]:

    override def createApplySerializeAndStore(
      serializedCpg: SerializedCpg,
      inverse: Boolean = false,
      prefix: String = ""
    ): Unit =
        try
            val diffGraph = new DiffGraphBuilder
            runWithBuilder(diffGraph)
            overflowdb.BatchedUpdate.applyDiff(cpg.graph, diffGraph, keyPool.orNull, null)
        catch
            case exc: Exception =>
                if !continueOnError then throw exc
        finally
            try
                finish()
            catch
                case _: Exception =>

    private def processChunk(chunk: Array[? <: AnyRef], builder: DiffGraphBuilder): Unit =
        chunk.foreach { part =>
            try
                runOnPart(builder, part.asInstanceOf[T])
            catch
                case ex: Exception =>
                    if !continueOnError then throw ex
        }

end ForkJoinParallelCpgPass

/** NewStyleCpgPassBase is the shared base between ForkJoinParallelCpgPass and
  * ConcurrentWriterCpgPass, containing shared boilerplate. We don't want ConcurrentWriterCpgPass as
  * a subclass of ForkJoinParallelCpgPass because that would make it hard to whether an instance is
  * non-racy.
  *
  * Please don't subclass this directly. The only reason it's not sealed is that this would mess
  * with our file hierarchy.
  */
abstract class NewStyleCpgPassBase[T <: AnyRef](private val maxChunkSize: Int = 1000)
    extends CpgPassBase:
    type DiffGraphBuilder = overflowdb.BatchedUpdate.DiffGraphBuilder
    def generateParts(): Array[? <: AnyRef]
    def init(): Unit   = {}
    def finish(): Unit = {}
    def runOnPart(builder: DiffGraphBuilder, part: T): Unit

    override def createAndApply(): Unit = createApplySerializeAndStore(null)

    override def runWithBuilder(externalBuilder: BatchedUpdate.DiffGraphBuilder): Int =
        try
            init()
            val parts  = generateParts()
            val nParts = parts.length

            if nParts > 0 && parts.length > maxChunkSize then
                parts.grouped(maxChunkSize).foreach { chunk =>
                    val chunkBuilder = new DiffGraphBuilder
                    chunk.foreach { part =>
                        runOnPart(chunkBuilder, part.asInstanceOf[T])
                    }
                    externalBuilder.absorb(chunkBuilder)
                    System.gc()
                }
            else
                nParts match
                    case 0 =>
                    case 1 =>
                        runOnPart(externalBuilder, parts(0).asInstanceOf[T])
                    case _ =>
                        externalBuilder.absorb(
                          java.util.Arrays
                              .stream(parts)
                              .parallel()
                              .collect(
                                () => new DiffGraphBuilder,
                                (builder: DiffGraphBuilder, part: AnyRef) =>
                                    runOnPart(builder, part.asInstanceOf[T]),
                                (leftBuilder: DiffGraphBuilder, rightBuilder: DiffGraphBuilder) =>
                                    leftBuilder.absorb(rightBuilder)
                              )
                        )
                end match
            end if
            nParts
        finally
            finish()

    private def processChunk(chunk: Array[? <: AnyRef], builder: DiffGraphBuilder): Unit =
        chunk.foreach { part =>
            runOnPart(builder, part.asInstanceOf[T])
        }

end NewStyleCpgPassBase

trait CpgPassBase:

    def createAndApply(): Unit

    def createApplySerializeAndStore(
      serializedCpg: SerializedCpg,
      inverse: Boolean = false,
      prefix: String = ""
    ): Unit

    /** Name of the pass. By default it is inferred from the name of the class, override if needed.
      */
    def name: String = getClass.getName

    /** Runs the cpg pass, adding changes to the passed builder. Use with caution -- API is
      * unstable. Returns max(nParts, 1), where nParts is either the number of parallel parts, or
      * the number of iterarator elements in case of legacy passes. Includes init() and finish()
      * logic.
      */
    def runWithBuilder(builder: overflowdb.BatchedUpdate.DiffGraphBuilder): Int

    /** Wraps runWithBuilder with logging, and swallows raised exceptions. Use with caution -- API
      * is unstable. A return value of -1 indicates failure, otherwise the return value of
      * runWithBuilder is passed through.
      */
    def runWithBuilderLogged(builder: overflowdb.BatchedUpdate.DiffGraphBuilder): Int =
        val nanoStart = System.nanoTime()
        val size0     = builder.size()
        Try(runWithBuilder(builder)) match
            case Success(nParts) =>
                nParts
            case Failure(exception) =>
                -1

    protected def generateOutFileName(prefix: String, outName: String, index: Int): String =
        val outputName =
            if outName.isEmpty then
                this.getClass.getSimpleName
            else
                outName
        prefix + "_" + outputName + "_" + index

    protected def store(
      overlay: GeneratedMessageV3,
      name: String,
      serializedCpg: SerializedCpg
    ): Unit =
        if overlay.getSerializedSize > 0 then
            serializedCpg.addOverlay(overlay, name)

    protected def withStartEndTimesLogged[A](fun: => A): A =
        val startTime = System.currentTimeMillis
        try
            fun
        finally
            val duration = (System.currentTimeMillis - startTime).millis.toCoarsest
end CpgPassBase
