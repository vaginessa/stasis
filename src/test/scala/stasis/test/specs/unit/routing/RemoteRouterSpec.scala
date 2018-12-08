package stasis.test.specs.unit.routing

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorSystem, Behavior, SpawnProtocol}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import org.scalatest.FutureOutcome
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import stasis.packaging.{Crate, Manifest}
import stasis.routing.exceptions.{PullFailure, PushFailure}
import stasis.routing.{Node, RemoteRouter}
import stasis.test.specs.unit.AsyncUnitSpec
import stasis.test.specs.unit.networking.mocks.MockEndpointClient.Statistic
import stasis.test.specs.unit.networking.mocks.{MockEndpointAddress, MockEndpointClient}
import stasis.test.specs.unit.persistence.mocks.{MockManifestStore, MockNodeStore}

class RemoteRouterSpec extends AsyncUnitSpec with Eventually with ScalaFutures {

  case class FixtureParam()

  def withFixture(test: OneArgAsyncTest): FutureOutcome =
    withFixture(test.toNoArgAsyncTest(FixtureParam()))

  override implicit val timeout: Timeout = 500.milliseconds

  private implicit val system: ActorSystem[SpawnProtocol] = ActorSystem(
    Behaviors.setup(_ => SpawnProtocol.behavior): Behavior[SpawnProtocol],
    "RemoteRouterSpec"
  )

  private implicit val mat: ActorMaterializer = ActorMaterializer()(system.toUntyped)

  private implicit val ec: ExecutionContext = system.executionContext

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(3.second, 250.milliseconds)

  private class TestRemoteRouter(
    val testClient: MockEndpointClient = new MockEndpointClient,
    val testManifestStore: MockManifestStore = new MockManifestStore,
    val testNodeStore: MockNodeStore = new MockNodeStore
  ) extends RemoteRouter(testClient, testManifestStore, testNodeStore)(system.toUntyped)

  private val testNodes = Seq(
    Node(id = Node.generateId()),
    Node(id = Node.generateId()),
    Node(id = Node.generateId())
  )

  private val testManifest = Manifest(
    crate = Crate.generateId(),
    copies = 4,
    retention = 60.seconds,
    source = Node(id = Node.generateId())
  )

  private val testContent = ByteString("some value")

  "A RemoteRouter" should "calculate crate copies distribution" in { _ =>
    val (node1, node2, node3) = testNodes match {
      case Seq(n1, n2, n3) => (n1, n2, n3)
    }

    RemoteRouter.distributeCopies(nodes = Seq(node1), copies = 3) should be(Map(node1 -> 3))

    RemoteRouter.distributeCopies(nodes = Seq(node1, node2), copies = 3) should be(Map(node1 -> 2, node2 -> 1))
      .or(be(Map(node1 -> 1, node2 -> 2)))

    RemoteRouter.distributeCopies(nodes = Seq(node1, node2, node3), copies = 1).values.toSeq should be(Seq(1))

    RemoteRouter.distributeCopies(nodes = Seq.empty, copies = 2) should be(Map.empty)

    RemoteRouter.distributeCopies(nodes = Seq(node1, node2), copies = 0) should be(Map.empty)
  }

  it should "push data to remote nodes" in { _ =>
    val router = new TestRemoteRouter()
    testNodes.foreach(node => router.testNodeStore.put(node, MockEndpointAddress()).await)

    router.push(testManifest, Source.single(testContent)).await

    eventually {
      router.testClient.crateCopies(testManifest.crate).await should be(testManifest.copies)
      router.testClient.crateNodes(testManifest.crate).await should be(testNodes.size)
      router.testClient.statistics(Statistic.PushCompleted) should be(testNodes.size)
      router.testClient.statistics(Statistic.PushFailed) should be(0)
    }
  }

  it should "fail to push data when no nodes are available" in { _ =>
    val router = new TestRemoteRouter()

    router
      .push(testManifest, Source.single(testContent))
      .map { response =>
        fail(s"Received unexpected push response from router: [$response]")
      }
      .recover {
        case PushFailure(message) =>
          message should be(s"Crate [${testManifest.crate}] was not pushed; no nodes found")
          router.testClient.statistics(Statistic.PushCompleted) should be(0)
          router.testClient.statistics(Statistic.PushFailed) should be(0)
      }
  }

  it should "fail to push data when no copies are requested" in { _ =>
    val router = new TestRemoteRouter()
    testNodes.foreach(node => router.testNodeStore.put(node, MockEndpointAddress()).await)

    router
      .push(testManifest.copy(copies = 0), Source.single(testContent))
      .map { response =>
        fail(s"Received unexpected push response from router: [$response]")
      }
      .recover {
        case PushFailure(message) =>
          message should be(s"Crate [${testManifest.crate}] was not pushed; failed to push to any node")
          router.testClient.statistics(Statistic.PushCompleted) should be(0)
          router.testClient.statistics(Statistic.PushFailed) should be(0)
      }
  }

  it should "recover from node failure on push" in { _ =>
    val testNodeStore = new MockNodeStore()
    testNodes.foreach(node => testNodeStore.put(node, MockEndpointAddress()).await)

    val router = new TestRemoteRouter(
      testClient = new MockEndpointClient(
        pushFailureAddresses = Map(
          testNodeStore.addressOf(testNodes.last).await.get -> new RuntimeException("test failure")
        )
      ),
      testNodeStore = testNodeStore
    )

    router.push(testManifest, Source.single(testContent)).await

    eventually {
      router.testClient.crateCopies(testManifest.crate).await should be(testManifest.copies - 1)
      router.testClient.crateNodes(testManifest.crate).await should be(testNodes.size - 1)
      router.testClient.statistics(Statistic.PushCompleted) should be(testNodes.size - 1)
      router.testClient.statistics(Statistic.PushFailed) should be(1)
    }
  }

  it should "recover from node address retrieval failure on push" in { _ =>
    val router = new TestRemoteRouter(
      testNodeStore = new MockNodeStore(addressFailureNodes = Seq(testNodes.last))
    )
    testNodes.foreach(node => router.testNodeStore.put(node, MockEndpointAddress()).await)

    router.push(testManifest, Source.single(testContent)).await

    eventually {
      router.testClient.crateCopies(testManifest.crate).await should be(testManifest.copies - 1)
      router.testClient.crateNodes(testManifest.crate).await should be(testNodes.size - 1)
      router.testClient.statistics(Statistic.PushCompleted) should be(testNodes.size - 1)
      router.testClient.statistics(Statistic.PushFailed) should be(0)
    }
  }

  it should "pull data from remote nodes" in { _ =>
    val router = new TestRemoteRouter()
    testNodes.foreach(node => router.testNodeStore.put(node, MockEndpointAddress()).await)
    router.push(testManifest, Source.single(testContent)).await

    eventually {
      val result = router
        .pull(testManifest.crate)
        .flatMap(_.getOrElse(Source.empty).runFold(ByteString.empty)(_ ++ _))
        .await

      result should be(testContent)
      router.testClient.statistics(Statistic.PullCompletedWithData) should be(1)
      router.testClient.statistics(Statistic.PullCompletedEmpty) should be(0)
      router.testClient.statistics(Statistic.PullFailed) should be(0)
    }
  }

  it should "fail to pull data when crate manifest is missing" in { _ =>
    val router = new TestRemoteRouter()
    testNodes.foreach(node => router.testNodeStore.put(node, MockEndpointAddress()).await)

    val missingCrateId = Crate.generateId()

    eventually {
      router
        .pull(missingCrateId)
        .map { response =>
          fail(s"Received unexpected pull response from router: [$response]")
        }
        .recover {
          case PullFailure(message) =>
            message should be(s"Crate [$missingCrateId] was not pulled; failed to retrieve manifest")
            router.testClient.statistics(Statistic.PullCompletedWithData) should be(0)
            router.testClient.statistics(Statistic.PullCompletedEmpty) should be(0)
            router.testClient.statistics(Statistic.PullFailed) should be(0)
        }
    }
  }

  it should "fail to pull data if crate manifest has no destinations" in { _ =>
    val router = new TestRemoteRouter()
    testNodes.foreach(node => router.testNodeStore.put(node, MockEndpointAddress()).await)

    router.testManifestStore.put(testManifest).await

    eventually {
      router
        .pull(testManifest.crate)
        .map { response =>
          fail(s"Received unexpected pull response from router: [$response]")
        }
        .recover {
          case PullFailure(message) =>
            message should be(s"Crate [${testManifest.crate}] was not pulled; no destinations found")
            router.testClient.statistics(Statistic.PullCompletedWithData) should be(0)
            router.testClient.statistics(Statistic.PullCompletedEmpty) should be(0)
            router.testClient.statistics(Statistic.PullFailed) should be(0)
        }
    }
  }

  it should "recover from node address retrieval failure on pull" in { _ =>
    val router = new TestRemoteRouter(
      testNodeStore = new MockNodeStore(addressFailureNodes = Seq(testNodes.last))
    )
    testNodes.foreach(node => router.testNodeStore.put(node, MockEndpointAddress()).await)
    router.push(testManifest, Source.single(testContent)).await
    router.testManifestStore.put(testManifest.copy(destinations = Seq(testNodes.last, testNodes.head)))

    eventually {
      val result = router
        .pull(testManifest.crate)
        .flatMap(_.getOrElse(Source.empty).runFold(ByteString.empty)(_ ++ _))
        .await

      result should be(testContent)
      router.testClient.statistics(Statistic.PullCompletedWithData) should be(1)
      router.testClient.statistics(Statistic.PullCompletedEmpty) should be(0)
      router.testClient.statistics(Statistic.PullFailed) should be(0)
    }
  }

  it should "recover from content not returned by individual nodes" in { _ =>
    val testNodeStore = new MockNodeStore()
    testNodes.foreach(node => testNodeStore.put(node, MockEndpointAddress()).await)

    val router = new TestRemoteRouter(
      testClient = new MockEndpointClient(
        pullEmptyAddresses = Seq(testNodeStore.addressOf(testNodes.head).await.get)
      ),
      testNodeStore = testNodeStore
    )

    router.push(testManifest, Source.single(testContent)).await

    eventually {
      val result = router
        .pull(testManifest.crate)
        .flatMap(_.getOrElse(Source.empty).runFold(ByteString.empty)(_ ++ _))
        .await

      result should be(testContent)
      router.testClient.statistics(Statistic.PullCompletedWithData) should be(1)
      router.testClient.statistics(Statistic.PullCompletedEmpty) should be(1)
      router.testClient.statistics(Statistic.PullFailed) should be(0)
    }
  }

  it should "recover from node failure on pull" in { _ =>
    val testNodeStore = new MockNodeStore()
    testNodes.foreach(node => testNodeStore.put(node, MockEndpointAddress()).await)

    val router = new TestRemoteRouter(
      testClient = new MockEndpointClient(
        pullFailureAddresses = Map(
          testNodeStore.addressOf(testNodes.head).await.get -> new RuntimeException("test failure")
        )
      ),
      testNodeStore = testNodeStore
    )

    router.push(testManifest, Source.single(testContent)).await

    eventually {
      val result = router
        .pull(testManifest.crate)
        .flatMap(_.getOrElse(Source.empty).runFold(ByteString.empty)(_ ++ _))
        .await

      result should be(testContent)
      router.testClient.statistics(Statistic.PullCompletedWithData) should be(1)
      router.testClient.statistics(Statistic.PullCompletedEmpty) should be(0)
      router.testClient.statistics(Statistic.PullFailed) should be(1)
    }
  }

  it should "fail to pull missing data from remote nodes" in { _ =>
    val testNodeStore = new MockNodeStore()
    testNodes.foreach(node => testNodeStore.put(node, MockEndpointAddress()).await)

    val router = new TestRemoteRouter(
      testClient = new MockEndpointClient(
        pullEmptyAddresses = Future.sequence(testNodes.map(testNodeStore.addressOf)).await.flatten
      ),
      testNodeStore = testNodeStore
    )

    router.push(testManifest, Source.single(testContent)).await

    eventually {
      router.pull(testManifest.crate).await should be(None)
      router.testClient.statistics(Statistic.PullCompletedWithData) should be(0)
      router.testClient.statistics(Statistic.PullCompletedEmpty) should be(testNodes.size)
      router.testClient.statistics(Statistic.PullFailed) should be(0)
    }
  }
}
