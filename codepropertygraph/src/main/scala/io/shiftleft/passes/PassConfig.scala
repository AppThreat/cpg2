package io.shiftleft.passes

/** Shared helpers for reading pass tuning knobs from system properties.
  *
  * Concurrency knobs of the parallel CPG passes (queue capacities, writer batch sizes, chunk sizes)
  * default to values derived from the number of available cores, but can be overridden at runtime
  * via system properties so that large-graph runs can be tuned without recompiling. An unset,
  * non-numeric or non-positive value always falls back to the supplied default.
  */
private[passes] object PassConfig:
    val cores: Int = Runtime.getRuntime.availableProcessors()

    /** Reads a strictly-positive int system property, falling back to `default` when absent or
      * invalid.
      */
    def intProp(name: String, default: Int): Int =
        Option(System.getProperty(name)).flatMap(_.toIntOption).filter(_ > 0).getOrElse(default)
