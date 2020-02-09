package stasis.client.api.http.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import play.api.libs.json.{Format, Json}
import stasis.client.api.http.Context
import stasis.client.ops.recovery.Recovery
import stasis.shared.ops.Operation

class Operations()(implicit override val mat: Materializer, context: Context) extends ApiRoutes {
  import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
  import stasis.core.api.Matchers._
  import Operations._

  def routes(): Route =
    concat(
      pathEndOrSingleSlash {
        get {
          import mat.executionContext

          val operationsState = for {
            operations <- context.executor.operations
            progress <- context.tracker.state.map(_.operations)
          } yield {
            operations.map {
              case (operation, operationType) =>
                val operationProgress = progress.getOrElse(
                  operation,
                  Operation.Progress.empty
                )

                OperationState(
                  operation = operation,
                  `type` = operationType,
                  progress = operationProgress
                )
            }
          }

          onSuccess(operationsState) { operations =>
            log.debug("API successfully retrieved state of [{}] operations", operations.size)
            discardEntity & complete(operations)
          }
        }
      },
      path("backup" / JavaUUID) { definition =>
        put {
          onSuccess(context.executor.startBackupWithRules(definition)) { operation =>
            log.debug("API started backup operation [{}]", operation)
            discardEntity & complete(StatusCodes.Accepted, OperationStarted(operation))
          }
        }
      },
      pathPrefix("recover" / JavaUUID) { definition =>
        put {
          concat(
            path("latest") {
              parameter("query".as[Recovery.PathQuery].?) { query =>
                val result = context.executor.startRecoveryWithDefinition(
                  definition = definition,
                  until = None,
                  query = query
                )

                onSuccess(result) { operation =>
                  log.debug(
                    "API started recovery operation [{}] for definition [{}] with latest entry",
                    operation,
                    definition
                  )
                  discardEntity & complete(StatusCodes.Accepted, OperationStarted(operation))
                }
              }
            },
            path("until" / IsoInstant) {
              until =>
                parameter("query".as[Recovery.PathQuery].?) { query =>
                  val result = context.executor.startRecoveryWithDefinition(
                    definition = definition,
                    until = Some(until),
                    query = query
                  )

                  onSuccess(result) { operation =>
                    log.debug(
                      "API started recovery operation [{}] for definition [{}] until [{}]",
                      operation,
                      definition,
                      until
                    )
                    discardEntity & complete(StatusCodes.Accepted, OperationStarted(operation))
                  }
                }
            },
            path("from" / JavaUUID) { entry =>
              parameter("query".as[Recovery.PathQuery].?) { query =>
                val result = context.executor.startRecoveryWithEntry(
                  entry = entry,
                  query = query
                )

                onSuccess(result) { operation =>
                  log.debug(
                    "API started recovery operation [{}] for definition [{}] with entry [{}]",
                    operation,
                    definition,
                    entry
                  )
                  discardEntity & complete(StatusCodes.Accepted, OperationStarted(operation))
                }
              }
            }
          )
        }
      },
      path(JavaUUID / "stop") { operation =>
        put {
          onSuccess(context.executor.stop(operation)) { _ =>
            log.debug("API stopped backup operation [{}]", operation)
            discardEntity & complete(StatusCodes.OK)
          }
        }
      }
    )
}

object Operations {
  import stasis.shared.api.Formats._

  final case class OperationStarted(operation: Operation.Id)

  final case class OperationState(operation: Operation.Id, `type`: Operation.Type, progress: Operation.Progress)

  implicit val operationStartedFormat: Format[OperationStarted] =
    Json.format[OperationStarted]

  implicit val operationStateFormat: Format[OperationState] =
    Json.format[OperationState]

  implicit val stringToRegexQuery: Unmarshaller[String, Recovery.PathQuery] =
    Unmarshaller.strict(Recovery.PathQuery.apply)
}