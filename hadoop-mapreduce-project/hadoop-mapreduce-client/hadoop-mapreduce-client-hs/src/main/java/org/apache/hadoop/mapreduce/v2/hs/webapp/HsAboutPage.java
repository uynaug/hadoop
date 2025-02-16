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

package org.apache.hadoop.mapreduce.v2.hs.webapp;

import static org.apache.hadoop.yarn.webapp.view.JQueryUI.ACCORDION;
import static org.apache.hadoop.yarn.webapp.view.JQueryUI.initID;

import org.apache.hadoop.mapreduce.v2.hs.JobHistoryServer;
import org.apache.hadoop.mapreduce.v2.hs.webapp.dao.HistoryInfo;
import org.apache.hadoop.yarn.util.Times;
import org.apache.hadoop.yarn.webapp.SubView;
import org.apache.hadoop.yarn.webapp.view.InfoBlock;

/**
 * A Page the shows info about the history server
 */
public class HsAboutPage extends HsView {

  /*
   * (non-Javadoc)
   * @see org.apache.hadoop.mapreduce.v2.hs.webapp.HsView#preHead(org.apache.hadoop.yarn.webapp.hamlet.Hamlet.HTML)
   */
  @Override protected void preHead(Page.HTML<_> html) {
    commonPreHead(html);
    //override the nav config from commonPReHead
    set(initID(ACCORDION, "nav"), "{autoHeight:false, active:0}");
    setTitle("About History Server");
  }

  /**
   * The content of this page is the attempts block
   * @return AttemptsBlock.class
   */
  @Override protected Class<? extends SubView> content() {
    HistoryInfo info = new HistoryInfo();
    info("History Server").
      _("BuildVersion", info.getHadoopBuildVersion()
        + " on " + info.getHadoopVersionBuiltOn()).
      _("History Server started on", Times.format(info.getStartedOn()));
    return InfoBlock.class;
  }
}
