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

package org.apache.hadoop.yarn.server.resourcemanager.scheduler;

import static org.apache.hadoop.metrics2.lib.Interns.info;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.metrics2.MetricsCollector;
import org.apache.hadoop.metrics2.MetricsInfo;
import org.apache.hadoop.metrics2.MetricsSource;
import org.apache.hadoop.metrics2.MetricsSystem;
import org.apache.hadoop.metrics2.annotation.Metric;
import org.apache.hadoop.metrics2.annotation.Metrics;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.metrics2.lib.MetricsRegistry;
import org.apache.hadoop.metrics2.lib.MutableCounterInt;
import org.apache.hadoop.metrics2.lib.MutableCounterLong;
import org.apache.hadoop.metrics2.lib.MutableGaugeInt;
import org.apache.hadoop.metrics2.lib.MutableGaugeLong;
import org.apache.hadoop.metrics2.lib.MutableRate;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceInformation;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppState;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler
    .QueueMetricsForCustomResources.QueueMetricsCustomResource;
import org.apache.hadoop.yarn.util.resource.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;

@InterfaceAudience.Private
@Metrics(context="yarn")
public class QueueMetrics implements MetricsSource {
  @Metric("# of apps submitted") MutableCounterInt appsSubmitted;
  @Metric("# of running apps") MutableGaugeInt appsRunning;
  @Metric("# of pending apps") MutableGaugeInt appsPending;
  @Metric("# of apps completed") MutableCounterInt appsCompleted;
  @Metric("# of apps killed") MutableCounterInt appsKilled;
  @Metric("# of apps failed") MutableCounterInt appsFailed;
  @Metric("Aggregate # of allocated node-local containers")
    MutableCounterLong aggregateNodeLocalContainersAllocated;
  @Metric("Aggregate # of allocated rack-local containers")
    MutableCounterLong aggregateRackLocalContainersAllocated;
  @Metric("Aggregate # of allocated off-switch containers")
    MutableCounterLong aggregateOffSwitchContainersAllocated;
  @Metric("Aggregate # of preempted containers") MutableCounterLong
      aggregateContainersPreempted;
  @Metric("Aggregate # of preempted memory seconds") MutableCounterLong
      aggregateMemoryMBSecondsPreempted;
  @Metric("Aggregate # of preempted vcore seconds") MutableCounterLong
      aggregateVcoreSecondsPreempted;
  @Metric("# of active users") MutableGaugeInt activeUsers;
  @Metric("# of active applications") MutableGaugeInt activeApplications;
  @Metric("App Attempt First Container Allocation Delay")
    MutableRate appAttemptFirstContainerAllocationDelay;
  @Metric("Aggregate total of preempted memory MB")
    MutableCounterLong aggregateMemoryMBPreempted;
  @Metric("Aggregate total of preempted vcores")
    MutableCounterLong aggregateVcoresPreempted;

  //Metrics updated only for "default" partition
  @Metric("Allocated memory in MB") MutableGaugeLong allocatedMB;
  @Metric("Allocated CPU in virtual cores") MutableGaugeInt allocatedVCores;
  @Metric("# of allocated containers") MutableGaugeInt allocatedContainers;
  @Metric("Aggregate # of allocated containers")
    MutableCounterLong aggregateContainersAllocated;
  @Metric("Aggregate # of released containers")
    MutableCounterLong aggregateContainersReleased;
  @Metric("Available memory in MB") MutableGaugeLong availableMB;
  @Metric("Available CPU in virtual cores") MutableGaugeInt availableVCores;
  @Metric("Pending memory allocation in MB") MutableGaugeLong pendingMB;
  @Metric("Pending CPU allocation in virtual cores")
    MutableGaugeInt pendingVCores;
  @Metric("# of pending containers") MutableGaugeInt pendingContainers;
  @Metric("# of reserved memory in MB") MutableGaugeLong reservedMB;
  @Metric("Reserved CPU in virtual cores") MutableGaugeInt reservedVCores;
  @Metric("# of reserved containers") MutableGaugeInt reservedContainers;

  private final MutableGaugeInt[] runningTime;
  private TimeBucketMetrics<ApplicationId> runBuckets;

  static final Logger LOG = LoggerFactory.getLogger(QueueMetrics.class);
  static final MetricsInfo RECORD_INFO = info("QueueMetrics",
      "Metrics for the resource scheduler");
  protected static final MetricsInfo QUEUE_INFO =
      info("Queue", "Metrics by queue");
  protected static final MetricsInfo USER_INFO =
      info("User", "Metrics by user");
  protected static final MetricsInfo PARTITION_INFO =
      info("Partition", "Metrics by partition");
  static final Splitter Q_SPLITTER =
      Splitter.on('.').omitEmptyStrings().trimResults();

  protected final MetricsRegistry registry;
  protected final String queueName;
  private QueueMetrics parent;
  private final Queue parentQueue;
  protected final MetricsSystem metricsSystem;
  protected final Map<String, QueueMetrics> users;
  protected final Configuration conf;
  private final boolean enableUserMetrics;
  private QueueMetricsForCustomResources queueMetricsForCustomResources;

  private static final String ALLOCATED_RESOURCE_METRIC_PREFIX =
      "AllocatedResource.";
  private static final String ALLOCATED_RESOURCE_METRIC_DESC =
    "Allocated NAME";

  private static final String AVAILABLE_RESOURCE_METRIC_PREFIX =
    "AvailableResource.";
  private static final String AVAILABLE_RESOURCE_METRIC_DESC =
    "Available NAME";

  private static final String PENDING_RESOURCE_METRIC_PREFIX =
    "PendingResource.";
  private static final String PENDING_RESOURCE_METRIC_DESC =
    "Pending NAME";

  private static final String RESERVED_RESOURCE_METRIC_PREFIX =
    "ReservedResource.";
  private static final String RESERVED_RESOURCE_METRIC_DESC =
    "Reserved NAME";

  private static final String AGGREGATE_PREEMPTED_SECONDS_METRIC_PREFIX =
    "AggregatePreemptedSeconds.";
  private static final String AGGREGATE_PREEMPTED_SECONDS_METRIC_DESC =
    "Aggregate Preempted Seconds for NAME";

  protected static final MetricsInfo P_RECORD_INFO =
      info("PartitionQueueMetrics", "Metrics for the resource scheduler");

  // Use "default" to operate NO_LABEL (default) partition internally
  public static final String DEFAULT_PARTITION = "default";

  // Use "" to register NO_LABEL (default) partition into metrics system
  public static final String DEFAULT_PARTITION_JMX_STR = "";

  // Metric Name Delimiter
  public static final String METRIC_NAME_DELIMITER = ".";
      
  protected QueueMetrics(MetricsSystem ms, String queueName, Queue parent, 
	       boolean enableUserMetrics, Configuration conf) {
    if (this instanceof PartitionQueueMetrics) {
      registry = new MetricsRegistry(P_RECORD_INFO);
    } else {
      registry = new MetricsRegistry(RECORD_INFO);
    }
    this.queueName = queueName;
    this.parent = parent != null ? parent.getMetrics() : null;
    this.users = enableUserMetrics ? new HashMap<String, QueueMetrics>()
                                   : null;
    this.parentQueue = parent;
    this.enableUserMetrics = enableUserMetrics;
    metricsSystem = ms;
    this.conf = conf;
    runningTime = buildBuckets(conf);

    createQueueMetricsForCustomResources();
  }

  protected QueueMetrics tag(MetricsInfo info, String value) {
    registry.tag(info, value);
    return this;
  }

  protected static StringBuilder sourceName(String queueName) {
    StringBuilder sb = new StringBuilder(RECORD_INFO.name());
    int i = 0;
    for (String node : Q_SPLITTER.split(queueName)) {
      sb.append(",q").append(i++).append('=').append(node);
    }
    return sb;
  }

  static StringBuilder pSourceName(String partition) {
    StringBuilder sb = new StringBuilder(P_RECORD_INFO.name());
    sb.append(",partition").append('=').append(partition);
    return sb;
  }

  static StringBuilder qSourceName(String queueName) {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (String node : Q_SPLITTER.split(queueName)) {
      sb.append(",q").append(i++).append('=').append(node);
    }
    return sb;
  }

  public synchronized static QueueMetrics forQueue(String queueName,
      Queue parent, boolean enableUserMetrics, Configuration conf) {
    return forQueue(DefaultMetricsSystem.instance(), queueName, parent,
        enableUserMetrics, conf);
  }

  /**
   * Helper method to clear cache.
   */
  @Private
  public synchronized static void clearQueueMetrics() {
    QUEUE_METRICS.clear();
  }

  /**
   * Simple metrics cache to help prevent re-registrations.
   */
  private static final Map<String, QueueMetrics> QUEUE_METRICS =
      new HashMap<String, QueueMetrics>();

  /**
   * Returns the metrics cache to help prevent re-registrations.
   *
   * @return A string to {@link QueueMetrics} map.
   */
  protected static Map<String, QueueMetrics> getQueueMetrics() {
    return QUEUE_METRICS;
  }

  public synchronized static QueueMetrics forQueue(MetricsSystem ms,
      String queueName, Queue parent, boolean enableUserMetrics,
      Configuration conf) {
    QueueMetrics metrics = getQueueMetrics().get(queueName);
    if (metrics == null) {
      metrics = new QueueMetrics(ms, queueName, parent, enableUserMetrics, conf)
          .tag(QUEUE_INFO, queueName);

      // Register with the MetricsSystems
      if (ms != null) {
        metrics = ms.register(sourceName(queueName).toString(),
            "Metrics for queue: " + queueName, metrics);
      }
      getQueueMetrics().put(queueName, metrics);
    }

    return metrics;
  }

  public synchronized QueueMetrics getUserMetrics(String userName) {
    if (users == null) {
      return null;
    }
    QueueMetrics metrics = users.get(userName);
    if (metrics == null) {
      metrics =
          new QueueMetrics(metricsSystem, queueName, null, false, conf);
      users.put(userName, metrics);
      metricsSystem.register(
          sourceName(queueName).append(",user=").append(userName).toString(),
          "Metrics for user '"+ userName +"' in queue '"+ queueName +"'",
          metrics.tag(QUEUE_INFO, queueName).tag(USER_INFO, userName));
    }
    return metrics;
  }

  /**
   * Partition * Queue Metrics
   *
   * Computes Metrics at Partition (Node Label) * Queue Level.
   *
   * Sample JMX O/P Structure:
   *
   * PartitionQueueMetrics (labelX)
   *  QueueMetrics (A)
   *    metrics
   *    QueueMetrics (A1)
   *      metrics
   *    QueueMetrics (A2)
   *      metrics
   *  QueueMetrics (B)
   *    metrics
   *
   * @param partition
   * @return QueueMetrics
   */
  public synchronized QueueMetrics getPartitionQueueMetrics(String partition) {

    String partitionJMXStr = partition;

    if ((partition == null)
        || (partition.equals(RMNodeLabelsManager.NO_LABEL))) {
      partition = DEFAULT_PARTITION;
      partitionJMXStr = DEFAULT_PARTITION_JMX_STR;
    }

    String metricName = partition + METRIC_NAME_DELIMITER + this.queueName;
    QueueMetrics metrics = getQueueMetrics().get(metricName);

    if (metrics == null) {
      QueueMetrics queueMetrics =
          new PartitionQueueMetrics(metricsSystem, this.queueName, parentQueue,
              this.enableUserMetrics, this.conf, partition);
      metricsSystem.register(
          pSourceName(partitionJMXStr).append(qSourceName(this.queueName))
              .toString(),
          "Metrics for queue: " + this.queueName,
          queueMetrics.tag(PARTITION_INFO, partitionJMXStr).tag(QUEUE_INFO,
              this.queueName));
      getQueueMetrics().put(metricName, queueMetrics);
      return queueMetrics;
    } else {
      return metrics;
    }
  }

  /**
   * Partition Metrics
   *
   * Computes Metrics at Partition (Node Label) Level.
   *
   * Sample JMX O/P Structure:
   *
   * PartitionQueueMetrics (labelX)
   *  metrics
   *
   * @param partition
   * @return QueueMetrics
   */
  private QueueMetrics getPartitionMetrics(String partition) {

    String partitionJMXStr = partition;
    if ((partition == null)
        || (partition.equals(RMNodeLabelsManager.NO_LABEL))) {
      partition = DEFAULT_PARTITION;
      partitionJMXStr = DEFAULT_PARTITION_JMX_STR;
    }

    String metricName = partition + METRIC_NAME_DELIMITER;
    QueueMetrics metrics = getQueueMetrics().get(metricName);
    if (metrics == null) {
      metrics = new PartitionQueueMetrics(metricsSystem, this.queueName, null,
          false, this.conf, partition);

      // Register with the MetricsSystems
      if (metricsSystem != null) {
        metricsSystem.register(pSourceName(partitionJMXStr).toString(),
            "Metrics for partition: " + partitionJMXStr,
            (PartitionQueueMetrics) metrics.tag(PARTITION_INFO,
                partitionJMXStr));
      }
      getQueueMetrics().put(metricName, metrics);
    }
    return metrics;
  }

  private ArrayList<Integer> parseInts(String value) {
    ArrayList<Integer> result = new ArrayList<Integer>();
    for(String s: value.split(",")) {
      result.add(Integer.parseInt(s.trim()));
    }
    return result;
  }

  private MutableGaugeInt[] buildBuckets(Configuration conf) {
    ArrayList<Integer> buckets = 
      parseInts(conf.get(YarnConfiguration.RM_METRICS_RUNTIME_BUCKETS,
		        YarnConfiguration.DEFAULT_RM_METRICS_RUNTIME_BUCKETS));
    MutableGaugeInt[] result = new MutableGaugeInt[buckets.size() + 1];
    result[0] = registry.newGauge("running_0", "", 0);
    long[] cuts = new long[buckets.size()];
    for(int i=0; i < buckets.size(); ++i) {
      result[i+1] = registry.newGauge("running_" + buckets.get(i), "", 0);
      cuts[i] = buckets.get(i) * 1000L * 60; // covert from min to ms
    }
    this.runBuckets = new TimeBucketMetrics<ApplicationId>(cuts);
    return result;
  }

  private void updateRunningTime() {
    int[] counts = runBuckets.getBucketCounts(System.currentTimeMillis());
    for(int i=0; i < counts.length; ++i) {
      runningTime[i].set(counts[i]); 
    }
  }

  public void getMetrics(MetricsCollector collector, boolean all) {
    updateRunningTime();
    registry.snapshot(collector.addRecord(registry.info()), all);
  }

  public void submitApp(String user) {
    appsSubmitted.incr();
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.submitApp(user);
    }
    if (parent != null) {
      parent.submitApp(user);
    }
  }

  public void submitAppAttempt(String user) {
    appsPending.incr();
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.submitAppAttempt(user);
    }
    if (parent != null) {
      parent.submitAppAttempt(user);
    }
  }

  public void runAppAttempt(ApplicationId appId, String user) {
    runBuckets.add(appId, System.currentTimeMillis());
    appsRunning.incr();
    appsPending.decr();
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.runAppAttempt(appId, user);
    }
    if (parent != null) {
      parent.runAppAttempt(appId, user);
    }
  }

  public void finishAppAttempt(
      ApplicationId appId, boolean isPending, String user) {
    runBuckets.remove(appId);
    if (isPending) {
      appsPending.decr();
    } else {
      appsRunning.decr();
    }
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.finishAppAttempt(appId, isPending, user);
    }
    if (parent != null) {
      parent.finishAppAttempt(appId, isPending, user);
    }
  }

  public void finishApp(String user, RMAppState rmAppFinalState) {
    switch (rmAppFinalState) {
      case KILLED: appsKilled.incr(); break;
      case FAILED: appsFailed.incr(); break;
      default: appsCompleted.incr();  break;
    }
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.finishApp(user, rmAppFinalState);
    }
    if (parent != null) {
      parent.finishApp(user, rmAppFinalState);
    }
  }
  
  public void moveAppFrom(AppSchedulingInfo app) {
    if (app.isPending()) {
      appsPending.decr();
    } else {
      appsRunning.decr();
    }
    QueueMetrics userMetrics = getUserMetrics(app.getUser());
    if (userMetrics != null) {
      userMetrics.moveAppFrom(app);
    }
    if (parent != null) {
      parent.moveAppFrom(app);
    }
  }
  
  public void moveAppTo(AppSchedulingInfo app) {
    if (app.isPending()) {
      appsPending.incr();
    } else {
      appsRunning.incr();
    }
    QueueMetrics userMetrics = getUserMetrics(app.getUser());
    if (userMetrics != null) {
      userMetrics.moveAppTo(app);
    }
    if (parent != null) {
      parent.moveAppTo(app);
    }
  }

  /**
   * Set available resources. To be called by scheduler periodically as
   * resources become available.
   * @param partition Node Partition
   * @param limit resource limit
   */
  public void setAvailableResourcesToQueue(String partition, Resource limit) {
    if (partition == null || partition.equals(RMNodeLabelsManager.NO_LABEL)) {
      setAvailableResources(limit);
    }
    QueueMetrics partitionQueueMetrics = getPartitionQueueMetrics(partition);
    if (partitionQueueMetrics != null) {
      partitionQueueMetrics.setAvailableResources(limit);
      if (this.queueName.equals("root")) {
        QueueMetrics partitionMetrics = getPartitionMetrics(partition);
        if (partitionMetrics != null) {
          partitionMetrics.setAvailableResources(limit);
        }
      }
    }
  }

  /**
   * Set Available resources with support for resource vectors.
   *
   * @param limit
   */
  public void setAvailableResources(Resource limit) {
    availableMB.set(limit.getMemorySize());
    availableVCores.set(limit.getVirtualCores());
    if (queueMetricsForCustomResources != null) {
      queueMetricsForCustomResources.setAvailable(limit);
      registerCustomResources(
          queueMetricsForCustomResources.getAvailableValues(),
          AVAILABLE_RESOURCE_METRIC_PREFIX, AVAILABLE_RESOURCE_METRIC_DESC);
    }
  }
  
  /**
   * Set available resources. To be called by scheduler periodically as
   * resources become available.
   * @param limit resource limit
   */
  public void setAvailableResourcesToQueue(Resource limit) {
    this.setAvailableResourcesToQueue(RMNodeLabelsManager.NO_LABEL, limit);
  }

  /**
   * Set available resources. To be called by scheduler periodically as
   * resources become available.
   * @param partition Node Partition
   * @param user
   * @param limit resource limit
   */
  public void setAvailableResourcesToUser(String partition,
      String user, Resource limit) {
    if(partition == null || partition.equals(RMNodeLabelsManager.NO_LABEL)) {
      QueueMetrics userMetrics = getUserMetrics(user);
      if (userMetrics != null) {
        userMetrics.setAvailableResources(limit);
      }
    }
    QueueMetrics partitionQueueMetrics = getPartitionQueueMetrics(partition);
    if (partitionQueueMetrics != null) {
      QueueMetrics partitionUserMetrics =
          partitionQueueMetrics.getUserMetrics(user);
      if (partitionUserMetrics != null) {
        partitionUserMetrics.setAvailableResources(limit);
      }
    }
  }

  /**
   * Increment pending resource metrics
   * @param partition Node Partition
   * @param user
   * @param containers
   * @param res the TOTAL delta of resources note this is different from
   *            the other APIs which use per container resource
   */
  public void incrPendingResources(String partition, String user,
      int containers, Resource res) {

    if (partition == null || partition.equals(RMNodeLabelsManager.NO_LABEL)) {
      internalIncrPendingResources(partition, user, containers, res);
    }

    QueueMetrics partitionQueueMetrics = getPartitionQueueMetrics(partition);
    if (partitionQueueMetrics != null) {
      partitionQueueMetrics.internalIncrPendingResources(partition, user,
          containers, res);
      QueueMetrics partitionMetrics = getPartitionMetrics(partition);
      if (partitionMetrics != null) {
        partitionMetrics.incrementPendingResources(containers, res);
      }
    }
  }

  public void internalIncrPendingResources(String partition, String user,
      int containers, Resource res) {
    incrementPendingResources(containers, res);
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.internalIncrPendingResources(partition, user, containers,
          res);
    }
    if (parent != null) {
      parent.internalIncrPendingResources(partition, user, containers, res);
    }
  }
  
  private void incrementPendingResources(int containers, Resource res) {
    pendingContainers.incr(containers);
    pendingMB.incr(res.getMemorySize() * containers);
    pendingVCores.incr(res.getVirtualCores() * containers);
    if (queueMetricsForCustomResources != null) {
      queueMetricsForCustomResources.increasePending(res, containers);
      registerCustomResources(queueMetricsForCustomResources.getPendingValues(),
          PENDING_RESOURCE_METRIC_PREFIX, PENDING_RESOURCE_METRIC_DESC);
    }
  }
  
  protected Map<String, Long> initAndGetCustomResources() {
    Map<String, Long> customResources = new HashMap<String, Long>();
    ResourceInformation[] resources = ResourceUtils.getResourceTypesArray();

    for (int i = 2; i < resources.length; i++) {
      ResourceInformation resource = resources[i];
      customResources.put(resource.getName(), Long.valueOf(0));
    }
    return customResources;
  }

  protected void createQueueMetricsForCustomResources() {
    if (ResourceUtils.getNumberOfKnownResourceTypes() > 2) {
      this.queueMetricsForCustomResources =
          new QueueMetricsForCustomResources();
      registerCustomResources();
    }
  }

  /**
   * Register all custom resources metrics as part of initialization. As and
   * when this metric object construction happens for any queue, all custom
   * resource metrics value would be initialized with '0' like any other
   * mandatory resources metrics
   */
  protected void registerCustomResources() {
    Map<String, Long> customResources = initAndGetCustomResources();
    registerCustomResources(customResources, ALLOCATED_RESOURCE_METRIC_PREFIX,
        ALLOCATED_RESOURCE_METRIC_DESC);
    registerCustomResources(customResources, AVAILABLE_RESOURCE_METRIC_PREFIX,
        AVAILABLE_RESOURCE_METRIC_DESC);
    registerCustomResources(customResources, PENDING_RESOURCE_METRIC_PREFIX,
        PENDING_RESOURCE_METRIC_DESC);
    registerCustomResources(customResources, RESERVED_RESOURCE_METRIC_PREFIX,
        RESERVED_RESOURCE_METRIC_DESC);
    registerCustomResources(customResources,
        AGGREGATE_PREEMPTED_SECONDS_METRIC_PREFIX,
        AGGREGATE_PREEMPTED_SECONDS_METRIC_DESC);
  }

  protected void registerCustomResources(Map<String, Long> customResources,
      String metricPrefix, String metricDesc) {
    for (Entry<String, Long> entry : customResources.entrySet()) {
      String resourceName = entry.getKey();
      Long resourceValue = entry.getValue();

      MutableGaugeLong resourceMetric =
        (MutableGaugeLong) this.registry.get(metricPrefix + resourceName);

      if (resourceMetric == null) {
        resourceMetric =
          this.registry.newGauge(metricPrefix + resourceName,
              metricDesc.replace("NAME", resourceName), 0L);
      }
      resourceMetric.set(resourceValue);
    }
  }

  public void decrPendingResources(String partition, String user,
      int containers, Resource res) {

    if (partition == null || partition.equals(RMNodeLabelsManager.NO_LABEL)) {
      internalDecrPendingResources(partition, user, containers, res);
    }

    QueueMetrics partitionQueueMetrics = getPartitionQueueMetrics(partition);
    if (partitionQueueMetrics != null) {
      partitionQueueMetrics.internalDecrPendingResources(partition, user,
          containers, res);
      QueueMetrics partitionMetrics = getPartitionMetrics(partition);
      if (partitionMetrics != null) {
        partitionMetrics.decrementPendingResources(containers, res);
      }
    }
  }

  protected void internalDecrPendingResources(String partition, String user,
      int containers, Resource res) {
    decrementPendingResources(containers, res);
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.internalDecrPendingResources(partition, user, containers,
          res);
    }
    if (parent != null) {
      parent.internalDecrPendingResources(partition, user, containers, res);
    }
  }

  private void decrementPendingResources(int containers, Resource res) {
    pendingContainers.decr(containers);
    pendingMB.decr(res.getMemorySize() * containers);
    pendingVCores.decr(res.getVirtualCores() * containers);
    if (queueMetricsForCustomResources != null) {
      queueMetricsForCustomResources.decreasePending(res, containers);
      registerCustomResources(queueMetricsForCustomResources.getPendingValues(),
          PENDING_RESOURCE_METRIC_PREFIX, PENDING_RESOURCE_METRIC_DESC);
    }
  }

  public void incrNodeTypeAggregations(String user, NodeType type) {
    if (type == NodeType.NODE_LOCAL) {
      aggregateNodeLocalContainersAllocated.incr();
    } else if (type == NodeType.RACK_LOCAL) {
      aggregateRackLocalContainersAllocated.incr();
    } else if (type == NodeType.OFF_SWITCH) {
      aggregateOffSwitchContainersAllocated.incr();
    } else {
      return;
    }
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.incrNodeTypeAggregations(user, type);
    }
    if (parent != null) {
      parent.incrNodeTypeAggregations(user, type);
    }
  }

  public void allocateResources(String partition, String user, int containers,
      Resource res, boolean decrPending) {

    if (partition == null || partition.equals(RMNodeLabelsManager.NO_LABEL)) {
      internalAllocateResources(partition, user, containers, res, decrPending);
    }

    QueueMetrics partitionQueueMetrics = getPartitionQueueMetrics(partition);
    if (partitionQueueMetrics != null) {
      partitionQueueMetrics.internalAllocateResources(partition, user,
          containers, res, decrPending);
      QueueMetrics partitionMetrics = getPartitionMetrics(partition);
      if (partitionMetrics != null) {
        partitionMetrics.computeAllocateResources(containers, res, decrPending);
      }
    }
  }

  public void internalAllocateResources(String partition, String user,
      int containers, Resource res, boolean decrPending) {
    computeAllocateResources(containers, res, decrPending);
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.internalAllocateResources(partition, user, containers, res,
          decrPending);
    }
    if (parent != null) {
      parent.internalAllocateResources(partition, user, containers, res,
          decrPending);
    }
  }
  
  /**
   * Allocate Resources for a partition with support for resource vectors.
   *
   * @param containers number of containers
   * @param res resource containing memory size, vcores etc
   * @param decrPending decides whether to decrease pending resource or not
   */
  private void computeAllocateResources(int containers, Resource res,
      boolean decrPending) {
    allocatedContainers.incr(containers);
    aggregateContainersAllocated.incr(containers);
    allocatedMB.incr(res.getMemorySize() * containers);
    allocatedVCores.incr(res.getVirtualCores() * containers);
    if (queueMetricsForCustomResources != null) {
      queueMetricsForCustomResources.increaseAllocated(res, containers);
      registerCustomResources(
          queueMetricsForCustomResources.getAllocatedValues(),
          ALLOCATED_RESOURCE_METRIC_PREFIX, ALLOCATED_RESOURCE_METRIC_DESC);
    }
    if (decrPending) {
      decrementPendingResources(containers, res);
    }
  }
  
  /**
   * Allocate Resource for container size change.
   * @param partition Node Partition
   * @param user
   * @param res
   */
  public void allocateResources(String partition, String user, Resource res) {
    allocatedMB.incr(res.getMemorySize());
    allocatedVCores.incr(res.getVirtualCores());
    if (queueMetricsForCustomResources != null) {
      queueMetricsForCustomResources.increaseAllocated(res);
      registerCustomResources(
          queueMetricsForCustomResources.getAllocatedValues(),
          ALLOCATED_RESOURCE_METRIC_PREFIX, ALLOCATED_RESOURCE_METRIC_DESC);
    }

    pendingMB.decr(res.getMemorySize());
    pendingVCores.decr(res.getVirtualCores());
    if (queueMetricsForCustomResources != null) {
      queueMetricsForCustomResources.decreasePending(res);
      registerCustomResources(
          queueMetricsForCustomResources.getPendingValues(),
          PENDING_RESOURCE_METRIC_PREFIX, PENDING_RESOURCE_METRIC_DESC);
    }

    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.allocateResources(partition, user, res);
    }
    if (parent != null) {
      parent.allocateResources(partition, user, res);
    }
  }

  public void releaseResources(String partition, String user, int containers,
      Resource res) {

    if (partition == null || partition.equals(RMNodeLabelsManager.NO_LABEL)) {
      internalReleaseResources(partition, user, containers, res);
    }

    QueueMetrics partitionQueueMetrics = getPartitionQueueMetrics(partition);
    if (partitionQueueMetrics != null) {
      partitionQueueMetrics.internalReleaseResources(partition, user,
          containers, res);
      QueueMetrics partitionMetrics = getPartitionMetrics(partition);
      if (partitionMetrics != null) {
        partitionMetrics.computeReleaseResources(containers, res);
      }
    }
  }

  public void internalReleaseResources(String partition, String user,
      int containers, Resource res) {

    computeReleaseResources(containers, res);
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.internalReleaseResources(partition, user, containers, res);
    }
    if (parent != null) {
      parent.internalReleaseResources(partition, user, containers, res);
    }
  }

  /**
   * Release Resources for a partition with support for resource vectors.
   *
   * @param containers number of containers
   * @param res resource containing memory size, vcores etc
   */
  private void computeReleaseResources(int containers, Resource res) {
    allocatedContainers.decr(containers);
    aggregateContainersReleased.incr(containers);
    allocatedMB.decr(res.getMemorySize() * containers);
    allocatedVCores.decr(res.getVirtualCores() * containers);
    if (queueMetricsForCustomResources != null) {
      queueMetricsForCustomResources.decreaseAllocated(res, containers);
      registerCustomResources(
          queueMetricsForCustomResources.getAllocatedValues(),
          ALLOCATED_RESOURCE_METRIC_PREFIX, ALLOCATED_RESOURCE_METRIC_DESC);
    }
  }

  public void preemptContainer() {
    aggregateContainersPreempted.incr();
    if (parent != null) {
      parent.preemptContainer();
    }
  }

  public void updatePreemptedMemoryMBSeconds(long mbSeconds) {
    aggregateMemoryMBSecondsPreempted.incr(mbSeconds);
    if (parent != null) {
      parent.updatePreemptedMemoryMBSeconds(mbSeconds);
    }
  }

  public void updatePreemptedVcoreSeconds(long vcoreSeconds) {
    aggregateVcoreSecondsPreempted.incr(vcoreSeconds);
    if (parent != null) {
      parent.updatePreemptedVcoreSeconds(vcoreSeconds);
    }
  }

  public void updatePreemptedSecondsForCustomResources(Resource res,
          long seconds) {
    if (queueMetricsForCustomResources != null) {
      queueMetricsForCustomResources
          .increaseAggregatedPreemptedSeconds(res, seconds);
      registerCustomResources(
          queueMetricsForCustomResources.getAggregatePreemptedSeconds()
              .getValues(),
          AGGREGATE_PREEMPTED_SECONDS_METRIC_PREFIX,
          AGGREGATE_PREEMPTED_SECONDS_METRIC_DESC);
    }
    if (parent != null) {
      parent.updatePreemptedSecondsForCustomResources(res, seconds);
    }
  }

  public void updatePreemptedResources(Resource res) {
    aggregateMemoryMBPreempted.incr(res.getMemorySize());
    aggregateVcoresPreempted.incr(res.getVirtualCores());
    if (parent != null) {
      parent.updatePreemptedResources(res);
    }
  }

  public void reserveResource(String partition, String user, Resource res) {
    if (partition == null || partition.equals(RMNodeLabelsManager.NO_LABEL)) {
      internalReserveResources(partition, user, res);
    }
    QueueMetrics partitionQueueMetrics = getPartitionQueueMetrics(partition);
    if (partitionQueueMetrics != null) {
      partitionQueueMetrics.internalReserveResources(partition, user, res);
      QueueMetrics partitionMetrics = getPartitionMetrics(partition);
      if (partitionMetrics != null) {
        partitionMetrics.incrReserveResources(res);
      }
    }
  }

  protected void internalReserveResources(String partition, String user,
      Resource res) {
    incrReserveResources(res);
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.internalReserveResources(partition, user, res);
    }
    if (parent != null) {
      parent.internalReserveResources(partition, user, res);
    }
  }

  public void incrReserveResources(Resource res) {
    reservedContainers.incr();
    reservedMB.incr(res.getMemorySize());
    reservedVCores.incr(res.getVirtualCores());
    if (queueMetricsForCustomResources != null) {
      queueMetricsForCustomResources.increaseReserved(res);
      registerCustomResources(
          queueMetricsForCustomResources.getReservedValues(),
          RESERVED_RESOURCE_METRIC_PREFIX, RESERVED_RESOURCE_METRIC_DESC);
    }
  }
  
  public void unreserveResource(String partition, String user, Resource res) {
    if (partition == null || partition.equals(RMNodeLabelsManager.NO_LABEL)) {
      internalUnReserveResources(partition, user, res);
    }
    QueueMetrics partitionQueueMetrics = getPartitionQueueMetrics(partition);
    if (partitionQueueMetrics != null) {
      partitionQueueMetrics.internalUnReserveResources(partition, user, res);
      QueueMetrics partitionMetrics = getPartitionMetrics(partition);
      if (partitionMetrics != null) {
        partitionMetrics.decrReserveResource(res);
      }
    }
  }

  protected void internalUnReserveResources(String partition, String user,
      Resource res) {
    decrReserveResource(res);
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.internalUnReserveResources(partition, user, res);
    }
    if (parent != null) {
      parent.internalUnReserveResources(partition, user, res);
    }
  }

  public void decrReserveResource(Resource res) {
    int containers = 1;
    reservedContainers.decr(containers);
    reservedMB.decr(res.getMemorySize());
    reservedVCores.decr(res.getVirtualCores());
    if (queueMetricsForCustomResources != null) {
      queueMetricsForCustomResources.decreaseReserved(res);
      registerCustomResources(
          queueMetricsForCustomResources.getReservedValues(),
          RESERVED_RESOURCE_METRIC_PREFIX, RESERVED_RESOURCE_METRIC_DESC);
    }
  }
  
  public void incrActiveUsers() {
    activeUsers.incr();
  }
  
  public void decrActiveUsers() {
    activeUsers.decr();
  }
  
  public void activateApp(String user) {
    activeApplications.incr();
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.activateApp(user);
    }
    if (parent != null) {
      parent.activateApp(user);
    }
  }
  
  public void deactivateApp(String user) {
    activeApplications.decr();
    QueueMetrics userMetrics = getUserMetrics(user);
    if (userMetrics != null) {
      userMetrics.deactivateApp(user);
    }
    if (parent != null) {
      parent.deactivateApp(user);
    }
  }

  public void addAppAttemptFirstContainerAllocationDelay(long latency) {
    appAttemptFirstContainerAllocationDelay.add(latency);
  }

  public int getAppsSubmitted() {
    return appsSubmitted.value();
  }

  public int getAppsRunning() {
    return appsRunning.value();
  }

  public int getAppsPending() {
    return appsPending.value();
  }

  public int getAppsCompleted() {
    return appsCompleted.value();
  }

  public int getAppsKilled() {
    return appsKilled.value();
  }

  public int getAppsFailed() {
    return appsFailed.value();
  }

  public Resource getAllocatedResources() {
    if (queueMetricsForCustomResources != null) {
      return Resource.newInstance(allocatedMB.value(), allocatedVCores.value(),
              queueMetricsForCustomResources.getAllocatedValues());
    }
    return Resource.newInstance(allocatedMB.value(),
            allocatedVCores.value());
  }

  public Resource getAvailableResources() {
    if (queueMetricsForCustomResources != null) {
      return Resource.newInstance(availableMB.value(), availableVCores.value(),
          queueMetricsForCustomResources.getAvailableValues());
    }
    return Resource.newInstance(availableMB.value(), availableVCores.value());
  }

  public Resource getPendingResources() {
    if (queueMetricsForCustomResources != null) {
      return Resource.newInstance(pendingMB.value(), pendingVCores.value(),
          queueMetricsForCustomResources.getPendingValues());
    }
    return Resource.newInstance(pendingMB.value(), pendingVCores.value());
  }

  public Resource getReservedResources() {
    if (queueMetricsForCustomResources != null) {
      return Resource.newInstance(reservedMB.value(), reservedVCores.value(),
          queueMetricsForCustomResources.getReservedValues());
    }
    return Resource.newInstance(reservedMB.value(), reservedVCores.value());
  }

  /**
   * Handle this specially as this has a long value and it could be
   * truncated when casted into an int parameter of
   * Resource.newInstance (vCores).
   * @return QueueMetricsCustomResource
   */
  @VisibleForTesting
  public QueueMetricsCustomResource getAggregatedPreemptedSecondsResources() {
    return queueMetricsForCustomResources.getAggregatePreemptedSeconds();
  }

  @VisibleForTesting
  public MutableCounterLong getAggregateMemoryMBSecondsPreempted() {
    return aggregateMemoryMBSecondsPreempted;
  }

  @VisibleForTesting
  public MutableCounterLong getAggregateVcoreSecondsPreempted() {
    return aggregateVcoreSecondsPreempted;
  }

  public long getAllocatedMB() {
    return allocatedMB.value();
  }
  
  public int getAllocatedVirtualCores() {
    return allocatedVCores.value();
  }

  public int getAllocatedContainers() {
    return allocatedContainers.value();
  }

  public long getAvailableMB() {
    return availableMB.value();
  }  
  
  public int getAvailableVirtualCores() {
    return availableVCores.value();
  }

  public long getPendingMB() {
    return pendingMB.value();
  }
  
  public int getPendingVirtualCores() {
    return pendingVCores.value();
  }

  public int getPendingContainers() {
    return pendingContainers.value();
  }
  
  public long getReservedMB() {
    return reservedMB.value();
  }
  
  public int getReservedVirtualCores() {
    return reservedVCores.value();
  }

  public int getReservedContainers() {
    return reservedContainers.value();
  }
  
  public int getActiveUsers() {
    return activeUsers.value();
  }
  
  public int getActiveApps() {
    return activeApplications.value();
  }
  
  public MetricsSystem getMetricsSystem() {
    return metricsSystem;
  }

  public long getAggregateAllocatedContainers() {
    return aggregateContainersAllocated.value();
  }

  public long getAggregateNodeLocalContainersAllocated() {
    return aggregateNodeLocalContainersAllocated.value();
  }

  public long getAggregateRackLocalContainersAllocated() {
    return aggregateRackLocalContainersAllocated.value();
  }

  public long getAggregateOffSwitchContainersAllocated() {
    return aggregateOffSwitchContainersAllocated.value();
  }

  public long getAggegatedReleasedContainers() {
    return aggregateContainersReleased.value();
  }

  public long getAggregatePreemptedContainers() {
    return aggregateContainersPreempted.value();
  }

  @VisibleForTesting
  public long getAggregateMemoryMBPreempted() {
    return aggregateMemoryMBPreempted.value();
  }

  @VisibleForTesting
  public long getAggregateVcoresPreempted() {
    return aggregateVcoresPreempted.value();
  }

  @VisibleForTesting
  public QueueMetricsForCustomResources getQueueMetricsForCustomResources() {
    return this.queueMetricsForCustomResources;
  }

  public void setQueueMetricsForCustomResources(
      QueueMetricsForCustomResources metrics) {
    this.queueMetricsForCustomResources = metrics;
  }

  public void setParent(QueueMetrics parent) {
    this.parent = parent;
  }

  public Queue getParentQueue() {
    return parentQueue;
  }
}
