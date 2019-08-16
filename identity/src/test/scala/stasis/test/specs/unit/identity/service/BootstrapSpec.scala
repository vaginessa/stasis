package stasis.test.specs.unit.identity.service

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorSystem, Behavior, SpawnProtocol}
import akka.event.{Logging, LoggingAdapter}
import com.typesafe.config.Config
import stasis.identity.model.Seconds
import stasis.identity.model.apis.Api
import stasis.identity.model.secrets.Secret
import stasis.identity.service.Bootstrap.Entities
import stasis.identity.service.{Bootstrap, Persistence}
import stasis.test.specs.unit.AsyncUnitSpec
import stasis.test.specs.unit.identity.model.Generators

import scala.concurrent.duration._

class BootstrapSpec extends AsyncUnitSpec {
  "Bootstrap" should "setup the service with provided entities" in {
    val expectedEntities = Entities(
      apis = Generators.generateSeq(min = 1, g = Generators.generateApi),
      clients = Generators.generateSeq(min = 1, g = Generators.generateClient),
      owners = Generators.generateSeq(min = 1, g = Generators.generateResourceOwner)
    )

    val persistence = new Persistence(
      persistenceConfig = config.getConfig("persistence"),
      authorizationCodeExpiration = 3.seconds,
      refreshTokenExpiration = 3.seconds
    )

    Bootstrap
      .run(
        entities = expectedEntities,
        persistence = persistence
      )
      .flatMap { _ =>
        for {
          actualApis <- persistence.apis.apis
          actualClients <- persistence.clients.clients
          actualOwners <- persistence.resourceOwners.owners
          _ <- persistence.drop()
        } yield {
          val identityApi = Api(id = Api.ManageIdentity)

          actualApis.values.toSeq.sortBy(_.id) should be((expectedEntities.apis :+ identityApi).sortBy(_.id))
          actualClients.values.toSeq.sortBy(_.id) should be(expectedEntities.clients.sortBy(_.id))
          actualOwners.values.toSeq.sortBy(_.username) should be(expectedEntities.owners.sortBy(_.username))
        }
      }
  }

  it should "setup the service with configured entities" in {
    val persistence = new Persistence(
      persistenceConfig = config.getConfig("persistence"),
      authorizationCodeExpiration = 3.seconds,
      refreshTokenExpiration = 3.seconds
    )

    val expectedApis = Seq(
      Api(id = Api.ManageIdentity),
      Api(id = "example-api")
    )

    Bootstrap
      .run(
        bootstrapConfig = config.getConfig("bootstrap-enabled"),
        persistence = persistence
      )
      .flatMap { _ =>
        for {
          actualApis <- persistence.apis.apis
          actualClients <- persistence.clients.clients
          actualOwners <- persistence.resourceOwners.owners
          _ <- persistence.drop()
        } yield {
          actualApis.values.toSeq.sortBy(_.id) should be(expectedApis.sortBy(_.id))

          actualClients.values.toList.sortBy(_.redirectUri) match {
            case client1 :: client2 :: client3 :: Nil =>
              client1.redirectUri should be("http://localhost:8080/example/uri1")
              client1.tokenExpiration should be(Seconds(90 * 60))
              client1.active should be(true)
              client1.secret.isSameAs(
                rawSecret = "example-client-secret",
                salt = client1.salt
              )(clientSecretsConfig) should be(true)

              client2.id should be(java.util.UUID.fromString("2c4311a6-f9a8-4c9f-8634-98afd90753e0"))
              client2.redirectUri should be("http://localhost:8080/example/uri2")

              client3.redirectUri should be("http://localhost:8080/example/uri3")

            case unexpectedResult =>
              fail(s"Unexpected result received: [$unexpectedResult]")
          }

          actualOwners.values.toList match {
            case owner :: Nil =>
              owner.username should be("example-user")
              owner.allowedScopes should be(Seq("example-scope-a", "example-scope-b", "example-scope-c"))
              owner.active should be(true)
              owner.password.isSameAs(
                rawSecret = "example-user-password",
                salt = owner.salt
              )(ownerSecretsConfig) should be(true)

            case unexpectedResult =>
              fail(s"Unexpected result received: [$unexpectedResult]")
          }
        }
      }
  }

  it should "not run if not enabled" in {
    val persistence = new Persistence(
      persistenceConfig = config.getConfig("persistence"),
      authorizationCodeExpiration = 3.seconds,
      refreshTokenExpiration = 3.seconds
    )

    Bootstrap
      .run(
        bootstrapConfig = config.getConfig("bootstrap-disabled"),
        persistence = persistence
      )
      .flatMap { _ =>
        for {
          _ <- persistence.init()
          actualApis <- persistence.apis.apis
          actualClients <- persistence.clients.clients
          actualOwners <- persistence.resourceOwners.owners
          _ <- persistence.drop()
        } yield {
          actualApis should be(Map.empty)
          actualClients should be(Map.empty)
          actualOwners should be(Map.empty)
        }
      }
  }

  private implicit val system: ActorSystem[SpawnProtocol] = ActorSystem(
    Behaviors.setup(_ => SpawnProtocol.behavior): Behavior[SpawnProtocol],
    "BootstrapSpec"
  )

  private implicit val untyped: akka.actor.ActorSystem = system.toUntyped

  private implicit val log: LoggingAdapter = Logging(untyped, this.getClass.getName)

  private implicit val clientSecretsConfig: Secret.ClientConfig =
    Secret.ClientConfig(
      algorithm = "PBKDF2WithHmacSHA512",
      iterations = 10000,
      derivedKeySize = 32,
      saltSize = 16,
      authenticationDelay = 20.millis
    )

  private implicit val ownerSecretsConfig: Secret.ResourceOwnerConfig =
    Secret.ResourceOwnerConfig(
      algorithm = "PBKDF2WithHmacSHA512",
      iterations = 10000,
      derivedKeySize = 32,
      saltSize = 16,
      authenticationDelay = 20.millis
    )

  private val config: Config = system.settings.config.getConfig("stasis.test.identity")
}