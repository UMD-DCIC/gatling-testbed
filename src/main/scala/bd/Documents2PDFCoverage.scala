package bd

import scala.concurrent.duration._
import io.gatling.commons.validation._
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.jdbc.Predef._
import bd.BrownDogAPI._
import umd.ciber.ciber_sampling.CiberQueryBuilder

class Documents2PDFCoverage extends Simulation {

  // Data: 1100 random paths, less than 20GB files, including listed extensions
  val cqbiter = new CiberQueryBuilder().limit(2000).minBytes(100).maxBytes(20e6.toInt).includeExtensions("DOC", "DOCX", "ODF", "RTF", "WPD", "WP", "LWP", "WSD").iterator()
  val feeder = Iterator.continually({ Map("FILE_PATH" -> cqbiter.next) })

  val scnFeedToBD = scenario("Documents2PDFCoverageScenario")
    .feed(feeder)
    .exec(initActions)
    .exec( session => {
      val path = session("FILE_PATH").as[String]
      val extension = path.substring(path.lastIndexOf(".") + 1).toLowerCase()
      session.set("INPUT_FILE_EXTENSION", extension)
        .set("OUTPUT_FILE_EXTENSION", "pdf")
    })
    .exec(assertConvertable)
    .exec(convertByFilePath)

  setUp(
    scnLogin.inject(atOnceUsers(1),nothingFor(45 seconds)),
    scnFeedToBD.inject(constantUsersPerSec(10) during(100))
  ).protocols(httpProtocol)
}
