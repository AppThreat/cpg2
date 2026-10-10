package io.shiftleft.utils

import java.io.Reader
import java.nio.charset.{CharsetDecoder, CodingErrorAction}
import java.nio.file.Path
import java.util.regex.Pattern
import scala.io.{BufferedSource, Codec, Source}
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

object IOUtils:

    // java.util.regex matches by code point: a well-formed surrogate pair is one supplementary
    // code point and never matches this class, so only lone surrogates do.
    private val surrogatePattern: Pattern = Pattern.compile("[\\uD800-\\uDFFF]")

    private val boms: Set[Char] = Set(
      '\uefbb', // UTF-8
      '\ufeff', // UTF-16 (BE)
      '\ufffe'  // UTF-16 (LE)
    )

    /** Creates a new UTF-8 decoder. Sadly, instances of CharsetDecoder are not thread-safe as the
      * doc states: 'Instances of this class are not safe for use by multiple concurrent threads.'
      * (copied from: [[java.nio.charset.CharsetDecoder]])
      *
      * As we are using it in a [[io.shiftleft.passes.ForkJoinParallelCpgPass]] or
      * [[io.shiftleft.passes.ConcurrentWriterCpgPass]] a it needs to be thread-safe. Hence, we make
      * sure to create a new instance everytime.
      */
    private def createDecoder(): CharsetDecoder =
        Codec.UTF8.decoder
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private def skipBOMIfPresent(reader: Reader): Unit =
        reader.mark(1)
        val possibleBOM = new Array[Char](1)
        reader.read(possibleBOM)
        if !boms.contains(possibleBOM(0)) then
            reader.reset()

    /** Java strings are stored as sequences of 16-bit chars (UTF-16 code units). A character
      * beyond the basic multilingual plane (an emoji, a mathematical alphanumeric, a CJK extension
      * ideograph) is a well-formed surrogate pair and is kept: frontends need it verbatim, e.g.
      * Python accepts such characters in identifiers. Only a lone surrogate, which no encoder can
      * write, is replaced - by a single '?', so every offset into the content stays valid.
      */
    private def replaceUnpairedSurrogates(input: String): String =
        val matches = surrogatePattern.matcher(input)
        if matches.find() then matches.replaceAll("?")
        else input

    private def contentFromBufferedSource(bufferedSource: BufferedSource): Seq[String] =
        val reader = bufferedSource.bufferedReader()
        skipBOMIfPresent(reader)
        reader.lines().iterator().asScala.map(replaceUnpairedSurrogates).toSeq

    private def contentStringFromBufferedSource(bufferedSource: BufferedSource): String =
        val reader        = bufferedSource.bufferedReader()
        val stringBuilder = new StringBuilder
        val bufferSize    = 1024
        var productive    = true

        skipBOMIfPresent(reader)
        while productive do
            val buffer = new Array[Char](bufferSize)
            val read   = reader.read(buffer)
            productive = read > 0
            if productive then
                stringBuilder.appendAll(buffer, 0, read)

        replaceUnpairedSurrogates(stringBuilder.toString)

    /** Reads a file at the given path and:
      *   - skips BOM if present
      *   - replaces lone surrogates with '?' (characters beyond the BMP are kept)
      *   - uses UTF-8 encoding (replacing malformed and unmappable characters)
      *
      * @param path
      *   the file path
      * @return
      *   a Seq with all lines in the given file as Strings
      */
    def readLinesInFile(path: Path): Seq[String] =
        Using.resource(Source.fromFile(path.toFile)(using createDecoder()))(
          contentFromBufferedSource
        )

    /** Reads a file at the given path and:
      *   - skips BOM if present
      *   - replaces lone surrogates with '?' (characters beyond the BMP are kept)
      *   - uses UTF-8 encoding (replacing malformed and unmappable characters)
      *
      * @param path
      *   the file path
      * @return
      *   a String with the given file's contents
      */
    def readEntireFile(path: Path): String =
        Using.resource(Source.fromFile(path.toFile)(using createDecoder()))(
          contentStringFromBufferedSource
        )
end IOUtils
