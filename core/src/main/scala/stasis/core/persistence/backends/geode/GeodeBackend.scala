package stasis.core.persistence.backends.geode

import akka.Done
import akka.util.ByteString
import org.apache.geode.cache.Region
import stasis.core.persistence.backends.KeyValueBackend

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class GeodeBackend[K, V](
  protected val region: Region[String, Array[Byte]],
  protected val serdes: KeyValueBackend.Serdes[K, V]
)(implicit ec: ExecutionContext)
    extends KeyValueBackend[K, V] {
  import serdes._

  override def init(): Future[Done] = Future.successful(Done)

  override def drop(): Future[Done] = Future {
    region.clear()
    Done
  }

  override def put(key: K, value: V): Future[Done] = Future {
    region.create(key.asGeodeKey, value.asGeodeValue)
    Done
  }

  override def delete(key: K): Future[Boolean] = Future {
    Option(region.remove(key.asGeodeKey)).isDefined
  }

  override def get(key: K): Future[Option[V]] = Future {
    Option(region.get(key.asGeodeKey)).map { result =>
      ByteString.fromArray(result): V
    }
  }

  override def contains(key: K): Future[Boolean] =
    Future.successful(region.containsKey(key.asGeodeKey))

  override def entries: Future[Map[K, V]] = Future {
    region
      .getAll(region.keySet())
      .asScala
      .toMap
      .map {
        case (k, v) =>
          (k: K) -> (ByteString.fromArray(v): V)
      }
  }

  private implicit class GeodeKey(key: K) {
    def asGeodeKey: String = key: String
  }

  private implicit class GeodeValue(value: V) {
    def asGeodeValue: Array[Byte] = (value: ByteString).toArray
  }
}