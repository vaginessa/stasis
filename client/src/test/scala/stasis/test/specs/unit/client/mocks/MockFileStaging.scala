package stasis.test.specs.unit.client.mocks

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import stasis.client.staging.FileStaging
import stasis.test.specs.unit.client.ResourceHelpers
import stasis.test.specs.unit.client.mocks.MockFileStaging.Statistic

import scala.concurrent.Future

class MockFileStaging() extends FileStaging with ResourceHelpers {
  private val stats: Map[Statistic, AtomicInteger] = Map(
    Statistic.TemporaryCreated -> new AtomicInteger(0),
    Statistic.TemporaryDiscarded -> new AtomicInteger(0),
    Statistic.Destaged -> new AtomicInteger(0)
  )

  override def temporary(): Future[Path] = {
    stats(Statistic.TemporaryCreated).incrementAndGet()
    Future.successful("/ops/temp-file-1".asTestResource)
  }

  override def discard(file: Path): Future[Done] = {
    stats(Statistic.TemporaryDiscarded).incrementAndGet()
    Future.successful(Done)
  }

  override def destage(from: Path, to: Path): Future[Done] = {
    stats(Statistic.Destaged).incrementAndGet()
    Future.successful(Done)
  }

  def statistics: Map[Statistic, Int] = stats.mapValues(_.get())
}

object MockFileStaging {
  sealed trait Statistic
  object Statistic {
    case object TemporaryCreated extends Statistic
    case object TemporaryDiscarded extends Statistic
    case object Destaged extends Statistic
  }
}