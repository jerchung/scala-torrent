package org.jerchung.torrent.actor.message

import akka.util.ByteString
import org.scalatest._
import BT._

class BTMessageSpec extends WordSpecLike {

  "A BT Message" when {

    "converting to ByteString" should {

      "correctly convert for KeepAlive" in {
        assert(KeepAlive.toByteString == ByteString(0, 0, 0, 0))
      }

      "correctly convert for Choke" in {
        assert(Choke.toByteString == ByteString(0, 0, 0, 1, 0))
      }

      "correctly convert for Unchoke" in {
        assert(Unchoke.toByteString == ByteString(0, 0, 0, 1, 1))
      }

      "correctly convert for Interested" in {
        assert(Interested.toByteString == ByteString(0, 0, 0, 1, 2))
      }

      "correctly convert for NotInterested" in {
        assert(NotInterested.toByteString == ByteString(0, 0, 0, 1, 3))
      }

      "correctly convert for Bitfield" in {

      }
    }
  }
}