/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.workflow;

import static io.zeebe.broker.test.EmbeddedBrokerConfigurator.setPartitionCount;
import static io.zeebe.protocol.Protocol.DEPLOYMENT_PARTITION;
import static io.zeebe.test.util.TestUtil.doRepeatedly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;

import io.zeebe.UnstableTest;
import io.zeebe.broker.test.EmbeddedBrokerRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.Protocol;
import io.zeebe.protocol.clientapi.RecordType;
import io.zeebe.protocol.clientapi.ValueType;
import io.zeebe.protocol.impl.record.value.deployment.ResourceType;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.intent.DeploymentIntent;
import io.zeebe.test.broker.protocol.clientapi.ClientApiRule;
import io.zeebe.test.broker.protocol.clientapi.ExecuteCommandResponse;
import io.zeebe.test.broker.protocol.clientapi.SubscribedRecord;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.RuleChain;

public class CreateDeploymentMultiplePartitionsTest {

  private static final BpmnModelInstance WORKFLOW =
      Bpmn.createExecutableProcess("process").startEvent().endEvent().done();

  private static final BpmnModelInstance WORKFLOW_2 =
      Bpmn.createExecutableProcess("process2").startEvent().endEvent().done();

  public static final int PARTITION_ID = DEPLOYMENT_PARTITION;
  public static final int PARTITION_COUNT = 3;

  public EmbeddedBrokerRule brokerRule = new EmbeddedBrokerRule(setPartitionCount(3));

  public ClientApiRule apiRule = new ClientApiRule(brokerRule::getClientAddress);

  @Rule public RuleChain ruleChain = RuleChain.outerRule(brokerRule).around(apiRule);

  @Test
  public void shouldCreateDeploymentOnAllPartitions() throws Exception {
    // when
    final ExecuteCommandResponse resp =
        apiRule
            .createCmdRequest()
            .partitionId(Protocol.DEPLOYMENT_PARTITION)
            .type(ValueType.DEPLOYMENT, DeploymentIntent.CREATE)
            .command()
            .put(
                "resources",
                Collections.singletonList(deploymentResource(bpmnXml(WORKFLOW), "process.bpmn")))
            .done()
            .sendAndAwait();

    // then
    assertThat(resp.key()).isGreaterThanOrEqualTo(0L);
    assertThat(resp.position()).isGreaterThanOrEqualTo(0L);
    assertThat(resp.partitionId()).isEqualTo(PARTITION_ID);

    assertThat(resp.recordType()).isEqualTo(RecordType.EVENT);
    assertThat(resp.intent()).isEqualTo(DeploymentIntent.CREATED);

    assertCreatedDeploymentEventOnPartition(0, resp.key());
    assertCreatedDeploymentEventOnPartition(1, resp.key());
    assertCreatedDeploymentEventOnPartition(2, resp.key());
  }

  @Test
  public void shouldCreateDeploymentWithYamlResourcesOnAllPartitions() throws Exception {
    // given
    final Path yamlFile =
        Paths.get(getClass().getResource("/workflows/simple-workflow.yaml").toURI());
    final byte[] yamlWorkflow = Files.readAllBytes(yamlFile);

    // when
    final ExecuteCommandResponse resp =
        apiRule
            .partition()
            .deployWithResponse(
                yamlWorkflow, ResourceType.YAML_WORKFLOW.name(), "simple-workflow.yaml");

    // then
    assertThat(resp.key()).isGreaterThanOrEqualTo(0L);
    assertThat(resp.position()).isGreaterThanOrEqualTo(0L);
    assertThat(resp.partitionId()).isEqualTo(PARTITION_ID);

    assertThat(resp.recordType()).isEqualTo(RecordType.EVENT);
    assertThat(resp.intent()).isEqualTo(DeploymentIntent.CREATED);

    final Map<String, Object> resources = deploymentResource(yamlWorkflow, "simple-workflow.yaml");
    resources.put("resourceType", ResourceType.YAML_WORKFLOW.name());

    for (int i = 0; i < PARTITION_COUNT; i++) {
      assertCreatedDeploymentEventResources(
          i,
          resp.key(),
          (deploymentCreatedEvent) -> {
            final Map<String, Object> map =
                (Map<String, Object>)
                    ((List) deploymentCreatedEvent.value().get("resources")).get(0);
            assertThat(map).contains(entry("resourceType", ResourceType.YAML_WORKFLOW.name()));

            final List<Map<String, Object>> deployedWorkflows =
                (List<Map<String, Object>>) deploymentCreatedEvent.value().get("workflows");
            assertThat(deployedWorkflows).hasSize(1);
            assertThat(deployedWorkflows.get(0))
                .containsExactly(
                    entry("bpmnProcessId", "yaml-workflow"),
                    entry("version", 1L),
                    entry("workflowKey", 1L),
                    entry("resourceName", "simple-workflow.yaml"));
          });
    }
  }

  @Test
  public void shouldCreateDeploymentResourceWithMultipleWorkflows() {
    // given
    final List<Map<String, Object>> resources = new ArrayList<>();
    resources.add(deploymentResource(bpmnXml(WORKFLOW), "process.bpmn"));
    resources.add(deploymentResource(bpmnXml(WORKFLOW_2), "process2.bpmn"));

    // when
    final ExecuteCommandResponse resp =
        apiRule
            .createCmdRequest()
            .partitionId(DEPLOYMENT_PARTITION)
            .type(ValueType.DEPLOYMENT, DeploymentIntent.CREATE)
            .command()
            .put("resources", resources)
            .done()
            .sendAndAwait();

    // then
    assertThat(resp.recordType()).isEqualTo(RecordType.EVENT);
    assertThat(resp.intent()).isEqualTo(DeploymentIntent.CREATED);

    for (int i = 0; i < PARTITION_COUNT; i++) {
      assertCreatedDeploymentEventResources(
          i,
          resp.key(),
          (createdDeployment) -> {
            final List<Map<String, Object>> deployedWorkflows =
                Arrays.asList(
                    getDeployedWorkflow(createdDeployment, 0),
                    getDeployedWorkflow(createdDeployment, 1));
            assertThat(deployedWorkflows)
                .extracting(s -> s.get(WorkflowInstanceRecord.PROP_WORKFLOW_BPMN_PROCESS_ID))
                .contains("process", "process2");
          });
    }
  }

  @Test
  @Category(UnstableTest.class) // => https://github.com/zeebe-io/zeebe/issues/1250
  public void shouldIncrementWorkflowVersions() {
    // given

    // when
    final ExecuteCommandResponse d1 = apiRule.partition().deployWithResponse(WORKFLOW);
    final ExecuteCommandResponse d2 = apiRule.partition().deployWithResponse(WORKFLOW);

    // then
    final Map<String, Object> workflow1 = getDeployedWorkflow(d1, 0);
    assertThat(workflow1.get("version")).isEqualTo(1L);

    for (int i = 0; i < PARTITION_COUNT; i++) {
      assertCreatedDeploymentEventResources(
          i,
          d1.key(),
          createdDeployment -> {
            assertThat(getDeployedWorkflow(createdDeployment, 0).get("version")).isEqualTo(1L);
          });
    }

    final Map<String, Object> workflow2 = getDeployedWorkflow(d2, 0);
    assertThat(workflow2.get("version")).isEqualTo(2L);

    for (int i = 0; i < PARTITION_COUNT; i++) {
      assertCreatedDeploymentEventResources(
          i,
          d2.key(),
          createdDeployment -> {
            assertThat(getDeployedWorkflow(createdDeployment, 0).get("version")).isEqualTo(2L);
          });
    }
  }

  @Test
  @Category(UnstableTest.class) // => https://github.com/zeebe-io/zeebe/issues/1250
  public void shouldCreateDeploymentOnAllPartitionsWithRestartBroker() throws Exception {
    // given
    apiRule
        .createCmdRequest()
        .partitionId(Protocol.DEPLOYMENT_PARTITION)
        .type(ValueType.DEPLOYMENT, DeploymentIntent.CREATE)
        .command()
        .put(
            "resources",
            Collections.singletonList(deploymentResource(bpmnXml(WORKFLOW), "process.bpmn")))
        .done()
        .send()
        .await();

    // when
    brokerRule.restartBroker();
    doRepeatedly(apiRule::getPartitionIds).until(p -> !p.isEmpty());

    // then
    assertAnyCreatedDeploymentEventOnPartition(0);
    assertAnyCreatedDeploymentEventOnPartition(1);
    assertAnyCreatedDeploymentEventOnPartition(2);
  }

  private Map<String, Object> deploymentResource(final byte[] resource, final String name) {
    final Map<String, Object> deploymentResource = new HashMap<>();
    deploymentResource.put("resource", resource);
    deploymentResource.put("resourceType", ResourceType.BPMN_XML);
    deploymentResource.put("resourceName", name);

    return deploymentResource;
  }

  private byte[] bpmnXml(final BpmnModelInstance definition) {
    final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    Bpmn.writeModelToStream(outStream, definition);
    return outStream.toByteArray();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getDeployedWorkflow(
      final ExecuteCommandResponse d1, final int offset) {
    final List<Map<String, Object>> d1Workflows =
        (List<Map<String, Object>>) d1.getValue().get("workflows");
    return d1Workflows.get(offset);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getDeployedWorkflow(final SubscribedRecord record, final int offset) {
    final List<Map<String, Object>> d1Workflows =
        (List<Map<String, Object>>) record.value().get("workflows");
    return d1Workflows.get(offset);
  }

  private void assertCreatedDeploymentEventOnPartition(
      final int expectedPartition, final long expectedKey) {
    assertCreatedDeploymentEventResources(
        expectedPartition,
        expectedKey,
        (deploymentCreatedEvent) -> {
          assertThat(deploymentCreatedEvent.key()).isEqualTo(expectedKey);
          assertThat(deploymentCreatedEvent.partitionId()).isEqualTo(expectedPartition);

          assertDeploymentRecord(deploymentCreatedEvent);
        });
  }

  private void assertAnyCreatedDeploymentEventOnPartition(final int expectedPartition) {
    assertAnyCreatedDeploymentEventResources(
        expectedPartition,
        (deploymentCreatedEvent) -> {
          assertThat(deploymentCreatedEvent.partitionId()).isEqualTo(expectedPartition);

          assertDeploymentRecord(deploymentCreatedEvent);
        });
  }

  private void assertDeploymentRecord(final SubscribedRecord deploymentCreatedEvent) {
    final Map<String, Object> resources = deploymentResource(bpmnXml(WORKFLOW), "process.bpmn");
    resources.put("resourceType", "BPMN_XML");

    final Map<String, Object> map =
        (Map<String, Object>) ((List) deploymentCreatedEvent.value().get("resources")).get(0);
    assertThat(map).contains(entry("resource", resources.get("resource")));
    assertThat(map).contains(entry("resourceType", resources.get("resourceType")));

    final List<Map<String, Object>> deployedWorkflows =
        (List<Map<String, Object>>) deploymentCreatedEvent.value().get("workflows");
    assertThat(deployedWorkflows).hasSize(1);
    assertThat(deployedWorkflows.get(0))
        .containsExactly(
            entry("bpmnProcessId", "process"),
            entry("version", 1L),
            entry("workflowKey", 1L),
            entry("resourceName", "process.bpmn"));
  }

  private void assertCreatedDeploymentEventResources(
      final int expectedPartition,
      final long expectedKey,
      final Consumer<SubscribedRecord> deploymentAssert) {
    final SubscribedRecord deploymentCreatedEvent =
        apiRule
            .partition(expectedPartition)
            .receiveRecords()
            .skipUntil(r -> r.valueType() == ValueType.DEPLOYMENT)
            .filter(
                r ->
                    r.valueType() == ValueType.DEPLOYMENT
                        && r.intent() == DeploymentIntent.CREATED
                        && r.key() == expectedKey)
            .findFirst()
            .get();

    assertThat(deploymentCreatedEvent.key()).isEqualTo(expectedKey);
    assertThat(deploymentCreatedEvent.partitionId()).isEqualTo(expectedPartition);

    deploymentAssert.accept(deploymentCreatedEvent);
  }

  private void assertRejectedCreateDeploymentCommand(
      final int expectedPartition, final long expectedKey) {
    final SubscribedRecord deploymentCreateCommandRejection =
        apiRule
            .partition(expectedPartition)
            .receiveRecords()
            .filter(
                r ->
                    r.recordType() == RecordType.COMMAND_REJECTION
                        && r.valueType() == ValueType.DEPLOYMENT
                        && r.intent() == DeploymentIntent.CREATE
                        && r.key() == expectedKey)
            .findFirst()
            .get();

    assertThat(deploymentCreateCommandRejection.key()).isEqualTo(expectedKey);
    assertThat(deploymentCreateCommandRejection.partitionId()).isEqualTo(expectedPartition);
  }

  private void assertAnyCreatedDeploymentEventResources(
      final int expectedPartition, final Consumer<SubscribedRecord> deploymentAssert) {
    final SubscribedRecord deploymentCreatedEvent =
        apiRule
            .partition(expectedPartition)
            .receiveRecords()
            .skipUntil(r -> r.valueType() == ValueType.DEPLOYMENT)
            .filter(
                r ->
                    r.valueType() == ValueType.DEPLOYMENT && r.intent() == DeploymentIntent.CREATED)
            .findFirst()
            .get();

    assertThat(deploymentCreatedEvent.partitionId()).isEqualTo(expectedPartition);

    deploymentAssert.accept(deploymentCreatedEvent);
  }
}
