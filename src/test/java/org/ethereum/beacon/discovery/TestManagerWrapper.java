/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ethereum.beacon.discovery;

import static org.ethereum.beacon.discovery.util.Functions.PRIVKEY_SIZE;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.tuweni.bytes.Bytes;
import org.ethereum.beacon.discovery.network.NettyDiscoveryServer;
import org.ethereum.beacon.discovery.network.NetworkParcel;
import org.ethereum.beacon.discovery.packet.Packet;
import org.ethereum.beacon.discovery.packet.RawPacket;
import org.ethereum.beacon.discovery.pipeline.Envelope;
import org.ethereum.beacon.discovery.pipeline.Field;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeRecordBuilder;
import org.ethereum.beacon.discovery.util.Functions;
import org.ethereum.beacon.discovery.util.Utils;
import org.mockito.Mockito;
import org.web3j.crypto.ECKeyPair;
import reactor.core.publisher.Flux;

public class TestManagerWrapper {

  public class TestMessage {
    private final NodeRecord from;
    private final NetworkParcel parcel;

    public TestMessage(NodeRecord from, NetworkParcel parcel) {
      this.from = from;
      this.parcel = parcel;
    }

    public NodeRecord getFrom() {
      return from;
    }

    public RawPacket getRawPacket() {
      return parcel.getPacket();
    }

    public NetworkParcel getParcel() {
      return parcel;
    }

    public Packet<?> getPacket() {
      TestManagerWrapper destNode = testNetwork.find(getParcel().getDestination().getPort());
      return getRawPacket().demaskPacket(destNode.getNodeRecord().getNodeId());
    }
  }

  public static final String LOCALHOST = "127.0.0.1";
  public static final Duration RETRY_TIMEOUT = Duration.ofSeconds(30);
  public static final Duration LIVE_CHECK_INTERVAL = Duration.ofSeconds(30);

  private final TestNetwork testNetwork;
  private final DiscoveryManagerImpl discoveryManager;
  private final BlockingQueue<NetworkParcel> outbound = new LinkedBlockingQueue<>();

  public static TestManagerWrapper create(TestNetwork testNetwork, int seed) {
    return new TestManagerWrapper(testNetwork, createTestManager(seed));
  }

  private TestManagerWrapper(TestNetwork testNetwork, DiscoveryManagerImpl discoveryManager) {
    this.testNetwork = testNetwork;
    this.discoveryManager = discoveryManager;
    Flux.from(discoveryManager.getOutgoingMessages()).subscribe(outbound::add);
  }

  public DiscoveryManagerImpl getDiscoveryManager() {
    return discoveryManager;
  }

  public NodeRecord getNodeRecord() {
    return getDiscoveryManager().getLocalNodeRecord();
  }

  public int getPort() {
    return getNodeRecord().getUdpAddress().orElseThrow().getPort();
  }

  public TestMessage nextOutbound() {
    try {
      NetworkParcel ret = outbound.poll(5, TimeUnit.SECONDS);
      if (ret == null) {
        throw new RuntimeException("No outbound messages");
      }
      return new TestMessage(getNodeRecord(), ret);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void deliver(TestMessage message) {
    Envelope envelope = new Envelope();
    envelope.put(Field.INCOMING, message.getRawPacket().getBytes());
    envelope.put(Field.NODE, message.getFrom());
    getDiscoveryManager().getIncomingPipeline().push(envelope);
  }

  public List<TestMessage> exchangeAll(TestManagerWrapper other) {
    List<TestMessage> ret = new ArrayList<>();
    while (!(this.outbound.isEmpty() && other.outbound.isEmpty())) {
      while (!this.outbound.isEmpty()) {
        TestMessage msg = this.nextOutbound();
        ret.add(msg);
        other.deliver(msg);
      }
      while (!other.outbound.isEmpty()) {
        TestMessage msg = other.nextOutbound();
        ret.add(msg);
        this.deliver(msg);
      }
    }
    return ret;
  }

  private static DiscoveryManagerImpl createTestManager(int seed) {
    ECKeyPair keyPair = Functions.generateECKeyPair(new Random(seed));
    final Bytes privateKey =
        Bytes.wrap(Utils.extractBytesFromUnsignedBigInt(keyPair.getPrivateKey(), PRIVKEY_SIZE));

    final NodeRecord nodeRecord =
        new NodeRecordBuilder().privateKey(privateKey).address(LOCALHOST, 9000 + seed).build();
    DiscoverySystemBuilder builder = new DiscoverySystemBuilder();
    builder
        .discoveryServer(Mockito.mock(NettyDiscoveryServer.class))
        .localNodeRecord(nodeRecord)
        .privateKey(privateKey)
        .retryTimeout(RETRY_TIMEOUT)
        .lifeCheckInterval(LIVE_CHECK_INTERVAL);
    DiscoveryManagerImpl mgr = builder.buildDiscoveryManager();
    mgr.getIncomingPipeline().build();
    mgr.getOutgoingPipeline().build();
    return mgr;
  }
}
