package com.example.smtp

import akka.actor._
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import java.net.InetSocketAddress
import scala.concurrent.duration._
import akka.event.LoggingReceive

// states
sealed trait State
case object Idle extends State
case object MailFrom extends State
case object RcptTo extends State
case object Data extends State
case object DataCollector extends State

// envelope transitions
sealed trait EmptyEnvelope
case object Uninitialized extends EmptyEnvelope
case class Envelope(mailFrom: String, rcptTo: List[String], data: String) extends EmptyEnvelope

case class SmtpMessage(msg: String)
class SessionHandler(connection: ActorRef, socket: ActorRef) extends Actor with FSM[State,EmptyEnvelope]
      with ActorLogging {

  def say(s: String) = socket ! Tcp.Write(ByteString(s + "\n"))
  def whine() = {
    say("500 Wat?")
    stay()
  }
  def cmd(s: String, command: String => Boolean) = command(s.toUpperCase.trim)

  say("220 Bro Ready")
  startWith(Idle, Uninitialized, Option(120 seconds))

  when(Idle) {
    case Event(SmtpMessage(msg), _)
      if cmd(msg, s => s.startsWith("HELO ") || s.startsWith("EHLO ")) =>
        goto(MailFrom) using Uninitialized
  }
  when(MailFrom) {
    case Event(SmtpMessage(msg), _) if cmd(msg, _.startsWith("MAIL FROM:")) =>
      goto(RcptTo) using Envelope(msg.trim.replace("MAIL FROM:", ""), Nil, "")
  }
  when(RcptTo) {
    case Event(SmtpMessage(msg), envelope: Envelope) if cmd(msg, _.startsWith("RCPT TO:")) =>
      goto(Data) using Envelope(envelope.mailFrom, List(msg.trim.replace("RCPT TO:", "")), "")
  }
  when(Data) {
    case Event(SmtpMessage(msg), envelope: Envelope) if cmd(msg, _ == "DATA") =>
      goto(DataCollector) using envelope
  }
  when(DataCollector) {
    case Event(SmtpMessage(msg), envelope: Envelope) =>
      if (msg.trim == ".") {
        goto(Idle) using Uninitialized
      }
      else {
        goto(DataCollector) using envelope.copy(data = envelope.data + msg)
      }
  }

  onTransition {
    case DataCollector -> _ => stateData match {
      case envelope =>
        log.debug("got envelope: {}", envelope)
        say("250 Accepted")
    }
    case Data -> DataCollector => say("354 Gimme some data...")
    case _ -> _ => say("250 Bro")
  }
  whenUnhandled {
    case Event(SmtpMessage(msg), _) if cmd(msg, _ == "QUIT") =>
      say("Bye!")
      socket ! Tcp.Close
      stop()
    case _ => whine()
  }
}

class ConnectionHandler(remote: InetSocketAddress,
                        connection: ActorRef, socket: ActorRef)
  extends Actor with ActorLogging {

  // watch for connection termination
  context.watch(connection)
  val sessionHandler = context.actorOf(Props(new SessionHandler(connection, socket)))
  def receive = {
    case Tcp.Received(data) =>
      val text = data.utf8String
      sessionHandler ! SmtpMessage(text)
    case _: Tcp.ConnectionClosed =>
      log.debug("{} Peer closed connection, stopping", remote)
      context.stop(self)
    case Terminated(`connection`) =>
      log.debug("Stopping, because connection for remote address {} died", remote)
      context.stop(self)
  }
}

class SmtpService(endpoint: InetSocketAddress) extends Actor with ActorLogging {
  import context.system
  IO(Tcp) ! Tcp.Bind(self, endpoint)
  def receive = {
    case Tcp.Connected(remote, _) =>
      log.debug("Remote address {} connected", remote)
      // set up connection handler
      val socket = sender
      val handler = context.actorOf(Props(new ConnectionHandler(remote, self, socket)))
      sender ! Tcp.Register(handler)
    case x => println(x)
  }
}

object Boot extends App {
  implicit val system = ActorSystem()
  val endpoint = new InetSocketAddress("127.0.0.1", 1111)
  val listener = system.actorOf(Props(new SmtpService(endpoint)))
  readLine(s"Hit ENTER to exit ...${System.getProperty("line.separator")}")
  system.shutdown()
}