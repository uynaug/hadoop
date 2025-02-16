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

package org.apache.hadoop.yarn.server.resourcemanager.webapp;

import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceTypeInfo;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager;
import org.apache.hadoop.yarn.server.resourcemanager.webapp.dao.ClusterMetricsInfo;
import org.apache.hadoop.yarn.server.resourcemanager.webapp.dao.SchedulerInfo;
import org.apache.hadoop.yarn.server.resourcemanager.webapp.dao.UserMetricsInfo;

import org.apache.hadoop.yarn.util.resource.ResourceUtils;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.apache.hadoop.yarn.webapp.hamlet.Hamlet;
import org.apache.hadoop.yarn.webapp.hamlet.Hamlet.DIV;
import org.apache.hadoop.yarn.webapp.view.HtmlBlock;

import com.google.inject.Inject;

import java.util.Arrays;

/**
 * Provides an table with an overview of many cluster wide metrics and if
 * per user metrics are enabled it will show an overview of what the
 * current user is using on the cluster.
 */
public class MetricsOverviewTable extends HtmlBlock {
  private static final long BYTES_IN_MB = 1024 * 1024;

  private final ResourceManager rm;

  @Inject
  MetricsOverviewTable(ResourceManager rm, ViewContext ctx) {
    super(ctx);
    this.rm = rm;
  }


  @Override
  protected void render(Block html) {
    //Yes this is a hack, but there is no other way to insert
    //CSS in the correct spot
    html.style(".metrics {margin-bottom:5px}"); 
    
    ClusterMetricsInfo clusterMetrics = new ClusterMetricsInfo(this.rm);
    
    DIV<Hamlet> div = html.div().$class("metrics");

    Resource usedResources;
    Resource totalResources;
    Resource reservedResources;
    int allocatedContainers;
    if (clusterMetrics.getCrossPartitionMetricsAvailable()) {
      allocatedContainers =
          clusterMetrics.getTotalAllocatedContainersAcrossPartition();
      usedResources =
          clusterMetrics.getTotalUsedResourcesAcrossPartition().getResource();
      totalResources =
          clusterMetrics.getTotalClusterResourcesAcrossPartition()
          .getResource();
      reservedResources =
          clusterMetrics.getTotalReservedResourcesAcrossPartition()
          .getResource();
      // getTotalUsedResourcesAcrossPartition includes reserved resources.
      Resources.subtractFrom(usedResources, reservedResources);
    } else {
      allocatedContainers = clusterMetrics.getContainersAllocated();
      usedResources = Resource.newInstance(
          clusterMetrics.getAllocatedMB(),
          (int) clusterMetrics.getAllocatedVirtualCores());
      totalResources = Resource.newInstance(
          clusterMetrics.getTotalMB(),
          (int) clusterMetrics.getTotalVirtualCores());
      reservedResources = Resource.newInstance(
          clusterMetrics.getReservedMB(),
          (int) clusterMetrics.getReservedVirtualCores());
    }

    div.h3("Cluster Metrics").
    table("#metricsoverview").
    thead().$class("ui-widget-header").
      tr().
        th().$class("ui-state-default")._("Apps Submitted")._().
        th().$class("ui-state-default")._("Apps Pending")._().
        th().$class("ui-state-default")._("Apps Running")._().
        th().$class("ui-state-default")._("Apps Completed")._().
        th().$class("ui-state-default")._("Containers Running")._().
        th().$class("ui-state-default")._("Used Resources")._().
        th().$class("ui-state-default")._("Total Resources")._().
        th().$class("ui-state-default")._("Reserved Resources")._().
        th().$class("ui-state-default")._("Physical Mem Used %")._().
        th().$class("ui-state-default")._("Physical VCores Used %")._().
      _().
    _().
    tbody().$class("ui-widget-content").
      tr().
        td(String.valueOf(clusterMetrics.getAppsSubmitted())).
        td(String.valueOf(clusterMetrics.getAppsPending())).
        td(String.valueOf(clusterMetrics.getAppsRunning())).
        td(
            String.valueOf(
                clusterMetrics.getAppsCompleted() + 
                clusterMetrics.getAppsFailed() + clusterMetrics.getAppsKilled()
                )
            ).
        td(String.valueOf(allocatedContainers)).
        td(usedResources.getFormattedString()).
        td(totalResources.getFormattedString()).
        td(reservedResources.getFormattedString()).
        td(String.valueOf(clusterMetrics.getUtilizedMBPercent())).
        td(String.valueOf(clusterMetrics.getUtilizedVirtualCoresPercent())).
      _().
    _()._();

    div.h3("Cluster Nodes Metrics").
    table("#nodemetricsoverview").
    thead().$class("ui-widget-header").
      tr().
        th().$class("ui-state-default")._("Active Nodes")._().
        th().$class("ui-state-default")._("Decommissioning Nodes")._().
        th().$class("ui-state-default")._("Decommissioned Nodes")._().
        th().$class("ui-state-default")._("Lost Nodes")._().
        th().$class("ui-state-default")._("Unhealthy Nodes")._().
        th().$class("ui-state-default")._("Rebooted Nodes")._().
        th().$class("ui-state-default")._("Shutdown Nodes")._().
      _().
    _().
    tbody().$class("ui-widget-content").
      tr().
        td().a(url("nodes"),String.valueOf(clusterMetrics.getActiveNodes()))._().
        td().a(url("nodes/decommissioning"), String.valueOf(clusterMetrics.getDecommissioningNodes()))._().
        td().a(url("nodes/decommissioned"),String.valueOf(clusterMetrics.getDecommissionedNodes()))._().
        td().a(url("nodes/lost"),String.valueOf(clusterMetrics.getLostNodes()))._().
        td().a(url("nodes/unhealthy"),String.valueOf(clusterMetrics.getUnhealthyNodes()))._().
        td().a(url("nodes/rebooted"),String.valueOf(clusterMetrics.getRebootedNodes()))._().
        td().a(url("nodes/shutdown"),String.valueOf(clusterMetrics.getShutdownNodes()))._().
      _().
    _()._();

    String user = request().getRemoteUser();
    if (user != null) {
      UserMetricsInfo userMetrics = new UserMetricsInfo(this.rm, user);
      if (userMetrics.metricsAvailable()) {
        div.h3("User Metrics for " + user).
        table("#usermetricsoverview").
        thead().$class("ui-widget-header").
          tr().
            th().$class("ui-state-default")._("Apps Submitted")._().
            th().$class("ui-state-default")._("Apps Pending")._().
            th().$class("ui-state-default")._("Apps Running")._().
            th().$class("ui-state-default")._("Apps Completed")._().
            th().$class("ui-state-default")._("Containers Running")._().
            th().$class("ui-state-default")._("Containers Pending")._().
            th().$class("ui-state-default")._("Containers Reserved")._().
            th().$class("ui-state-default")._("Memory Used")._().
            th().$class("ui-state-default")._("Memory Pending")._().
            th().$class("ui-state-default")._("Memory Reserved")._().
            th().$class("ui-state-default")._("VCores Used")._().
            th().$class("ui-state-default")._("VCores Pending")._().
            th().$class("ui-state-default")._("VCores Reserved")._().
          _().
        _().
        tbody().$class("ui-widget-content").
          tr().
            td(String.valueOf(userMetrics.getAppsSubmitted())).
            td(String.valueOf(userMetrics.getAppsPending())).
            td(String.valueOf(userMetrics.getAppsRunning())).
            td(
                String.valueOf(
                    (userMetrics.getAppsCompleted() + 
                     userMetrics.getAppsFailed() + userMetrics.getAppsKilled())
                    )
              ).
            td(String.valueOf(userMetrics.getRunningContainers())).
            td(String.valueOf(userMetrics.getPendingContainers())).
            td(String.valueOf(userMetrics.getReservedContainers())).
            td(StringUtils.byteDesc(userMetrics.getAllocatedMB() * BYTES_IN_MB)).
            td(StringUtils.byteDesc(userMetrics.getPendingMB() * BYTES_IN_MB)).
            td(StringUtils.byteDesc(userMetrics.getReservedMB() * BYTES_IN_MB)).
            td(String.valueOf(userMetrics.getAllocatedVirtualCores())).
            td(String.valueOf(userMetrics.getPendingVirtualCores())).
            td(String.valueOf(userMetrics.getReservedVirtualCores())).
          _().
        _()._();
        
      }
    }

    SchedulerInfo schedulerInfo = new SchedulerInfo(this.rm);
    
    div.h3("Scheduler Metrics").
    table("#schedulermetricsoverview").
    thead().$class("ui-widget-header").
      tr().
        th().$class("ui-state-default")._("Scheduler Type")._().
        th().$class("ui-state-default")._("Scheduling Resource Type")._().
        th().$class("ui-state-default")._("Minimum Allocation")._().
        th().$class("ui-state-default")._("Maximum Allocation")._().
        th().$class("ui-state-default")
            ._("Maximum Cluster Application Priority")._().
      _().
    _().
    tbody().$class("ui-widget-content").
      tr().
        td(String.valueOf(schedulerInfo.getSchedulerType())).
        td(String.valueOf(Arrays.toString(ResourceUtils.getResourcesTypeInfo()
            .toArray(new ResourceTypeInfo[0])))).
        td(schedulerInfo.getMinAllocation().toString()).
        td(schedulerInfo.getMaxAllocation().toString()).
        td(String.valueOf(schedulerInfo.getMaxClusterLevelAppPriority())).
      _().
    _()._();

    div._();
  }
}
