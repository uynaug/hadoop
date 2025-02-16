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

import com.google.inject.Inject;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.ResourceInformation;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.nodelabels.CommonNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager;
import org.apache.hadoop.yarn.server.resourcemanager.nodelabels.RMNodeLabelsManager;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.webapp.dao.NodeInfo;
import org.apache.hadoop.yarn.util.Times;
import org.apache.hadoop.yarn.util.resource.ResourceUtils;
import org.apache.hadoop.yarn.webapp.SubView;
import org.apache.hadoop.yarn.webapp.hamlet.Hamlet;
import org.apache.hadoop.yarn.webapp.hamlet.Hamlet.TABLE;
import org.apache.hadoop.yarn.webapp.hamlet.Hamlet.TBODY;
import org.apache.hadoop.yarn.webapp.view.HtmlBlock;

import java.util.Collection;

import static org.apache.hadoop.yarn.webapp.YarnWebParams.NODE_LABEL;
import static org.apache.hadoop.yarn.webapp.YarnWebParams.NODE_STATE;
import static org.apache.hadoop.yarn.webapp.view.JQueryUI.DATATABLES;
import static org.apache.hadoop.yarn.webapp.view.JQueryUI.DATATABLES_ID;
import static org.apache.hadoop.yarn.webapp.view.JQueryUI.initID;
import static org.apache.hadoop.yarn.webapp.view.JQueryUI.tableInit;

class NodesPage extends RmView {

  static class NodesBlock extends HtmlBlock {
    final ResourceManager rm;
    private static final long BYTES_IN_MB = 1024 * 1024;
    private static final long BYTES_IN_GB = 1024 * 1024 * 1024;
    private static boolean opportunisticContainersEnabled;

    @Inject
    NodesBlock(ResourceManager rm, ViewContext ctx) {
      super(ctx);
      this.rm = rm;
      this.opportunisticContainersEnabled = YarnConfiguration
          .isOpportunisticContainerAllocationEnabled(
              this.rm.getRMContext().getYarnConfiguration());
    }

    @Override
    protected void render(Block html) {
      html._(MetricsOverviewTable.class);

      ResourceScheduler sched = rm.getResourceScheduler();

      String type = $(NODE_STATE);
      String labelFilter = $(NODE_LABEL, CommonNodeLabelsManager.ANY).trim();
      Hamlet.TR<Hamlet.THEAD<TABLE<Hamlet>>> trbody =
          html.table("#nodes").thead().tr()
              .th(".nodelabels", "Node Labels")
              .th(".rack", "Rack")
              .th(".state", "Node State")
              .th(".nodeaddress", "Node Address")
              .th(".nodehttpaddress", "Node HTTP Address")
              .th(".lastHealthUpdate", "Last health-update")
              .th(".healthReport", "Health-report");

      if (!this.opportunisticContainersEnabled) {
        trbody.th(".containers", "Containers")
            .th(".mem", "Mem Used")
            .th(".mem", "Mem Avail")
            .th(".mem", "Phys Mem Used %")
            .th(".vcores", "VCores Used")
            .th(".vcores", "VCores Avail")
            .th(".vcores", "Phys VCores Used %")
            .th(".gpus", "GPUs Used")
            .th(".gpus", "GPUs Avail");
      } else {
        trbody.th(".containers", "Running Containers (G)")
            .th(".mem", "Mem Used (G)")
            .th(".mem", "Mem Avail (G)")
            .th(".mem", "Phys Mem Used %")
            .th(".vcores", "VCores Used (G)")
            .th(".vcores", "VCores Avail (G)")
            .th(".vcores", "Phys VCores Used %")
            .th(".gpus", "GPUs Used (G)")
            .th(".gpus", "GPUs Avail (G)")
            .th(".containers", "Running Containers (O)")
            .th(".mem", "Mem Used (O)")
            .th(".vcores", "VCores Used (O)")
            .th(".containers", "Queued Containers");
      }

      TBODY<TABLE<Hamlet>> tbody =
          trbody.th(".nodeManagerVersion", "Version")._()._().tbody();

      NodeState stateFilter = null;
      if (type != null && !type.isEmpty()) {
        stateFilter = NodeState.valueOf(StringUtils.toUpperCase(type));
      }
      Collection<RMNode> rmNodes = this.rm.getRMContext().getRMNodes().values();
      boolean isInactive = false;
      if (stateFilter != null) {
        switch (stateFilter) {
        case DECOMMISSIONED:
        case LOST:
        case REBOOTED:
        case SHUTDOWN:
          rmNodes = this.rm.getRMContext().getInactiveRMNodes().values();
          isInactive = true;
          break;
        case DECOMMISSIONING:
          // Do nothing
          break;
        default:
          LOG.debug("Unexpected state filter for inactive RM node");
        }
      }
      StringBuilder nodeTableData = new StringBuilder("[\n");
      for (RMNode ni : rmNodes) {
        if (stateFilter != null) {
          NodeState state = ni.getState();
          if (!stateFilter.equals(state)) {
            continue;
          }
        } else {
          // No filter. User is asking for all nodes. Make sure you skip the
          // unhealthy nodes.
          if (ni.getState() == NodeState.UNHEALTHY) {
            continue;
          }
        }
        // Besides state, we need to filter label as well.
        if (!labelFilter.equals(RMNodeLabelsManager.ANY)) {
          if (labelFilter.isEmpty()) {
            // Empty label filter means only shows nodes without label
            if (!ni.getNodeLabels().isEmpty()) {
              continue;
            }
          } else if (!ni.getNodeLabels().contains(labelFilter)) {
            // Only nodes have given label can show on web page.
            continue;
          }
        }
        NodeInfo info = new NodeInfo(ni, sched);
        int usedMemory = (int) info.getUsedMemory();
        int availableMemory = (int) info.getAvailableMemory();
        nodeTableData.append("[\"")
            .append(StringUtils.join(",", info.getNodeLabels())).append("\",\"")
            .append(info.getRack()).append("\",\"").append(info.getState())
            .append("\",\"").append(info.getNodeId());
        if (isInactive) {
          nodeTableData.append("\",\"").append("N/A").append("\",\"");
        } else {
          String httpAddress = info.getNodeHTTPAddress();
          nodeTableData.append("\",\"<a ").append("href='" + "//" + httpAddress)
              .append("'>").append(httpAddress).append("</a>\",").append("\"");
        }
        Integer gpuIndex = ResourceUtils.getResourceTypeIndex()
            .get(ResourceInformation.GPU_URI);
        long usedGPUs = 0;
        long availableGPUs = 0;
        if (gpuIndex != null && info.getUsedResource() != null
            && info.getAvailableResource() != null) {
          usedGPUs = info.getUsedResource().getResource()
              .getResourceValue(ResourceInformation.GPU_URI);
          availableGPUs = info.getAvailableResource().getResource()
              .getResourceValue(ResourceInformation.GPU_URI);
        }
        nodeTableData.append("<br title='")
            .append(String.valueOf(info.getLastHealthUpdate())).append("'>")
            .append(Times.format(info.getLastHealthUpdate())).append("\",\"")
            .append(StringEscapeUtils.escapeJava(info.getHealthReport())).append("\",\"")
            .append(String.valueOf(info.getNumContainers())).append("\",\"")
            .append("<br title='").append(String.valueOf(usedMemory))
            .append("'>").append(StringUtils.byteDesc(usedMemory * BYTES_IN_MB))
            .append("\",\"").append("<br title='")
            .append(String.valueOf(availableMemory)).append("'>")
            .append(StringUtils.byteDesc(availableMemory * BYTES_IN_MB))
            .append("\",\"")
            .append(String.valueOf((int) info.getMemUtilization()))
            .append("\",\"")
            .append(String.valueOf(info.getUsedVirtualCores()))
            .append("\",\"")
            .append(String.valueOf(info.getAvailableVirtualCores()))
            .append("\",\"")
            .append(String.valueOf((int) info.getVcoreUtilization()))
            .append("\",\"")
            .append(String.valueOf(usedGPUs))
            .append("\",\"")
            .append(String.valueOf(availableGPUs))
            .append("\",\"");

        // If opportunistic containers are enabled, add extra fields.
        if (this.opportunisticContainersEnabled) {
          nodeTableData
              .append(String.valueOf(info.getNumRunningOpportContainers()))
              .append("\",\"").append("<br title='")
              .append(String.valueOf(info.getUsedMemoryOpportGB())).append("'>")
              .append(StringUtils.byteDesc(
                  info.getUsedMemoryOpportGB() * BYTES_IN_GB))
              .append("\",\"")
              .append(String.valueOf(info.getUsedVirtualCoresOpport()))
              .append("\",\"")
              .append(String.valueOf(info.getNumQueuedContainers()))
              .append("\",\"");
        }

        nodeTableData.append(ni.getNodeManagerVersion())
            .append("\"],\n");
      }
      if (nodeTableData.charAt(nodeTableData.length() - 2) == ',') {
        nodeTableData.delete(nodeTableData.length() - 2,
            nodeTableData.length() - 1);
      }
      nodeTableData.append("]");
      html.script().$type("text/javascript")
          ._("var nodeTableData=" + nodeTableData)._();
      tbody._()._();
    }
  }

  @Override
  protected void preHead(Page.HTML<_> html) {
    commonPreHead(html);
    String type = $(NODE_STATE);
    String title = "Nodes of the cluster";
    if (type != null && !type.isEmpty()) {
      title = title + " (" + type + ")";
    }
    setTitle(title);
    set(DATATABLES_ID, "nodes");
    set(initID(DATATABLES, "nodes"), nodesTableInit());
    setTableStyles(html, "nodes", ".healthStatus {width:10em}",
        ".healthReport {width:10em}");
  }

  @Override
  protected Class<? extends SubView> content() {
    return NodesBlock.class;
  }

  private String nodesTableInit() {
    StringBuilder b = tableInit().append(", 'aaData': nodeTableData")
        .append(", bDeferRender: true").append(", bProcessing: true")
        .append(", aoColumnDefs: [");
    b.append("{'bSearchable': false, 'aTargets': [ 7 ]}");
    b.append(", {'sType': 'title-numeric', 'bSearchable': false, "
        + "'aTargets': [ 8, 9 ] }");
    b.append(", {'sType': 'title-numeric', 'aTargets': [ 5 ]}");
    b.append("]}");
    return b.toString();
  }
}
