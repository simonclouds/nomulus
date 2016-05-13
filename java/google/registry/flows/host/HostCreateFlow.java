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

package com.google.domain.registry.flows.host;

import static com.google.domain.registry.flows.host.HostFlowUtils.lookupSuperordinateDomain;
import static com.google.domain.registry.flows.host.HostFlowUtils.validateHostName;
import static com.google.domain.registry.flows.host.HostFlowUtils.verifyDomainIsSameRegistrar;
import static com.google.domain.registry.model.EppResourceUtils.createContactHostRoid;
import static com.google.domain.registry.model.eppoutput.Result.Code.Success;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.util.CollectionUtils.isNullOrEmpty;

import com.google.common.base.Optional;
import com.google.domain.registry.dns.DnsQueue;
import com.google.domain.registry.flows.EppException;
import com.google.domain.registry.flows.EppException.ParameterValueRangeErrorException;
import com.google.domain.registry.flows.EppException.RequiredParameterMissingException;
import com.google.domain.registry.flows.ResourceCreateFlow;
import com.google.domain.registry.model.domain.DomainResource;
import com.google.domain.registry.model.eppoutput.CreateData.HostCreateData;
import com.google.domain.registry.model.eppoutput.EppOutput;
import com.google.domain.registry.model.host.HostCommand.Create;
import com.google.domain.registry.model.host.HostResource;
import com.google.domain.registry.model.host.HostResource.Builder;
import com.google.domain.registry.model.ofy.ObjectifyService;
import com.google.domain.registry.model.reporting.HistoryEntry;

import com.googlecode.objectify.Ref;

/**
 * An EPP flow that creates a new host resource.
 *
 * @error {@link com.google.domain.registry.flows.EppXmlTransformer.IpAddressVersionMismatchException}
 * @error {@link com.google.domain.registry.flows.ResourceCreateFlow.ResourceAlreadyExistsException}
 * @error {@link HostFlowUtils.HostNameTooLongException}
 * @error {@link HostFlowUtils.HostNameTooShallowException}
 * @error {@link HostFlowUtils.InvalidHostNameException}
 * @error {@link HostFlowUtils.SuperordinateDomainDoesNotExistException}
 * @error {@link SubordinateHostMustHaveIpException}
 * @error {@link UnexpectedExternalHostIpException}
 */
public class HostCreateFlow extends ResourceCreateFlow<HostResource, Builder, Create> {

  /**
   * The superordinate domain of the host object if creating an in-bailiwick host, or null if
   * creating an external host. This is looked up before we actually create the Host object so that
   * we can detect error conditions earlier. By the time {@link #setCreateProperties} is called
   * (where this reference is actually used), we no longer have the ability to return an
   * {@link EppException}.
   *
   * <p>The general model of these classes is to do validation of parameters up front before we get
   * to the actual object creation, which is why this class looks up and stores the superordinate
   * domain ahead of time.
   */
  private Optional<Ref<DomainResource>> superordinateDomain;

  @Override
  protected void initResourceCreateOrMutateFlow() throws EppException {
    superordinateDomain = Optional.fromNullable(lookupSuperordinateDomain(
        validateHostName(command.getFullyQualifiedHostName()), now));
  }

  @Override
  protected String createFlowRepoId() {
    return createContactHostRoid(ObjectifyService.allocateId());
  }

  @Override
  protected void verifyCreateIsAllowed() throws EppException {
    verifyDomainIsSameRegistrar(superordinateDomain.orNull(), getClientId());
    boolean willBeSubordinate = superordinateDomain.isPresent();
    boolean hasIpAddresses = !isNullOrEmpty(command.getInetAddresses());
    if (willBeSubordinate != hasIpAddresses) {
      // Subordinate hosts must have ip addresses and external hosts must not have them.
      throw willBeSubordinate
          ? new SubordinateHostMustHaveIpException()
          : new UnexpectedExternalHostIpException();
    }
  }

  @Override
  protected void setCreateProperties(Builder builder) {
    if (superordinateDomain.isPresent()) {
      builder.setSuperordinateDomain(superordinateDomain.get());
    }
  }

  /** Modify any other resources that need to be informed of this create. */
  @Override
  protected void modifyCreateRelatedResources() {
    if (superordinateDomain.isPresent()) {
      ofy().save().entity(superordinateDomain.get().get().asBuilder()
          .addSubordinateHost(command.getFullyQualifiedHostName())
          .build());
    }
  }

  @Override
  protected void enqueueTasks() {
    // Only update DNS if this is a subordinate host. External hosts have no glue to write, so they
    // are only written as NS records from the referencing domain.
    if (superordinateDomain.isPresent()) {
      DnsQueue.create().addHostRefreshTask(newResource.getFullyQualifiedHostName());
    }
  }

  @Override
  protected final HistoryEntry.Type getHistoryEntryType() {
    return HistoryEntry.Type.HOST_CREATE;
  }

  @Override
  protected EppOutput getOutput() {
    return createOutput(Success,
        HostCreateData.create(newResource.getFullyQualifiedHostName(), now));
  }

  /** Subordinate hosts must have an ip address. */
  static class SubordinateHostMustHaveIpException extends RequiredParameterMissingException {
    public SubordinateHostMustHaveIpException() {
      super("Subordinate hosts must have an ip address");
    }
  }

  /** External hosts must not have ip addresses. */
  static class UnexpectedExternalHostIpException extends ParameterValueRangeErrorException {
    public UnexpectedExternalHostIpException() {
      super("External hosts must not have ip addresses");
    }
  }
}