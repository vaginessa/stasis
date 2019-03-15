package stasis.test.specs.unit.server.api.routes

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{RequestEntity, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import stasis.core.routing.Node
import stasis.server.api.requests.{CreateDatasetDefinition, UpdateDatasetDefinition}
import stasis.server.api.responses.{CreatedDatasetDefinition, DeletedDatasetDefinition}
import stasis.server.api.routes.DatasetDefinitions
import stasis.server.model.datasets.{DatasetDefinition, DatasetDefinitionStore}
import stasis.server.model.devices.{Device, DeviceStore}
import stasis.server.model.users.User
import stasis.server.security.ResourceProvider
import stasis.test.specs.unit.AsyncUnitSpec
import stasis.test.specs.unit.server.model.mocks.{MockDatasetDefinitionStore, MockDeviceStore}
import stasis.test.specs.unit.server.security.mocks.MockResourceProvider

import scala.concurrent.Future
import scala.concurrent.duration._

class DatasetDefinitionsSpec extends AsyncUnitSpec with ScalatestRouteTest {
  import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
  import stasis.server.api.Formats._

  import scala.language.implicitConversions

  private implicit val untypedSystem: ActorSystem = ActorSystem(name = "DatasetDefinitionsSpec")
  private implicit val log: LoggingAdapter = Logging(untypedSystem, this.getClass.getName)

  private trait TestFixtures {
    lazy val deviceStore: DeviceStore = new MockDeviceStore()

    lazy val definitionStore: DatasetDefinitionStore = new MockDatasetDefinitionStore

    lazy val provider: ResourceProvider = new MockResourceProvider(
      resources = Set(
        deviceStore.view(),
        deviceStore.viewSelf(),
        definitionStore.view(),
        definitionStore.viewSelf(),
        definitionStore.manage(),
        definitionStore.manageSelf()
      )
    )

    lazy val routes: Route = DatasetDefinitions(provider, user)
  }

  private val user = User.generateId()

  private val userDevice =
    Device(
      id = Device.generateId(),
      node = Node.generateId(),
      owner = user,
      isActive = true,
      limits = None
    )

  private val definitions = Seq(
    DatasetDefinition(
      id = DatasetDefinition.generateId(),
      device = userDevice.id,
      schedule = None,
      redundantCopies = 1,
      existingVersions = DatasetDefinition.Retention(
        DatasetDefinition.Retention.Policy.All,
        duration = 1.second
      ),
      removedVersions = DatasetDefinition.Retention(
        DatasetDefinition.Retention.Policy.LatestOnly,
        duration = 1.second
      )
    ),
    DatasetDefinition(
      id = DatasetDefinition.generateId(),
      device = Device.generateId(),
      schedule = None,
      redundantCopies = 2,
      existingVersions = DatasetDefinition.Retention(
        DatasetDefinition.Retention.Policy.AtMost(versions = 5),
        duration = 1.second
      ),
      removedVersions = DatasetDefinition.Retention(
        DatasetDefinition.Retention.Policy.LatestOnly,
        duration = 1.second
      )
    )
  )

  private val createRequest = CreateDatasetDefinition(
    device = Device.generateId(),
    schedule = None,
    redundantCopies = 1,
    existingVersions = DatasetDefinition.Retention(DatasetDefinition.Retention.Policy.All, duration = 1.second),
    removedVersions = DatasetDefinition.Retention(DatasetDefinition.Retention.Policy.All, duration = 1.second)
  )

  private val updateRequest = UpdateDatasetDefinition(
    schedule = None,
    redundantCopies = 1,
    existingVersions = DatasetDefinition.Retention(DatasetDefinition.Retention.Policy.All, duration = 1.second),
    removedVersions = DatasetDefinition.Retention(DatasetDefinition.Retention.Policy.All, duration = 1.second)
  )

  private implicit def createRequestToEntity(request: CreateDatasetDefinition): RequestEntity =
    Marshal(request).to[RequestEntity].await

  private implicit def updateRequestToEntity(request: UpdateDatasetDefinition): RequestEntity =
    Marshal(request).to[RequestEntity].await

  "DatasetDefinitions routes (full permissions)" should "respond with all definitions" in {
    val fixtures = new TestFixtures {}
    Future.sequence(definitions.map(fixtures.definitionStore.manage().create)).await

    Get("/") ~> fixtures.routes ~> check {
      status should be(StatusCodes.OK)
      responseAs[Seq[DatasetDefinition]] should contain theSameElementsAs definitions
    }
  }

  they should "create new definitions" in {
    val fixtures = new TestFixtures {}
    Post("/").withEntity(createRequest) ~> fixtures.routes ~> check {
      status should be(StatusCodes.OK)

      fixtures.definitionStore
        .view()
        .get(entityAs[CreatedDatasetDefinition].definition)
        .map(_.isDefined should be(true))
    }
  }

  they should "respond with existing definitions" in {
    val fixtures = new TestFixtures {}

    fixtures.definitionStore.manage().create(definitions.head).await

    Get(s"/${definitions.head.id}") ~> fixtures.routes ~> check {
      status should be(StatusCodes.OK)
      responseAs[DatasetDefinition] should be(definitions.head)
    }
  }

  they should "fail if a definition is missing" in {
    val fixtures = new TestFixtures {}
    Get(s"/${DatasetDefinition.generateId()}") ~> fixtures.routes ~> check {
      status should be(StatusCodes.NotFound)
    }
  }

  they should "update existing definitions" in {
    val fixtures = new TestFixtures {}
    fixtures.definitionStore.manage().create(definitions.head).await
    val updatedCopies = 42

    Put(s"/${definitions.head.id}")
      .withEntity(updateRequest.copy(redundantCopies = updatedCopies)) ~> fixtures.routes ~> check {
      fixtures.definitionStore
        .view()
        .get(definitions.head.id)
        .map(_.map(_.redundantCopies) should be(Some(updatedCopies)))
    }
  }

  they should "fail to update if a definition is missing" in {
    val fixtures = new TestFixtures {}
    Put(s"/${DatasetDefinition.generateId()}")
      .withEntity(updateRequest) ~> fixtures.routes ~> check {
      status should be(StatusCodes.BadRequest)
    }
  }

  they should "delete existing definitions" in {
    val fixtures = new TestFixtures {}
    fixtures.definitionStore.manage().create(definitions.head).await

    Delete(s"/${definitions.head.id}") ~> fixtures.routes ~> check {
      status should be(StatusCodes.OK)
      responseAs[DeletedDatasetDefinition] should be(DeletedDatasetDefinition(existing = true))

      fixtures.definitionStore
        .view()
        .get(definitions.head.id)
        .map(_ should be(None))
    }
  }

  they should "not delete missing definitions" in {
    val fixtures = new TestFixtures {}

    Delete(s"/${definitions.head.id}") ~> fixtures.routes ~> check {
      status should be(StatusCodes.OK)
      responseAs[DeletedDatasetDefinition] should be(DeletedDatasetDefinition(existing = false))
    }
  }

  "DatasetDefinitions routes (self permissions)" should "respond with all definitions" in {
    val fixtures = new TestFixtures {}
    Future.sequence(definitions.map(fixtures.definitionStore.manage().create)).await
    fixtures.deviceStore.manage().create(userDevice).await

    Get("/own") ~> fixtures.routes ~> check {
      status should be(StatusCodes.OK)
      responseAs[Seq[DatasetDefinition]] should contain theSameElementsAs definitions.take(1)
    }
  }

  they should "create new definitions" in {
    val fixtures = new TestFixtures {}
    fixtures.deviceStore.manage().create(userDevice).await

    Post("/own")
      .withEntity(createRequest.copy(device = userDevice.id)) ~> fixtures.routes ~> check {
      status should be(StatusCodes.OK)

      fixtures.definitionStore
        .view()
        .get(entityAs[CreatedDatasetDefinition].definition)
        .map(_.isDefined should be(true))
    }
  }

  they should "respond with existing definitions" in {
    val fixtures = new TestFixtures {}
    fixtures.deviceStore.manage().create(userDevice).await

    fixtures.definitionStore.manage().create(definitions.head).await

    Get(s"/own/${definitions.head.id}") ~> fixtures.routes ~> check {
      status should be(StatusCodes.OK)
      responseAs[DatasetDefinition] should be(definitions.head)
    }
  }

  they should "fail if a definition is missing" in {
    val fixtures = new TestFixtures {}
    Get(s"/own/${DatasetDefinition.generateId()}") ~> fixtures.routes ~> check {
      status should be(StatusCodes.NotFound)
    }
  }

  they should "update existing definitions" in {
    val fixtures = new TestFixtures {}
    fixtures.deviceStore.manage().create(userDevice).await
    fixtures.definitionStore.manage().create(definitions.head.copy(device = userDevice.id)).await
    val updatedCopies = 42

    Put(s"/own/${definitions.head.id}")
      .withEntity(updateRequest.copy(redundantCopies = updatedCopies)) ~> fixtures.routes ~> check {
      fixtures.definitionStore
        .view()
        .get(definitions.head.id)
        .map(_.map(_.redundantCopies) should be(Some(updatedCopies)))
    }
  }

  they should "fail to update if a definition is missing" in {
    val fixtures = new TestFixtures {}
    Put(s"/own/${DatasetDefinition.generateId()}")
      .withEntity(updateRequest) ~> fixtures.routes ~> check {
      status should be(StatusCodes.BadRequest)
    }
  }

  they should "delete existing definitions" in {
    val fixtures = new TestFixtures {}
    fixtures.deviceStore.manage().create(userDevice).await
    fixtures.definitionStore.manage().create(definitions.head.copy(device = userDevice.id)).await

    Delete(s"/own/${definitions.head.id}") ~> fixtures.routes ~> check {
      status should be(StatusCodes.OK)
      responseAs[DeletedDatasetDefinition] should be(DeletedDatasetDefinition(existing = true))

      fixtures.definitionStore
        .view()
        .get(definitions.head.id)
        .map(_ should be(None))
    }
  }

  they should "not delete missing definitions" in {
    val fixtures = new TestFixtures {}
    fixtures.deviceStore.manage().create(userDevice).await

    Delete(s"/own/${definitions.head.id}") ~> fixtures.routes ~> check {
      status should be(StatusCodes.OK)
      responseAs[DeletedDatasetDefinition] should be(DeletedDatasetDefinition(existing = false))
    }
  }
}