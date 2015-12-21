package storrent.file

import akka.util.ByteString

object FileWorker {
  case class Read(index: Int, offset: Int, length: Int)
  case class Write(index: Int, offset: Int, block: ByteString)
  case class ReadDone(part: Int, block: Array[Byte])
  case class WriteDone(index: Int)
}
