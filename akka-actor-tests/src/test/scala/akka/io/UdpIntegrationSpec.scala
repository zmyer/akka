/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

import java.net.InetSocketAddress
import akka.testkit.{ TestProbe, ImplicitSender, AkkaSpec }
import akka.util.ByteString
import akka.actor.ActorRef
import akka.io.Udp._
import akka.TestUtils._

class UdpIntegrationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.actor.serialize-creators = on""") with ImplicitSender {

  val addresses = temporaryServerAddresses(3, udp = true)

  def bindUdp(address: InetSocketAddress, handler: ActorRef): ActorRef = {
    val commander = TestProbe()
    commander.send(IO(Udp), Bind(handler, address))
    commander.expectMsg(Bound(address))
    commander.sender
  }

  val simpleSender: ActorRef = {
    val commander = TestProbe()
    commander.send(IO(Udp), SimpleSender)
    commander.expectMsg(SimpleSenderReady)
    commander.sender
  }

  
    @Test def `must be able to send without binding`: Unit = {
      val serverAddress = addresses(0)
      val server = bindUdp(serverAddress, testActor)
      val data = ByteString("To infinity and beyond!")
      simpleSender ! Send(data, serverAddress)

      assertThat(expectMsgType[Received].data, equalTo(data))

    }

    @Test def `must be able to send several packet back and forth with binding`: Unit = {
      val serverAddress = addresses(1)
      val clientAddress = addresses(2)
      val server = bindUdp(serverAddress, testActor)
      val client = bindUdp(clientAddress, testActor)
      val data = ByteString("Fly little packet!")

      def checkSendingToClient(): Unit = {
        server ! Send(data, clientAddress)
        expectMsgPF() {
          case Received(d, a) ⇒
            assertThat(d, equalTo(data))
            a must be === serverAddress
        }
      }
      def checkSendingToServer(): Unit = {
        client ! Send(data, serverAddress)
        expectMsgPF() {
          case Received(d, a) ⇒
            assertThat(d, equalTo(data))
            a must be === clientAddress
        }
      }

      (0 until 20).foreach(_ ⇒ checkSendingToServer())
      (0 until 20).foreach(_ ⇒ checkSendingToClient())
      (0 until 20).foreach { i ⇒
        if (i % 2 == 0) checkSendingToServer()
        else checkSendingToClient()
      }
    }
  }