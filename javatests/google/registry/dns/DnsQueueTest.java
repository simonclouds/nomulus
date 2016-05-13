// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.dns;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.domain.registry.testing.DatastoreHelper.createTld;
import static com.google.domain.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static com.google.domain.registry.testing.TaskQueueHelper.assertTasksEnqueued;

import com.google.domain.registry.testing.AppEngineRule;
import com.google.domain.registry.testing.ExceptionRule;
import com.google.domain.registry.testing.TaskQueueHelper.TaskMatcher;

import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DnsQueue}. */
@RunWith(JUnit4.class)
public class DnsQueueTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue()
      .build();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  private DnsQueue dnsQueue;

  @Before
  public void init() {
    dnsQueue = new DnsQueue();
    dnsQueue.queue = getQueue("dns-pull");
    dnsQueue.writeBatchSize = 10;
    dnsQueue.writeLockTimeout = Duration.standardSeconds(30);
  }

  @Test
  public void test_addHostRefreshTask_success() throws Exception {
    createTld("tld");
    dnsQueue.addHostRefreshTask("octopus.tld");
    assertTasksEnqueued("dns-pull",
        new TaskMatcher().tag("tld").payload("Target-Type=HOST&Target-Name=octopus.tld&tld=tld"));
  }

  @Test
  public void test_addHostRefreshTask_failsOnUnknownTld() throws Exception {
    thrown.expect(IllegalArgumentException.class,
        "octopus.notatld is not a subordinate host to a known tld");
    try {
      dnsQueue.addHostRefreshTask("octopus.notatld");
    } finally {
      assertNoTasksEnqueued("dns-pull");
    }
  }

  @Test
  public void test_addDomainRefreshTask_success() throws Exception {
    createTld("tld");
    dnsQueue.addDomainRefreshTask("octopus.tld");
    assertTasksEnqueued("dns-pull",
        new TaskMatcher().tag("tld").payload("Target-Type=DOMAIN&Target-Name=octopus.tld&tld=tld"));
  }

  @Test
  public void test_addDomainRefreshTask_failsOnUnknownTld() throws Exception {
    thrown.expect(IllegalArgumentException.class, "TLD notatld does not exist");
    try {
      dnsQueue.addDomainRefreshTask("fake.notatld");
    } finally {
      assertNoTasksEnqueued("dns-pull");
    }
  }
}
