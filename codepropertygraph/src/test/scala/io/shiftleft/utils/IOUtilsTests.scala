package io.shiftleft.utils

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class IOUtilsTests extends AnyWordSpec with Matchers {

  private def withFile(bytes: Array[Byte])(f: Path => Unit): Unit = {
    val path = Files.createTempFile("ioutils", ".txt")
    try {
      Files.write(path, bytes)
      f(path)
    } finally Files.deleteIfExists(path)
  }

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  "IOUtils" should {
    "keep characters beyond the basic multilingual plane" in {
      // U+1D431 MATHEMATICAL BOLD SMALL X, U+1F600 GRINNING FACE, U+20000 CJK EXTENSION B
      val content = "𝐱 = '😀'\n𠀀 = 1"
      withFile(utf8(content)) { path =>
        IOUtils.readLinesInFile(path) shouldBe Seq("𝐱 = '😀'", "𠀀 = 1")
        IOUtils.readEntireFile(path) shouldBe content
      }
    }

    "replace malformed input, including an encoded lone surrogate, without leaving a surrogate" in {
      // ED A0 80 is a CESU-8 high surrogate: malformed UTF-8, never a lone surrogate in the result
      val bytes = utf8("a") ++ Array(0xed, 0xa0, 0x80).map(_.toByte) ++ utf8("b\né")
      withFile(bytes) { path =>
        val content = IOUtils.readEntireFile(path)
        content.head shouldBe 'a'
        content.last shouldBe 'é'
        content.exists(Character.isSurrogate) shouldBe false
        content should include("�")
      }
    }

    "skip a UTF-8 byte order mark" in {
      withFile(Array(0xef, 0xbb, 0xbf).map(_.toByte) ++ utf8("x = 1\n")) { path =>
        IOUtils.readLinesInFile(path) shouldBe Seq("x = 1")
        IOUtils.readEntireFile(path) shouldBe "x = 1\n"
      }
    }
  }
}
