package stasis.client.api

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.HttpCredentials
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.{Done, NotUsed}
import stasis.core.networking.http.{HttpEndpointAddress, HttpEndpointClient}
import stasis.core.packaging
import stasis.core.routing.Node

import scala.concurrent.Future

class DefaultServerCoreEndpointClient(
  coreAddress: HttpEndpointAddress,
  coreCredentials: HttpCredentials,
  override val self: Node.Id
)(implicit system: ActorSystem)
    extends ServerCoreEndpointClient {
  private val client: HttpEndpointClient = new HttpEndpointClient(credentials = _ => Some(coreCredentials))

  override def push(manifest: packaging.Manifest, content: Source[ByteString, NotUsed]): Future[Done] =
    client.push(coreAddress, manifest, content)

  override def pull(crate: Node.Id): Future[Option[Source[ByteString, NotUsed]]] =
    client.pull(coreAddress, crate)
}