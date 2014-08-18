package com.novemberain.hop.client

import com.novemberain.hop.client.domain.ConnectionInfo
import com.novemberain.hop.client.domain.NodeInfo
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.ShutdownListener
import com.rabbitmq.client.ShutdownSignalException
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClientSpec extends Specification {
  protected static final String DEFAULT_USERNAME = "guest"
  protected static final String DEFAULT_PASSWORD = "guest"

  protected Client client
  private final ConnectionFactory cf = initializeConnectionFactory()

  protected ConnectionFactory initializeConnectionFactory() {
    final cf = new ConnectionFactory()
    cf.setAutomaticRecoveryEnabled(false)
    cf
  }

  def setup() {
    client = new Client("http://127.0.0.1:15672/api/", DEFAULT_USERNAME, DEFAULT_PASSWORD)
  }

  def "GET /api/overview"() {
    when: "client requests GET /api/overview"
    final conn = openConnection()
    final ch = conn.createChannel()
    1000.times { ch.basicPublish("", "", null, null) }

    def res = client.getOverview()
    def xts = res.getExchangeTypes().collect { it.getName() }

    then: "the response is converted successfully"
    res.getNode().startsWith("rabbit@")
    res.getErlangVersion() != null
    res.getStatisticsDbNode().startsWith("rabbit@")

    final msgStats = res.getMessageStats()
    msgStats.basicPublish >= 0
    msgStats.basicPublishDetails.rate >= 0.0
    msgStats.publisherConfirm >= 0
    msgStats.basicDeliver >= 0
    msgStats.basicReturn >= 0

    final qTotals = res.getQueueTotals()
    qTotals.messages >= 0
    qTotals.messagesReady >= 0
    qTotals.messagesUnacknowledged >= 0

    final oTotals = res.getObjectTotals();
    oTotals.connections >= 0
    oTotals.channels >= 0
    oTotals.exchanges >= 0
    oTotals.queues >= 0
    oTotals.consumers >= 0

    res.listeners.size() >= 1
    res.contexts.size() >= 1

    xts.contains("topic")
    xts.contains("fanout")
    xts.contains("direct")
    xts.contains("headers")

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  def "GET /api/aliveness-test/{vhost}"() {
    when: "client performs aliveness check for the / vhost"
    final hasSucceeded = client.alivenessTest("/")

    then: "the check succeeds"
    hasSucceeded
  }

  def "GET /api/whoami"() {
    when: "client retrieves active name authentication details"
    final res = client.whoAmI()

    then: "the details are returned"
    res.name == DEFAULT_USERNAME
    res.tags ==~ /administrator/
  }

  def "GET /api/nodes"() {
    when: "client retrieves a list of cluster nodes"
    final res = client.getNodes()
    final node = res.first()

    then: "the list is returned"
    res.size() == 1
    verifyNode(node)
  }

  def "GET /api/nodes/{name}"() {
    when: "client retrieves a list of cluster nodes"
    final res = client.getNodes()
    final name = res.first().name
    final node = client.getNode(name)

    then: "the list is returned"
    res.size() == 1
    verifyNode(node)
  }

  def "GET /api/connections"() {
    given: "an open RabbitMQ client connection"
    final conn = openConnection()

    when: "client retrieves a list of connections"
    final res = client.getConnections()
    final fst = res.first()

    then: "the list is returned"
    res.size() >= 1
    verifyConnectionInfo(fst)

    cleanup:
    conn.close()
  }

  def "GET /api/connections/{name}"() {
    given: "an open RabbitMQ client connection"
    final conn = openConnection()

    when: "client retrieves connection info with the correct name"
    final xs = client.getConnections()
    final fst = client.getConnection(xs.first().name)

    then: "the info is returned"
    verifyConnectionInfo(fst)

    cleanup:
    conn.close()
  }

  def "DELETE /api/connections/{name}"() {
    given: "an open RabbitMQ client connection"
    final latch = new CountDownLatch(1)
    final conn = openConnection()
    conn.addShutdownListener(new ShutdownListener() {
      @Override
      void shutdownCompleted(ShutdownSignalException e) {
        latch.countDown()
      }
    })
    assert conn.isOpen()

    when: "client closes the connection"
    final xs = client.getConnections()
    xs.each({ client.closeConnection(it.name) })

    and: "some time passes"
    awaitOn(latch)

    then: "the connection is closed"
    !conn.isOpen()

    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  def "GET /api/channels"() {
    given: "an open RabbitMQ client connection with 1 channel"
    final conn = openConnection()
    final ch = conn.createChannel()

    when: "client lists channels"
    final chs = client.getChannels()
    final chi = chs.first()

    then: "the list is returned"
    chi.getConsumerCount() == 0
    chi.number == ch.getChannelNumber()
    chi.node.startsWith("rabbit@")
    chi.state == "running"
    !chi.usesPublisherConfirms()
    !chi.transactional


    cleanup:
    if (conn.isOpen()) {
      conn.close()
    }
  }

  protected boolean awaitOn(CountDownLatch latch) {
    latch.await(5, TimeUnit.SECONDS)
  }

  protected void verifyConnectionInfo(ConnectionInfo info) {
    info.port == ConnectionFactory.DEFAULT_AMQP_PORT
    !info.usesTLS
    info.peerHost.equals(info.host)
  }

  protected Connection openConnection() {
    this.cf.newConnection()
  }

  protected void verifyNode(NodeInfo node) {
    assert node.name != null
    assert node.type == "disc"
    assert node.isDiskNode()
    assert node.socketsUsed < node.socketsTotal
    assert node.erlangProcessesUsed < node.erlangProcessesTotal
    assert node.erlangRunQueueLength >= 0
    assert node.memoryUsed < node.memoryLimit
    assert !node.memoryAlarmActive
    assert node.diskFree > node.diskFreeLimit
    assert !node.diskAlarmActive
    assert node.authMechanisms.size() >= 1
    assert node.erlangApps.size() >= 1
  }
}
