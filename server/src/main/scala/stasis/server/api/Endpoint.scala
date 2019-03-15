package stasis.server.api

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}
import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.stream.ActorMaterializer
import stasis.server.security.exceptions.AuthorizationFailure
import stasis.server.security.{ResourceProvider, UserAuthenticator}

class Endpoint(
  resourceProvider: ResourceProvider,
  authenticator: UserAuthenticator
)(implicit val system: ActorSystem) {

  private implicit val mat: ActorMaterializer = ActorMaterializer()
  private implicit val ec: ExecutionContextExecutor = system.dispatcher

  private implicit val log: LoggingAdapter = Logging(system, this.getClass.getName)

  def start(hostname: String, port: Int): Future[Http.ServerBinding] =
    Http().bindAndHandle(endpointRoutes, hostname, port)

  private implicit def sanitizingExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case e: AuthorizationFailure =>
        extractRequestEntity { entity =>
          val _ = entity.discardBytes()
          log.error(e, "User authorization failed: [{}]", e.getMessage)
          complete(StatusCodes.Forbidden)
        }

      case NonFatal(e) =>
        extractRequestEntity { entity =>
          val _ = entity.discardBytes()
          val failureReference = java.util.UUID.randomUUID()

          log.error(
            e,
            "Unhandled exception encountered: [{}]; failure reference is [{}]",
            e.getMessage,
            failureReference
          )

          complete(
            HttpResponse(
              status = StatusCodes.InternalServerError,
              entity = s"Failed to process request; failure reference is [$failureReference]"
            )
          )
        }
    }

  val endpointRoutes: Route =
    (extractMethod & extractUri & extractClientIP & extractRequest) { (method, uri, remoteAddress, request) =>
      extractCredentials {
        case Some(credentials) =>
          onComplete(authenticator.authenticate(credentials)) {
            case Success(user) =>
              concat(
                pathPrefix("datasets") {
                  concat(
                    pathPrefix("definitions") {
                      routes.DatasetDefinitions(resourceProvider, user)
                    },
                    pathPrefix("entries") {
                      routes.DatasetEntries(resourceProvider, user)
                    }
                  )
                },
                pathPrefix("users") {
                  routes.Users(resourceProvider, user)
                },
                pathPrefix("devices") {
                  routes.Devices(resourceProvider, user)
                },
                pathPrefix("schedules") {
                  routes.Schedules(resourceProvider, user)
                }
              )

            case Failure(e) =>
              val _ = request.discardEntityBytes()

              log.warning(
                "Rejecting [{}] request for [{}] with invalid credentials from [{}]: [{}]",
                method.value,
                uri,
                remoteAddress,
                e
              )

              complete(StatusCodes.Unauthorized)
          }

        case None =>
          val _ = request.discardEntityBytes()

          log.warning(
            "Rejecting [{}] request for [{}] with no credentials from [{}]",
            method.value,
            uri,
            remoteAddress
          )

          complete(StatusCodes.Unauthorized)
      }
    }
}