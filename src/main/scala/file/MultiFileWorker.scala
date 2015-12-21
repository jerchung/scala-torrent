package storrent.file

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.ByteString
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
import storrent.core.Core._

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
      def tallyReads(fileManager: ActorRef, numExpected: Int, index: Int): ActorRef
      def tallyWrites(fileManager: ActorRef, writers: Set[ActorRef]): ActorRef
      def singleFileWorker(path: String, pieceSize: Int, fileSize: Int): ActorRef
    }
  }

  trait AppCake extends Cake { this: MultiFileWorker =>
    override object provider extends Provider {
      def tallyReads(fileManager: ActorRef, numExpected: Int, index: Int) =
        context.actorOf(TallyReads.props(fileManager, numExpected, index))
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

  // When reading multiple parts of multiple files, need to combine the
  // responses from the multiple SingleFileWorkers into one response to send
  // back to FileManager
  object TallyReads {
    def props(
        fileManager: ActorRef,
        numExpected: Int,
        index: Int): Props = {
      Props(new TallyReads(fileManager, numExpected, index))
    }
  }

  class TallyReads(
      fileManager: ActorRef,
      numExpected: Int,
      index: Int)
      extends Actor {

    var dones = List[FW.ReadDone]()

    def receive = tally(numExpected)

    def tally(left: Int): Receive = {
      // the index argument in ReadDone is the index of the piece this block
      // is a part of, not the index of the block itself within the piece
      case d: FW.ReadDone =>
        dones = d :: dones
        val rem = left - 1
        if (rem == 0) {
          val combinedBlock = dones.sortBy { case FW.ReadDone(part, _) => part }
                                  .map { case FW.ReadDone(_, b) => b}
                                  .foldLeft(mutable.ArrayBuffer[Byte]()) { (buf, block) =>
                                    buf ++= block
                                  }.toArray
          fileManager ! FM.ReadDone(index, combinedBlock)
          context.stop(self)
        } else {
          context.become(tally(rem))
        }
    }

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
          fileManager ! FM.WriteDone(index)
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
  extends Actor { this: MultiFileWorker.Cake with Parent =>

  import MultiFileWorker._
  import context.dispatcher

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
    case FM.Read(index, offset, length) =>
      val totalOffset = index * pieceSize + offset
      val workerJobs = affectedFileJobs(totalOffset, length)
      val tallyReads = provider.tallyReads(parent, workerJobs.size, index)
      workerJobs foreach { case WorkerJob(worker, off, len, part) =>
        worker.tell(FW.Read(off, len, part), tallyReads)
      }

    // Actually flushes data to disk
    case FM.Write(index, offset, block) =>
      val totalOffset = index * pieceSize + offset
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
