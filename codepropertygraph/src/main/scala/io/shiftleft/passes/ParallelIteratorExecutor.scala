package io.shiftleft.passes

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class ParallelIteratorExecutor[T](iterator: Iterator[T])(implicit
  executionContext: ExecutionContext
):
    def map[D](func: T => D): Iterator[D] =
        val batchSize = 1000
        iterator.grouped(batchSize).flatMap { batch =>
            val futures = batch.map { element =>
                Future { func(element) }
            }.toList
            Await.result(
              Future.sequence(futures),
              Duration(10, java.util.concurrent.TimeUnit.SECONDS)
            )
        }
