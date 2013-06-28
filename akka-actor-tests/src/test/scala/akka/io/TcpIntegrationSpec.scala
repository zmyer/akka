/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import akka.testkit.AkkaSpec
import akka.util.ByteString
import akka.TestUtils._
import Tcp._

class TcpIntegrationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.actor.serialize-creators = on
    """) with TcpIntegrationSpecSupport {

  "The TCP transport implementation" should {

    @Test def `must properly bind a test server`: Unit = new TestSetup

    @Test def `must allow connecting to and disconnecting from the test server`: Unit = new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()
      clientHandler.send(clientConnection, Close)
      clientHandler.expectMsg(Closed)
      serverHandler.expectMsg(PeerClosed)
      verifyActorTermination(clientConnection)
      verifyActorTermination(serverConnection)
    }

    @Test def `must properly handle connection abort from one side`: Unit = new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()
      clientHandler.send(clientConnection, Abort)
      clientHandler.expectMsg(Aborted)
      serverHandler.expectMsgType[ErrorClosed]
      verifyActorTermination(clientConnection)
      verifyActorTermination(serverConnection)
    }

    @Test def `must properly complete one client/server request/response cycle`: Unit = new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()

      object Aye extends Event
      object Yes extends Event

      clientHandler.send(clientConnection, Write(ByteString("Captain on the bridge!"), Aye))
      clientHandler.expectMsg(Aye)
      assertThat(serverHandler.expectMsgType[Received].data.decodeString("ASCII"), equalTo("Captain on the bridge!"))

      serverHandler.send(serverConnection, Write(ByteString("For the king!"), Yes))
      serverHandler.expectMsg(Yes)
      assertThat(clientHandler.expectMsgType[Received].data.decodeString("ASCII"), equalTo("For the king!"))

      serverHandler.send(serverConnection, Close)
      serverHandler.expectMsg(Closed)
      clientHandler.expectMsg(PeerClosed)

      verifyActorTermination(clientConnection)
      verifyActorTermination(serverConnection)
    }

    @Test def `must support waiting for writes with backpressure`: Unit = new TestSetup {
      val (clientHandler, clientConnection, serverHandler, serverConnection) = establishNewClientConnection()

      object Ack extends Event

      serverHandler.send(serverConnection, Write(ByteString(Array.fill[Byte](100000)(0)), Ack))
      serverHandler.expectMsg(Ack)

      expectReceivedData(clientHandler, 100000)

      override def bindOptions = List(SO.SendBufferSize(1024))
      override def connectOptions = List(SO.ReceiveBufferSize(1024))
    }
  }