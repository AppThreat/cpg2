package io.shiftleft.utils

import java.util.concurrent.ForkJoinWorkerThread
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object ExecutionContextProvider:

    def getExecutionContext: ExecutionContextExecutor =
        Thread.currentThread() match
            case fjt: ForkJoinWorkerThread =>
                fjt.getPool match
                    case ec: ExecutionContextExecutor => ec
                    case _                            => ExecutionContext.global
            case _ => ExecutionContext.global
end ExecutionContextProvider
