package ldp

import java.util

import io.gatling.core.Predef.{details, _}
import io.gatling.http.Predef._
import umd.ciber.ciber_sampling.CiberQueryBuilder

import scala.concurrent.duration._

class SolidStressTestIngest extends Simulation {


  val BASE_URL = System.getenv("LDP_URL")
  val httpProtocol = http.baseUrl(BASE_URL)
  val SIM_USERS: Int = Integer.getInteger("users", 2000)
  val SIM_RAMP_TIME: Long = java.lang.Long.getLong("ramp", 200L)

  // Data: Unlimited newly random slice as URLs, files less than 20GB
  val seed = new java.lang.Float(.19855721)
  val cqbiter: util.Iterator[String] = new CiberQueryBuilder()
    .excludeExtensions("ttl")
    .limit(2000)
    .randomSeed(seed).minBytes(100).maxBytes(20e6.toInt).iterator()

  // Data: Unlimited newly random slice as URLs, files less than 20GB
  val rdfCqbiter: util.Iterator[String] = new CiberQueryBuilder()
    .includeExtensions("ttl")
    .limit(2000)
    .randomSeed(seed).minBytes(100).maxBytes(20e6.toInt).iterator()

  // Need to cache the results and loop through them again and again
  var binaryFileCache = loadCache(cqbiter)
  var rdfFileCache = loadCache(rdfCqbiter)

  val feeder = Iterator.continually({
    val path = binaryFileCache.head

    // rotate the cache for the next iteration
    binaryFileCache = binaryFileCache.drop(1) ++ binaryFileCache.take(1)
    val title = path.substring(path.lastIndexOf('/') + 1, path.length())


    val rdfPath = rdfFileCache.head
    // rotate the cache for the next iteration
    rdfFileCache = rdfFileCache.drop(1) ++ rdfFileCache.take(1)
    val rdfTitle = rdfPath.substring(rdfPath.lastIndexOf('/') + 1, rdfPath.length())


    Map("PATH" -> path, "TITLE" -> title,
      "RDF_PATH" -> rdfPath, "RDF_TITLE" -> rdfTitle)
  })


  private def loadCache(iter: util.Iterator[String]): List[String] = {
    var cache = List[String]()
    while (iter.hasNext) {
      val path = iter.next
      cache = path :: cache
    }

    cache
  }

  val LINK = "Link"
  val RDF_SOURCE_TYPE = "<http://www.w3.org/ns/ldp#RDFSource>; rel=\"type\""
  val BASIC_CONTAINER_TYPE = "<http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\""
  val HEADERS_TURTLE = Map("Content-Type" -> "text/turtle")
  val HEADERS_OCTET_STREAM = Map("Content-Type" -> "application/octet-stream")

  val CONTAINER_KEY = "Container"
  val RDF_SOURCE_KEY = "RDFSource"
  val NON_RDF_SOURCE_KEY = "NonRDFSource"
  val LOCATION = "Location"

  val CONTAINER_LOOKUP = "${Container}"
  val RDF_SOURCE_LOOKUP = "${RDFSource}"

  val NON_RDF_SOURCE_LOOKUP = "${NonRDFSource}"

  val CONTAINER_RDF =
    """
      @prefix dcterms: <http://purl.org/dc/terms/> .
      <> dcterms:title "${TITLE}" ;
         dcterms:source "${PATH}" ;
         dcterms:title "${RDF_TITLE}" ;
         dcterms:source "${RDF_PATH}" .
    """

  object Search {

    val searchContainer = exec(http("Get Container")
      .get(CONTAINER_LOOKUP)
      .check(status.is(200)))

    val searchRdfResource = exec(http("Get RDF Resource")
      .get(RDF_SOURCE_LOOKUP)
      .check(status.is(200)))

    val searchNonRdfResource = exec(http("Get Non RDF Resource")
      .get(NON_RDF_SOURCE_LOOKUP)
      .check(status.is(200)))
  }

  object Resource {

    val createContainer = exec(http("Create Container")
      .post(BASE_URL)
      .headers(HEADERS_TURTLE)
      .header(LINK, BASIC_CONTAINER_TYPE)
      .body(StringBody(CONTAINER_RDF))
      .check(status.in(201, 200), header(LOCATION).saveAs(CONTAINER_KEY))
    )

    val createRdf = exec(http("Create RDF Resource")
      .post(CONTAINER_LOOKUP)
      .headers(HEADERS_TURTLE)
      .header(LINK, RDF_SOURCE_TYPE)
      .body(RawFileBody("${RDF_PATH}"))
      .check(status.in(201, 200), header(LOCATION).saveAs(RDF_SOURCE_KEY)))
      .pause(2 seconds)

    val createNonRdfResource = exec(http("Create non-RDF binary")
      .post(CONTAINER_LOOKUP)
      .headers(HEADERS_OCTET_STREAM)
      .body(RawFileBody("${PATH}"))
      .check(status.in(201, 200), header(LOCATION).saveAs(NON_RDF_SOURCE_KEY)))

    val updateRdfMultipleTimes = repeat(10, "n") {
      exec(http("Update RDF Resource")
        .put(RDF_SOURCE_LOOKUP)
        .headers(HEADERS_TURTLE)
        .header(LINK, RDF_SOURCE_TYPE)
        .body(StringBody(
          """
            @prefix foaf: <http://xmlns.com/foaf/0.1/>.
            @prefix vcard: <http://www.w3.org/2006/vcard/ns#>.
            @prefix schem: <http://schema.org/>.
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.

            <>
                a schem:Person, vcard:Person;
                rdfs:label "Joe Bloggs"@en;
                foaf:name "Joe";
                foaf:givenName "Bloggs"@en;
                foaf:familyName "Bloggs"@en;
                foaf:birthday "01-01";
                foaf:age 43;
                foaf:gender "male"@en;
                rdfs:update "${n}".
          """
        ))
        .check(status.is(204)))
        .pause(2 seconds)
    }

    val deleteContainer = exec(http("Delete Resource")
      .delete(CONTAINER_LOOKUP)
      .check(status.is(204)))

    val deleteRdfResource = exec(http("Delete Resource")
      .delete(RDF_SOURCE_LOOKUP)
      .check(status.is(204)))

    val deleteNonRdfResource = exec(http("Delete Resource")
      .delete(NON_RDF_SOURCE_LOOKUP)
      .check(status.is(204)))

  }

  val createAndGetIngest = scenario("Create and Get RDF and Non RDF Resources")
    .feed(feeder)
    .exec(Resource.createContainer,
      Resource.createRdf, Search.searchRdfResource,
      Resource.createNonRdfResource, Search.searchNonRdfResource)

  val createAndDeleteIngest = scenario("Create and Delete RDF and Non RDF Resources")
    .feed(feeder)
    .exec(Resource.createContainer,
      Resource.createRdf,
      Resource.createNonRdfResource,
      Resource.deleteNonRdfResource,
      Resource.deleteRdfResource,
      Resource.deleteContainer)

  val createAndUpdateIngest = scenario("Create and Update RDF and Non RDF Resources")
    .feed(feeder)
    .exec(Resource.createContainer,
      Resource.createRdf,
      Resource.createNonRdfResource,
      Resource.updateRdfMultipleTimes)

  /*
   * Here we want to split the users into groups of tests. 60% of the users will be creating and getting
   * resources, 20% will be creating and updating, and the last 20% creates and deletes resources.
   */
  private val users60Percent: Int = SIM_USERS - (SIM_USERS * 40) / 100
  private val users20Percent: Int = (SIM_USERS * 20) / 100

  setUp(
    createAndGetIngest.inject(
      nothingFor(2 seconds),
      rampUsers(users60Percent) during (SIM_RAMP_TIME seconds)),

    createAndUpdateIngest.inject(
      nothingFor(5 seconds),
      rampUsers(users20Percent) during (SIM_RAMP_TIME seconds)),

    createAndDeleteIngest.inject(
      nothingFor(10 seconds),
      rampUsers(users20Percent) during (SIM_RAMP_TIME seconds))
  )
    .protocols(httpProtocol)
    .assertions(
      global.responseTime.max.lt(200),
      global.successfulRequests.percent.gt(99),
      details("Get RDF Resource").requestsPerSec.between(5, 50),
      details("Create non-RDF binary").responseTime.max.lt(150),
      details("Update RDF Resource").responseTime.max.lt(100),
      details("Delete Resource").responseTime.max.lt(80)
    )
}
