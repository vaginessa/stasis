package stasis.test.specs.unit.server.security.users

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Behavior, SpawnProtocol}
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import stasis.core.security.tls.EndpointContext
import stasis.server.security.users.{IdentityUserCredentialsManager, UserCredentialsManager}
import stasis.shared.model.users.User
import stasis.test.specs.unit.AsyncUnitSpec
import stasis.test.specs.unit.server.security.mocks.MockIdentityUserManageEndpoint
import stasis.test.specs.unit.server.security.mocks.MockIdentityUserManageEndpoint._
import stasis.test.specs.unit.shared.model.Generators

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.control.NonFatal

class IdentityUserCredentialsManagerSpec extends AsyncUnitSpec {
  "An IdentityUserCredentialsManager" should "provide its ID" in {
    val manager = createManager(identityUrl = "test-url")
    manager.id should be("test-url")
  }

  it should "support handling HTTP responses" in {
    import IdentityUserCredentialsManager.ExtendedHttpResponse

    val expectedConflictMessage = "Test response - conflict"
    val expectedNotFoundMessage = "Test response - not found"
    val expectedFailureMessage = "Test response - failure"

    for {
      success <- HttpResponse().withStatus(StatusCodes.OK).asResult
      conflict <- HttpResponse().withStatus(StatusCodes.Conflict).withEntity(expectedConflictMessage).asResult
      notFound <- HttpResponse().withStatus(StatusCodes.NotFound).withEntity(expectedNotFoundMessage).asResult
      failure <- HttpResponse().withStatus(StatusCodes.InternalServerError).withEntity(expectedFailureMessage).asResult.failed
    } yield {
      success should be(UserCredentialsManager.Result.Success)
      conflict should be(UserCredentialsManager.Result.Conflict(expectedConflictMessage))
      notFound should be(UserCredentialsManager.Result.NotFound(expectedNotFoundMessage))
      failure.getMessage should be(s"Identity request failed with [500 Internal Server Error]: [$expectedFailureMessage]")
    }
  }

  it should "create resource owners" in {
    val endpoint = MockIdentityUserManageEndpoint(port = ports.dequeue(), credentials = credentials)
    endpoint.start()

    val manager = createManager(endpoint.url)

    manager
      .createResourceOwner(
        user = Generators.generateUser,
        username = "test-user",
        rawPassword = "test-password"
      )
      .map { _ =>
        endpoint.stop()

        endpoint.created should be(1)
        endpoint.activated should be(0)
        endpoint.deactivated should be(0)
        endpoint.passwordUpdated should be(0)
      }
  }

  it should "handle resource owner creation failures" in {
    val endpoint = MockIdentityUserManageEndpoint(
      port = ports.dequeue(),
      credentials = credentials,
      creationResult = CreationResult.Failure
    )

    endpoint.start()

    val manager = createManager(endpoint.url)

    manager
      .createResourceOwner(
        user = Generators.generateUser,
        username = "test-user",
        rawPassword = "test-password"
      )
      .map { result =>
        fail(s"Unexpected result received: [$result]")
      }
      .recover { case NonFatal(e) =>
        endpoint.stop()

        e.getMessage should be("Identity request failed with [500 Internal Server Error]: []")

        endpoint.created should be(1)
        endpoint.activated should be(0)
        endpoint.deactivated should be(0)
        endpoint.passwordUpdated should be(0)
      }
  }

  it should "activate resource owners" in {
    val endpoint = MockIdentityUserManageEndpoint(port = ports.dequeue(), credentials = credentials)
    endpoint.start()

    val manager = createManager(endpoint.url)

    manager
      .activateResourceOwner(
        user = User.generateId()
      )
      .map { _ =>
        endpoint.stop()

        endpoint.created should be(0)
        endpoint.activated should be(1)
        endpoint.deactivated should be(0)
        endpoint.passwordUpdated should be(0)
      }
  }

  it should "handle resource owner activation failures" in {
    val endpoint = MockIdentityUserManageEndpoint(
      port = ports.dequeue(),
      credentials = credentials,
      activationResult = ActivationResult.Failure
    )
    endpoint.start()

    val manager = createManager(endpoint.url)

    manager
      .activateResourceOwner(
        user = User.generateId()
      )
      .map { result =>
        fail(s"Unexpected result received: [$result]")
      }
      .recover { case NonFatal(e) =>
        endpoint.stop()

        e.getMessage should be("Identity request failed with [500 Internal Server Error]: []")

        endpoint.created should be(0)
        endpoint.activated should be(1)
        endpoint.deactivated should be(0)
        endpoint.passwordUpdated should be(0)
      }
  }

  it should "deactivate resource owners" in {
    val endpoint = MockIdentityUserManageEndpoint(port = ports.dequeue(), credentials = credentials)
    endpoint.start()

    val manager = createManager(endpoint.url)

    manager
      .deactivateResourceOwner(
        user = User.generateId()
      )
      .map { _ =>
        endpoint.stop()

        endpoint.created should be(0)
        endpoint.activated should be(0)
        endpoint.deactivated should be(1)
        endpoint.passwordUpdated should be(0)
      }
  }

  it should "handle resource owner deactivation failures" in {
    val endpoint = MockIdentityUserManageEndpoint(
      port = ports.dequeue(),
      credentials = credentials,
      deactivationResult = DeactivationResult.Failure
    )
    endpoint.start()

    val manager = createManager(endpoint.url)

    manager
      .deactivateResourceOwner(
        user = User.generateId()
      )
      .map { result =>
        fail(s"Unexpected result received: [$result]")
      }
      .recover { case NonFatal(e) =>
        endpoint.stop()

        e.getMessage should be("Identity request failed with [500 Internal Server Error]: []")

        endpoint.created should be(0)
        endpoint.activated should be(0)
        endpoint.deactivated should be(1)
        endpoint.passwordUpdated should be(0)
      }
  }

  it should "reset resource owner passwords" in {
    val endpoint = MockIdentityUserManageEndpoint(port = ports.dequeue(), credentials = credentials)
    endpoint.start()

    val manager = createManager(endpoint.url)

    manager
      .setResourceOwnerPassword(
        user = User.generateId(),
        rawPassword = "test-password"
      )
      .map { _ =>
        endpoint.stop()

        endpoint.created should be(0)
        endpoint.activated should be(0)
        endpoint.deactivated should be(0)
        endpoint.passwordUpdated should be(1)
      }
  }

  it should "handle resource owner password reset failures" in {
    val endpoint = MockIdentityUserManageEndpoint(
      port = ports.dequeue(),
      credentials = credentials,
      passwordUpdateResult = PasswordUpdateResult.Failure
    )
    endpoint.start()

    val manager = createManager(endpoint.url)

    manager
      .setResourceOwnerPassword(
        user = User.generateId(),
        rawPassword = "test-password"
      )
      .map { result =>
        fail(s"Unexpected result received: [$result]")
      }
      .recover { case NonFatal(e) =>
        endpoint.stop()

        e.getMessage should be("Identity request failed with [500 Internal Server Error]: []")

        endpoint.created should be(0)
        endpoint.activated should be(0)
        endpoint.deactivated should be(0)
        endpoint.passwordUpdated should be(1)
      }
  }

  private def createManager(
    identityUrl: String,
    context: Option[EndpointContext] = None
  ): IdentityUserCredentialsManager =
    new IdentityUserCredentialsManager(
      identityUrl = identityUrl,
      identityCredentials = () => Future.successful(credentials),
      context = context,
      requestBufferSize = 100
    )

  private val credentials = OAuth2BearerToken(token = "test-token")

  private implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(
    Behaviors.setup(_ => SpawnProtocol()): Behavior[SpawnProtocol.Command],
    "IdentityUserCredentialsManagerSpec"
  )

  private val ports: mutable.Queue[Int] = (37000 to 37100).to(mutable.Queue)
}
