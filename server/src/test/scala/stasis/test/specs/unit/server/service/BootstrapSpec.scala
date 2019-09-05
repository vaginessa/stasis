package stasis.test.specs.unit.server.service

import java.time.LocalTime
import java.time.temporal.ChronoUnit.MINUTES
import java.util.UUID

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorSystem, Behavior, SpawnProtocol}
import akka.event.{Logging, LoggingAdapter}
import com.typesafe.config.Config
import stasis.server.service.Bootstrap.Entities
import stasis.server.service.{Bootstrap, Persistence}
import stasis.shared.model.datasets.DatasetDefinition
import stasis.shared.model.devices.Device
import stasis.shared.model.schedules.Schedule
import stasis.shared.model.users.User
import stasis.shared.security.Permission
import stasis.test.specs.unit.AsyncUnitSpec
import stasis.test.specs.unit.server.model.Generators

import scala.concurrent.duration._

class BootstrapSpec extends AsyncUnitSpec {
  "Bootstrap" should "setup the service with provided entities" in {
    val expectedEntities = Entities(
      definitions = Generators.generateSeq(min = 1, g = Generators.generateDefinition),
      devices = Generators.generateSeq(min = 1, g = Generators.generateDevice),
      schedules = Generators.generateSeq(min = 1, g = Generators.generateSchedule),
      users = Generators.generateSeq(min = 1, g = Generators.generateUser)
    )

    val persistence = new Persistence(
      persistenceConfig = config.getConfig("persistence")
    )

    Bootstrap
      .run(
        entities = expectedEntities,
        persistence = persistence
      )
      .flatMap { _ =>
        for {
          actualDefinitions <- persistence.datasetDefinitions.view().list()
          actualDevices <- persistence.devices.view().list()
          actualSchedules <- persistence.schedules.view().list()
          actualUsers <- persistence.users.view().list()
          _ <- persistence.drop()
        } yield {
          actualDefinitions.values.toSeq.sortBy(_.id) should be(expectedEntities.definitions.sortBy(_.id))
          actualDevices.values.toSeq.sortBy(_.id) should be(expectedEntities.devices.sortBy(_.id))
          actualSchedules.values.toSeq.sortBy(_.id) should be(expectedEntities.schedules.sortBy(_.id))
          actualUsers.values.toSeq.sortBy(_.id) should be(expectedEntities.users.sortBy(_.id))
        }
      }
  }

  it should "setup the service with configured entities" in {
    val persistence = new Persistence(
      persistenceConfig = config.getConfig("persistence")
    )

    val expectedDeviceId = UUID.fromString("9b47ab81-c472-40e6-834e-6ede83f8893b")
    val expectedUserId = UUID.fromString("749d8c0e-6105-4022-ae0e-39bd77752c5d")

    Bootstrap
      .run(
        bootstrapConfig = config.getConfig("bootstrap-enabled"),
        persistence = persistence
      )
      .flatMap { _ =>
        for {
          actualDefinitions <- persistence.datasetDefinitions.view().list()
          actualDevices <- persistence.devices.view().list()
          actualSchedules <- persistence.schedules.view().list()
          actualUsers <- persistence.users.view().list()
          _ <- persistence.drop()
        } yield {
          actualDefinitions.values.toList.sortBy(_.id.toString) match {
            case definition1 :: definition2 :: Nil =>
              definition1.device should be(expectedDeviceId)
              definition1.redundantCopies should be(2)
              definition1.existingVersions should be(
                DatasetDefinition.Retention(
                  policy = DatasetDefinition.Retention.Policy.AtMost(versions = 5),
                  duration = 7.days
                )
              )
              definition1.removedVersions should be(
                DatasetDefinition.Retention(
                  policy = DatasetDefinition.Retention.Policy.LatestOnly,
                  duration = 0.days
                )
              )

              definition2.device should be(expectedDeviceId)
              definition2.redundantCopies should be(1)
              definition2.existingVersions should be(
                DatasetDefinition.Retention(
                  policy = DatasetDefinition.Retention.Policy.All,
                  duration = 7.days
                )
              )
              definition2.removedVersions should be(
                DatasetDefinition.Retention(
                  policy = DatasetDefinition.Retention.Policy.All,
                  duration = 1.day
                )
              )

            case unexpectedResult =>
              fail(s"Unexpected result received: [$unexpectedResult]")
          }

          actualDevices.values.toList.sortBy(_.id.toString) match {
            case device1 :: device2 :: Nil =>
              device1.owner should be(expectedUserId)
              device1.active should be(true)
              device1.limits should be(None)

              device2.id should be(expectedDeviceId)
              device2.owner should be(expectedUserId)
              device2.active should be(true)
              device2.limits should be(
                Some(
                  Device.Limits(
                    maxCrates = 100000,
                    maxStorage = 536870912000L,
                    maxStoragePerCrate = 1073741824L,
                    maxRetention = 90.days,
                    minRetention = 3.days
                  )
                )
              )

            case unexpectedResult =>
              fail(s"Unexpected result received: [$unexpectedResult]")
          }

          actualSchedules.values.toList.sortBy(_.id.toString) match {
            case schedule1 :: schedule2 :: schedule3 :: Nil =>
              schedule1.process should be(Schedule.Process.Expiration)
              schedule1.instant.truncatedTo(MINUTES) should be(LocalTime.now().truncatedTo(MINUTES))
              schedule1.interval should be(30.minutes)
              schedule1.missed should be(Schedule.MissedAction.ExecuteNext)
              schedule1.overlap should be(Schedule.OverlapAction.CancelExisting)

              schedule2.process should be(Schedule.Process.Backup)
              schedule2.instant should be(LocalTime.parse("10:30"))
              schedule2.interval should be(12.hours)
              schedule2.missed should be(Schedule.MissedAction.ExecuteImmediately)
              schedule2.overlap should be(Schedule.OverlapAction.CancelNew)

              schedule3.process should be(Schedule.Process.Backup)
              schedule3.instant should be(LocalTime.parse("12:00"))
              schedule3.interval should be(1.hour)
              schedule3.missed should be(Schedule.MissedAction.ExecuteImmediately)
              schedule3.overlap should be(Schedule.OverlapAction.ExecuteAnyway)

            case unexpectedResult =>
              fail(s"Unexpected result received: [$unexpectedResult]")
          }

          actualUsers.values.toList.sortBy(_.id.toString) match {
            case user1 :: user2 :: Nil =>
              user1.active should be(true)
              user1.permissions should be(Set(Permission.View.Self, Permission.Manage.Self))
              user1.limits should be(None)

              user2.id should be(expectedUserId)
              user2.active should be(true)
              user2.permissions should be(
                Set(
                  Permission.View.Self,
                  Permission.View.Privileged,
                  Permission.View.Service,
                  Permission.Manage.Self,
                  Permission.Manage.Privileged,
                  Permission.Manage.Service
                )
              )
              user2.limits should be(
                Some(
                  User.Limits(
                    maxDevices = 10,
                    maxCrates = 100000,
                    maxStorage = 536870912000L,
                    maxStoragePerCrate = 1073741824L,
                    maxRetention = 90.days,
                    minRetention = 3.days
                  )
                )
              )

            case unexpectedResult =>
              fail(s"Unexpected result received: [$unexpectedResult]")
          }
        }
      }
  }

  it should "not run if not enabled" in {
    val persistence = new Persistence(
      persistenceConfig = config.getConfig("persistence")
    )

    Bootstrap
      .run(
        bootstrapConfig = config.getConfig("bootstrap-disabled"),
        persistence = persistence
      )
      .flatMap { _ =>
        for {
          _ <- persistence.init()
          actualDefinitions <- persistence.datasetDefinitions.view().list()
          actualDevices <- persistence.devices.view().list()
          actualSchedules <- persistence.schedules.view().list()
          actualUsers <- persistence.users.view().list()
          _ <- persistence.drop()
        } yield {
          actualDefinitions should be(Map.empty)
          actualDevices should be(Map.empty)
          actualSchedules should be(Map.empty)
          actualUsers should be(Map.empty)
        }
      }
  }

  private implicit val system: ActorSystem[SpawnProtocol] = ActorSystem(
    Behaviors.setup(_ => SpawnProtocol.behavior): Behavior[SpawnProtocol],
    "BootstrapSpec"
  )

  private implicit val untyped: akka.actor.ActorSystem = system.toUntyped

  private implicit val log: LoggingAdapter = Logging(untyped, this.getClass.getName)

  private val config: Config = system.settings.config.getConfig("stasis.test.server")
}