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
import storrent.file.FileManager.{FileWorker => FW}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent._
import storrent.core.Core._

object MultiFileWorker {
  def props(
      files: List[TorrentFile],
      pieceSize: Int,
      folder: String): Props = {
    Props(new MultiFileWorker(files, pieceSize, folder) with AppParent with AppCake)
  }

  trait Cake { this: MultiFileWorker =>
    def provider: Provider
    trait Provider {
      def tallyReads(fileManager: ActorRef, blockIndexes: Map[ActorRef, Int]): ActorRef
      def tallyWrites(fileManager: ActorRef, writers: Set[ActorRef]): ActorRef
      def singleFileWorker(path: String, pieceSize: Int): ActorRef
    }
  }

  trait AppCake extends Cake { this: MultiFileWorker =>
    override object provider extends Provider {
      def tallyReads(fileManager: ActorRef, blockIndexes: Map[ActorRef, Int]) =
        context.actorOf(TallyReads.props(fileManager, blockIndexes))
      def tallyWrites(fileManager: ActorRef, writers: Set[ActorRef]) =
        context.actorOf(TallyWrites.props(fileManager, writers))
      def singleFileWorker(path: String, pieceSize: Int) =
        context.actorOf(SingleFileWorker.props(path, pieceSize))
    }
  }

  case class WrappedFileWorker(path: String, length: Int, worker: ActorRef)

  // Offset here means offset WITHIN THE FILE, not within all the files as a
  // whole
  case class WorkerJob(worker: ActorRef, offset: Int, length: Int) {
    def tell(msg: Any, sender: ActorRef): Unit = { worker.tell(msg, sender) }
  }

  // When reading multiple parts of multiple files, need to combine the
  // responses from the multiple SingleFileWorkers into one response to send
  // back to FileManager
  object TallyReads {
    def props(
        fileManager: ActorRef,
        blockIndexes: Map[ActorRef, Int]): Props = {
      Props(new TallyReads(fileManager, blockIndexes))
    }
  }

  class TallyReads(
      fileManager: ActorRef,
      blockIndexes: Map[ActorRef, Int])
      extends Actor {

    val numExpected = blockIndexes.size
    val blocks = new Array[Array[Byte]](numExpected)

    def receive = tally(blockIndexes)

    def tally(blockIndexes: Map[ActorRef, Int]): Receive = {
      // the index argument in ReadDone is the index of the piece this block
      // is a part of, not the index of the block itself within the piece
      case FW.ReadDone(index, block) if (blockIndexes.contains(sender)) =>
        val blockIndex = blockIndexes(sender)
        blocks(blockIndex) = block
        val remaining = blockIndexes - sender
        if (remaining.isEmpty) {
          val combinedBlock = blocks.foldLeft(mutable.ArrayBuffer[Byte]()) { (buf, block) =>
            buf ++= block
          }.toArray
          fileManager ! FW.ReadDone(index, combinedBlock)
          context stop self
        } else {
          context.become(tally(remaining))
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
class MultiFileWorker(files: List[TorrentFile], pieceSize: Int, folder: String)
  extends Actor { this: MultiFileWorker.Cake with Parent =>

  import MultiFileWorker._
  import context.dispatcher

  val singleFileWorkers: List[WrappedFileWorker] = files map { f =>
    WrappedFileWorker(
      f.path,
      f.length,
      provider.singleFileWorker(folder + "/" + f.path, pieceSize)
    )
  }

  // Msgs sent from fileManager, forwarded to SingleFileWorker
  def receive = {
    // Actually reads data from disk
    case FW.Read(index, offset, length) =>
      val requestor = sender
      val workerJobs = affectedFileJobs(offset, length)
      val blockIndexes = indexWorkers(workerJobs)
      val tallyReads = provider.tallyReads(parent, blockIndexes)
      workerJobs foreach { case WorkerJob(worker, off, len) =>
        worker.tell(FW.Read(index, off, len), tallyReads)
      }

    // Actually flushes data to disk
    case FW.Write(index, offset, block) =>
      val requestor = sender
      val workerJobs = affectedFileJobs(offset, block.size)
      val workers = workerJobs.map { _.worker }.toSet
      val tallyWrites = provider.tallyWrites(parent, workers)
      workerJobs.foldLeft(block) { case (chunk, WorkerJob(worker, off, len)) =>
        val (data, remaining) = chunk.splitAt(len)
        worker.tell(FW.Write(index, off, data), tallyWrites)
        remaining
      }

  }

  def indexWorkers(workerJobs: List[WorkerJob]): Map[ActorRef, Int] = {
    workerJobs.foldLeft((0, Map[ActorRef, Int]())) {
      case ((idx, blockIndexes), workerJob) =>
        val entry = (workerJob.worker -> idx)
        (idx + 1, blockIndexes + entry)
    }._2
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
        affected: List[WorkerJob]): List[WorkerJob] = {
      fileWorkers match {
        case Nil => affected.reverse
        case WrappedFileWorker(path, length, worker) :: more =>
          val end = position + length
          if (end < targetStart) {
            fileJobsHelper(more, end, affected)
          } else if (position >= targetStop) {
            affected.reverse
          } else {
            val offset = if (targetStart > position) position else 0
            val jobLength = (length - offset) min (targetStop - position)
            val workerJob = WorkerJob(worker, offset, jobLength)
            fileJobsHelper(more, end, workerJob :: affected)
          }
      }
    }

    fileJobsHelper(singleFileWorkers, 0, List[WorkerJob]())
  }

}
