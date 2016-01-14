package storrent.file

import akka.actor.{ Actor, ActorLogging }
import akka.actor.ActorRef
import akka.actor.Props
import akka.pattern.ask
import akka.util.ByteString
import akka.util.Timeout
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import storrent.message.FM
import storrent.TorrentFile
import storrent.file.{ FileWorker => FW }
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import storrent.core.Core._
import scala.util.{ Failure, Success }

object MultiFileWorker {
  def props(
      files: List[TorrentFile],
      pieceSize: Int,
      folder: String,
      totalSize: Int): Props = {
    Props(new MultiFileWorker(files, pieceSize, folder, totalSize) with AppParent with AppCake)
  }

  trait Cake { this: MultiFileWorker =>
    def provider: Provider
    trait Provider {
      def tallyWrites(fileManager: ActorRef, writers: Set[ActorRef]): ActorRef
      def singleFileWorker(path: String, pieceSize: Int, fileSize: Int): ActorRef
    }
  }

  trait AppCake extends Cake { this: MultiFileWorker =>
    override object provider extends Provider {
      def tallyWrites(fileManager: ActorRef, writers: Set[ActorRef]) =
        context.actorOf(TallyWrites.props(fileManager, writers))
      def singleFileWorker(path: String, pieceSize: Int, fileSize: Int) =
        context.actorOf(SingleFileWorker.props(path, pieceSize, fileSize))
    }
  }

  case class WrappedFileWorker(path: String, length: Int, worker: ActorRef)

  // Offset here means offset WITHIN THE FILE, not within all the files as a
  // whole
  case class WorkerJob(worker: ActorRef, offset: Int, length: Int, part: Int) {
    def tell(msg: Any, sender: ActorRef): Unit = { worker.tell(msg, sender) }
  }

  object TallyWrites {
    def props(fileManager: ActorRef, writers: Set[ActorRef]): Props = {
      Props(new TallyWrites(fileManager, writers))
    }
  }

  class TallyWrites(
      fileManager: ActorRef,
      writers: Set[ActorRef])
      extends Actor {

    def receive = tally(writers)

    def tally(writers: Set[ActorRef]): Receive = {

      case FW.WriteDone(index) =>
        val remaining = writers - sender
        if (remaining.isEmpty) {
          fileManager ! FW.WriteDone(index)
          context.stop(self)
        } else {
          context.become(tally(remaining))
        }
    }
  }

}

/*
 * Parent MUST be FileManager
 */
class MultiFileWorker(files: List[TorrentFile], pieceSize: Int, folder: String, totalSize: Int)
  extends Actor with ActorLogging { this: MultiFileWorker.Cake with Parent =>

  import MultiFileWorker._
  import context.dispatcher

  implicit val timeout = Timeout(1.minute)

  val singleFileWorkers: List[WrappedFileWorker] = files map { f =>
    WrappedFileWorker(
      f.path,
      f.length,
      provider.singleFileWorker(folder + "/" + f.path, pieceSize, f.length)
    )
  }

  // Msgs sent from fileManager, forwarded to SingleFileWorker
  def receive = {
    // Actually reads data from disk
    case FW.Read(index, totalOffset, length) =>
      val manager = sender
      val workerJobs = affectedFileJobs(totalOffset, length)
      val blocksF = workerJobs.map { case WorkerJob(worker, off, len, part) =>
        (worker ? FW.Read(off, len, part)).mapTo[FW.ReadDone].map { rd => rd.block }
      }
      val combinedBlockF = Future.sequence(blocksF).map { blocks =>
        blocks.foldLeft(mutable.ArrayBuffer[Byte]()) { (buf, block) => buf ++= block }.toArray
      }
      combinedBlockF.onComplete {
        case Success(block) => manager ! FW.ReadDone(index, block)
        case Failure(e) => log.debug(s"Could not read piece $index")
      }

    // Actually flushes data to disk
    case FW.Write(index, totalOffset, block) =>
      val workerJobs = affectedFileJobs(totalOffset, block.size)
      val workers = workerJobs.map { _.worker }.toSet
      val tallyWrites = provider.tallyWrites(parent, workers)
      workerJobs.foldLeft(block) { case (chunk, WorkerJob(worker, off, _, len)) =>
        val (data, remaining) = chunk.splitAt(len)
        worker.tell(FW.Write(index, off, data), tallyWrites)
        remaining
      }

  }

  /*
   * Create "worker jobs" based on which files are affected by the offset / length
   * to be read / written.  The jobs contain actorRefs and the amount that should
   * be read from these actorRefs.
   */
  def affectedFileJobs(offset: Int, length: Int): List[WorkerJob] = {
    val targetStart = offset
    val targetStop = targetStart + length

    @tailrec
    def fileJobsHelper(
        fileWorkers: List[WrappedFileWorker],
        position: Int,
        part: Int,
        affected: List[WorkerJob]): List[WorkerJob] = {
      fileWorkers match {
        case Nil => affected.reverse
        case WrappedFileWorker(path, length, worker) :: more =>
          val end = position + length
          if (end < targetStart) {
            fileJobsHelper(more, end, part, affected)
          } else if (position >= targetStop) {
            affected.reverse
          } else {
            val offset = (targetStart - position) max 0
            val jobLength = (length - offset) min (targetStop - offset)
            val workerJob = WorkerJob(worker, offset, jobLength, part)
            fileJobsHelper(more, end, part + 1, workerJob :: affected)
          }
      }
    }

    fileJobsHelper(singleFileWorkers, 0, 0, List[WorkerJob]())
  }

}
