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

package com.google.domain.registry.tools;

import static com.google.common.io.BaseEncoding.base64;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.testing.DatastoreHelper.createTld;
import static com.google.domain.registry.testing.DatastoreHelper.newDomainApplication;
import static com.google.domain.registry.testing.DatastoreHelper.persistResource;
import static com.google.domain.registry.testing.DomainApplicationSubject.assertAboutApplications;
import static com.google.domain.registry.util.ResourceUtils.readResourceUtf8;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.domain.registry.flows.EppException.ParameterValuePolicyErrorException;
import com.google.domain.registry.flows.EppException.ParameterValueSyntaxErrorException;
import com.google.domain.registry.flows.EppException.RequiredParameterMissingException;
import com.google.domain.registry.model.domain.DomainApplication;
import com.google.domain.registry.model.reporting.HistoryEntry;
import com.google.domain.registry.model.smd.EncodedSignedMark;
import com.google.domain.registry.model.smd.SignedMarkRevocationList;
import com.google.domain.registry.tmch.TmchData;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link UpdateSmdCommandTest}. */
public class UpdateSmdCommandTest extends CommandTestCase<UpdateSmdCommand> {

  /** This is the id of the SMD stored in "Court-Agent-English-Active.xml". */
  public static final String ACTIVE_SMD_ID = "0000001761376042759136-65535";

  DomainApplication domainApplication;

  private static final String ACTIVE_SMD =
      readResourceUtf8(UpdateSmdCommandTest.class, "testdata/Court-Agent-English-Active.smd");
  private static final String DIFFERENT_LABEL_SMD =
      readResourceUtf8(UpdateSmdCommandTest.class, "testdata/Court-Agent-Chinese-Active.smd");
  private static final String INVALID_SMD =
      readResourceUtf8(UpdateSmdCommandTest.class,
          "testdata/InvalidSignature-Trademark-Agent-English-Active.smd");
  private static final String REVOKED_TMV_SMD =
      readResourceUtf8(UpdateSmdCommandTest.class,
          "testdata/TMVRevoked-Trademark-Agent-English-Active.smd");

  @Before
  public void init() {
    createTld("xn--q9jyb4c");
    domainApplication = persistResource(newDomainApplication("test-validate.xn--q9jyb4c")
        .asBuilder()
        .setCurrentSponsorClientId("TheRegistrar")
        .setEncodedSignedMarks(ImmutableList.of(EncodedSignedMark.create("base64", "garbage")))
        .build());
  }

  private DomainApplication reloadDomainApplication() {
    return ofy().load().entity(domainApplication).now();
  }

  @Test
  public void testSuccess() throws Exception {
    DateTime before = new DateTime(UTC);
    String smdFile = writeToTmpFile(ACTIVE_SMD);
    runCommand("--id=2-Q9JYB4C", "--smd=" + smdFile);

    EncodedSignedMark encodedSignedMark = TmchData.readEncodedSignedMark(ACTIVE_SMD);
    assertAboutApplications().that(reloadDomainApplication())
        .hasExactlyEncodedSignedMarks(encodedSignedMark).and()
        .hasLastEppUpdateTimeAtLeast(before).and()
        .hasLastEppUpdateClientId("TheRegistrar").and()
        .hasOnlyOneHistoryEntryWhich()
            .hasType(HistoryEntry.Type.DOMAIN_APPLICATION_UPDATE).and()
            .hasClientId("TheRegistrar");
  }

  @Test
  public void testFailure_invalidSmd() throws Exception {
    thrown.expectRootCause(ParameterValuePolicyErrorException.class);
    String smdFile = writeToTmpFile(INVALID_SMD);
    runCommand("--id=2-Q9JYB4C", "--smd=" + smdFile);
  }

  @Test
  public void testFailure_revokedSmd() throws Exception {
    thrown.expectRootCause(ParameterValuePolicyErrorException.class);
    DateTime now = new DateTime(UTC);
    SignedMarkRevocationList.create(now, ImmutableMap.of(ACTIVE_SMD_ID, now)).save();
    String smdFile = writeToTmpFile(ACTIVE_SMD);
    runCommand("--id=2-Q9JYB4C", "--smd=" + smdFile);
  }

  @Test
  public void testFailure_revokedTmv() throws Exception {
    thrown.expectRootCause(ParameterValuePolicyErrorException.class);
    String smdFile = writeToTmpFile(REVOKED_TMV_SMD);
    runCommand("--id=2-Q9JYB4C", "--smd=" + smdFile);
  }

  @Test
  public void testFailure_unparseableXml() throws Exception {
    thrown.expectRootCause(ParameterValueSyntaxErrorException.class);
    String smdFile = writeToTmpFile(base64().encode("This is not XML!".getBytes(UTF_8)));
    runCommand("--id=2-Q9JYB4C", "--smd=" + smdFile);
  }

  @Test
  public void testFailure_badlyEncodedData() throws Exception {
    thrown.expectRootCause(ParameterValueSyntaxErrorException.class);
    String smdFile = writeToTmpFile("Bad base64 data ~!@#$#@%%$#^$%^&^**&^)(*)(_".getBytes(UTF_8));
    runCommand("--id=2-Q9JYB4C", "--smd=" + smdFile);
  }

  @Test
  public void testFailure_wrongLabel() throws Exception {
    thrown.expectRootCause(RequiredParameterMissingException.class);
    String smdFile = writeToTmpFile(DIFFERENT_LABEL_SMD);
    runCommand("--id=2-Q9JYB4C", "--smd=" + smdFile);
  }

  @Test
  public void testFailure_nonExistentApplication() throws Exception {
    thrown.expectRootCause(IllegalArgumentException.class);
    String smdFile = writeToTmpFile(ACTIVE_SMD);
    runCommand("--id=3-Q9JYB4C", "--smd=" + smdFile);
  }

  @Test
  public void testFailure_deletedApplication() throws Exception {
    thrown.expectRootCause(IllegalArgumentException.class);
    persistResource(domainApplication.asBuilder().setDeletionTime(new DateTime(UTC)).build());
    String smdFile = writeToTmpFile(ACTIVE_SMD);
    runCommand("--id=2-Q9JYB4C", "--smd=" + smdFile);
  }
}