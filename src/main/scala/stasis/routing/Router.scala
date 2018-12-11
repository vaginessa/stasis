package stasis.routing

import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.{Done, NotUsed}
import stasis.packaging.{Crate, Manifest}
import stasis.persistence.{CrateStorageRequest, CrateStorageReservation}

import scala.concurrent.Future

trait Router {
  def push(
    manifest: Manifest,
    content: Source[ByteString, NotUsed]
  ): Future[Done]

  def pull(
    crate: Crate.Id
  ): Future[Option[Source[ByteString, NotUsed]]]

  def reserve(
    request: CrateStorageRequest
  ): Future[Option[CrateStorageReservation]]
}
