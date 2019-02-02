package stasis.test.specs.unit.security.jwt

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorSystem, Behavior, SpawnProtocol}
import akka.stream.ActorMaterializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import stasis.security.jwt.RemoteKeyProvider
import stasis.test.specs.unit.AsyncUnitSpec
import stasis.test.specs.unit.security.jwt.mocks.MockJwksEndpoint

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class RemoteKeyProviderSpec extends AsyncUnitSpec with Eventually with BeforeAndAfterAll {

  private implicit val system: ActorSystem[SpawnProtocol] = ActorSystem(
    Behaviors.setup(_ => SpawnProtocol.behavior): Behavior[SpawnProtocol],
    "RemoteKeyProviderSpec"
  )

  private implicit val untypedSystem: akka.actor.ActorSystem = system.toUntyped
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 250.milliseconds)

  private val ports: mutable.Queue[Int] = (18000 to 18100).to[mutable.Queue]

  override protected def afterAll(): Unit =
    system.terminate().await

  "An RemoteKeyProvider" should "provide keys from a JWKS endpoint" in {
    val endpoint = new MockJwksEndpoint(port = ports.dequeue())
    endpoint.start()

    val provider = RemoteKeyProvider(
      jwksEndpoint = s"${endpoint.url}/valid/jwks.json",
      refreshInterval = 1.second,
      issuer = "self"
    )

    val expectedKeyId = "rsa-1"
    val expectedKey = endpoint.keys(expectedKeyId)

    eventually {
      val actualKey = provider.key(id = Some(expectedKeyId)).await
      actualKey should be(expectedKey)
    }
  }

  it should "not provide keys with no IDs" in {
    val provider = RemoteKeyProvider(
      jwksEndpoint = "localhost",
      refreshInterval = 1.second,
      issuer = "self"
    )

    provider
      .key(id = None)
      .map { response =>
        fail(s"Received unexpected response from provider: [$response]")
      }
      .recover {
        case NonFatal(e) =>
          e.getMessage should be("Key expected but none was provided")
      }
  }

  it should "parse raw JWKS" in {
    val rawJwks =
      """
        |{
        |  "keys": [
        |    {
        |      "kty": "RSA",
        |      "kid": "rsa-1",
        |      "n": "fKyu6V00Oe09uOnszrA6AyMK6Xx1E193aqwy6rs5fnxaWHr7-aZo14-BjeXZPzUQrJc12hrUaRFiHbnhEBy2t5-UViZ1K2OaTAwqetdX0fJRAKKKT--4Xtc7Ya0LNd65CyMYyr5UOKCZaSDJdiSnqq1re3ruOwo9Znpj_96bAgE",
        |      "e": "1OtdxoiBvtYttClxle2IrnuDyyQBq_VdWT45Wj_YnMGwYAPMuQPsq8thGTX9_1VuFaJdkg990sP4s85rBQsv9Q"
        |    },
        |    {
        |      "kty": "RSA",
        |      "kid": "rsa-2",
        |      "n": "i-venDxgb4I6Prk3u_rw5n7PC1EZJagmOY1mZoxdf3pje5IWWrzEMr2S3L4pk4shM5Z5RLPXWq3GZbVLksHcVfif-q83P39F4yywfJobnE-xKAyEM--DY8ot4bv9wAa4jnTFCqmiIEzFeUaRgY5JktZ61uC0rCGG2RkDc5mp-W8",
        |      "e": "5ifdEkBfTGSEJu0Hrnq6fH0hz5bBNZUJG5ajYFN4m3mzwBfEkp4wGbl_3UXtx1lr185ztvy1gcN3bROvD94Wsw"
        |    },
        |    {
        |      "kty": "EC",
        |      "kid": "ec-1",
        |      "x": "ARIKKCnTvFSOMJd3UPznMS_dO51gCIowvN9zGp-0KjyFI6uN2ERFEif0I68nNe0T2nHZPzXiq9DnmmfccQvczYYd",
        |      "y": "AMuCEXhYxCh0yWJCHZfFLRUESyM7G4ESR_ywgiBMTMLjqjpmwdWEe7LVnCeB1H-eoKys5wpDe_Kr1hRY6-w7W8U2",
        |      "crv": "P-521"
        |    }
        |  ]
        |}
      """.stripMargin

    val expectedResult = Seq(Right(("rsa-1", "RSA")), Right(("rsa-2", "RSA")), Right(("ec-1", "EC")))
    val actualResult = RemoteKeyProvider.parseJwks(rawJwks) match {
      case Success(result) =>
        result.map(_.map(jwk => (jwk.id, jwk.key.getAlgorithm)))

      case Failure(e) =>
        fail(s"Received unexpected parsing result: [$e]")
    }

    actualResult should be(expectedResult)

    val emptyRawJwks = """{ "keys": [] }"""
    val expectedEmptyResult = Seq()
    val actualEmptyResult = RemoteKeyProvider.parseJwks(emptyRawJwks) match {
      case Success(result) =>
        result

      case Failure(e) =>
        fail(s"Received unexpected parsing result: [$e]")
    }

    actualEmptyResult should be(expectedEmptyResult)
  }

  it should "handle unexpected keys from JWKS endpoint" in {
    val rawJwks =
      """
        |{
        |  "keys": [
        |    {
        |      "kty": "RSA",
        |      "kid": "rsa-1",
        |      "n": "fKyu6V00Oe09uOnszrA6AyMK6Xx1E193aqwy6rs5fnxaWHr7-aZo14-BjeXZPzUQrJc12hrUaRFiHbnhEBy2t5-UViZ1K2OaTAwqetdX0fJRAKKKT--4Xtc7Ya0LNd65CyMYyr5UOKCZaSDJdiSnqq1re3ruOwo9Znpj_96bAgE",
        |      "e": "1OtdxoiBvtYttClxle2IrnuDyyQBq_VdWT45Wj_YnMGwYAPMuQPsq8thGTX9_1VuFaJdkg990sP4s85rBQsv9Q"
        |    },
        |    {
        |      "kty": "oct",
        |      "kid": "s-0",
        |      "k": "D5Npm15g6gitrrki_dif1dGJJ0dZU52b_9xcqrsZiwmnb6s8ZHepA2gm3FHd7kg2mxj2ErMhbnKX8EkQDTqLIQ"
        |    },
        |    {
        |      "kty": "EC",
        |      "x": "ARIKKCnTvFSOMJd3UPznMS_dO51gCIowvN9zGp-0KjyFI6uN2ERFEif0I68nNe0T2nHZPzXiq9DnmmfccQvczYYd",
        |      "y": "AMuCEXhYxCh0yWJCHZfFLRUESyM7G4ESR_ywgiBMTMLjqjpmwdWEe7LVnCeB1H-eoKys5wpDe_Kr1hRY6-w7W8U2",
        |      "crv": "P-521"
        |    }
        |  ]
        |}
      """.stripMargin

    val expectedResult =
      Seq(
        Right(("rsa-1", "RSA")),
        Right(("s-0", "AES")),
        Left("Found key of type [EC] without an ID")
      )

    val actualResult = RemoteKeyProvider.parseJwks(rawJwks) match {
      case Success(result) =>
        result.map(_.right.map(jwk => (jwk.id, jwk.key.getAlgorithm)).left.map(_.failure))

      case Failure(e) =>
        fail(s"Received unexpected parsing result: [$e]")
    }

    actualResult should be(expectedResult)
  }

  it should "retrieve raw JWKS from endpoint" in {
    val endpoint = new MockJwksEndpoint(port = ports.dequeue())
    endpoint.start()

    val expectedResult = endpoint.jwks.toJson

    RemoteKeyProvider.getRawJwks(s"${endpoint.url}/valid/jwks.json").map { actualResult =>
      actualResult should be(expectedResult)
    }
  }

  it should "handle JWKS endpoint failure" in {
    val endpoint = new MockJwksEndpoint(port = ports.dequeue())
    endpoint.start()

    RemoteKeyProvider
      .getRawJwks(s"${endpoint.url}/invalid/jwks.json")
      .map { response =>
        fail(s"Received unexpected response from endpoint: [$response]")
      }
      .recover {
        case NonFatal(e) =>
          e.getMessage should be("Endpoint responded with unexpected status: [500 Internal Server Error]")
      }
  }
}
