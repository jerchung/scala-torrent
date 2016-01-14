package storrent.file

import akka.util.ByteString

object FileWorker {
  case class Read(index: Int, totalOffset: Int, length: Int)
  case class Write(index: Int, totalOffset: Int, block: ByteString)
  case class ReadDone(index: Int, block: Array[Byte])
  case class WriteDone(index: Int)
}
