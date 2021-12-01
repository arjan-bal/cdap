/*
 * Copyright © 2021 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.app;

import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.inject.Inject;
import com.sun.management.OperatingSystemMXBean;
import io.cdap.cdap.api.metrics.MetricsCollectionService;
import io.cdap.cdap.api.metrics.MetricsContext;
import io.cdap.cdap.common.conf.CConfiguration;
import io.cdap.cdap.common.conf.Constants;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

public class JMXMetricsCollector extends AbstractScheduledService {
  private final CConfiguration cConf;
  private final Configuration hConf;
  private final MetricsCollectionService metricsCollector;
  private static final Logger LOG = LoggerFactory.getLogger(JMXMetricsCollector.class);
  private final String serverUrl;
  private static final long megaByte = 1024 * 1024;

  @Inject
  public JMXMetricsCollector(CConfiguration cConf, Configuration hConf, MetricsCollectionService metrics) {
    this.cConf = cConf;
    this.hConf = hConf;
    this.metricsCollector = metrics;
    this.serverUrl = String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", "localhost",
                                   cConf.getInt(Constants.JMXMetricsCollector.SERVER_PORT));
  }

  @Override
  protected void startUp() {
    LOG.info("Starting JMXMetricsCollector.");
  }

  @Override
  protected void shutDown() {
    LOG.info("Shutting down JMXMetricsCollector has completed.");
  }

  @Override
  protected void runOneIteration() {
    // TODO: Get component name according to the pod this service is running in
    MetricsContext context = metricsCollector.getContext(Constants.Metrics.APP_FABRIC_CONTEXT);
    MBeanServerConnection mBeanConn;
    try {
      JMXServiceURL serviceUrl = new JMXServiceURL(serverUrl);
      JMXConnector jmxConnector = JMXConnectorFactory.connect(serviceUrl, null);
      mBeanConn = jmxConnector.getMBeanServerConnection();
    } catch (Exception e) {
      LOG.warn("Error occurred while connecting to JMX server: " + e.getMessage());
      return;
    }
    getAndPublishMemoryMetrics(mBeanConn, context);
    getAndPublishCPUMetrics(mBeanConn, context);
    getAndPublishThreadMetrics(mBeanConn, context);
  }

  private void getAndPublishMemoryMetrics(MBeanServerConnection mBeanConn, MetricsContext context) {
    MemoryMXBean mxBean;
    try {
      mxBean = ManagementFactory.newPlatformMXBeanProxy(mBeanConn, ManagementFactory.MEMORY_MXBEAN_NAME,
                                MemoryMXBean.class);
    } catch (IOException e) {
      LOG.warn("Error occurred while collecting memory metrics from JMX: " + e.getMessage());
      return;
    }
    MemoryUsage heapMemoryUsage = mxBean.getHeapMemoryUsage();
    context.gauge(Constants.Metrics.JVMResource.HEAP_MEMORY_USED_MB, heapMemoryUsage.getUsed() / megaByte);
    context.gauge(Constants.Metrics.JVMResource.HEAP_MEMORY_MAX_MB, heapMemoryUsage.getMax() / megaByte);
  }

  private void getAndPublishCPUMetrics(MBeanServerConnection conn, MetricsContext context) {
    OperatingSystemMXBean mxBean;
    try {
      mxBean = ManagementFactory.newPlatformMXBeanProxy(conn, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME,
                                OperatingSystemMXBean.class);
    } catch (IOException e) {
      LOG.warn("Error occurred while collecting memory metrics from JMX: " + e.getMessage());
      return;
    }
    double systemCPULoad = mxBean.getSystemCpuLoad();
    if (systemCPULoad < 0) {
      LOG.info("CPU load fro JVM process is not yet available");
      return;
    }
    context.gauge(Constants.Metrics.JVMResource.PROCESS_CPU_LOAD_PERCENT, (long) systemCPULoad * 100);
  }

  private void getAndPublishThreadMetrics(MBeanServerConnection conn, MetricsContext context) {
    ThreadMXBean mxBean;
    try {
      mxBean = ManagementFactory.newPlatformMXBeanProxy(conn, ManagementFactory.THREAD_MXBEAN_NAME,
                                ThreadMXBean.class);
    } catch (IOException e) {
      LOG.warn("Error occurred while collecting thread metrics from JMX: " + e.getMessage());
      return;
    }
    context.gauge(Constants.Metrics.JVMResource.THREAD_COUNT, mxBean.getThreadCount());
  }

  @Override
  protected Scheduler scheduler() {
    return Scheduler.newFixedRateSchedule(0,
                                          cConf.getInt(Constants.JMXMetricsCollector.POLL_INTERVAL), TimeUnit.SECONDS);
  }
}
