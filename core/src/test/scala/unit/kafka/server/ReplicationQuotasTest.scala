/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package unit.kafka.server

import java.util.Properties

import kafka.admin.AdminUtils
import kafka.admin.AdminUtils._
import kafka.common._
import kafka.log.LogConfig._
import kafka.server.KafkaConfig.fromProps
import kafka.server.QuotaType._
import kafka.server._
import kafka.utils.TestUtils
import kafka.utils.TestUtils._
import kafka.zk.ZooKeeperTestHarness
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.junit.Assert._
import org.junit.{After, Before, Test}
import scala.collection.JavaConverters._

/**
  * This is the main test which ensure Replication Quotas work correctly.
  *
  * The test will fail if the quota is < 1MB/s as 1MB is the default for replica.fetch.max.bytes.
  * So with a throttle of 100KB/s, 1 fetch of 1 partition would fill 10s of quota. In turn causing
  * the throttled broker to pause for > 10s
  *
  * Anything over 100MB/s tends to fail as this is the non-throttled replication rate
  */

class ReplicationQuotasTest extends ZooKeeperTestHarness {
  def percentError(percent: Int, value: Long): Long = Math.round(value * percent / 100)

  val msg100KB = new Array[Byte](100000)
  var brokers: Seq[KafkaServer] = null
  val topic = "topic1"
  var producer: KafkaProducer[Array[Byte], Array[Byte]] = null

  @Before
  override def setUp() {
    super.setUp()
  }

  @After
  override def tearDown() {
    brokers.par.foreach(_.shutdown())
    producer.close()
    super.tearDown()
  }

  @Test
  def shouldBootstrapTwoBrokersWithLeaderThrottle(): Unit = {
    shouldMatchQuotaReplicatingThroughAnAsymmetricTopology(true)
  }

  @Test
  def shouldBootstrapTwoBrokersWithFollowerThrottle(): Unit = {
    shouldMatchQuotaReplicatingThroughAnAsymmetricTopology(false)
  }

  def shouldMatchQuotaReplicatingThroughAnAsymmetricTopology(leaderThrottle: Boolean): Unit = {
    /**
      * In short we have 8 brokers, 2 are not-started. We assign replicas for the two non-started
      * brokers, so when we start them we can monitor replication from the 6 to the 2.
      *
      * We also have two non-throttled partitions on two of the 6 brokers, just to make sure
      * regular replication works as expected.
      */

    brokers = (100 to 105).map { id => TestUtils.createServer(fromProps(createBrokerConfig(id, zkConnect))) }

    //Given six partitions, lead on nodes 0,1,2,3,4,5 but will followers on node 6,7 (not started yet)
    //And two extra partitions 6,7, which we don't intend on throttling
    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic, Map(
      0 -> Seq(100, 106), //Throttled
      1 -> Seq(101, 106), //Throttled
      2 -> Seq(102, 106), //Throttled
      3 -> Seq(103, 107), //Throttled
      4 -> Seq(104, 107), //Throttled
      5 -> Seq(105, 107), //Throttled
      6 -> Seq(100, 106), //Not Throttled
      7 -> Seq(101, 107) //Not Throttled
    ))

    val msg = msg100KB
    val msgCount: Int = 1000
    val expectedDuration = 10 //Keep the test to N seconds
    var throttle: Long = msgCount * msg.length / expectedDuration
    if (!leaderThrottle) throttle = throttle * 3 //Follower throttle needs to replicate 3x as fast to get the same duration as there are three replicas to replicate for each of the two follower brokers

    //Set the throttle limit on all 8 brokers, but only assign throttled replicas to the six leaders, or two followers
    (100 to 107).foreach { brokerId =>
      changeBrokerConfig(zkUtils, Seq(brokerId), property(KafkaConfig.ThrottledReplicationRateLimitProp, throttle.toString))
    }
    if (leaderThrottle)
      changeTopicConfig(zkUtils, topic, property(ThrottledReplicasListProp, "0:100,1:101,2:102,3:103,4:104,5:105")) //partition-broker:... throttle the 6 leaders
    else
      changeTopicConfig(zkUtils, topic, property(ThrottledReplicasListProp, "0:106,1:106,2:106,3:107,4:107,5:107")) //partition-broker:... throttle the two followers

    //Add data equally to each partition
    producer = TestUtils.createNewProducer(TestUtils.getBrokerListStrFromServers(brokers), retries = 5, acks = 0)
    (0 until msgCount).foreach { x =>
      (0 to 7).foreach { partition =>
        producer.send(new ProducerRecord(topic, partition, null, msg)).get
      }
    }

    //Ensure data is fully written: broker 1 has partition 1, broker 2 has partition 2 etc
    (0 to 5).foreach { id => waitForOffsetsToMatch(msgCount, id, 100 + id) }
    //Check the non-throttled partitions too
    waitForOffsetsToMatch(msgCount, 6, 100)
    waitForOffsetsToMatch(msgCount, 7, 101)

    val start = System.currentTimeMillis()

    //When we create the 2 new, empty brokers
    brokers = brokers :+ TestUtils.createServer(fromProps(createBrokerConfig(106, zkConnect)))
    brokers = brokers :+ TestUtils.createServer(fromProps(createBrokerConfig(107, zkConnect)))

    //Check that throttled config correctly migrated to the new brokers
    (106 to 107).foreach { brokerId =>
      assertEquals(throttle, brokerFor(brokerId).quotaManagers.follower.upperBound())
    }
    if (!leaderThrottle) {
      (0 to 2).foreach { partition =>
        assertTrue(brokerFor(106).quotaManagers.follower.isThrottled(new TopicAndPartition(topic, partition)))
      }
      (3 to 5).foreach { partition =>
        assertTrue(brokerFor(107).quotaManagers.follower.isThrottled(new TopicAndPartition(topic, partition)))
      }
    }

    //Wait for non-throttled partitions to replicate first
    (6 to 7).foreach { id => waitForOffsetsToMatch(msgCount, id, 100 + id) }
    val unthrottledTook = System.currentTimeMillis() - start

    //Wait for replicas 0,1,2,3,4,5 to fully replicated to broker 106,107
    (0 to 2).foreach { id => waitForOffsetsToMatch(msgCount, id, 106) }
    (3 to 5).foreach { id => waitForOffsetsToMatch(msgCount, id, 107) }

    val throttledTook = System.currentTimeMillis() - start

    //Check the recorded throttled rate is what we expect
    if (leaderThrottle) {
      (100 to 105).map(brokerFor(_)).foreach { broker =>
        val metricName = broker.metrics.metricName("byte-rate", LeaderReplication.toString, "Tracking byte-rate for" + LeaderReplication)
        val measuredRate = broker.metrics.metrics.asScala(metricName).value()
        info(s"Broker:${broker.config.brokerId} Expected:$throttle, Recorded Rate was:$measuredRate")
        assertEquals(throttle, measuredRate, percentError(25, throttle))
      }
    } else {
      (106 to 107).map(brokerFor(_)).foreach { broker =>
        val metricName = broker.metrics.metricName("byte-rate", FollowerReplication.toString, "Tracking byte-rate for" + FollowerReplication)
        val measuredRate = broker.metrics.metrics.asScala(metricName).value()
        info(s"Broker:${broker.config.brokerId} Expected:$throttle, Recorded Rate was:$measuredRate")
        assertEquals(throttle, measuredRate, percentError(25, throttle))
      }
    }

    //Check the times for throttled/unthrottled are each side of what we expect
    info(s"Unthrottled took: $unthrottledTook, Throttled took: $throttledTook, for expeted $expectedDuration secs")
    assertTrue(s"Unthrottled replication of ${unthrottledTook}ms should be < ${expectedDuration * 1000}ms",
      unthrottledTook < expectedDuration * 1000)
    assertTrue((s"Throttled replication of ${throttledTook}ms should be > ${expectedDuration * 1000}ms"),
      throttledTook > expectedDuration * 1000)
    assertTrue((s"Throttled replication of ${throttledTook}ms should be < ${expectedDuration * 1500}ms"),
      throttledTook < expectedDuration * 1000 * 1.5)
  }

  @Test
  def shouldThrottleOldSegments(): Unit = {
    /**
      * Simple test which ensures throttled replication works when the dataset spans many segments
      */

    //2 brokers with 1MB Segment Size & 1 partition
    val config: Properties = createBrokerConfig(100, zkConnect)
    config.put("log.segment.bytes", (1024 * 1024).toString)
    brokers = Seq(TestUtils.createServer(fromProps(config)))
    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic, Map(0 -> Seq(100, 101)))

    //Write 20MBs and throttle at 5MB/s
    val msg = msg100KB
    val msgCount: Int = 200
    val expectedDuration = 4
    val throttle: Long = msg.length * msgCount / expectedDuration

    //Set the throttle limit leader
    changeBrokerConfig(zkUtils, Seq(100), property(KafkaConfig.ThrottledReplicationRateLimitProp, throttle.toString))
    changeTopicConfig(zkUtils, topic, property(ThrottledReplicasListProp, "0:100"))

    //Add data
    addData(msgCount, msg)

    val start = System.currentTimeMillis()

    //Start the new broker (and hence start replicating)
    brokers = brokers :+ TestUtils.createServer(fromProps(createBrokerConfig(101, zkConnect)))
    waitForOffsetsToMatch(msgCount, 0, 101)

    val throttledTook = System.currentTimeMillis() - start

    assertTrue((s"Throttled replication of ${throttledTook}ms should be > ${expectedDuration * 1000 * 0.9}ms"),
      throttledTook > expectedDuration * 1000 * 0.9)
    assertTrue((s"Throttled replication of ${throttledTook}ms should be < ${expectedDuration * 1500}ms"),
      throttledTook < expectedDuration * 1000 * 1.5)
  }

  def addData(msgCount: Int, msg: Array[Byte]): Boolean = {
    producer = TestUtils.createNewProducer(TestUtils.getBrokerListStrFromServers(brokers), retries = 5, acks = 0)
    (0 until msgCount).foreach { x => producer.send(new ProducerRecord(topic, msg)).get }
    waitForOffsetsToMatch(msgCount, 0, 100)
  }

  private def waitForOffsetsToMatch(offset: Int, partitionId: Int, brokerId: Int): Boolean = {
    waitUntilTrue(() => {
      offset == brokerFor(brokerId).getLogManager.getLog(TopicAndPartition(topic, partitionId)).map(_.logEndOffset).getOrElse(0)
    }, s"Offsets did not match for partition $partitionId on broker $brokerId", 60000)
  }

  private def property(key: String, value: String) = {
    new Properties() { put(key, value) }
  }

  private def brokerFor(id: Int): KafkaServer = brokers.filter(_.config.brokerId == id)(0)
}
