/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs;

import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_CACHE_DROP_BEHIND_READS;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_CACHE_DROP_BEHIND_WRITES;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_CACHE_READAHEAD;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_CONTEXT;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_CONTEXT_DEFAULT;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_LOCAL_INTERFACES;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_SERVER_DEFAULTS_VALIDITY_PERIOD_MS_DEFAULT;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_SERVER_DEFAULTS_VALIDITY_PERIOD_MS_KEY;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_DEFAULT;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CryptoCodec;
import org.apache.hadoop.crypto.CryptoInputStream;
import org.apache.hadoop.crypto.CryptoOutputStream;
import org.apache.hadoop.crypto.key.KeyProvider;
import org.apache.hadoop.crypto.key.KeyProvider.KeyVersion;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.BlockStorageLocation;
import org.apache.hadoop.fs.CacheFlag;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileEncryptionInfo;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.FsStatus;
import org.apache.hadoop.fs.FsTracer;
import org.apache.hadoop.fs.HdfsBlockLocation;
import org.apache.hadoop.fs.InvalidPathException;
import org.apache.hadoop.fs.MD5MD5CRC32CastagnoliFileChecksum;
import org.apache.hadoop.fs.MD5MD5CRC32FileChecksum;
import org.apache.hadoop.fs.MD5MD5CRC32GzipFileChecksum;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Options.ChecksumOpt;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathIsNotEmptyDirectoryException;
import org.apache.hadoop.fs.QuotaUsage;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.fs.VolumeId;
import org.apache.hadoop.fs.XAttr;
import org.apache.hadoop.fs.XAttrSetFlag;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.ha.HAServiceProtocol;
import org.apache.hadoop.hdfs.NameNodeProxiesClient.ProxyAndInfo;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.client.HdfsDataInputStream;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream;
import org.apache.hadoop.hdfs.client.impl.DfsClientConf;
import org.apache.hadoop.hdfs.client.impl.LeaseRenewer;
import org.apache.hadoop.hdfs.net.Peer;
import org.apache.hadoop.hdfs.protocol.AclException;
import org.apache.hadoop.hdfs.protocol.BlockStoragePolicy;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveEntry;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveInfo;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveIterator;
import org.apache.hadoop.hdfs.protocol.CachePoolEntry;
import org.apache.hadoop.hdfs.protocol.CachePoolInfo;
import org.apache.hadoop.hdfs.protocol.CachePoolIterator;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.CorruptFileBlocks;
import org.apache.hadoop.hdfs.protocol.DSQuotaExceededException;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.EncryptionZone;
import org.apache.hadoop.hdfs.protocol.EncryptionZoneIterator;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsBlocksMetadata;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.RollingUpgradeAction;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LastBlockWithStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.NSQuotaExceededException;
import org.apache.hadoop.hdfs.protocol.OpenFileEntry;
import org.apache.hadoop.hdfs.protocol.OpenFilesIterator;
import org.apache.hadoop.hdfs.protocol.QuotaByStorageTypeExceededException;
import org.apache.hadoop.hdfs.protocol.RollingUpgradeInfo;
import org.apache.hadoop.hdfs.protocol.SnapshotAccessControlException;
import org.apache.hadoop.hdfs.protocol.SnapshotDiffReport;
import org.apache.hadoop.hdfs.protocol.SnapshottableDirectoryStatus;
import org.apache.hadoop.hdfs.protocol.UnresolvedPathException;
import org.apache.hadoop.hdfs.protocol.datatransfer.DataTransferProtoUtil;
import org.apache.hadoop.hdfs.protocol.datatransfer.IOStreamPair;
import org.apache.hadoop.hdfs.protocol.datatransfer.Op;
import org.apache.hadoop.hdfs.protocol.datatransfer.ReplaceDatanodeOnFailure;
import org.apache.hadoop.hdfs.protocol.datatransfer.Sender;
import org.apache.hadoop.hdfs.protocol.datatransfer.TrustedChannelResolver;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.DataEncryptionKeyFactory;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.DataTransferSaslUtil;
import org.apache.hadoop.hdfs.protocol.datatransfer.sasl.SaslDataTransferClient;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.BlockOpResponseProto;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.OpBlockChecksumResponseProto;
import org.apache.hadoop.hdfs.protocolPB.PBHelperClient;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.security.token.block.DataEncryptionKey;
import org.apache.hadoop.hdfs.security.token.block.InvalidBlockTokenException;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.server.datanode.CachingStrategy;
import org.apache.hadoop.hdfs.server.namenode.SafeModeException;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorageReport;
import org.apache.hadoop.hdfs.util.IOUtilsClient;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.EnumSetWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.MD5Hash;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.retry.LossyRetryInvocationHandler;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.ipc.RetriableException;
import org.apache.hadoop.ipc.RpcNoSuchMethodException;
import org.apache.hadoop.net.DNS;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenRenewer;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.DataChecksum.Type;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.Time;
import org.apache.htrace.core.TraceScope;
import org.apache.htrace.core.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.net.InetAddresses;

/********************************************************
 * DFSClient can connect to a Hadoop Filesystem and
 * perform basic file tasks.  It uses the ClientProtocol
 * to communicate with a NameNode daemon, and connects
 * directly to DataNodes to read/write block data.
 *
 * Hadoop DFS users should obtain an instance of
 * DistributedFileSystem, which uses DFSClient to handle
 * filesystem tasks.
 *
 ********************************************************/
@InterfaceAudience.Private
public class DFSClient implements java.io.Closeable, RemotePeerFactory,
    DataEncryptionKeyFactory {
  public static final Logger LOG = LoggerFactory.getLogger(DFSClient.class);

  private final Configuration conf;
  private final Tracer tracer;
  private final DfsClientConf dfsClientConf;
  final ClientProtocol namenode;
  /* The service used for delegation tokens */
  private Text dtService;

  final UserGroupInformation ugi;
  volatile boolean clientRunning = true;
  volatile long lastLeaseRenewal;
  private volatile FsServerDefaults serverDefaults;
  private volatile long serverDefaultsLastUpdate;
  final String clientName;
  final SocketFactory socketFactory;
  final ReplaceDatanodeOnFailure dtpReplaceDatanodeOnFailure;
  final short dtpReplaceDatanodeOnFailureReplication;
  private final FileSystem.Statistics stats;
  private final URI namenodeUri;
  private final Random r = new Random();
  private SocketAddress[] localInterfaceAddrs;
  private DataEncryptionKey encryptionKey;
  final SaslDataTransferClient saslClient;
  private final CachingStrategy defaultReadCachingStrategy;
  private final CachingStrategy defaultWriteCachingStrategy;
  private final ClientContext clientContext;

  private static final DFSHedgedReadMetrics HEDGED_READ_METRIC =
      new DFSHedgedReadMetrics();
  private static ThreadPoolExecutor HEDGED_READ_THREAD_POOL;
  private final int smallBufferSize;
  private final long serverDefaultsValidityPeriod;

  /**
   * Disabled stop DeadNodeDetectorThread for the testing when MiniDFSCluster
   * start.
   */
  private static volatile boolean disabledStopDeadNodeDetectorThreadForTest =
      false;

  @VisibleForTesting
  public static void setDisabledStopDeadNodeDetectorThreadForTest(
      boolean disabledStopDeadNodeDetectorThreadForTest) {
    DFSClient.disabledStopDeadNodeDetectorThreadForTest =
        disabledStopDeadNodeDetectorThreadForTest;
  }

  public DfsClientConf getConf() {
    return dfsClientConf;
  }

  Configuration getConfiguration() {
    return conf;
  }

  /**
   * A map from file names to {@link DFSOutputStream} objects
   * that are currently being written by this client.
   * Note that a file can only be written by a single client.
   */
  private final Map<Long, DFSOutputStream> filesBeingWritten = new HashMap<>();

  /**
   * Same as this(NameNode.getNNAddress(conf), conf);
   * @see #DFSClient(InetSocketAddress, Configuration)
   * @deprecated Deprecated at 0.21
   */
  @Deprecated
  public DFSClient(Configuration conf) throws IOException {
    this(DFSUtilClient.getNNAddress(conf), conf);
  }

  public DFSClient(InetSocketAddress address, Configuration conf)
      throws IOException {
    this(DFSUtilClient.getNNUri(address), conf);
  }

  /**
   * Same as this(nameNodeUri, conf, null);
   * @see #DFSClient(URI, Configuration, FileSystem.Statistics)
   */
  public DFSClient(URI nameNodeUri, Configuration conf) throws IOException {
    this(nameNodeUri, conf, null);
  }

  /**
   * Same as this(nameNodeUri, null, conf, stats);
   * @see #DFSClient(URI, ClientProtocol, Configuration, FileSystem.Statistics)
   */
  public DFSClient(URI nameNodeUri, Configuration conf,
      FileSystem.Statistics stats) throws IOException {
    this(nameNodeUri, null, conf, stats);
  }

  /**
   * Create a new DFSClient connected to the given nameNodeUri or rpcNamenode.
   * If HA is enabled and a positive value is set for
   * {@link HdfsClientConfigKeys#DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY}
   * in the configuration, the DFSClient will use
   * {@link LossyRetryInvocationHandler} as its RetryInvocationHandler.
   * Otherwise one of nameNodeUri or rpcNamenode must be null.
   */
  @VisibleForTesting
  public DFSClient(URI nameNodeUri, ClientProtocol rpcNamenode,
      Configuration conf, FileSystem.Statistics stats) throws IOException {
    // Copy only the required DFSClient configuration
    this.tracer = FsTracer.get(conf);
    this.dfsClientConf = new DfsClientConf(conf);
    this.conf = conf;
    this.stats = stats;
    this.socketFactory = NetUtils.getSocketFactory(conf, ClientProtocol.class);
    this.dtpReplaceDatanodeOnFailure = ReplaceDatanodeOnFailure.get(conf);
    this.smallBufferSize = DFSUtilClient.getSmallBufferSize(conf);
    this.dtpReplaceDatanodeOnFailureReplication = (short) conf
        .getInt(HdfsClientConfigKeys.BlockWrite.ReplaceDatanodeOnFailure.
                MIN_REPLICATION,
            HdfsClientConfigKeys.BlockWrite.ReplaceDatanodeOnFailure.
                MIN_REPLICATION_DEFAULT);
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "Sets " + HdfsClientConfigKeys.BlockWrite.ReplaceDatanodeOnFailure.
              MIN_REPLICATION + " to "
              + dtpReplaceDatanodeOnFailureReplication);
    }
    this.ugi = UserGroupInformation.getCurrentUser();

    this.namenodeUri = nameNodeUri;
    this.clientName = "DFSClient_" + dfsClientConf.getTaskId() + "_" +
        ThreadLocalRandom.current().nextInt()  + "_" +
        Thread.currentThread().getId();
    int numResponseToDrop = conf.getInt(
        DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY,
        DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_DEFAULT);
    ProxyAndInfo<ClientProtocol> proxyInfo = null;
    AtomicBoolean nnFallbackToSimpleAuth = new AtomicBoolean(false);

    if (numResponseToDrop > 0) {
      // This case is used for testing.
      LOG.warn(DFS_CLIENT_TEST_DROP_NAMENODE_RESPONSE_NUM_KEY
          + " is set to " + numResponseToDrop
          + ", this hacked client will proactively drop responses");
      proxyInfo = NameNodeProxiesClient.createProxyWithLossyRetryHandler(conf,
          nameNodeUri, ClientProtocol.class, numResponseToDrop,
          nnFallbackToSimpleAuth);
    }

    if (proxyInfo != null) {
      this.dtService = proxyInfo.getDelegationTokenService();
      this.namenode = proxyInfo.getProxy();
    } else if (rpcNamenode != null) {
      // This case is used for testing.
      Preconditions.checkArgument(nameNodeUri == null);
      this.namenode = rpcNamenode;
      dtService = null;
    } else {
      Preconditions.checkArgument(nameNodeUri != null,
          "null URI");
      proxyInfo = NameNodeProxiesClient.createProxyWithClientProtocol(conf,
          nameNodeUri, nnFallbackToSimpleAuth);
      this.dtService = proxyInfo.getDelegationTokenService();
      this.namenode = proxyInfo.getProxy();
    }

    String localInterfaces[] =
        conf.getTrimmedStrings(DFS_CLIENT_LOCAL_INTERFACES);
    localInterfaceAddrs = getLocalInterfaceAddrs(localInterfaces);
    if (LOG.isDebugEnabled() && 0 != localInterfaces.length) {
      LOG.debug("Using local interfaces [" +
          Joiner.on(',').join(localInterfaces)+ "] with addresses [" +
          Joiner.on(',').join(localInterfaceAddrs) + "]");
    }

    Boolean readDropBehind =
        (conf.get(DFS_CLIENT_CACHE_DROP_BEHIND_READS) == null) ?
            null : conf.getBoolean(DFS_CLIENT_CACHE_DROP_BEHIND_READS, false);
    Long readahead = (conf.get(DFS_CLIENT_CACHE_READAHEAD) == null) ?
        null : conf.getLong(DFS_CLIENT_CACHE_READAHEAD, 0);
    this.serverDefaultsValidityPeriod =
            conf.getLong(DFS_CLIENT_SERVER_DEFAULTS_VALIDITY_PERIOD_MS_KEY,
      DFS_CLIENT_SERVER_DEFAULTS_VALIDITY_PERIOD_MS_DEFAULT);
    Boolean writeDropBehind =
        (conf.get(DFS_CLIENT_CACHE_DROP_BEHIND_WRITES) == null) ?
            null : conf.getBoolean(DFS_CLIENT_CACHE_DROP_BEHIND_WRITES, false);
    this.defaultReadCachingStrategy =
        new CachingStrategy(readDropBehind, readahead);
    this.defaultWriteCachingStrategy =
        new CachingStrategy(writeDropBehind, readahead);
    this.clientContext = ClientContext.get(
        conf.get(DFS_CLIENT_CONTEXT, DFS_CLIENT_CONTEXT_DEFAULT),
        dfsClientConf, conf);

    if (dfsClientConf.getHedgedReadThreadpoolSize() > 0) {
      this.initThreadsNumForHedgedReads(dfsClientConf.getHedgedReadThreadpoolSize());
    }
    this.saslClient = new SaslDataTransferClient(
        conf, DataTransferSaslUtil.getSaslPropertiesResolver(conf),
        TrustedChannelResolver.getInstance(conf), nnFallbackToSimpleAuth);
  }

  /**
   * Return the socket addresses to use with each configured
   * local interface. Local interfaces may be specified by IP
   * address, IP address range using CIDR notation, interface
   * name (e.g. eth0) or sub-interface name (e.g. eth0:0).
   * The socket addresses consist of the IPs for the interfaces
   * and the ephemeral port (port 0). If an IP, IP range, or
   * interface name matches an interface with sub-interfaces
   * only the IP of the interface is used. Sub-interfaces can
   * be used by specifying them explicitly (by IP or name).
   *
   * @return SocketAddresses for the configured local interfaces,
   *    or an empty array if none are configured
   * @throws UnknownHostException if a given interface name is invalid
   */
  private static SocketAddress[] getLocalInterfaceAddrs(
      String interfaceNames[]) throws UnknownHostException {
    List<SocketAddress> localAddrs = new ArrayList<>();
    for (String interfaceName : interfaceNames) {
      if (InetAddresses.isInetAddress(interfaceName)) {
        localAddrs.add(new InetSocketAddress(interfaceName, 0));
      } else if (NetUtils.isValidSubnet(interfaceName)) {
        for (InetAddress addr : NetUtils.getIPs(interfaceName, false)) {
          localAddrs.add(new InetSocketAddress(addr, 0));
        }
      } else {
        for (String ip : DNS.getIPs(interfaceName, false)) {
          localAddrs.add(new InetSocketAddress(ip, 0));
        }
      }
    }
    return localAddrs.toArray(new SocketAddress[localAddrs.size()]);
  }

  /**
   * Select one of the configured local interfaces at random. We use a random
   * interface because other policies like round-robin are less effective
   * given that we cache connections to datanodes.
   *
   * @return one of the local interface addresses at random, or null if no
   *    local interfaces are configured
   */
  SocketAddress getRandomLocalInterfaceAddr() {
    if (localInterfaceAddrs.length == 0) {
      return null;
    }
    final int idx = r.nextInt(localInterfaceAddrs.length);
    final SocketAddress addr = localInterfaceAddrs[idx];
    LOG.debug("Using local interface {}", addr);
    return addr;
  }

  /**
   * Return the timeout that clients should use when writing to datanodes.
   * @param numNodes the number of nodes in the pipeline.
   */
  int getDatanodeWriteTimeout(int numNodes) {
    final int t = dfsClientConf.getDatanodeSocketWriteTimeout();
    return t > 0? t + HdfsConstants.WRITE_TIMEOUT_EXTENSION*numNodes: 0;
  }

  int getDatanodeReadTimeout(int numNodes) {
    final int t = dfsClientConf.getSocketTimeout();
    return t > 0? HdfsConstants.READ_TIMEOUT_EXTENSION*numNodes + t: 0;
  }

  @VisibleForTesting
  public String getClientName() {
    return clientName;
  }

  void checkOpen() throws IOException {
    if (!clientRunning) {
      throw new IOException("Filesystem closed");
    }
  }

  /** Return the lease renewer instance. The renewer thread won't start
   *  until the first output stream is created. The same instance will
   *  be returned until all output streams are closed.
   */
  public LeaseRenewer getLeaseRenewer() {
    return LeaseRenewer.getInstance(
        namenodeUri != null ? namenodeUri.getAuthority() : "null", ugi, this);
  }

  /** Get a lease and start automatic renewal */
  private void beginFileLease(final long inodeId, final DFSOutputStream out)
      throws IOException {
    synchronized (filesBeingWritten) {
      putFileBeingWritten(inodeId, out);
      getLeaseRenewer().put(this);
    }
  }

  /** Stop renewal of lease for the file. */
  void endFileLease(final long inodeId) {
    synchronized (filesBeingWritten) {
      removeFileBeingWritten(inodeId);
      // remove client from renewer if no files are open
      if (filesBeingWritten.isEmpty()) {
        getLeaseRenewer().closeClient(this);
      }
    }
  }


  /** Put a file. Only called from LeaseRenewer, where proper locking is
   *  enforced to consistently update its local dfsclients array and
   *  client's filesBeingWritten map.
   */
  public void putFileBeingWritten(final long inodeId,
      final DFSOutputStream out) {
    synchronized(filesBeingWritten) {
      filesBeingWritten.put(inodeId, out);
      // update the last lease renewal time only when there was no
      // writes. once there is one write stream open, the lease renewer
      // thread keeps it updated well with in anyone's expiration time.
      if (lastLeaseRenewal == 0) {
        updateLastLeaseRenewal();
      }
    }
  }

  /** Remove a file. Only called from LeaseRenewer. */
  public void removeFileBeingWritten(final long inodeId) {
    synchronized(filesBeingWritten) {
      filesBeingWritten.remove(inodeId);
      if (filesBeingWritten.isEmpty()) {
        lastLeaseRenewal = 0;
      }
    }
  }

  /** Is file-being-written map empty? */
  public boolean isFilesBeingWrittenEmpty() {
    synchronized(filesBeingWritten) {
      return filesBeingWritten.isEmpty();
    }
  }

  /** @return true if the client is running */
  public boolean isClientRunning() {
    return clientRunning;
  }

  long getLastLeaseRenewal() {
    return lastLeaseRenewal;
  }

  void updateLastLeaseRenewal() {
    synchronized(filesBeingWritten) {
      if (filesBeingWritten.isEmpty()) {
        return;
      }
      lastLeaseRenewal = Time.monotonicNow();
    }
  }

  /**
   * Renew leases.
   * @return true if lease was renewed. May return false if this
   * client has been closed or has no files open.
   **/
  public boolean renewLease() throws IOException {
    if (clientRunning && !isFilesBeingWrittenEmpty()) {
      try {
        namenode.renewLease(clientName);
        updateLastLeaseRenewal();
        return true;
      } catch (IOException e) {
        // Abort if the lease has already expired.
        final long elapsed = Time.monotonicNow() - getLastLeaseRenewal();
        if (elapsed > dfsClientConf.getleaseHardLimitPeriod()) {
          LOG.warn("Failed to renew lease for " + clientName + " for "
              + (elapsed/1000) + " seconds (>= hard-limit ="
              + (dfsClientConf.getleaseHardLimitPeriod() / 1000) + " seconds.) "
              + "Closing all files being written ...", e);
          closeAllFilesBeingWritten(true);
        } else {
          // Let the lease renewer handle it and retry.
          throw e;
        }
      }
    }
    return false;
  }

  /**
   * Close connections the Namenode.
   */
  void closeConnectionToNamenode() {
    RPC.stopProxy(namenode);
  }

  /** Close/abort all files being written. */
  public void closeAllFilesBeingWritten(final boolean abort) {
    for(;;) {
      final long inodeId;
      final DFSOutputStream out;
      synchronized(filesBeingWritten) {
        if (filesBeingWritten.isEmpty()) {
          return;
        }
        inodeId = filesBeingWritten.keySet().iterator().next();
        out = filesBeingWritten.remove(inodeId);
      }
      if (out != null) {
        try {
          if (abort) {
            out.abort();
          } else {
            out.close();
          }
        } catch(IOException ie) {
          LOG.error("Failed to " + (abort ? "abort" : "close") + " file: "
              + out.getSrc() + " with inode: " + inodeId, ie);
        }
      }
    }
  }

  /**
   * Close the file system, abandoning all of the leases and files being
   * created and close connections to the namenode.
   */
  @Override
  public synchronized void close() throws IOException {
    if(clientRunning) {
      // lease renewal stops when all files are closed
      closeAllFilesBeingWritten(false);
      clientRunning = false;
      // close dead node detector thread
      if (!disabledStopDeadNodeDetectorThreadForTest) {
        clientContext.stopDeadNodeDetectorThread();
      }

      // close connections to the namenode
      closeConnectionToNamenode();
    }
  }

  /**
   * Close all open streams, abandoning all of the leases and files being
   * created.
   * @param abort whether streams should be gracefully closed
   */
  public void closeOutputStreams(boolean abort) {
    if (clientRunning) {
      closeAllFilesBeingWritten(abort);
    }
  }

  /**
   * @see ClientProtocol#getPreferredBlockSize(String)
   */
  public long getBlockSize(String f) throws IOException {
    try (TraceScope ignored = newPathTraceScope("getBlockSize", f)) {
      return namenode.getPreferredBlockSize(f);
    } catch (IOException ie) {
      LOG.warn("Problem getting block size", ie);
      throw ie;
    }
  }

  /**
   * Get server default values for a number of configuration params.
   * @see ClientProtocol#getServerDefaults()
   */
  public FsServerDefaults getServerDefaults() throws IOException {
    long now = Time.monotonicNow();
    if ((serverDefaults == null) ||
        (now - serverDefaultsLastUpdate > serverDefaultsValidityPeriod)) {
      serverDefaults = namenode.getServerDefaults();
      serverDefaultsLastUpdate = now;
    }
    assert serverDefaults != null;
    return serverDefaults;
  }

  /**
   * Get a canonical token service name for this client's tokens.  Null should
   * be returned if the client is not using tokens.
   * @return the token service for the client
   */
  @InterfaceAudience.LimitedPrivate( { "HDFS" })
  public String getCanonicalServiceName() {
    return (dtService != null) ? dtService.toString() : null;
  }

  /**
   * @see ClientProtocol#getDelegationToken(Text)
   */
  public Token<DelegationTokenIdentifier> getDelegationToken(Text renewer)
      throws IOException {
    assert dtService != null;
    try (TraceScope ignored = tracer.newScope("getDelegationToken")) {
      Token<DelegationTokenIdentifier> token =
          namenode.getDelegationToken(renewer);
      if (token != null) {
        token.setService(this.dtService);
        LOG.info("Created " + DelegationTokenIdentifier.stringifyToken(token));
      } else {
        LOG.info("Cannot get delegation token from " + renewer);
      }
      return token;
    }
  }

  /**
   * Renew a delegation token
   * @param token the token to renew
   * @return the new expiration time
   * @throws IOException
   * @deprecated Use Token.renew instead.
   */
  @Deprecated
  public long renewDelegationToken(Token<DelegationTokenIdentifier> token)
      throws IOException {
    LOG.info("Renewing " + DelegationTokenIdentifier.stringifyToken(token));
    try {
      return token.renew(conf);
    } catch (InterruptedException ie) {
      throw new RuntimeException("caught interrupted", ie);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(InvalidToken.class,
          AccessControlException.class);
    }
  }

  /**
   * Cancel a delegation token
   * @param token the token to cancel
   * @throws IOException
   * @deprecated Use Token.cancel instead.
   */
  @Deprecated
  public void cancelDelegationToken(Token<DelegationTokenIdentifier> token)
      throws IOException {
    LOG.info("Cancelling " + DelegationTokenIdentifier.stringifyToken(token));
    try {
      token.cancel(conf);
    } catch (InterruptedException ie) {
      throw new RuntimeException("caught interrupted", ie);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(InvalidToken.class,
          AccessControlException.class);
    }
  }

  @InterfaceAudience.Private
  public static class Renewer extends TokenRenewer {

    static {
      //Ensure that HDFS Configuration files are loaded before trying to use
      // the renewer.
      HdfsConfiguration.init();
    }

    @Override
    public boolean handleKind(Text kind) {
      return DelegationTokenIdentifier.HDFS_DELEGATION_KIND.equals(kind);
    }

    @SuppressWarnings("unchecked")
    @Override
    public long renew(Token<?> token, Configuration conf) throws IOException {
      Token<DelegationTokenIdentifier> delToken =
          (Token<DelegationTokenIdentifier>) token;
      ClientProtocol nn = getNNProxy(delToken, conf);
      try {
        return nn.renewDelegationToken(delToken);
      } catch (RemoteException re) {
        throw re.unwrapRemoteException(InvalidToken.class,
            AccessControlException.class);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void cancel(Token<?> token, Configuration conf) throws IOException {
      Token<DelegationTokenIdentifier> delToken =
          (Token<DelegationTokenIdentifier>) token;
      LOG.info("Cancelling " +
          DelegationTokenIdentifier.stringifyToken(delToken));
      ClientProtocol nn = getNNProxy(delToken, conf);
      try {
        nn.cancelDelegationToken(delToken);
      } catch (RemoteException re) {
        throw re.unwrapRemoteException(InvalidToken.class,
            AccessControlException.class);
      }
    }

    private static ClientProtocol getNNProxy(
        Token<DelegationTokenIdentifier> token, Configuration conf)
        throws IOException {
      URI uri = HAUtilClient.getServiceUriFromToken(
          HdfsConstants.HDFS_URI_SCHEME, token);
      if (HAUtilClient.isTokenForLogicalUri(token) &&
          !HAUtilClient.isLogicalUri(conf, uri)) {
        // If the token is for a logical nameservice, but the configuration
        // we have disagrees about that, we can't actually renew it.
        // This can be the case in MR, for example, if the RM doesn't
        // have all of the HA clusters configured in its configuration.
        throw new IOException("Unable to map logical nameservice URI '" +
            uri + "' to a NameNode. Local configuration does not have " +
            "a failover proxy provider configured.");
      }

      ProxyAndInfo<ClientProtocol> info =
          NameNodeProxiesClient.createProxyWithClientProtocol(conf, uri, null);
      assert info.getDelegationTokenService().equals(token.getService()) :
          "Returned service '" + info.getDelegationTokenService().toString() +
              "' doesn't match expected service '" +
              token.getService().toString() + "'";

      return info.getProxy();
    }

    @Override
    public boolean isManaged(Token<?> token) throws IOException {
      return true;
    }

  }

  /**
   * Report corrupt blocks that were discovered by the client.
   * @see ClientProtocol#reportBadBlocks(LocatedBlock[])
   */
  public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
    namenode.reportBadBlocks(blocks);
  }

  public LocatedBlocks getLocatedBlocks(String src, long start)
      throws IOException {
    return getLocatedBlocks(src, start, dfsClientConf.getPrefetchSize());
  }

  /*
   * This is just a wrapper around callGetBlockLocations, but non-static so that
   * we can stub it out for tests.
   */
  @VisibleForTesting
  public LocatedBlocks getLocatedBlocks(String src, long start, long length)
      throws IOException {
    try (TraceScope ignored = newPathTraceScope("getBlockLocations", src)) {
      return callGetBlockLocations(namenode, src, start, length);
    }
  }

  /**
   * @see ClientProtocol#getBlockLocations(String, long, long)
   */
  static LocatedBlocks callGetBlockLocations(ClientProtocol namenode,
      String src, long start, long length)
      throws IOException {
    try {
      return namenode.getBlockLocations(src, start, length);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  /**
   * Recover a file's lease
   * @param src a file's path
   * @return true if the file is already closed
   * @throws IOException
   */
  boolean recoverLease(String src) throws IOException {
    checkOpen();

    try (TraceScope ignored = newPathTraceScope("recoverLease", src)) {
      return namenode.recoverLease(src, clientName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(FileNotFoundException.class,
          AccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  /**
   * Get block location info about file
   *
   * getBlockLocations() returns a list of hostnames that store
   * data for a specific file region.  It returns a set of hostnames
   * for every block within the indicated region.
   *
   * This function is very useful when writing code that considers
   * data-placement when performing operations.  For example, the
   * MapReduce system tries to schedule tasks on the same machines
   * as the data-block the task processes.
   */
  public BlockLocation[] getBlockLocations(String src, long start,
      long length) throws IOException {
    try (TraceScope ignored = newPathTraceScope("getBlockLocations", src)) {
      LocatedBlocks blocks = getLocatedBlocks(src, start, length);
      BlockLocation[] locations = DFSUtilClient.locatedBlocks2Locations(blocks);
      HdfsBlockLocation[] hdfsLocations =
          new HdfsBlockLocation[locations.length];
      for (int i = 0; i < locations.length; i++) {
        hdfsLocations[i] = new HdfsBlockLocation(locations[i], blocks.get(i));
      }
      return hdfsLocations;
    }
  }

  /**
   * Get block location information about a list of {@link HdfsBlockLocation}.
   * Used by {@link DistributedFileSystem#getFileBlockStorageLocations(List)} to
   * get {@link BlockStorageLocation}s for blocks returned by
   * {@link DistributedFileSystem#getFileBlockLocations(org.apache.hadoop.fs.FileStatus, long, long)}
   * .
   * 
   * This is done by making a round of RPCs to the associated datanodes, asking
   * the volume of each block replica. The returned array of
   * {@link BlockStorageLocation} expose this information as a
   * {@link VolumeId}.
   * 
   * @param blockLocations
   *          target blocks on which to query volume location information
   * @return volumeBlockLocations original block array augmented with additional
   *         volume location information for each replica.
   */
  public BlockStorageLocation[] getBlockStorageLocations(
      List<BlockLocation> blockLocations) throws IOException,
      UnsupportedOperationException, InvalidBlockTokenException {
    if (!getConf().isHdfsBlocksMetadataEnabled()) {
      throw new UnsupportedOperationException("Datanode-side support for " +
          "getVolumeBlockLocations() must also be enabled in the client " +
          "configuration.");
    }
    // Downcast blockLocations and fetch out required LocatedBlock(s)
    List<LocatedBlock> blocks = new ArrayList<LocatedBlock>();
    for (BlockLocation loc : blockLocations) {
      if (!(loc instanceof HdfsBlockLocation)) {
        throw new ClassCastException("DFSClient#getVolumeBlockLocations " +
            "expected to be passed HdfsBlockLocations");
      }
      HdfsBlockLocation hdfsLoc = (HdfsBlockLocation) loc;
      blocks.add(hdfsLoc.getLocatedBlock());
    }
    
    // Re-group the LocatedBlocks to be grouped by datanodes, with the values
    // a list of the LocatedBlocks on the datanode.
    Map<DatanodeInfo, List<LocatedBlock>> datanodeBlocks = 
        new LinkedHashMap<DatanodeInfo, List<LocatedBlock>>();
    for (LocatedBlock b : blocks) {
      for (DatanodeInfo info : b.getLocations()) {
        if (!datanodeBlocks.containsKey(info)) {
          datanodeBlocks.put(info, new ArrayList<LocatedBlock>());
        }
        List<LocatedBlock> l = datanodeBlocks.get(info);
        l.add(b);
      }
    }
        
    // Make RPCs to the datanodes to get volume locations for its replicas
    TraceScope scope =
      tracer.newScope("getBlockStorageLocations");
    Map<DatanodeInfo, HdfsBlocksMetadata> metadatas;
    try {
      metadatas = BlockStorageLocationUtil.
          queryDatanodesForHdfsBlocksMetadata(conf, datanodeBlocks,
              getConf().getFileBlockStorageLocationsNumThreads(),
              getConf().getFileBlockStorageLocationsTimeoutMs(),
              getConf().isConnectToDnViaHostname(), tracer, scope.getSpanId());
      if (LOG.isTraceEnabled()) {
        LOG.trace("metadata returned: "
            + Joiner.on("\n").withKeyValueSeparator("=").join(metadatas));
      }
    } finally {
      scope.close();
    }
    
    // Regroup the returned VolumeId metadata to again be grouped by
    // LocatedBlock rather than by datanode
    Map<LocatedBlock, List<VolumeId>> blockVolumeIds = BlockStorageLocationUtil
        .associateVolumeIdsWithBlocks(blocks, metadatas);
    
    // Combine original BlockLocations with new VolumeId information
    BlockStorageLocation[] volumeBlockLocations = BlockStorageLocationUtil
        .convertToVolumeBlockLocations(blocks, blockVolumeIds);

    return volumeBlockLocations;
  }

  /**
   * Wraps the stream in a CryptoInputStream if the underlying file is
   * encrypted.
   */
  public HdfsDataInputStream createWrappedInputStream(DFSInputStream dfsis)
      throws IOException {
    FileEncryptionInfo feInfo = dfsis.getFileEncryptionInfo();
    if (feInfo != null) {
      CryptoInputStream cryptoIn;
      try (TraceScope ignored = getTracer().newScope("decryptEDEK")) {
        cryptoIn = HdfsKMSUtil.createWrappedInputStream(dfsis,
            getKeyProvider(), feInfo, getConfiguration());
      }
      return new HdfsDataInputStream(cryptoIn);
    } else {
      // No FileEncryptionInfo so no encryption.
      return new HdfsDataInputStream(dfsis);
    }
  }

  /**
   * Wraps the stream in a CryptoOutputStream if the underlying file is
   * encrypted.
   */
  public HdfsDataOutputStream createWrappedOutputStream(DFSOutputStream dfsos,
      FileSystem.Statistics statistics) throws IOException {
    return createWrappedOutputStream(dfsos, statistics, 0);
  }

  /**
   * Wraps the stream in a CryptoOutputStream if the underlying file is
   * encrypted.
   */
  public HdfsDataOutputStream createWrappedOutputStream(DFSOutputStream dfsos,
      FileSystem.Statistics statistics, long startPos) throws IOException {
    final FileEncryptionInfo feInfo = dfsos.getFileEncryptionInfo();
    if (feInfo != null) {
      // File is encrypted, wrap the stream in a crypto stream.
      // Currently only one version, so no special logic based on the version #
      HdfsKMSUtil.getCryptoProtocolVersion(feInfo);
      final CryptoCodec codec = HdfsKMSUtil.getCryptoCodec(conf, feInfo);
      KeyVersion decrypted;
      try (TraceScope ignored = tracer.newScope("decryptEDEK")) {
        decrypted = HdfsKMSUtil.decryptEncryptedDataEncryptionKey(feInfo,
          getKeyProvider());
      }
      final CryptoOutputStream cryptoOut =
          new CryptoOutputStream(dfsos, codec,
              decrypted.getMaterial(), feInfo.getIV(), startPos);
      return new HdfsDataOutputStream(cryptoOut, statistics, startPos);
    } else {
      // No FileEncryptionInfo present so no encryption.
      return new HdfsDataOutputStream(dfsos, statistics, startPos);
    }
  }

  public DFSInputStream open(String src) throws IOException {
    return open(src, dfsClientConf.getIoBufferSize(), true);
  }

  /**
   * Create an input stream that obtains a nodelist from the
   * namenode, and then reads from all the right places.  Creates
   * inner subclass of InputStream that does the right out-of-band
   * work.
   * @deprecated Use {@link #open(String, int, boolean)} instead.
   */
  @Deprecated
  public DFSInputStream open(String src, int buffersize, boolean verifyChecksum,
      FileSystem.Statistics stats) throws IOException {
    return open(src, buffersize, verifyChecksum);
  }


  /**
   * Create an input stream that obtains a nodelist from the
   * namenode, and then reads from all the right places.  Creates
   * inner subclass of InputStream that does the right out-of-band
   * work.
   */
  public DFSInputStream open(String src, int buffersize, boolean verifyChecksum)
      throws IOException {
    checkOpen();
    //    Get block info from namenode
    try (TraceScope ignored = newPathTraceScope("newDFSInputStream", src)) {
      return new DFSInputStream(this, src, verifyChecksum, null);
    }
  }

  /**
   * Get the namenode associated with this DFSClient object
   * @return the namenode associated with this DFSClient object
   */
  public ClientProtocol getNamenode() {
    return namenode;
  }

  /**
   * Call {@link #create(String, boolean, short, long, Progressable)} with
   * default <code>replication</code> and <code>blockSize<code> and null <code>
   * progress</code>.
   */
  public OutputStream create(String src, boolean overwrite)
      throws IOException {
    return create(src, overwrite, dfsClientConf.getDefaultReplication(),
        dfsClientConf.getDefaultBlockSize(), null);
  }

  /**
   * Call {@link #create(String, boolean, short, long, Progressable)} with
   * default <code>replication</code> and <code>blockSize<code>.
   */
  public OutputStream create(String src,
      boolean overwrite, Progressable progress) throws IOException {
    return create(src, overwrite, dfsClientConf.getDefaultReplication(),
        dfsClientConf.getDefaultBlockSize(), progress);
  }

  /**
   * Call {@link #create(String, boolean, short, long, Progressable)} with
   * null <code>progress</code>.
   */
  public OutputStream create(String src, boolean overwrite, short replication,
      long blockSize) throws IOException {
    return create(src, overwrite, replication, blockSize, null);
  }

  /**
   * Call {@link #create(String, boolean, short, long, Progressable, int)}
   * with default bufferSize.
   */
  public OutputStream create(String src, boolean overwrite, short replication,
      long blockSize, Progressable progress) throws IOException {
    return create(src, overwrite, replication, blockSize, progress,
        dfsClientConf.getIoBufferSize());
  }

  /**
   * Call {@link #create(String, FsPermission, EnumSet, short, long,
   * Progressable, int, ChecksumOpt)} with default <code>permission</code>
   * {@link FsPermission#getFileDefault()}.
   *
   * @param src File name
   * @param overwrite overwrite an existing file if true
   * @param replication replication factor for the file
   * @param blockSize maximum block size
   * @param progress interface for reporting client progress
   * @param buffersize underlying buffersize
   *
   * @return output stream
   */
  public OutputStream create(String src, boolean overwrite, short replication,
      long blockSize, Progressable progress, int buffersize)
      throws IOException {
    return create(src, FsPermission.getFileDefault(),
        overwrite ? EnumSet.of(CreateFlag.CREATE, CreateFlag.OVERWRITE)
            : EnumSet.of(CreateFlag.CREATE), replication, blockSize, progress,
        buffersize, null);
  }

  /**
   * Call {@link #create(String, FsPermission, EnumSet, boolean, short,
   * long, Progressable, int, ChecksumOpt)} with <code>createParent</code>
   *  set to true.
   */
  public DFSOutputStream create(String src, FsPermission permission,
      EnumSet<CreateFlag> flag, short replication, long blockSize,
      Progressable progress, int buffersize, ChecksumOpt checksumOpt)
      throws IOException {
    return create(src, permission, flag, true,
        replication, blockSize, progress, buffersize, checksumOpt, null);
  }

  /**
   * Create a new dfs file with the specified block replication
   * with write-progress reporting and return an output stream for writing
   * into the file.
   *
   * @param src File name
   * @param permission The permission of the directory being created.
   *          If null, use default permission
   *          {@link FsPermission#getFileDefault()}
   * @param flag indicates create a new file or create/overwrite an
   *          existing file or append to an existing file
   * @param createParent create missing parent directory if true
   * @param replication block replication
   * @param blockSize maximum block size
   * @param progress interface for reporting client progress
   * @param buffersize underlying buffer size
   * @param checksumOpt checksum options
   *
   * @return output stream
   *
   * @see ClientProtocol#create for detailed description of exceptions thrown
   */
  public DFSOutputStream create(String src, FsPermission permission,
      EnumSet<CreateFlag> flag, boolean createParent, short replication,
      long blockSize, Progressable progress, int buffersize,
      ChecksumOpt checksumOpt) throws IOException {
    return create(src, permission, flag, createParent, replication, blockSize,
        progress, buffersize, checksumOpt, null);
  }

  private FsPermission applyUMask(FsPermission permission) {
    if (permission == null) {
      permission = FsPermission.getFileDefault();
    }
    return permission.applyUMask(dfsClientConf.getUMask());
  }

  private FsPermission applyUMaskDir(FsPermission permission) {
    if (permission == null) {
      permission = FsPermission.getDirDefault();
    }
    return permission.applyUMask(dfsClientConf.getUMask());
  }

  /**
   * Same as {@link #create(String, FsPermission, EnumSet, boolean, short, long,
   * Progressable, int, ChecksumOpt)} with the addition of favoredNodes that is
   * a hint to where the namenode should place the file blocks.
   * The favored nodes hint is not persisted in HDFS. Hence it may be honored
   * at the creation time only. HDFS could move the blocks during balancing or
   * replication, to move the blocks from favored nodes. A value of null means
   * no favored nodes for this create
   */
  public DFSOutputStream create(String src, FsPermission permission,
      EnumSet<CreateFlag> flag, boolean createParent, short replication,
      long blockSize, Progressable progress, int buffersize,
      ChecksumOpt checksumOpt, InetSocketAddress[] favoredNodes)
      throws IOException {
    checkOpen();
    final FsPermission masked = applyUMask(permission);
    LOG.debug("{}: masked={}", src, masked);
    final DFSOutputStream result = DFSOutputStream.newStreamForCreate(this,
        src, masked, flag, createParent, replication, blockSize, progress,
        dfsClientConf.createChecksum(checksumOpt),
        getFavoredNodesStr(favoredNodes));
    beginFileLease(result.getFileId(), result);
    return result;
  }

  private String[] getFavoredNodesStr(InetSocketAddress[] favoredNodes) {
    String[] favoredNodeStrs = null;
    if (favoredNodes != null) {
      favoredNodeStrs = new String[favoredNodes.length];
      for (int i = 0; i < favoredNodes.length; i++) {
        favoredNodeStrs[i] =
            favoredNodes[i].getHostName() + ":" + favoredNodes[i].getPort();
      }
    }
    return favoredNodeStrs;
  }

  /**
   * Append to an existing file if {@link CreateFlag#APPEND} is present
   */
  private DFSOutputStream primitiveAppend(String src, EnumSet<CreateFlag> flag,
      Progressable progress) throws IOException {
    if (flag.contains(CreateFlag.APPEND)) {
      HdfsFileStatus stat = getFileInfo(src);
      if (stat == null) { // No file to append to
        // New file needs to be created if create option is present
        if (!flag.contains(CreateFlag.CREATE)) {
          throw new FileNotFoundException(
              "failed to append to non-existent file " + src + " on client "
                  + clientName);
        }
        return null;
      }
      return callAppend(src, flag, progress, null);
    }
    return null;
  }

  /**
   * Same as {{@link #create(String, FsPermission, EnumSet, short, long,
   *  Progressable, int, ChecksumOpt)} except that the permission
   *  is absolute (ie has already been masked with umask.
   */
  public DFSOutputStream primitiveCreate(String src, FsPermission absPermission,
      EnumSet<CreateFlag> flag, boolean createParent, short replication,
      long blockSize, Progressable progress, int buffersize,
      ChecksumOpt checksumOpt) throws IOException {
    checkOpen();
    CreateFlag.validate(flag);
    DFSOutputStream result = primitiveAppend(src, flag, progress);
    if (result == null) {
      DataChecksum checksum = dfsClientConf.createChecksum(checksumOpt);
      result = DFSOutputStream.newStreamForCreate(this, src, absPermission,
          flag, createParent, replication, blockSize, progress, checksum, null);
    }
    beginFileLease(result.getFileId(), result);
    return result;
  }

  /**
   * Creates a symbolic link.
   *
   * @see ClientProtocol#createSymlink(String, String,FsPermission, boolean)
   */
  public void createSymlink(String target, String link, boolean createParent)
      throws IOException {
    try (TraceScope ignored = newPathTraceScope("createSymlink", target)) {
      final FsPermission dirPerm = applyUMask(null);
      namenode.createSymlink(target, link, dirPerm, createParent);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileAlreadyExistsException.class,
          FileNotFoundException.class,
          ParentNotDirectoryException.class,
          NSQuotaExceededException.class,
          DSQuotaExceededException.class,
          QuotaByStorageTypeExceededException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * Resolve the *first* symlink, if any, in the path.
   *
   * @see ClientProtocol#getLinkTarget(String)
   */
  public String getLinkTarget(String path) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getLinkTarget", path)) {
      return namenode.getLinkTarget(path);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class);
    }
  }

  /**
   * Invoke namenode append RPC.
   * It retries in case of {@link BlockNotYetCompleteException}.
   */
  private LastBlockWithStatus callAppend(String src,
      EnumSetWritable<CreateFlag> flag) throws IOException {
    final long startTime = Time.monotonicNow();
    for(;;) {
      try {
        return namenode.append(src, clientName, flag);
      } catch(RemoteException re) {
        if (Time.monotonicNow() - startTime > 5000
            || !RetriableException.class.getName().equals(
                re.getClassName())) {
          throw re;
        }

        try { // sleep and retry
          Thread.sleep(500);
        } catch (InterruptedException e) {
          throw DFSUtilClient.toInterruptedIOException("callAppend", e);
        }
      }
    }
  }

  /** Method to get stream returned by append call */
  private DFSOutputStream callAppend(String src, EnumSet<CreateFlag> flag,
      Progressable progress, String[] favoredNodes) throws IOException {
    CreateFlag.validateForAppend(flag);
    try {
      final LastBlockWithStatus blkWithStatus = callAppend(src,
          new EnumSetWritable<>(flag, CreateFlag.class));
      HdfsFileStatus status = blkWithStatus.getFileStatus();
      if (status == null) {
        LOG.debug("NameNode is on an older version, request file " +
            "info with additional RPC call for file: {}", src);
        status = getFileInfo(src);
      }
      return DFSOutputStream.newStreamForAppend(this, src, flag, progress,
          blkWithStatus.getLastBlock(), status,
          dfsClientConf.createChecksum(null), favoredNodes);
    } catch(RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          SafeModeException.class,
          DSQuotaExceededException.class,
          QuotaByStorageTypeExceededException.class,
          UnsupportedOperationException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * Append to an existing HDFS file.
   *
   * @param src file name
   * @param buffersize buffer size
   * @param flag indicates whether to append data to a new block instead of
   *             the last block
   * @param progress for reporting write-progress; null is acceptable.
   * @param statistics file system statistics; null is acceptable.
   * @return an output stream for writing into the file
   *
   * @see ClientProtocol#append(String, String, EnumSetWritable)
   */
  public HdfsDataOutputStream append(final String src, final int buffersize,
      EnumSet<CreateFlag> flag, final Progressable progress,
      final FileSystem.Statistics statistics) throws IOException {
    final DFSOutputStream out = append(src, buffersize, flag, null, progress);
    return createWrappedOutputStream(out, statistics, out.getInitialLen());
  }

  /**
   * Append to an existing HDFS file.
   *
   * @param src file name
   * @param buffersize buffer size
   * @param flag indicates whether to append data to a new block instead of the
   *          last block
   * @param progress for reporting write-progress; null is acceptable.
   * @param statistics file system statistics; null is acceptable.
   * @param favoredNodes FavoredNodes for new blocks
   * @return an output stream for writing into the file
   * @see ClientProtocol#append(String, String, EnumSetWritable)
   */
  public HdfsDataOutputStream append(final String src, final int buffersize,
      EnumSet<CreateFlag> flag, final Progressable progress,
      final FileSystem.Statistics statistics,
      final InetSocketAddress[] favoredNodes) throws IOException {
    final DFSOutputStream out = append(src, buffersize, flag,
        getFavoredNodesStr(favoredNodes), progress);
    return createWrappedOutputStream(out, statistics, out.getInitialLen());
  }

  private DFSOutputStream append(String src, int buffersize,
      EnumSet<CreateFlag> flag, String[] favoredNodes, Progressable progress)
      throws IOException {
    checkOpen();
    final DFSOutputStream result = callAppend(src, flag, progress,
        favoredNodes);
    beginFileLease(result.getFileId(), result);
    return result;
  }

  /**
   * Set replication for an existing file.
   * @param src file name
   * @param replication replication to set the file to
   *
   * @see ClientProtocol#setReplication(String, short)
   */
  public boolean setReplication(String src, short replication)
      throws IOException {
    try (TraceScope ignored = newPathTraceScope("setReplication", src)) {
      return namenode.setReplication(src, replication);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          SafeModeException.class,
          DSQuotaExceededException.class,
          QuotaByStorageTypeExceededException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * Set storage policy for an existing file/directory
   * @param src file/directory name
   * @param policyName name of the storage policy
   */
  public void setStoragePolicy(String src, String policyName)
      throws IOException {
    try (TraceScope ignored = newPathTraceScope("setStoragePolicy", src)) {
      namenode.setStoragePolicy(src, policyName);
    } catch (RemoteException e) {
      throw e.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          SafeModeException.class,
          NSQuotaExceededException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * Unset storage policy set for a given file/directory.
   * @param src file/directory name
   */
  public void unsetStoragePolicy(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("unsetStoragePolicy", src)) {
      namenode.unsetStoragePolicy(src);
    } catch (RemoteException e) {
      throw e.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          SafeModeException.class,
          NSQuotaExceededException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * @param path file/directory name
   * @return Get the storage policy for specified path
   */
  public BlockStoragePolicy getStoragePolicy(String path) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getStoragePolicy", path)) {
      return namenode.getStoragePolicy(path);
    } catch (RemoteException e) {
      throw e.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          SafeModeException.class,
          UnresolvedPathException.class);
    }
  }

  /**
   * @return All the existing storage policies
   */
  public BlockStoragePolicy[] getStoragePolicies() throws IOException {
    try (TraceScope ignored = tracer.newScope("getStoragePolicies")) {
      return namenode.getStoragePolicies();
    }
  }

  /**
   * Rename file or directory.
   * @see ClientProtocol#rename(String, String)
   * @deprecated Use {@link #rename(String, String, Options.Rename...)} instead.
   */
  @Deprecated
  public boolean rename(String src, String dst) throws IOException {
    checkOpen();
    try (TraceScope ignored = newSrcDstTraceScope("rename", src, dst)) {
      return namenode.rename(src, dst);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          NSQuotaExceededException.class,
          DSQuotaExceededException.class,
          QuotaByStorageTypeExceededException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * Move blocks from src to trg and delete src
   * See {@link ClientProtocol#concat}.
   */
  public void concat(String trg, String [] srcs) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("concat")) {
      namenode.concat(trg, srcs);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }
  /**
   * Rename file or directory.
   * @see ClientProtocol#rename2(String, String, Options.Rename...)
   */
  public void rename(String src, String dst, Options.Rename... options)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = newSrcDstTraceScope("rename2", src, dst)) {
      namenode.rename2(src, dst, options);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          DSQuotaExceededException.class,
          QuotaByStorageTypeExceededException.class,
          FileAlreadyExistsException.class,
          FileNotFoundException.class,
          ParentNotDirectoryException.class,
          SafeModeException.class,
          NSQuotaExceededException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * Truncate a file to an indicated size
   * See {@link ClientProtocol#truncate}.
   */
  public boolean truncate(String src, long newLength) throws IOException {
    checkOpen();
    if (newLength < 0) {
      throw new HadoopIllegalArgumentException(
          "Cannot truncate to a negative file size: " + newLength + ".");
    }
    try (TraceScope ignored = newPathTraceScope("truncate", src)) {
      return namenode.truncate(src, newLength, clientName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  /**
   * Delete file or directory.
   * See {@link ClientProtocol#delete(String, boolean)}.
   */
  @Deprecated
  public boolean delete(String src) throws IOException {
    checkOpen();
    return delete(src, true);
  }

  /**
   * delete file or directory.
   * delete contents of the directory if non empty and recursive
   * set to true
   *
   * @see ClientProtocol#delete(String, boolean)
   */
  public boolean delete(String src, boolean recursive) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("delete", src)) {
      return namenode.delete(src, recursive);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          SafeModeException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class,
          PathIsNotEmptyDirectoryException.class);
    }
  }

  /** Implemented using getFileInfo(src)
   */
  public boolean exists(String src) throws IOException {
    checkOpen();
    return getFileInfo(src) != null;
  }

  /**
   * Get a partial listing of the indicated directory
   * No block locations need to be fetched
   */
  public DirectoryListing listPaths(String src,  byte[] startAfter)
      throws IOException {
    return listPaths(src, startAfter, false);
  }

  /**
   * Get a partial listing of the indicated directory
   *
   * Recommend to use HdfsFileStatus.EMPTY_NAME as startAfter
   * if the application wants to fetch a listing starting from
   * the first entry in the directory
   *
   * @see ClientProtocol#getListing(String, byte[], boolean)
   */
  public DirectoryListing listPaths(String src,  byte[] startAfter,
      boolean needLocation) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("listPaths", src)) {
      return namenode.getListing(src, startAfter, needLocation);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  /**
   * Get the file info for a specific file or directory.
   * @param src The string representation of the path to the file
   * @return object containing information regarding the file
   *         or null if file not found
   *
   * @see ClientProtocol#getFileInfo(String) for description of exceptions
   */
  public HdfsFileStatus getFileInfo(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getFileInfo", src)) {
      return namenode.getFileInfo(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  /**
   * Close status of a file
   * @return true if file is already closed
   */
  public boolean isFileClosed(String src) throws IOException{
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("isFileClosed", src)) {
      return namenode.isFileClosed(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  /**
   * Get the file info for a specific file or directory. If src
   * refers to a symlink then the FileStatus of the link is returned.
   * @param src path to a file or directory.
   *
   * For description of exceptions thrown
   * @see ClientProtocol#getFileLinkInfo(String)
   */
  public HdfsFileStatus getFileLinkInfo(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getFileLinkInfo", src)) {
      return namenode.getFileLinkInfo(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  @InterfaceAudience.Private
  public void clearDataEncryptionKey() {
    LOG.debug("Clearing encryption key");
    synchronized (this) {
      encryptionKey = null;
    }
  }

  /**
   * @return true if data sent between this client and DNs should be encrypted,
   *         false otherwise.
   * @throws IOException in the event of error communicating with the NN
   */
  boolean shouldEncryptData() throws IOException {
    FsServerDefaults d = getServerDefaults();
    return d != null && d.getEncryptDataTransfer();
  }

  @Override
  public DataEncryptionKey newDataEncryptionKey() throws IOException {
    if (shouldEncryptData()) {
      synchronized (this) {
        if (encryptionKey == null ||
            encryptionKey.expiryDate < Time.now()) {
          LOG.debug("Getting new encryption token from NN");
          encryptionKey = namenode.getDataEncryptionKey();
        }
        return encryptionKey;
      }
    } else {
      return null;
    }
  }

  @VisibleForTesting
  public DataEncryptionKey getEncryptionKey() {
    return encryptionKey;
  }

  /**
   * Get the checksum of the whole file of a range of the file. Note that the
   * range always starts from the beginning of the file.
   * @param src The file path
   * @param length the length of the range, i.e., the range is [0, length]
   * @return The checksum
   * @see DistributedFileSystem#getFileChecksum(Path)
   */
  public MD5MD5CRC32FileChecksum getFileChecksum(String src, long length)
      throws IOException {
    checkOpen();
    Preconditions.checkArgument(length >= 0);
    //get block locations for the file range
    LocatedBlocks blockLocations = callGetBlockLocations(namenode, src, 0,
        length);
    if (null == blockLocations) {
      throw new FileNotFoundException("File does not exist: " + src);
    }
    if (blockLocations.isUnderConstruction()) {
      throw new IOException("Fail to get checksum, since file " + src
          + " is under construction.");
    }
    List<LocatedBlock> locatedblocks = blockLocations.getLocatedBlocks();
    final DataOutputBuffer md5out = new DataOutputBuffer();
    int bytesPerCRC = -1;
    DataChecksum.Type crcType = DataChecksum.Type.DEFAULT;
    long crcPerBlock = 0;
    boolean refetchBlocks = false;
    int lastRetriedIndex = -1;

    // get block checksum for each block
    long remaining = length;
    if (src.contains(HdfsConstants.SEPARATOR_DOT_SNAPSHOT_DIR_SEPARATOR)) {
      remaining = Math.min(length, blockLocations.getFileLength());
    }
    for(int i = 0; i < locatedblocks.size() && remaining > 0; i++) {
      if (refetchBlocks) {  // refetch to get fresh tokens
        blockLocations = callGetBlockLocations(namenode, src, 0, length);
        if (null == blockLocations) {
          throw new FileNotFoundException("File does not exist: " + src);
        }
        if (blockLocations.isUnderConstruction()) {
          throw new IOException("Fail to get checksum, since file " + src
              + " is under construction.");
        }
        locatedblocks = blockLocations.getLocatedBlocks();
        refetchBlocks = false;
      }
      LocatedBlock lb = locatedblocks.get(i);
      final ExtendedBlock block = lb.getBlock();
      if (remaining < block.getNumBytes()) {
        block.setNumBytes(remaining);
      }
      remaining -= block.getNumBytes();
      final DatanodeInfo[] datanodes = lb.getLocations();

      //try each datanode location of the block
      final int timeout = 3000 * datanodes.length +
          dfsClientConf.getSocketTimeout();
      boolean done = false;
      for(int j = 0; !done && j < datanodes.length; j++) {
        DataOutputStream out = null;
        DataInputStream in = null;

        try {
          //connect to a datanode
          IOStreamPair pair = connectToDN(datanodes[j], timeout, lb);
          out = new DataOutputStream(new BufferedOutputStream(pair.out,
              smallBufferSize));
          in = new DataInputStream(pair.in);

          LOG.debug("write to {}: {}, block={}",
              datanodes[j], Op.BLOCK_CHECKSUM, block);
          // get block MD5
          new Sender(out).blockChecksum(block, lb.getBlockToken());

          final BlockOpResponseProto reply =
              BlockOpResponseProto.parseFrom(PBHelperClient.vintPrefixed(in));

          String logInfo = "for block " + block + " from datanode " +
              datanodes[j];
          DataTransferProtoUtil.checkBlockOpStatus(reply, logInfo);

          OpBlockChecksumResponseProto checksumData =
              reply.getChecksumResponse();

          //read byte-per-checksum
          final int bpc = checksumData.getBytesPerCrc();
          if (i == 0) { //first block
            bytesPerCRC = bpc;
          }
          else if (bpc != bytesPerCRC) {
            throw new IOException("Byte-per-checksum not matched: bpc=" + bpc
                + " but bytesPerCRC=" + bytesPerCRC);
          }

          //read crc-per-block
          final long cpb = checksumData.getCrcPerBlock();
          if (locatedblocks.size() > 1 && i == 0) {
            crcPerBlock = cpb;
          }

          //read md5
          final MD5Hash md5 = new MD5Hash(
              checksumData.getMd5().toByteArray());
          md5.write(md5out);

          // read crc-type
          final DataChecksum.Type ct;
          if (checksumData.hasCrcType()) {
            ct = PBHelperClient.convert(checksumData
                .getCrcType());
          } else {
            LOG.debug("Retrieving checksum from an earlier-version DataNode: " +
                "inferring checksum by reading first byte");
            ct = inferChecksumTypeByReading(lb, datanodes[j]);
          }

          if (i == 0) { // first block
            crcType = ct;
          } else if (crcType != DataChecksum.Type.MIXED
              && crcType != ct) {
            // if crc types are mixed in a file
            crcType = DataChecksum.Type.MIXED;
          }

          done = true;

          if (LOG.isDebugEnabled()) {
            if (i == 0) {
              LOG.debug("set bytesPerCRC=" + bytesPerCRC
                  + ", crcPerBlock=" + crcPerBlock);
            }
            LOG.debug("got reply from " + datanodes[j] + ": md5=" + md5);
          }
        } catch (InvalidBlockTokenException ibte) {
          if (i > lastRetriedIndex) {
            LOG.debug("Got access token error in response to OP_BLOCK_CHECKSUM "
                    + "for file {} for block {} from datanode {}. Will retry "
                    + "the block once.",
                src, block, datanodes[j]);
            lastRetriedIndex = i;
            done = true; // actually it's not done; but we'll retry
            i--; // repeat at i-th block
            refetchBlocks = true;
            break;
          }
        } catch (IOException ie) {
          LOG.warn("src=" + src + ", datanodes["+j+"]=" + datanodes[j], ie);
        } finally {
          IOUtils.closeStream(in);
          IOUtils.closeStream(out);
        }
      }

      if (!done) {
        throw new IOException("Fail to get block MD5 for " + block);
      }
    }

    //compute file MD5
    final MD5Hash fileMD5 = MD5Hash.digest(md5out.getData());
    switch (crcType) {
    case CRC32:
      return new MD5MD5CRC32GzipFileChecksum(bytesPerCRC,
          crcPerBlock, fileMD5);
    case CRC32C:
      return new MD5MD5CRC32CastagnoliFileChecksum(bytesPerCRC,
          crcPerBlock, fileMD5);
    default:
      // If there is no block allocated for the file,
      // return one with the magic entry that matches what previous
      // hdfs versions return.
      if (locatedblocks.size() == 0 || length == 0) {
        return new MD5MD5CRC32GzipFileChecksum(0, 0, fileMD5);
      }
      // We will get here if above condition is not met.
      return null;
    }
  }

  /**
   * Connect to the given datanode's datantrasfer port, and return
   * the resulting IOStreamPair. This includes encryption wrapping, etc.
   */
  private IOStreamPair connectToDN(DatanodeInfo dn, int timeout,
      LocatedBlock lb) throws IOException {
    boolean success = false;
    Socket sock = null;
    try {
      sock = socketFactory.createSocket();
      String dnAddr = dn.getXferAddr(getConf().isConnectToDnViaHostname());
      LOG.debug("Connecting to datanode {}", dnAddr);
      NetUtils.connect(sock, NetUtils.createSocketAddr(dnAddr), timeout);
      sock.setTcpNoDelay(dfsClientConf.getDataTransferTcpNoDelay());
      sock.setSoTimeout(timeout);

      OutputStream unbufOut = NetUtils.getOutputStream(sock);
      InputStream unbufIn = NetUtils.getInputStream(sock);
      IOStreamPair ret = saslClient.newSocketSend(sock, unbufOut, unbufIn, this,
          lb.getBlockToken(), dn);
      success = true;
      return ret;
    } finally {
      if (!success) {
        IOUtils.closeSocket(sock);
      }
    }
  }

  /**
   * Infer the checksum type for a replica by sending an OP_READ_BLOCK
   * for the first byte of that replica. This is used for compatibility
   * with older HDFS versions which did not include the checksum type in
   * OpBlockChecksumResponseProto.
   *
   * @param lb the located block
   * @param dn the connected datanode
   * @return the inferred checksum type
   * @throws IOException if an error occurs
   */
  private Type inferChecksumTypeByReading(LocatedBlock lb, DatanodeInfo dn)
      throws IOException {
    IOStreamPair pair = connectToDN(dn, dfsClientConf.getSocketTimeout(), lb);

    try {
      DataOutputStream out = new DataOutputStream(
          new BufferedOutputStream(pair.out, smallBufferSize));
      DataInputStream in = new DataInputStream(pair.in);

      new Sender(out).readBlock(lb.getBlock(), lb.getBlockToken(), clientName,
          0, 1, true, CachingStrategy.newDefaultStrategy());
      final BlockOpResponseProto reply =
          BlockOpResponseProto.parseFrom(PBHelperClient.vintPrefixed(in));
      String logInfo = "trying to read " + lb.getBlock() + " from datanode " +
          dn;
      DataTransferProtoUtil.checkBlockOpStatus(reply, logInfo);

      return PBHelperClient.convert(
          reply.getReadOpChecksumInfo().getChecksum().getType());
    } finally {
      IOUtilsClient.cleanup(null, pair.in, pair.out);
    }
  }

  /**
   * Set permissions to a file or directory.
   * @param src path name.
   * @param permission permission to set to
   *
   * @see ClientProtocol#setPermission(String, FsPermission)
   */
  public void setPermission(String src, FsPermission permission)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("setPermission", src)) {
      namenode.setPermission(src, permission);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          SafeModeException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * Set file or directory owner.
   * @param src path name.
   * @param username user id.
   * @param groupname user group.
   *
   * @see ClientProtocol#setOwner(String, String, String)
   */
  public void setOwner(String src, String username, String groupname)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("setOwner", src)) {
      namenode.setOwner(src, username, groupname);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          SafeModeException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  private long getStateByIndex(int stateIndex) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("getStats")) {
      long[] states =  namenode.getStats();
      return states.length > stateIndex ? states[stateIndex] : -1;
    }
  }

  /**
   * @see ClientProtocol#getStats()
   */
  public FsStatus getDiskStatus() throws IOException {
    return new FsStatus(getStateByIndex(0),
        getStateByIndex(1), getStateByIndex(2));
  }

  /**
   * Returns count of blocks with no good replicas left. Normally should be
   * zero.
   * @throws IOException
   */
  public long getMissingBlocksCount() throws IOException {
    return getStateByIndex(ClientProtocol.
        GET_STATS_MISSING_BLOCKS_IDX);
  }

  /**
   * Returns count of blocks with replication factor 1 and have
   * lost the only replica.
   * @throws IOException
   */
  public long getMissingReplOneBlocksCount() throws IOException {
    return getStateByIndex(ClientProtocol.
        GET_STATS_MISSING_REPL_ONE_BLOCKS_IDX);
  }

  /**
   * Returns count of blocks pending on deletion.
   * @throws IOException
   */
  public long getPendingDeletionBlocksCount() throws IOException {
    return getStateByIndex(ClientProtocol.
        GET_STATS_PENDING_DELETION_BLOCKS_IDX);
  }

  /**
   * Returns count of blocks with one of more replica missing.
   * @throws IOException
   */
  public long getUnderReplicatedBlocksCount() throws IOException {
    return getStateByIndex(ClientProtocol.
        GET_STATS_UNDER_REPLICATED_IDX);
  }

  /**
   * Returns count of blocks with at least one replica marked corrupt.
   * @throws IOException
   */
  public long getCorruptBlocksCount() throws IOException {
    return getStateByIndex(ClientProtocol.
        GET_STATS_CORRUPT_BLOCKS_IDX);
  }

  /**
   * Returns number of bytes that reside in Blocks with future generation
   * stamps.
   * @return Bytes in Blocks with future generation stamps.
   * @throws IOException
   */
  public long getBytesInFutureBlocks() throws IOException {
    return getStateByIndex(ClientProtocol.
        GET_STATS_BYTES_IN_FUTURE_BLOCKS_IDX);
  }

  /**
   * @return a list in which each entry describes a corrupt file/block
   * @throws IOException
   */
  public CorruptFileBlocks listCorruptFileBlocks(String path, String cookie)
      throws IOException {
    checkOpen();
    try (TraceScope ignored
             = newPathTraceScope("listCorruptFileBlocks", path)) {
      return namenode.listCorruptFileBlocks(path, cookie);
    }
  }

  public DatanodeInfo[] datanodeReport(DatanodeReportType type)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("datanodeReport")) {
      return namenode.getDatanodeReport(type);
    }
  }

  public DatanodeStorageReport[] getDatanodeStorageReport(
      DatanodeReportType type) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("datanodeStorageReport")) {
      return namenode.getDatanodeStorageReport(type);
    }
  }

  /**
   * Enter, leave or get safe mode.
   *
   * @see ClientProtocol#setSafeMode(HdfsConstants.SafeModeAction,boolean)
   */
  public boolean setSafeMode(SafeModeAction action) throws IOException {
    return setSafeMode(action, false);
  }

  /**
   * Enter, leave or get safe mode.
   *
   * @param action
   *          One of SafeModeAction.GET, SafeModeAction.ENTER and
   *          SafeModeActiob.LEAVE
   * @param isChecked
   *          If true, then check only active namenode's safemode status, else
   *          check first namenode's status.
   * @see ClientProtocol#setSafeMode(HdfsConstants.SafeModeAction, boolean)
   */
  public boolean setSafeMode(SafeModeAction action, boolean isChecked)
      throws IOException{
    try (TraceScope ignored = tracer.newScope("setSafeMode")) {
      return namenode.setSafeMode(action, isChecked);
    }
  }

  /**
   * Create one snapshot.
   *
   * @param snapshotRoot The directory where the snapshot is to be taken
   * @param snapshotName Name of the snapshot
   * @return the snapshot path.
   * @see ClientProtocol#createSnapshot(String, String)
   */
  public String createSnapshot(String snapshotRoot, String snapshotName)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("createSnapshot")) {
      return namenode.createSnapshot(snapshotRoot, snapshotName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  /**
   * Delete a snapshot of a snapshottable directory.
   *
   * @param snapshotRoot The snapshottable directory that the
   *                    to-be-deleted snapshot belongs to
   * @param snapshotName The name of the to-be-deleted snapshot
   * @throws IOException
   * @see ClientProtocol#deleteSnapshot(String, String)
   */
  public void deleteSnapshot(String snapshotRoot, String snapshotName)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("deleteSnapshot")) {
      namenode.deleteSnapshot(snapshotRoot, snapshotName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  /**
   * Rename a snapshot.
   * @param snapshotDir The directory path where the snapshot was taken
   * @param snapshotOldName Old name of the snapshot
   * @param snapshotNewName New name of the snapshot
   * @throws IOException
   * @see ClientProtocol#renameSnapshot(String, String, String)
   */
  public void renameSnapshot(String snapshotDir, String snapshotOldName,
      String snapshotNewName) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("renameSnapshot")) {
      namenode.renameSnapshot(snapshotDir, snapshotOldName, snapshotNewName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  /**
   * Get all the current snapshottable directories.
   * @return All the current snapshottable directories
   * @throws IOException
   * @see ClientProtocol#getSnapshottableDirListing()
   */
  public SnapshottableDirectoryStatus[] getSnapshottableDirListing()
      throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("getSnapshottableDirListing")) {
      return namenode.getSnapshottableDirListing();
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  /**
   * Allow snapshot on a directory.
   *
   * @see ClientProtocol#allowSnapshot(String snapshotRoot)
   */
  public void allowSnapshot(String snapshotRoot) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("allowSnapshot")) {
      namenode.allowSnapshot(snapshotRoot);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  /**
   * Disallow snapshot on a directory.
   *
   * @see ClientProtocol#disallowSnapshot(String snapshotRoot)
   */
  public void disallowSnapshot(String snapshotRoot) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("disallowSnapshot")) {
      namenode.disallowSnapshot(snapshotRoot);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  /**
   * Get the difference between two snapshots, or between a snapshot and the
   * current tree of a directory.
   * @see ClientProtocol#getSnapshotDiffReport(String, String, String)
   */
  public SnapshotDiffReport getSnapshotDiffReport(String snapshotDir,
      String fromSnapshot, String toSnapshot) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("getSnapshotDiffReport")) {
      return namenode.getSnapshotDiffReport(snapshotDir,
          fromSnapshot, toSnapshot);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public long addCacheDirective(
      CacheDirectiveInfo info, EnumSet<CacheFlag> flags) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("addCacheDirective")) {
      return namenode.addCacheDirective(info, flags);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public void modifyCacheDirective(
      CacheDirectiveInfo info, EnumSet<CacheFlag> flags) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("modifyCacheDirective")) {
      namenode.modifyCacheDirective(info, flags);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public void removeCacheDirective(long id)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("removeCacheDirective")) {
      namenode.removeCacheDirective(id);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public RemoteIterator<CacheDirectiveEntry> listCacheDirectives(
      CacheDirectiveInfo filter) throws IOException {
    return new CacheDirectiveIterator(namenode, filter, tracer);
  }

  public void addCachePool(CachePoolInfo info) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("addCachePool")) {
      namenode.addCachePool(info);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public void modifyCachePool(CachePoolInfo info) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("modifyCachePool")) {
      namenode.modifyCachePool(info);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public void removeCachePool(String poolName) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("removeCachePool")) {
      namenode.removeCachePool(poolName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException();
    }
  }

  public RemoteIterator<CachePoolEntry> listCachePools() throws IOException {
    return new CachePoolIterator(namenode, tracer);
  }

  /**
   * Save namespace image.
   *
   */
  void saveNamespace() throws IOException {
    try (TraceScope ignored = tracer.newScope("saveNamespace")) {
      namenode.saveNamespace();
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class);
    }
  }

  /**
   * Rolls the edit log on the active NameNode.
   * @return the txid of the new log segment
   *
   * @see ClientProtocol#rollEdits()
   */
  long rollEdits() throws IOException {
    try (TraceScope ignored = tracer.newScope("rollEdits")) {
      return namenode.rollEdits();
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class);
    }
  }

  @VisibleForTesting
  ExtendedBlock getPreviousBlock(long fileId) {
    return filesBeingWritten.get(fileId).getBlock();
  }

  /**
   * enable/disable restore failed storage.
   *
   * @see ClientProtocol#restoreFailedStorage(String arg)
   */
  boolean restoreFailedStorage(String arg) throws IOException{
    try (TraceScope ignored = tracer.newScope("restoreFailedStorage")) {
      return namenode.restoreFailedStorage(arg);
    }
  }

  /**
   * Refresh the hosts and exclude files.  (Rereads them.)
   * See {@link ClientProtocol#refreshNodes()}
   * for more details.
   *
   * @see ClientProtocol#refreshNodes()
   */
  public void refreshNodes() throws IOException {
    try (TraceScope ignored = tracer.newScope("refreshNodes")) {
      namenode.refreshNodes();
    }
  }

  /**
   * Dumps DFS data structures into specified file.
   *
   * @see ClientProtocol#metaSave(String)
   */
  public void metaSave(String pathname) throws IOException {
    try (TraceScope ignored = tracer.newScope("metaSave")) {
      namenode.metaSave(pathname);
    }
  }

  /**
   * Requests the namenode to tell all datanodes to use a new, non-persistent
   * bandwidth value for dfs.balance.bandwidthPerSec.
   * See {@link ClientProtocol#setBalancerBandwidth(long)}
   * for more details.
   *
   * @see ClientProtocol#setBalancerBandwidth(long)
   */
  public void setBalancerBandwidth(long bandwidth) throws IOException {
    try (TraceScope ignored = tracer.newScope("setBalancerBandwidth")) {
      namenode.setBalancerBandwidth(bandwidth);
    }
  }

  /**
   * @see ClientProtocol#finalizeUpgrade()
   */
  public void finalizeUpgrade() throws IOException {
    try (TraceScope ignored = tracer.newScope("finalizeUpgrade")) {
      namenode.finalizeUpgrade();
    }
  }

  RollingUpgradeInfo rollingUpgrade(RollingUpgradeAction action)
      throws IOException {
    try (TraceScope ignored = tracer.newScope("rollingUpgrade")) {
      return namenode.rollingUpgrade(action);
    }
  }

  /**
   */
  @Deprecated
  public boolean mkdirs(String src) throws IOException {
    return mkdirs(src, null, true);
  }

  /**
   * Create a directory (or hierarchy of directories) with the given
   * name and permission.
   *
   * @param src The path of the directory being created
   * @param permission The permission of the directory being created.
   * If permission == null, use {@link FsPermission#getDirDefault()}.
   * @param createParent create missing parent directory if true
   *
   * @return True if the operation success.
   *
   * @see ClientProtocol#mkdirs(String, FsPermission, boolean)
   */
  public boolean mkdirs(String src, FsPermission permission,
      boolean createParent) throws IOException {
    final FsPermission masked = applyUMaskDir(permission);
    return primitiveMkdir(src, masked, createParent);
  }

  /**
   * Same {{@link #mkdirs(String, FsPermission, boolean)} except
   * that the permissions has already been masked against umask.
   */
  public boolean primitiveMkdir(String src, FsPermission absPermission)
      throws IOException {
    return primitiveMkdir(src, absPermission, true);
  }

  /**
   * Same {{@link #mkdirs(String, FsPermission, boolean)} except
   * that the permissions has already been masked against umask.
   */
  public boolean primitiveMkdir(String src, FsPermission absPermission,
      boolean createParent) throws IOException {
    checkOpen();
    if (absPermission == null) {
      absPermission = applyUMaskDir(null);
    }
    LOG.debug("{}: masked={}", src, absPermission);
    try (TraceScope ignored = tracer.newScope("mkdir")) {
      return namenode.mkdirs(src, absPermission, createParent);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          InvalidPathException.class,
          FileAlreadyExistsException.class,
          FileNotFoundException.class,
          ParentNotDirectoryException.class,
          SafeModeException.class,
          NSQuotaExceededException.class,
          DSQuotaExceededException.class,
          QuotaByStorageTypeExceededException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * Get {@link ContentSummary} rooted at the specified directory.
   * @param src The string representation of the path
   *
   * @see ClientProtocol#getContentSummary(String)
   */
  ContentSummary getContentSummary(String src) throws IOException {
    try (TraceScope ignored = newPathTraceScope("getContentSummary", src)) {
      return namenode.getContentSummary(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  /**
   * Get {@link org.apache.hadoop.fs.QuotaUsage} rooted at the specified directory.
   * @param src The string representation of the path
   *
   * @see ClientProtocol#getQuotaUsage(String)
   */
  QuotaUsage getQuotaUsage(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getQuotaUsage", src)) {
      return namenode.getQuotaUsage(src);
    } catch(RemoteException re) {
      IOException ioe = re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class,
          RpcNoSuchMethodException.class);
      if (ioe instanceof RpcNoSuchMethodException) {
        LOG.debug("The version of namenode doesn't support getQuotaUsage API." +
            " Fall back to use getContentSummary API.");
        return getContentSummary(src);
      } else {
        throw ioe;
      }
    }
  }

  /**
   * Sets or resets quotas for a directory.
   * @see ClientProtocol#setQuota(String, long, long, StorageType)
   */
  void setQuota(String src, long namespaceQuota, long storagespaceQuota)
      throws IOException {
    // sanity check
    if ((namespaceQuota <= 0 &&
          namespaceQuota != HdfsConstants.QUOTA_DONT_SET &&
          namespaceQuota != HdfsConstants.QUOTA_RESET) ||
        (storagespaceQuota < 0 &&
            storagespaceQuota != HdfsConstants.QUOTA_DONT_SET &&
            storagespaceQuota != HdfsConstants.QUOTA_RESET)) {
      throw new IllegalArgumentException("Invalid values for quota : " +
          namespaceQuota + " and " +
          storagespaceQuota);

    }
    try (TraceScope ignored = newPathTraceScope("setQuota", src)) {
      // Pass null as storage type for traditional namespace/storagespace quota.
      namenode.setQuota(src, namespaceQuota, storagespaceQuota, null);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          NSQuotaExceededException.class,
          DSQuotaExceededException.class,
          QuotaByStorageTypeExceededException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * Sets or resets quotas by storage type for a directory.
   * @see ClientProtocol#setQuota(String, long, long, StorageType)
   */
  void setQuotaByStorageType(String src, StorageType type, long quota)
      throws IOException {
    if (quota <= 0 && quota != HdfsConstants.QUOTA_DONT_SET &&
        quota != HdfsConstants.QUOTA_RESET) {
      throw new IllegalArgumentException("Invalid values for quota :" +
          quota);
    }
    if (type == null) {
      throw new IllegalArgumentException("Invalid storage type(null)");
    }
    if (!type.supportTypeQuota()) {
      throw new IllegalArgumentException(
          "Don't support Quota for storage type : " + type.toString());
    }
    try (TraceScope ignored = newPathTraceScope("setQuotaByStorageType", src)) {
      namenode.setQuota(src, HdfsConstants.QUOTA_DONT_SET, quota, type);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          QuotaByStorageTypeExceededException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }
  /**
   * set the modification and access time of a file
   *
   * @see ClientProtocol#setTimes(String, long, long)
   */
  public void setTimes(String src, long mtime, long atime) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("setTimes", src)) {
      namenode.setTimes(src, mtime, atime);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class,
          SnapshotAccessControlException.class);
    }
  }

  /**
   * @deprecated use {@link HdfsDataInputStream} instead.
   */
  @Deprecated
  public static class DFSDataInputStream extends HdfsDataInputStream {

    public DFSDataInputStream(DFSInputStream in) throws IOException {
      super(in);
    }
  }

  void reportChecksumFailure(String file, ExtendedBlock blk, DatanodeInfo dn) {
    DatanodeInfo [] dnArr = { dn };
    LocatedBlock [] lblocks = { new LocatedBlock(blk, dnArr) };
    reportChecksumFailure(file, lblocks);
  }

  // just reports checksum failure and ignores any exception during the report.
  void reportChecksumFailure(String file, LocatedBlock lblocks[]) {
    try {
      reportBadBlocks(lblocks);
    } catch (IOException ie) {
      LOG.info("Found corruption while reading " + file
          + ". Error repairing corrupt blocks. Bad blocks remain.", ie);
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[clientName=" + clientName
        + ", ugi=" + ugi + "]";
  }

  public CachingStrategy getDefaultReadCachingStrategy() {
    return defaultReadCachingStrategy;
  }

  public CachingStrategy getDefaultWriteCachingStrategy() {
    return defaultWriteCachingStrategy;
  }

  public ClientContext getClientContext() {
    return clientContext;
  }

  public void modifyAclEntries(String src, List<AclEntry> aclSpec)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("modifyAclEntries", src)) {
      namenode.modifyAclEntries(src, aclSpec);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          AclException.class,
          FileNotFoundException.class,
          NSQuotaExceededException.class,
          SafeModeException.class,
          SnapshotAccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  public void removeAclEntries(String src, List<AclEntry> aclSpec)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("removeAclEntries")) {
      namenode.removeAclEntries(src, aclSpec);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          AclException.class,
          FileNotFoundException.class,
          NSQuotaExceededException.class,
          SafeModeException.class,
          SnapshotAccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  public void removeDefaultAcl(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("removeDefaultAcl")) {
      namenode.removeDefaultAcl(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          AclException.class,
          FileNotFoundException.class,
          NSQuotaExceededException.class,
          SafeModeException.class,
          SnapshotAccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  public void removeAcl(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("removeAcl")) {
      namenode.removeAcl(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          AclException.class,
          FileNotFoundException.class,
          NSQuotaExceededException.class,
          SafeModeException.class,
          SnapshotAccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  public void setAcl(String src, List<AclEntry> aclSpec) throws IOException {
    checkOpen();
    try (TraceScope ignored = tracer.newScope("setAcl")) {
      namenode.setAcl(src, aclSpec);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          AclException.class,
          FileNotFoundException.class,
          NSQuotaExceededException.class,
          SafeModeException.class,
          SnapshotAccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  public AclStatus getAclStatus(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getAclStatus", src)) {
      return namenode.getAclStatus(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          AclException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  public void createEncryptionZone(String src, String keyName)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("createEncryptionZone", src)) {
      namenode.createEncryptionZone(src, keyName);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          SafeModeException.class,
          UnresolvedPathException.class);
    }
  }

  public EncryptionZone getEZForPath(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getEZForPath", src)) {
      return namenode.getEZForPath(src);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  public RemoteIterator<EncryptionZone> listEncryptionZones()
      throws IOException {
    checkOpen();
    return new EncryptionZoneIterator(namenode, tracer);
  }

  public void setXAttr(String src, String name, byte[] value, 
      EnumSet<XAttrSetFlag> flag) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("setXAttr", src)) {
      namenode.setXAttr(src, XAttrHelper.buildXAttr(name, value), flag);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          NSQuotaExceededException.class,
          SafeModeException.class,
          SnapshotAccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  public byte[] getXAttr(String src, String name) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getXAttr", src)) {
      final List<XAttr> xAttrs = XAttrHelper.buildXAttrAsList(name);
      final List<XAttr> result = namenode.getXAttrs(src, xAttrs);
      return XAttrHelper.getFirstXAttrValue(result);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  public Map<String, byte[]> getXAttrs(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getXAttrs", src)) {
      return XAttrHelper.buildXAttrMap(namenode.getXAttrs(src, null));
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  public Map<String, byte[]> getXAttrs(String src, List<String> names)
      throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("getXAttrs", src)) {
      return XAttrHelper.buildXAttrMap(namenode.getXAttrs(
          src, XAttrHelper.buildXAttrs(names)));
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  public List<String> listXAttrs(String src) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("listXAttrs", src)) {
      final Map<String, byte[]> xattrs =
          XAttrHelper.buildXAttrMap(namenode.listXAttrs(src));
      return Lists.newArrayList(xattrs.keySet());
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  public void removeXAttr(String src, String name) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("removeXAttr", src)) {
      namenode.removeXAttr(src, XAttrHelper.buildXAttr(name));
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          NSQuotaExceededException.class,
          SafeModeException.class,
          SnapshotAccessControlException.class,
          UnresolvedPathException.class);
    }
  }

  public void checkAccess(String src, FsAction mode) throws IOException {
    checkOpen();
    try (TraceScope ignored = newPathTraceScope("checkAccess", src)) {
      namenode.checkAccess(src, mode);
    } catch (RemoteException re) {
      throw re.unwrapRemoteException(AccessControlException.class,
          FileNotFoundException.class,
          UnresolvedPathException.class);
    }
  }

  public DFSInotifyEventInputStream getInotifyEventStream() throws IOException {
    return new DFSInotifyEventInputStream(namenode, tracer);
  }

  public DFSInotifyEventInputStream getInotifyEventStream(long lastReadTxid)
      throws IOException {
    return new DFSInotifyEventInputStream(namenode, tracer,
        lastReadTxid);
  }

  @Override // RemotePeerFactory
  public Peer newConnectedPeer(InetSocketAddress addr,
      Token<BlockTokenIdentifier> blockToken, DatanodeID datanodeId)
      throws IOException {
    Peer peer = null;
    boolean success = false;
    Socket sock = null;
    final int socketTimeout = dfsClientConf.getSocketTimeout();
    try {
      sock = socketFactory.createSocket();
      NetUtils.connect(sock, addr, getRandomLocalInterfaceAddr(),
          socketTimeout);
      peer = DFSUtilClient.peerFromSocketAndKey(saslClient, sock, this,
          blockToken, datanodeId, socketTimeout);
      success = true;
      return peer;
    } finally {
      if (!success) {
        IOUtilsClient.cleanup(LOG, peer);
        IOUtils.closeSocket(sock);
      }
    }
  }

  void updateFileSystemReadStats(int distance, int nRead) {
    if (stats != null) {
      stats.incrementBytesRead(nRead);
      stats.incrementBytesReadByDistance(distance, nRead);
    }
  }

  /**
   * Create hedged reads thread pool, HEDGED_READ_THREAD_POOL, if
   * it does not already exist.
   * @param num Number of threads for hedged reads thread pool.
   * If zero, skip hedged reads thread pool creation.
   */
  private synchronized void initThreadsNumForHedgedReads(int num) {
    if (num <= 0 || HEDGED_READ_THREAD_POOL != null) return;
    HEDGED_READ_THREAD_POOL = new ThreadPoolExecutor(1, num, 60,
        TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
        new Daemon.DaemonFactory() {
          private final AtomicInteger threadIndex = new AtomicInteger(0);
          @Override
          public Thread newThread(Runnable r) {
            Thread t = super.newThread(r);
            t.setName("hedgedRead-" + threadIndex.getAndIncrement());
            return t;
          }
        },
        new ThreadPoolExecutor.CallerRunsPolicy() {
          @Override
          public void rejectedExecution(Runnable runnable,
              ThreadPoolExecutor e) {
            LOG.info("Execution rejected, Executing in current thread");
            HEDGED_READ_METRIC.incHedgedReadOpsInCurThread();
            // will run in the current thread
            super.rejectedExecution(runnable, e);
          }
        });
    HEDGED_READ_THREAD_POOL.allowCoreThreadTimeOut(true);
    LOG.debug("Using hedged reads; pool threads={}", num);
  }

  ThreadPoolExecutor getHedgedReadsThreadPool() {
    return HEDGED_READ_THREAD_POOL;
  }

  boolean isHedgedReadsEnabled() {
    return (HEDGED_READ_THREAD_POOL != null) &&
        HEDGED_READ_THREAD_POOL.getMaximumPoolSize() > 0;
  }

  DFSHedgedReadMetrics getHedgedReadMetrics() {
    return HEDGED_READ_METRIC;
  }

  URI getKeyProviderUri() throws IOException {
    return HdfsKMSUtil.getKeyProviderUri(ugi, namenodeUri,
        getServerDefaults().getKeyProviderUri(), conf);
  }

  public KeyProvider getKeyProvider() throws IOException {
    return clientContext.getKeyProviderCache().get(conf, getKeyProviderUri());
  }

  @VisibleForTesting
  public void setKeyProvider(KeyProvider provider) {
    clientContext.getKeyProviderCache().setKeyProvider(conf, provider);
  }

  /**
   * Probe for encryption enabled on this filesystem.
   * @return true if encryption is enabled
   */
  boolean isHDFSEncryptionEnabled() throws IOException {
    return getKeyProviderUri() != null;
  }

  /**
   * Returns the SaslDataTransferClient configured for this DFSClient.
   *
   * @return SaslDataTransferClient configured for this DFSClient
   */
  public SaslDataTransferClient getSaslDataTransferClient() {
    return saslClient;
  }

  TraceScope newPathTraceScope(String description, String path) {
    TraceScope scope = tracer.newScope(description);
    if (path != null) {
      scope.addKVAnnotation("path", path);
    }
    return scope;
  }

  TraceScope newSrcDstTraceScope(String description, String src, String dst) {
    TraceScope scope = tracer.newScope(description);
    if (src != null) {
      scope.addKVAnnotation("src", src);
    }
    if (dst != null) {
      scope.addKVAnnotation("dst", dst);
    }
    return scope;
  }

  /**
   * Full detailed tracing for read requests: path, position in the file,
   * and length.
   *
   * @param reqLen requested length
   */
  TraceScope newReaderTraceScope(String description, String path, long pos,
      int reqLen) {
    TraceScope scope = newPathTraceScope(description, path);
    scope.addKVAnnotation("pos", Long.toString(pos));
    scope.addKVAnnotation("reqLen", Integer.toString(reqLen));
    return scope;
  }

  /** Add the returned length info to the scope. */
  void addRetLenToReaderScope(TraceScope scope, int retLen) {
    scope.addKVAnnotation("retLen", Integer.toString(retLen));
  }

  Tracer getTracer() {
    return tracer;
  }

  /**
   * Get a remote iterator to the open files list managed by NameNode.
   *
   * @throws IOException
   */
  public RemoteIterator<OpenFileEntry> listOpenFiles() throws IOException {
    checkOpen();
    return new OpenFilesIterator(namenode, tracer);
  }

  /**
   * A blocking call to wait for Observer NameNode state ID to reach to the
   * current client state ID. Current client state ID is given by the client
   * alignment context.
   * An assumption is that client alignment context has the state ID set at this
   * point. This is become ObserverReadProxyProvider sets up the initial state
   * ID when it is being created.
   *
   * @throws IOException
   */
  public void msync() throws IOException {
    namenode.msync();
  }

  /**
   * An unblocking call to get the HA service state of NameNode.
   *
   * @return HA state of NameNode
   * @throws IOException
   */
  @VisibleForTesting
  public HAServiceProtocol.HAServiceState getHAServiceState()
      throws IOException {
    return namenode.getHAServiceState();
  }

  /**
   * If deadNodeDetectionEnabled is true, return the dead nodes that detected by
   * all the DFSInputStreams in the same client. Otherwise return the dead nodes
   * that detected by given DFSInputStream.
   */
  public ConcurrentHashMap<DatanodeInfo, DatanodeInfo> getDeadNodes(
      DFSInputStream dfsInputStream) {
    if (clientContext.isDeadNodeDetectionEnabled()) {
      ConcurrentHashMap<DatanodeInfo, DatanodeInfo> deadNodes =
          new ConcurrentHashMap<DatanodeInfo, DatanodeInfo>();
      if (dfsInputStream != null) {
        deadNodes.putAll(dfsInputStream.getLocalDeadNodes());
      }

      Set<DatanodeInfo> detectDeadNodes =
          clientContext.getDeadNodeDetector().clearAndGetDetectedDeadNodes();
      for (DatanodeInfo detectDeadNode : detectDeadNodes) {
        deadNodes.put(detectDeadNode, detectDeadNode);
      }
      return deadNodes;
    } else {
      return dfsInputStream.getLocalDeadNodes();
    }
  }

  /**
   * If deadNodeDetectionEnabled is true, judgement based on whether this
   * datanode is included or not in DeadNodeDetector. Otherwise judgment based
   * given DFSInputStream.
   */
  public boolean isDeadNode(DFSInputStream dfsInputStream,
      DatanodeInfo datanodeInfo) {
    if (isDeadNodeDetectionEnabled()) {
      boolean isDeadNode =
          clientContext.getDeadNodeDetector().isDeadNode(datanodeInfo);
      if (dfsInputStream != null) {
        isDeadNode = isDeadNode
            || dfsInputStream.getLocalDeadNodes().contains(datanodeInfo);
      }
      return isDeadNode;
    } else {
      return dfsInputStream.getLocalDeadNodes().contains(datanodeInfo);
    }
  }

  /**
   * Add given datanode in DeadNodeDetector.
   */
  public void addNodeToDeadNodeDetector(DFSInputStream dfsInputStream,
      DatanodeInfo datanodeInfo) {
    if (!isDeadNodeDetectionEnabled()) {
      LOG.debug("DeadNode detection is not enabled, skip to add node {}.",
          datanodeInfo);
      return;
    }
    clientContext.getDeadNodeDetector().addNodeToDetect(dfsInputStream,
        datanodeInfo);
  }

  /**
   * Remove given datanode from DeadNodeDetector.
   */
  public void removeNodeFromDeadNodeDetector(DFSInputStream dfsInputStream,
      DatanodeInfo datanodeInfo) {
    if (!isDeadNodeDetectionEnabled()) {
      LOG.debug("DeadNode detection is not enabled, skip to remove node {}.",
          datanodeInfo);
      return;
    }
    clientContext.getDeadNodeDetector()
        .removeNodeFromDeadNodeDetector(dfsInputStream, datanodeInfo);
  }

  /**
   * Remove datanodes that given block placed on from DeadNodeDetector.
   */
  public void removeNodeFromDeadNodeDetector(DFSInputStream dfsInputStream,
      LocatedBlocks locatedBlocks) {
    if (!isDeadNodeDetectionEnabled() || locatedBlocks == null) {
      LOG.debug("DeadNode detection is not enabled or given block {} " +
          "is null, skip to remove node.", locatedBlocks);
      return;
    }
    for (LocatedBlock locatedBlock : locatedBlocks.getLocatedBlocks()) {
      for (DatanodeInfo datanodeInfo : locatedBlock.getLocations()) {
        removeNodeFromDeadNodeDetector(dfsInputStream, datanodeInfo);
      }
    }
  }

  private boolean isDeadNodeDetectionEnabled() {
    return clientContext.isDeadNodeDetectionEnabled();
  }
}
