package stasis.test.specs.unit.networking.http

import scala.concurrent.duration._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, SpawnProtocol}
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.{RequestEntity, StatusCodes}
import akka.http.scaladsl.server.MissingQueryParamRejection
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import stasis.networking.http.HttpEndpoint
import stasis.networking.http.HttpEndpoint._
import stasis.packaging.Crate
import stasis.persistence.{CrateStorageRequest, CrateStorageReservation}
import stasis.routing.Node
import stasis.test.specs.unit.AsyncUnitSpec
import stasis.test.specs.unit.persistence.mocks.{MockCrateStore, MockReservationStore}
import stasis.test.specs.unit.routing.mocks.MockRouter
import stasis.test.specs.unit.security.mocks.MockNodeAuthenticator

class HttpEndpointSpec extends AsyncUnitSpec with ScalatestRouteTest {

  private implicit val typedSystem: akka.actor.typed.ActorSystem[SpawnProtocol] = akka.actor.typed.ActorSystem(
    Behaviors.setup(_ => SpawnProtocol.behavior): Behavior[SpawnProtocol],
    "HttpEndpointSpec_Untyped"
  )

  private class TestHttpEndpoint(
    val testCrateStore: Option[MockCrateStore] = None,
    val testReservationStore: MockReservationStore = new MockReservationStore(),
    val testAuthenticator: MockNodeAuthenticator = new MockNodeAuthenticator(testUser, testPassword)
  ) extends HttpEndpoint(
        new MockRouter(testCrateStore.getOrElse(new MockCrateStore(testReservationStore))),
        testReservationStore.view,
        testAuthenticator
      )

  private val crateContent = "some value"

  private val testUser = "test-user"
  private val testPassword = "test-password"

  private val testCredentials = BasicHttpCredentials(username = testUser, password = testPassword)

  private val testReservation = CrateStorageReservation(
    id = CrateStorageReservation.generateId(),
    crate = Crate.generateId(),
    size = crateContent.length,
    copies = 3,
    retention = 3.seconds,
    expiration = 1.second,
    origin = Node.generateId(),
    target = Node.generateId()
  )

  "An HTTP Endpoint" should "successfully authenticate a client" in {
    val endpoint = new TestHttpEndpoint()
    endpoint.testReservationStore.put(testReservation).await

    Put(s"/crate/${testReservation.crate}?reservation=${testReservation.id}")
      .addCredentials(testCredentials)
      .withEntity(crateContent) ~> endpoint.routes ~> check {
      status should be(StatusCodes.OK)
    }
  }

  it should "fail to authenticate if no reservation ID is provided" in {
    val endpoint = new TestHttpEndpoint()

    Put(s"/crate/${Crate.generateId()}")
      .addCredentials(testCredentials)
      .withEntity(crateContent) ~> endpoint.routes ~> check {
      rejection should be(MissingQueryParamRejection("reservation"))
    }
  }

  it should "fail to authenticate a client with no credentials" in {
    val endpoint = new TestHttpEndpoint()

    Put(s"/crate/some-crate-id?reservation=${testReservation.id}")
      .withEntity(crateContent) ~> endpoint.routes ~> check {
      status should be(StatusCodes.Unauthorized)
    }
  }

  it should "fail to authenticate a client with invalid credentials" in {
    val endpoint = new TestHttpEndpoint()

    Put(s"/crate/some-crate-id?reservation=${testReservation.id}")
      .addCredentials(testCredentials.copy(password = "invalid-password"))
      .withEntity(crateContent) ~> endpoint.routes ~> check {
      status should be(StatusCodes.Unauthorized)
    }

    Put(s"/crate/some-crate-id?reservation=${testReservation.id}")
      .addCredentials(testCredentials.copy(username = "invalid-username"))
      .withEntity(crateContent) ~> endpoint.routes ~> check {
      status should be(StatusCodes.Unauthorized)
    }
  }

  it should "successfully process reservation requests" in {
    val endpoint = new TestHttpEndpoint()

    val storageRequest = CrateStorageRequest(
      crate = Crate.generateId(),
      size = 42,
      copies = 3,
      retention = 15.seconds,
      origin = Node.generateId(),
      source = Node.generateId()
    )

    val expectedReservation = CrateStorageReservation(
      id = CrateStorageReservation.generateId(),
      crate = Crate.generateId(),
      size = storageRequest.size,
      copies = storageRequest.copies,
      retention = storageRequest.retention,
      expiration = 1.day,
      origin = Node.generateId(),
      target = Node.generateId()
    )

    Put(s"/reserve")
      .addCredentials(testCredentials)
      .withEntity(Marshal(storageRequest).to[RequestEntity].await) ~> endpoint.routes ~> check {
      status should be(StatusCodes.OK)
      val actualReservation = responseAs[CrateStorageReservation]
      actualReservation.size should be(expectedReservation.size)
      actualReservation.copies should be(expectedReservation.copies)
      actualReservation.retention should be(expectedReservation.retention)
      actualReservation.expiration should be(expectedReservation.expiration)
    }
  }

  it should "reject reservation requests that cannot be fulfilled" in {
    val reservationStore = new MockReservationStore()
    val endpoint = new TestHttpEndpoint(
      testCrateStore = Some(new MockCrateStore(reservationStore, maxReservationSize = Some(99)))
    )

    val storageRequest = CrateStorageRequest(
      crate = Crate.generateId(),
      size = 100,
      copies = 3,
      retention = 15.seconds,
      origin = Node.generateId(),
      source = Node.generateId()
    )

    Put(s"/reserve")
      .addCredentials(testCredentials)
      .withEntity(Marshal(storageRequest).to[RequestEntity].await) ~> endpoint.routes ~> check {
      status should be(StatusCodes.InsufficientStorage)
    }
  }

  it should "successfully store crates" in {
    val endpoint = new TestHttpEndpoint()
    endpoint.testReservationStore.put(testReservation).await

    Put(s"/crate/${testReservation.crate}?reservation=${testReservation.id}")
      .addCredentials(testCredentials)
      .withEntity(crateContent) ~> endpoint.routes ~> check {
      status should be(StatusCodes.OK)
    }
  }

  it should "fail to store crates when reservation is missing" in {
    val endpoint = new TestHttpEndpoint()

    val crateId = Crate.generateId()

    Put(s"/crate/$crateId?reservation=${testReservation.id}")
      .addCredentials(testCredentials)
      .withEntity(crateContent) ~> endpoint.routes ~> check {
      status should be(StatusCodes.FailedDependency)
    }
  }

  it should "fail to store crates when reservation is for a different crate" in {
    val endpoint = new TestHttpEndpoint()
    endpoint.testReservationStore.put(testReservation).await

    Put(s"/crate/${Crate.generateId()}?reservation=${testReservation.id}")
      .addCredentials(testCredentials)
      .withEntity(crateContent) ~> endpoint.routes ~> check {
      status should be(StatusCodes.BadRequest)
    }
  }

  it should "successfully retrieve crates" in {
    val endpoint = new TestHttpEndpoint()
    endpoint.testReservationStore.put(testReservation).await

    Put(s"/crate/${testReservation.crate}?reservation=${testReservation.id}")
      .addCredentials(testCredentials)
      .withEntity(crateContent) ~> endpoint.routes ~> check {
      status should be(StatusCodes.OK)

      Get(s"/crate/${testReservation.crate}")
        .addCredentials(testCredentials) ~> endpoint.routes ~> check {
        status should be(StatusCodes.OK)
        responseAs[ByteString] should be(crateContent.getBytes)
      }
    }
  }

  it should "fail to retrieve missing crates" in {
    val endpoint = new TestHttpEndpoint()

    Get(s"/crate/${Crate.generateId()}?reservation=${testReservation.id}")
      .addCredentials(testCredentials) ~> endpoint.routes ~> check {
      status should be(StatusCodes.NotFound)
    }
  }

  it should "successfully delete existing crates" in {
    val endpoint = new TestHttpEndpoint()
    endpoint.testReservationStore.put(testReservation).await

    Put(s"/crate/${testReservation.crate}?reservation=${testReservation.id}")
      .addCredentials(testCredentials)
      .withEntity(crateContent) ~> endpoint.routes ~> check {
      status should be(StatusCodes.OK)

      Delete(s"/crate/${testReservation.crate}")
        .addCredentials(testCredentials) ~> endpoint.routes ~> check {
        status should be(StatusCodes.OK)
      }
    }
  }

  it should "fail to delete missing crates" in {
    val endpoint = new TestHttpEndpoint()

    Delete(s"/crate/${Crate.generateId()}")
      .addCredentials(testCredentials) ~> endpoint.routes ~> check {
      status should be(StatusCodes.InternalServerError)
    }
  }
}
