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
import akka.TestUtils._

class UdpConnectedIntegrationSpec extends AkkaSpec("""
    akka.loglevel = INFO
    akka.actor.serialize-creators = on
    """) with ImplicitSender {

  val addresses = temporaryServerAddresses(3, udp = true)

  def bindUdp(address: InetSocketAddress, handler: ActorRef): ActorRef = {
    val commander = TestProbe()
    commander.send(IO(Udp), Udp.Bind(handler, address))
    commander.expectMsg(Udp.Bound(address))
    commander.sender
  }

  def connectUdp(localAddress: Option[InetSocketAddress], remoteAddress: InetSocketAddress, handler: ActorRef): ActorRef = {
    val commander = TestProbe()
    commander.send(IO(UdpConnected), UdpConnected.Connect(handler, remoteAddress, localAddress, Nil))
    commander.expectMsg(UdpConnected.Connected)
    commander.sender
  }

  
    @Test def `must be able to send and receive without binding`: Unit = {
      val serverAddress = addresses(0)
      val server = bindUdp(serverAddress, testActor)
      val data1 = ByteString("To infinity and beyond!")
      val data2 = ByteString("All your datagram belong to us")
      connectUdp(localAddress = None, serverAddress, testActor) ! UdpConnected.Send(data1)

      val clientAddress = expectMsgPF() {
        case Udp.Received(d, a) ⇒
          assertThat(d, equalTo(data1))
          a
      }

      server ! Udp.Send(data2, clientAddress)

      assertThat(expectMsgType[UdpConnected.Received].data, equalTo(data2))
    }

    @Test def `must be able to send and receive with binding`: Unit = {
      val serverAddress = addresses(1)
      val clientAddress = addresses(2)
      val server = bindUdp(serverAddress, testActor)
      val data1 = ByteString("To infinity and beyond!")
      val data2 = ByteString("All your datagram belong to us")
      connectUdp(Some(clientAddress), serverAddress, testActor) ! UdpConnected.Send(data1)

      expectMsgPF() {
        case Udp.Received(d, a) ⇒
          assertThat(d, equalTo(data1))
          a must be === clientAddress
      }

      server ! Udp.Send(data2, clientAddress)

      assertThat(expectMsgType[UdpConnected.Received].data, equalTo(data2))
    }

  }