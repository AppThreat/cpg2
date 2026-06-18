package io.shiftleft.passes

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PassConfigTests extends AnyWordSpec with Matchers {

    private def withProperty(name: String, value: String)(body: => Unit): Unit = {
        val previous = Option(System.getProperty(name))
        try {
            System.setProperty(name, value)
            body
        } finally
            previous match {
                case Some(v) => System.setProperty(name, v)
                case None    => System.clearProperty(name)
            }
    }

    "PassConfig.intProp" should {
        "return the default when the property is unset" in {
            System.clearProperty("odb.test.unset")
            PassConfig.intProp("odb.test.unset", 7) shouldBe 7
        }

        "honor a valid positive override" in withProperty("odb.test.valid", "123") {
            PassConfig.intProp("odb.test.valid", 7) shouldBe 123
        }

        "fall back to the default for zero, negative or non-numeric values" in {
            for (invalid <- Seq("0", "-1", "abc", "")) withProperty("odb.test.invalid", invalid) {
                PassConfig.intProp("odb.test.invalid", 7) shouldBe 7
            }
        }
    }

    "the per-pass tuning knobs" should {
        "default to strictly positive batch sizes" in {
            StreamingCpgPass.writerBatchSize should be > 0
            ConcurrentWriterCpgPass.writerBatchSize should be > 0
            // guards against the historical 0-batch-size bug on low-core machines
            OrderedParallelCpgPass.writerBatchSize should be > 0
        }

        "be overridable via their system properties" in
            withProperty("odb.orderedparallelpass.writerBatchSize", "9") {
                OrderedParallelCpgPass.writerBatchSize shouldBe 9
            }
    }
}
