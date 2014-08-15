package org.jerchung.torrent.actor.persist

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.ByteString
import com.escalatesoft.subcut.inject._
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import org.jerchung.torrent.dependency.BindingKeys._
import org.jerchung.torrent.actor.message.FM
import org.jerchung.torrent.actor.message.FW
import org.jerchung.torrent.TorrentFile
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent._
import ExecutionContext.Implicits.global

object MultiFileWorker {

  def props(
      files: List[TorrentFile],
      pieceSize: Int)
      (implicit bindingModule: BindingModule): Props = {
    Props(new MultiFileWorker(files, pieceSize))
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
  object ReadAccumulator {
    def props(
        fileManager: ActorRef,
        blockIndexes: Map[ActorRef, Int]): Props = {
      Props(new ReadAccumulator(fileManager, blockIndexes))
    }
  }

  class ReadAccumulator(
      fileManager: ActorRef,
      blockIndexes: Map[ActorRef, Int])
      extends Actor {

    val numExpected = blockIndexes.size
    val blocks = new Array[Array[Byte]](numExpected)

    def receive = accum(blockIndexes)

    def accum(blockIndexes: Map[ActorRef, Int]): Receive = {

      // the index argument in ReadDone is the index of the piece this block
      // is a part of, not the index of the block itself within the piece
      case FW.ReadDone(index, block) if (blockIndexes contains sender) =>
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
          context.become(accum(remaining))
        }

    }

  }

  object WriteAccumulator {
    def props(fileManager: ActorRef, writers: Set[ActorRef]): Props = {
      Props(new WriteAccumulator(fileManager, writers))
    }
  }

  class WriteAccumulator(
      fileManager: ActorRef,
      writers: Set[ActorRef])
      extends Actor {

    def receive = accum(writers)

    def accum(writers: Set[ActorRef]): Receive = {

      case FW.WriteDone(index) =>
        val remaining = writers - sender
        if (remaining.isEmpty) {
          fileManager ! FW.WriteDone(index)
          context stop self
        } else {
          context.become(accum(remaining))
        }
    }
  }

}

/*
 * Parent MUST be FileManager
 */
class MultiFileWorker(files: List[TorrentFile], pieceSize: Int)
    extends Actor
    with AutoInjectable {

  import MultiFileWorker._

  val parent = injectOptional [ActorRef](ParentId) getOrElse { context.parent }

  val singleFileWorkers: List[WrappedFileWorker] = files map { f =>
    WrappedFileWorker(
      f.path,
      f.length,
      createFileWorker(f.path, pieceSize, f.length)
    )
  }

  // Msgs sent from fileManager, forwarded to SingleFileWorker
  def receive = {

    // Actually reads data from disk
    case FW.Read(index, offset, length) =>
      val requestor = sender
      Future {
        val workerJobs = affectedFileJobs(offset, length)
        val blockIndexes = indexWorkers(workerJobs)
        val readAccumulator = createReadAccumulator(blockIndexes)
        workerJobs foreach { case WorkerJob(worker, off, len) =>
          worker.tell(FW.Read(index, off, len), readAccumulator)
        }
      }

    // Actually flushes data to disk
    case FW.Write(index, offset, block) =>
      val requestor = sender
      Future {
        val workerJobs = affectedFileJobs(offset, block.size)
        val workers = workerJobs.foldLeft(Set[ActorRef]()) { (w, wj) =>
          w + wj.worker
        }
        val writeAccumulator = createWriteAccumulator(workers)
        workerJobs.foldLeft(block) { case (chunk, WorkerJob(worker, off, len)) =>
          val (data, remaining) = chunk.splitAt(len)
          worker.tell(FW.Write(index, off, data), writeAccumulator)
          remaining
        }
      }

  }

  def createReadAccumulator(blockIndexes: Map[ActorRef, Int]): ActorRef = {
    injectOptional [ActorRef](ReadAccumulatorId) getOrElse {
      context.actorOf(ReadAccumulator.props(parent, blockIndexes))
    }
  }

  def createWriteAccumulator(workers: Set[ActorRef]): ActorRef = {
    injectOptional [ActorRef](WriteAccumulatorId) getOrElse {
      context.actorOf(WriteAccumulator.props(parent, workers))
    }
  }

  def createFileWorker(path: String, pieceSize: Int, length: Int): ActorRef = {
    injectOptional [ActorRef](FileWorkerId) getOrElse {
      context.actorOf(SingleFileWorker.props(path, pieceSize, length))
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