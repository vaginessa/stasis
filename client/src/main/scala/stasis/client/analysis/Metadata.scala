package stasis.client.analysis

import java.nio.file.attribute.{FileTime, PosixFileAttributeView, PosixFileAttributes, PosixFilePermissions}
import java.nio.file.{Files, LinkOption, Path}
import java.time.Instant

import akka.Done
import akka.stream.Materializer
import stasis.client.model.{DatasetMetadata, FileMetadata, SourceFile}
import stasis.core.packaging.Crate

import scala.concurrent.{ExecutionContext, Future}

trait Metadata {
  def collect(file: Path): Future[SourceFile]
  def collect(file: Path, existingMetadata: Option[FileMetadata]): Future[SourceFile]
}

object Metadata {
  class Default(checksum: Checksum, lastDatasetMetadata: DatasetMetadata)(implicit mat: Materializer) extends Metadata {
    private implicit val ec: ExecutionContext = mat.executionContext

    private val existingFilesMetadata: Map[Path, FileMetadata] =
      (lastDatasetMetadata.contentChanged ++ lastDatasetMetadata.metadataChanged).map { fileMetadata =>
        fileMetadata.path -> fileMetadata
      }.toMap

    override def collect(file: Path): Future[SourceFile] =
      collect(file = file, existingMetadata = existingFilesMetadata.get(file))

    override def collect(file: Path, existingMetadata: Option[FileMetadata]): Future[SourceFile] =
      for {
        currentChecksum <- checksum.calculate(file)
        currentMetadata <- extractFileMetadata(
          file = file,
          withChecksum = currentChecksum,
          withCrate = existingMetadata match {
            case Some(metadata) if metadata.checksum == currentChecksum => metadata.crate
            case _                                                      => Crate.generateId()
          }
        )
      } yield {
        SourceFile(
          path = file,
          existingMetadata = existingMetadata,
          currentMetadata = currentMetadata
        )
      }
  }

  def extractFileMetadata(
    file: Path,
    withChecksum: BigInt,
    withCrate: Crate.Id
  )(implicit ec: ExecutionContext): Future[FileMetadata] =
    Future {
      val attributes = Files.readAttributes(file, classOf[PosixFileAttributes], LinkOption.NOFOLLOW_LINKS)

      FileMetadata(
        path = file,
        size = attributes.size,
        link = if (Files.isSymbolicLink(file)) Some(Files.readSymbolicLink(file)) else None,
        isHidden = Files.isHidden(file),
        created = attributes.creationTime.toInstant,
        updated = attributes.lastModifiedTime.toInstant,
        owner = attributes.owner.getName,
        group = attributes.group.getName,
        permissions = PosixFilePermissions.toString(attributes.permissions()),
        checksum = withChecksum,
        crate = withCrate
      )
    }

  def applyFileMetadata(metadata: FileMetadata)(implicit ec: ExecutionContext): Future[Done] =
    applyFileMetadataTo(metadata, metadata.path)

  def applyFileMetadataTo(metadata: FileMetadata, file: Path)(implicit ec: ExecutionContext): Future[Done] = Future {
    val attributes = Files.getFileAttributeView(file, classOf[PosixFileAttributeView], LinkOption.NOFOLLOW_LINKS)

    attributes.setPermissions(PosixFilePermissions.fromString(metadata.permissions))

    val lookupService = file.getFileSystem.getUserPrincipalLookupService

    val owner = lookupService.lookupPrincipalByName(metadata.owner)
    val group = lookupService.lookupPrincipalByGroupName(metadata.group)

    attributes.setOwner(owner)
    attributes.setGroup(group)

    attributes.setTimes(
      /* lastModifiedTime */ FileTime.from(metadata.updated),
      /* lastAccessTime */ FileTime.from(Instant.now()),
      /* createTime */ FileTime.from(metadata.created)
    )

    Done
  }
}
