package stasis.identity.api.oauth

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import play.api.libs.json.{Format, Json}
import stasis.identity.api.Formats._
import stasis.identity.api.oauth.directives.AuthDirectives
import stasis.identity.api.oauth.setup.Providers
import stasis.identity.model.clients.Client
import stasis.identity.model.codes.AuthorizationCode
import stasis.identity.model.errors.TokenError
import stasis.identity.model.realms.Realm
import stasis.identity.model.tokens._
import stasis.identity.model.{GrantType, ResponseType, Seconds}

import scala.concurrent.ExecutionContext

class AuthorizationCodeGrant(
  override val providers: Providers
)(implicit system: ActorSystem, override val mat: Materializer)
    extends AuthDirectives {
  import AuthorizationCodeGrant._
  import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._

  override implicit protected def ec: ExecutionContext = system.dispatcher
  override protected def log: LoggingAdapter = Logging(system, this.getClass.getName)

  def authorization(realm: Realm): Route =
    get {
      parameters(
        "response_type".as[ResponseType],
        "client_id".as[Client.Id],
        "redirect_uri".as[String].?,
        "scope".as[String].?,
        "state".as[String]
      ).as(AuthorizationRequest) { request =>
        retrieveClient(request.client_id) {
          case client if request.redirect_uri.forall(_ == client.redirectUri) =>
            val redirectUri = Uri(request.redirect_uri.getOrElse(client.redirectUri))

            (authenticateResourceOwner(redirectUri, request.state) & extractApiAudience(realm.id, request.scope)) {
              (owner, audience) =>
                val scope = apiAudienceToScope(audience)

                generateAuthorizationCode(
                  client = client.id,
                  redirectUri = redirectUri,
                  state = request.state,
                  owner = owner,
                  scope = scope
                ) { code =>
                  log.debug(
                    "Realm [{}]: Successfully generated authorization code for client [{}]",
                    realm.id,
                    client.id
                  )

                  redirect(
                    redirectUri.withQuery(AuthorizationResponse(code, request.state, scope).asQuery),
                    StatusCodes.Found
                  )
                }
            }

          case client =>
            log.warning(
              "Realm [{}]: Redirect URI [{}] for client [{}] did not match URI provided in request: [{}]",
              realm.id,
              client.redirectUri,
              client.id,
              request.redirect_uri
            )

            complete(
              StatusCodes.BadRequest,
              "The request has missing, invalid or mismatching redirection URI and/or client identifier"
            )
        }
      }
    }

  def token(realm: Realm): Route =
    post {
      parameters(
        "grant_type".as[GrantType],
        "code".as[AuthorizationCode],
        "redirect_uri".as[String].?,
        "client_id".as[Client.Id]
      ).as(AccessTokenRequest) { request =>
        authenticateClient(realm) {
          case client if client.id == request.client_id && request.redirect_uri.forall(_ == client.redirectUri) =>
            consumeAuthorizationCode(client.id, request.code) { (owner, scope) =>
              extractApiAudience(realm.id, scope) { audience =>
                val scope = apiAudienceToScope(audience)

                (generateAccessToken(owner, audience) & generateRefreshToken(realm, client.id, owner, scope)) {
                  (accessToken, refreshToken) =>
                    log.debug(
                      "Realm [{}]: Successfully generated {} for client [{}]",
                      realm.id,
                      refreshToken match {
                        case Some(_) => "access and refresh tokens"
                        case None    => "access token"
                      },
                      client.id
                    )

                    complete(
                      StatusCodes.OK,
                      List[HttpHeader](
                        headers.`Content-Type`(ContentTypes.`application/json`),
                        headers.`Cache-Control`(headers.CacheDirectives.`no-store`)
                      ),
                      AccessTokenResponse(
                        access_token = accessToken,
                        token_type = TokenType.Bearer,
                        expires_in = client.tokenExpiration,
                        refresh_token = refreshToken
                      )
                    )
                }
              }
            }

          case client =>
            log.warning(
              "Realm [{}]: Encountered mismatched client identifiers (expected [{}], found [{}]) " +
                "and/or redirect URIs (expected [{}], found [{}])",
              Array(
                realm.id,
                client.id,
                request.client_id,
                client.redirectUri,
                request.redirect_uri
              )
            )

            complete(
              StatusCodes.BadRequest,
              TokenError.InvalidRequest
            )
        }
      }
    }
}

object AuthorizationCodeGrant {
  implicit val accessTokenResponseFormat: Format[AccessTokenResponse] = Json.format[AccessTokenResponse]

  final case class AuthorizationRequest(
    response_type: ResponseType,
    client_id: Client.Id,
    redirect_uri: Option[String],
    scope: Option[String],
    state: String
  ) {
    require(response_type == ResponseType.Code, "response type must be 'code'")
    require(redirect_uri.forall(_.nonEmpty), "redirect URI must not be empty")
    require(scope.forall(_.nonEmpty), "scope must not be empty")
    require(state.nonEmpty, "state must not be empty")
  }

  final case class AccessTokenRequest(
    grant_type: GrantType,
    code: AuthorizationCode,
    redirect_uri: Option[String],
    client_id: Client.Id
  ) {
    require(grant_type == GrantType.AuthorizationCode, "grant type must be 'authorization_code'")
    require(code.value.nonEmpty, "code must not be empty")
    require(redirect_uri.forall(_.nonEmpty), "redirect URI must not be empty")
  }

  final case class AuthorizationResponse(
    code: AuthorizationCode,
    state: String,
    scope: Option[String]
  ) {
    def asQuery: Uri.Query =
      Uri.Query(
        scope.foldLeft(
          Map(
            "code" -> code.value,
            "state" -> state
          )
        ) { case (baseParams, actualScope) => baseParams + ("scope" -> actualScope) }
      )
  }

  final case class AccessTokenResponse(
    access_token: AccessToken,
    token_type: TokenType,
    expires_in: Seconds,
    refresh_token: Option[RefreshToken]
  )
}