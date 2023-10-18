/*
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

package org.apache.zeppelin.interpreter.launcher;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

@EnableKubernetesMockClient(https = false, crud = true)
class PodPhaseWatcherTest {

  KubernetesClient client;

  @Test
  void testPhase() throws InterruptedException {
    // CREATE
    client.pods().inNamespace("ns1")
        .create(new PodBuilder().withNewMetadata().withName("pod1").endMetadata().build());
    // READ
    PodList podList = client.pods().inNamespace("ns1").list();
    assertNotNull(podList);
    assertEquals(1, podList.getItems().size());
    Pod pod = podList.getItems().get(0);
    // WATCH
    PodPhaseWatcher podWatcher = new PodPhaseWatcher(
        phase -> StringUtils.equalsAnyIgnoreCase(phase, "Succeeded", "Failed", "Running"));
    try (Watch watch = client.pods().inNamespace("ns1").withName("pod1").watch(podWatcher)) {

      // Update Pod to "pending" phase
      pod.setStatus(new PodStatus(null, null, null, null, null, null, null, "Pending", null, null,
              null, null, null));
      pod = client.pods().inNamespace("ns1").updateStatus(pod);

      // Wait a little bit, till update is applied
      Thread.sleep(1000);
      // Update Pod to "Running" phase
      pod.setStatus(new PodStatusBuilder(new PodStatus(null, null, null, null, null, null, null,
              "Running", null, null, null, null, null)).build());
      client.pods().inNamespace("ns1").updateStatus(pod);

      assertTrue(podWatcher.getCountDownLatch().await(1, TimeUnit.SECONDS));
    }
  }

  @Test
  void testPhaseWithError() throws InterruptedException {
    // CREATE
    client.pods().inNamespace("ns1")
        .create(new PodBuilder().withNewMetadata().withName("pod1").endMetadata().build());
    // READ
    PodList podList = client.pods().inNamespace("ns1").list();
    assertNotNull(podList);
    assertEquals(1, podList.getItems().size());
    // WATCH
    PodPhaseWatcher podWatcher = new PodPhaseWatcher(
        phase -> StringUtils.equalsAnyIgnoreCase(phase, "Succeeded", "Failed", "Running"));
    Watch watch = client.pods().inNamespace("ns1").withName("pod1").watch(podWatcher);

    // In the case of close, we do not block thread execution
    watch.close();
    assertTrue(podWatcher.getCountDownLatch().await(1, TimeUnit.SECONDS));
  }
}
