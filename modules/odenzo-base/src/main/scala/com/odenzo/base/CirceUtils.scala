package com.odenzo.base

import cats.effect._
import cats.syntax.all._
import cats.{ApplicativeError, Monad}
import io.circe._
import io.circe.jawn.JawnParser
import io.circe.syntax._
import scribe.Logging

import java.io.{File, FileOutputStream, OutputStreamWriter}
import java.net.URL
import scala.io.{BufferedSource, Source}
import scala.math.Ordering

/**  Traits for working with Circe Json / DOM
  */
trait CirceUtils extends Logging {

  case class Person(name:String, age:Int)

  given personOrdering as Ordering[Person] {
    override def compare(x: Person, y: Person): Int =
      x.surname.compareTo(y.surname)
  }

  def json2object[F[_]](json: Json)(implicit F: ApplicativeError[F, Throwable]): F[JsonObject] =
    F.fromOption(json.asObject, OError("JSON was not a JSonObject" + json))

  def json2array[F[_]](json: Json)(implicit F: ApplicativeError[F, Throwable]): F[Vector[Json]] =
    F.fromOption(json.asArray, OError("JSON was not a Array" + json))

  def json2string[F[_]](json: Json)(implicit F: ApplicativeError[F, Throwable]): F[String] =
    F.fromOption(json.asString, OError("JSON was not a String" + json))

  /** To avoid importing io.circe.syntax to use .asJson :-)
    *  Also allows explicitly passing in the encoder
    */
  def encode[A](a: A)(implicit enc: Encoder[A]): Json = enc.apply(a)

  /** Easily decode wrapped in our Either AppError style. */
  def decode[A: Decoder, F[_]](json: Json)(implicit F: ApplicativeError[F, Throwable]): F[A] =
    F.fromEither(json.as[A])

  /** Easily decode wrapped in our Either AppError style. */
  def decodeObj[A: Decoder, F[_]](jobj: JsonObject)(implicit F: ApplicativeError[F, Throwable]): F[A] = F.fromEither(jobj.asJson.as[A])

  def findField(json: JsonObject, fieldName: String): Option[Json] = json(fieldName)

  def findField[F[_]](json: Json, fieldName: String)(implicit F: ApplicativeError[F, Throwable]): F[Option[Json]] =
    json2object(json).map(findField(_, fieldName))

  /** Finds the field and decodes. If field not found None returned, if found and decoding
    * error than IO raises an error.
    */
  def findFieldAs[T: Decoder, F[_]](jsonObject: JsonObject, fieldName: String)(implicit F: ApplicativeError[F, Throwable]): F[Option[T]] = {
    findField(jsonObject, fieldName).traverse(j => F.fromEither(j.as[T]))
  }

  def extractFieldFromObject[F[_]](jobj: JsonObject, fieldName: String)(implicit F: ApplicativeError[F, Throwable]): F[Json] = {
    F.fromOption(jobj.apply(fieldName), OError(s"Could not Find $fieldName in JSonObject "))
  }

  /** Little utility for common case where an JsonObject just has "key": value
    * WHere value may be heterogenous?
    */
  def extractAsKeyValueList[F[_]](json: Json)(implicit F: ApplicativeError[F, Throwable]): F[List[(String, Json)]] = {

    F.fromOption(json.asObject.map(_.toList), OError("JSON Fragment was not a JSON Object"))
  }

  /** Ripled doesn't like objects like { x=null } */
  val droppingNullsPrinter: Printer = Printer.spaces2.copy(dropNullValues = true)

  /** Converts json to formatted text dropping null JsonObject fields. */
  def print(json: Json): String                = json.printWith(droppingNullsPrinter)
  def printObj(jsonObject: JsonObject): String = print(jsonObject.asJson)

  /** Caution: Uses BigDecimal and BigInt in parsing.
    *  @param m The text, in this case the response message text from websocket.
    *  @return JSON or an exception if problems parsing, error holds the original String.
    */
  def parse[F[_]: Sync](m: String): F[Json] = {
    ApplicativeError[F, Throwable].fromEither(parser.parse(m).leftMap(pf => OError("Error Parsing String to Json: $m", pf)))
  }

  def parseAsJson[F[_]](f: File)(implicit F: ApplicativeError[F, Throwable]): F[Json] = {
    F.fromEither(new JawnParser().parseFile(f).leftMap(pf => OError(s"Error Parsing File $f to Json", pf)))
  }

  def hasField(name: String, json: Json): Boolean = json.asObject.exists(hasField(name, _))

  def hasField(name: String, json: JsonObject): Boolean = json(name).isDefined

  /** Finds top level field in the supplied json object */
  def extractField[F[_]](name: String, json: JsonObject)(implicit F: ApplicativeError[F, Throwable]): F[Json] = {
    extractFieldFromObject(json, name)
  }

  def extractField[F[_]](name: String, json: Json)(implicit F: Sync[F]): F[Json] = {
    json2object[F](json).flatMap(v => extractFieldFromObject[F](v, name))
  }

  def extractFieldAs[T: Decoder, F[_]](name: String, json: Json)(implicit F: ApplicativeError[F, Throwable]): Any = {

    import cats.syntax.all._
    // To avoid map or flatMap
    val obj: Either[Throwable, T] = Either
      .fromOption(json.asObject, OError("Not a JSONObject"))
      .flatMap(jo => Either.fromOption(jo(name), OError(s"No Field $name")))
      .flatMap(jf => jf.as[T])
    F.fromEither(obj)
  }

  def loadJsonResource[F[_]: Sync](path: String): F[Json] = {

    val resource: URL          = getClass.getResource(path)
    val source: BufferedSource = Source.fromURL(resource)
    val data: String           = source.getLines().mkString("\n")
    parse[F](data)
  }

  /** Construct a Cats Resource with the JSON parsed from the named Java resource
    */
  def makeJsonResource[F[_]: Sync: Monad](path: String): F[Json] = {

    val url: URL = getClass.getResource(path)
    val acquire  = Sync[F].delay(Source.fromURL(url))
    val resource = Resource.fromAutoCloseable(acquire)

    resource.use { src =>
      parse(src.mkString)
    }

  }

  def loadJson[F[_]: Sync](url: URL): F[Json] = {
    val acquire: F[BufferedSource] = Sync[F].delay(Source.fromURL(url))
    Resource
      .fromAutoCloseable(acquire)
      .use { src =>
        parse(src.mkString)
      }
  }

  def writeJson(json: Json, file: File): IO[Unit] = {
    val open = IO(new OutputStreamWriter(new FileOutputStream(file, false), "UTF-8"))
    Resource
      .fromAutoCloseable(open)
      .use { out =>
        IO(out.write(json.spaces4))
      }
  }

  /** Ripled doesn't like objects like { x=null } and neither does Binary-Codec lib
    * {{{
    *      droppingNullsSortedPrinter.pretty(json)
    * }}}
    */
  val droppingNullsSortedPrinter: Printer = Printer.spaces2SortKeys.copy(dropNullValues = true)

  /** This probably doesn't preserve the ordering of fields in Object. */
  def replaceField(name: String, in: JsonObject, withValue: Json): JsonObject = {
    in.remove(name).add(name, withValue)
  }

  /** Does top level sorting of fields in this object alphanumeric with capital before lowercase
    * See if circe sorted fields does this nicely
    */
  def sortFields(obj: JsonObject): JsonObject = {
    JsonObject.fromIterable(obj.toVector.sortBy(_._1))
  }

  /** This does not recurse down, top level fields only */
  def sortFieldsDroppingNulls(obj: JsonObject): JsonObject = {
    val iter = obj.toVector.filter(_._2 =!= Json.Null).sortBy(_._1)
    JsonObject.fromIterable(iter)
  }

  def dropNullValues(obj: JsonObject): Json = obj.asJson.deepDropNullValues

  def parseAndDecode[T: Decoder, F[_]: Sync](m: String, decoder: Decoder[T]): F[T] = {
    for {
      json <- parse[F](m)
      o    <- ApplicativeError[F, Throwable].fromEither(decoder.decodeJson(json))
    } yield o
  }

  def loadJsonRecordsFromFile[T: io.circe.Codec, F[_]: Sync](file: File): F[List[T]] = {
    import io.circe.generic.codec.DerivedAsObjectCodec.deriveCodec
    for {
      json <- CirceUtils.loadJson(file.toURI.toURL)(Sync[F])
      txns <- ApplicativeError[F, Throwable].fromEither(json.as[List[T]])
    } yield txns
  }

  /** Lets try a Scala 3 Something */

}

object CirceUtils extends CirceUtils
