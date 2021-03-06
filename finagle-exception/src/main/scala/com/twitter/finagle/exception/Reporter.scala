package com.twitter.finagle.exception

import java.net.{SocketAddress, InetSocketAddress, InetAddress}
import scala.collection.JavaConversions._

import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.scribe.{LogEntry, ResultCode, scribe}

import com.twitter.util.GZIPStringEncoder
import com.twitter.util.{Time, Monitor, NullMonitor}

import com.twitter.finagle.tracing.Trace
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.thrift.ThriftClientFramedCodec


trait ClientMonitorFactory extends (String => Monitor)
trait ServerMonitorFactory extends ((String, SocketAddress) => Monitor)

trait MonitorFactory extends ServerMonitorFactory with ClientMonitorFactory {
  def clientMonitor(serviceName: String): Monitor
  def serverMonitor(serviceName: String, address: SocketAddress): Monitor

  def apply(serviceName: String) = clientMonitor(serviceName)
  def apply(serviceName: String, address: SocketAddress) = serverMonitor(serviceName, address)
}

object NullMonitorFactory extends MonitorFactory {
  def clientMonitor(serviceName: String) = NullMonitor
  def serverMonitor(serviceName: String, address: SocketAddress) = NullMonitor
}

/**
 * A collection of methods to construct a Monitor that logs to a ScribeHandler
 * specifically for the chickadee exception reporting service. These methods are not generic
 * enough for general use.
 */
object Reporter {
  private[Reporter] val scribeCategory = "chickadee"

  /**
   * Creates a default reporter.
   *
   * Default in this case means that the instance of ServiceException it constructs when its
   * receive method is called does not report any endpoints.
   */
  def defaultReporter(scribeHost: String, scribePort: Int, serviceName: String): Reporter = {
    new Reporter(makeClient(scribeHost, scribePort), serviceName)
  }

  /**
   * Create a default client reporter.
   *
   * Default means the Reporter instance created by defaultReporter with the addition of
   * reporting the client based on the localhost address as the client endpoint.
   *
   * It returns a String => Reporter, which conforms to ClientBuilder's monitor option.
   */
  @deprecated("Use reporterFactory instead")
  def clientReporter(scribeHost: String, scribePort: Int): String => Monitor = {
    monitorFactory(scribeHost, scribePort).clientMonitor _
  }

  /**
   * Create a default source (i.e. server) reporter.
   *
   * Default means the Reporter instance created by defaultReporter with the addition of
   * reporting the source based on the SocketAddress argument.
   *
   * It returns a (String, SocketAddress) => Reporter, which conforms to ServerBuilder's
   * monitor option.
   */
  @deprecated("Use reporterFactory instead")
  def sourceReporter(scribeHost: String, scribePort: Int): (String, SocketAddress) => Monitor = {
    monitorFactory(scribeHost, scribePort).serverMonitor _
  }


  /**
   * Create a reporter factory that can produce either a client or server reporter based
   * on the signature.
   */
  def monitorFactory(scribeHost: String, scribePort: Int): MonitorFactory = new MonitorFactory {
    private[this] val scribeClient = makeClient(scribeHost, scribePort)

    def clientMonitor(serviceName: String) =
      new Reporter(scribeClient, serviceName).withClient()
    def serverMonitor(serviceName: String, address: SocketAddress) =
      new Reporter(scribeClient, serviceName).withSource(address)
  }


  private[this] def makeClient(scribeHost: String, scribePort: Int) = {
    val service = ClientBuilder() // these are from the zipkin tracer
      .hosts(new InetSocketAddress(scribeHost, scribePort))
      .codec(ThriftClientFramedCodec())
      .hostConnectionLimit(5)
      .build()

    new scribe.FinagledClient(service, new TBinaryProtocol.Factory())
  }
}

/**
 * An implementation of ExceptionReceiver custom to the chickadee reporting service.
 *
 * Optionally logs stats to a statsReceiver if desired.
 *
 * Note that this implementation does not guarantee that a logged exception will be received
 * by the configured scribe endpoint because it just drops a failed message and does not retry.
 * This is because it is intended to log to a local (i.e. on the same machine) scribe daemon, in
 * which case there should be no network failure. If there is failure in this case, something else
 * is very wrong!
 */
sealed case class Reporter(
  client: scribe.FinagledClient,
  serviceName: String,
  statsReceiver: StatsReceiver = NullStatsReceiver,
  private val sourceAddress: Option[String] = None,
  private val clientAddress: Option[String] = None) extends Monitor {

  /**
   * Add a modifier to append a client address (i.e. endpoint) to a generated ServiceException.
   *
   * The endpoint string is the ip address of the host (e.g. "127.0.0.1").
   */
  def withClient(address: InetAddress = InetAddress.getLocalHost) =
    copy(clientAddress = Some(address.getHostAddress))

  /**
   * Add a modifier to append a source address (i.e. endpoint) to a generated ServiceException.
   *
   * The endpoint string is the ip of the host concatenated with the port of the socket (e.g.
   * "127.0.0.1:8080").
   */
  def withSource(address: SocketAddress) =
    address match {
      case isa: InetSocketAddress => copy(sourceAddress = Some(isa.getAddress.getHostAddress + ":" + isa.getPort))
      case _ => this // don't deal with non-InetSocketAddress types, but don't crash either
    }

  /**
   * Create a default ServiceException and fold in the modifiers (i.e. to add a source/client
   * endpoint).
   */
  def createEntry(e: Throwable) = {
    var se = new ServiceException(serviceName, e, Time.now, Trace.id.traceId.toLong)

    sourceAddress foreach { sa => se = se withSource sa }
    clientAddress foreach { ca => se = se withClient ca }

    new LogEntry(Reporter.scribeCategory, GZIPStringEncoder.encodeString(se.toJson))
  }

  /**
   * Log an exception to the specified scribe endpoint.
   *
   * See top level comment for this class for more details on performance
   * implications.
   */
  def handle(t: Throwable) = {
    client.log(createEntry(t) :: Nil) onSuccess {
      case ResultCode.Ok => statsReceiver.counter("report_exception_ok").incr()
      case ResultCode.TryLater => statsReceiver.counter("report_exception_try_later").incr()
    } onFailure {
      case e => statsReceiver.counter("report_exception_" + e.toString).incr()
    }

    false  // did not actually handle
  }
}
