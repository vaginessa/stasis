package stasis.identity.service

import java.util.concurrent.atomic.AtomicReference

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorSystem, Behavior, SpawnProtocol}
import akka.event.{Logging, LoggingAdapter}
import com.typesafe.{config => typesafe}
import org.jose4j.jwk.JsonWebKey
import stasis.core.security.jwt.{JwtAuthenticator, LocalKeyProvider}
import stasis.identity.api.{IdentityEndpoint, manage => manageApi, oauth => oauthApi}
import stasis.identity.authentication.{manage, oauth}
import stasis.identity.model.apis.Api
import stasis.identity.model.codes.generators.DefaultAuthorizationCodeGenerator
import stasis.identity.model.secrets.Secret
import stasis.identity.model.tokens.generators.{JwtBearerAccessTokenGenerator, RandomRefreshTokenGenerator}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

trait Service {
  import Service._

  private implicit val system: ActorSystem[SpawnProtocol] = ActorSystem(
    Behaviors.setup(_ => SpawnProtocol.behavior): Behavior[SpawnProtocol],
    name = "stasis-identity-service"
  )

  private implicit val untyped: akka.actor.ActorSystem = system.toUntyped
  private implicit val ec: ExecutionContext = system.executionContext
  private implicit val log: LoggingAdapter = Logging(untyped, this.getClass.getName)

  protected def systemConfig: typesafe.Config = system.settings.config

  private val rawConfig: typesafe.Config = systemConfig.getConfig("stasis.identity")
  private val config: Config = Config(rawConfig.getConfig("service"))

  val persistence: Persistence = new Persistence(
    persistenceConfig = rawConfig.getConfig("persistence"),
    authorizationCodeExpiration = rawConfig.getDuration("codes.authorization.expiration").toMillis.millis,
    refreshTokenExpiration = rawConfig.getDuration("tokens.refresh.expiration").toMillis.millis
  )(system, config.internalQueryTimeout)

  private implicit val clientSecretsConfig: Secret.ClientConfig =
    Secret.ClientConfig(rawConfig.getConfig("secrets.client"))

  private implicit val ownerSecretsConfig: Secret.ResourceOwnerConfig =
    Secret.ResourceOwnerConfig(rawConfig.getConfig("secrets.resource-owner"))

  private val accessTokenSignatureKey: JsonWebKey =
    SignatureKey.fromConfig(rawConfig.getConfig("tokens.access.signature-key"))

  private val accessTokenIssuer: String =
    rawConfig.getString("tokens.access.issuer")

  private val oauthProviders = oauthApi.setup.Providers(
    apiStore = persistence.apis.view,
    clientStore = persistence.clients.view,
    realmStore = persistence.realms.view,
    refreshTokenStore = persistence.refreshTokens,
    authorizationCodeStore = persistence.authorizationCodes,
    accessTokenGenerator = new JwtBearerAccessTokenGenerator(
      issuer = accessTokenIssuer,
      jwk = accessTokenSignatureKey,
      jwtExpiration = rawConfig.getDuration("tokens.access.expiration").toMillis.millis
    ),
    authorizationCodeGenerator = new DefaultAuthorizationCodeGenerator(
      codeSize = rawConfig.getInt("codes.authorization.size")
    ),
    refreshTokenGenerator = new RandomRefreshTokenGenerator(
      tokenSize = rawConfig.getInt("tokens.refresh.size")
    ),
    clientAuthenticator = new oauth.DefaultClientAuthenticator(
      store = persistence.clients.view,
      secretConfig = clientSecretsConfig
    ),
    resourceOwnerAuthenticator = new oauth.DefaultResourceOwnerAuthenticator(
      store = persistence.resourceOwners.view,
      secretConfig = ownerSecretsConfig
    )
  )

  private val manageProviders = manageApi.setup.Providers(
    apiStore = persistence.apis,
    clientStore = persistence.clients,
    codeStore = persistence.authorizationCodes,
    ownerStore = persistence.resourceOwners,
    realmStore = persistence.realms,
    tokenStore = persistence.refreshTokens,
    ownerAuthenticator = new manage.DefaultResourceOwnerAuthenticator(
      store = persistence.resourceOwners.view,
      underlying = new JwtAuthenticator(
        provider = new LocalKeyProvider(
          jwk = accessTokenSignatureKey,
          issuer = accessTokenIssuer
        ),
        audience = Api.ManageMaster,
        expirationTolerance =
          rawConfig.getDuration("authenticators.resource-owner.expiration-tolerance").toMillis.millis
      )
    )
  )

  private val endpoint = new IdentityEndpoint(
    keys = Seq(accessTokenSignatureKey),
    oauthProviders = oauthProviders,
    manageProviders = manageProviders,
    manageConfig = manageApi.setup.Config(
      clientSecrets = clientSecretsConfig,
      ownerSecrets = ownerSecretsConfig
    )
  )

  private val serviceState: AtomicReference[State] = new AtomicReference[State](State.Starting)

  Bootstrap
    .run(rawConfig.getConfig("bootstrap"), persistence)
    .onComplete {
      case Success(_) =>
        serviceState.set(Service.State.Started)
        start()

      case Failure(e) =>
        log.error(e, "Bootstrap failed: [{}]", e.getMessage)
        serviceState.set(Service.State.Failed)
        stop()
    }

  private val _ = sys.addShutdownHook(stop())

  private def start(): Unit = {
    log.info("Identity service starting on [{}:{}]...", config.interface, config.port)
    val _ = endpoint.start(interface = config.interface, port = config.port)
  }

  def stop(): Unit = {
    log.info("Identity service stopping...")
    val _ = system.terminate()
  }

  def state: Service.State = serviceState.get()
}

object Service {
  sealed trait State
  object State {
    case object Starting extends State
    case object Started extends State
    case object Failed extends State
  }

  final case class Config(interface: String, port: Int, internalQueryTimeout: FiniteDuration)

  object Config {
    def apply(config: com.typesafe.config.Config): Config =
      Config(
        interface = config.getString("interface"),
        port = config.getInt("port"),
        internalQueryTimeout = config.getDuration("internal-query-timeout").toMillis.millis
      )
  }
}
