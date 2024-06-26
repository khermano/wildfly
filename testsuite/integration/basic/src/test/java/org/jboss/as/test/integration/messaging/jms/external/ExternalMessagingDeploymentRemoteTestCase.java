/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.integration.messaging.jms.external;

import static org.jboss.as.controller.client.helpers.ClientConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.shrinkwrap.api.ShrinkWrap.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.net.SocketPermission;
import java.net.URL;
import java.util.PropertyPermission;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.arquillian.setup.SnapshotServerSetupTask;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.test.integration.common.HttpRequest;
import org.jboss.as.test.integration.common.jms.JMSOperations;
import org.jboss.as.test.integration.common.jms.JMSOperationsProvider;
import org.jboss.as.test.shared.PermissionUtils;
import org.jboss.as.test.shared.ServerReload;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test that invoking a management operation that removes a JMS resource that is used by a deployed archive must fail:
 * the resource must not be removed and any depending services must be recovered.
 * The deployment must still be operating after the failing management operation.
 *
 * @author <a href="http://jmesnil.net/">Jeff Mesnil</a> (c) 2014 Red Hat inc.
 */
@RunWith(Arquillian.class)
@RunAsClient
@ServerSetup(ExternalMessagingDeploymentRemoteTestCase.SetupTask.class)
public class ExternalMessagingDeploymentRemoteTestCase {

    public static final String QUEUE_LOOKUP = "java:/jms/DependentMessagingDeploymentTestCase/myQueue";
    public static final String TOPIC_LOOKUP = "java:/jms/DependentMessagingDeploymentTestCase/myTopic";
    public static final String REMOTE_PCF = "remote-artemis";

    private static final String QUEUE_NAME = "myQueue";
    private static final String TOPIC_NAME = "myTopic";

    @ArquillianResource
    private URL url;

    @ArquillianResource
    private ManagementClient managementClient;

    static class SetupTask extends SnapshotServerSetupTask {

        private static final Logger logger = Logger.getLogger(ExternalMessagingDeploymentRemoteTestCase.SetupTask.class);

        @Override
        public void doSetup(org.jboss.as.arquillian.container.ManagementClient managementClient, String s) throws Exception {
            ServerReload.executeReloadAndWaitForCompletion(managementClient, true);
            JMSOperations ops = JMSOperationsProvider.getInstance(managementClient.getControllerClient());
            boolean needRemoteConnector = ops.isRemoteBroker();
            if (needRemoteConnector) {
                ops.addExternalRemoteConnector("remote-broker-connector", "messaging-activemq");
            } else {
                ops.createJmsQueue(QUEUE_NAME, "/queue/" + QUEUE_NAME);
                ops.createJmsTopic(TOPIC_NAME, "/topic/" + TOPIC_NAME);
                execute(managementClient, addSocketBinding("legacy-messaging", 5445), true);
                ops.addExternalRemoteConnector("legacy-broker-connector", "legacy-messaging");
                execute(managementClient, addRemoteAcceptor(ops.getServerAddress(), "legacy-broker-acceptor", "legacy-messaging"), true);
            }
            ModelNode op = Operations.createRemoveOperation(getInitialPooledConnectionFactoryAddress(ops.getServerAddress()));
            managementClient.getControllerClient().execute(op);
            op = Operations.createAddOperation(getPooledConnectionFactoryAddress());
            op.get("transaction").set("xa");
            op.get("entries").add("java:/JmsXA java:jboss/DefaultJMSConnectionFactory");
            if (needRemoteConnector) {
                op.get("connectors").add("remote-broker-connector");
            } else {
                op.get("connectors").add("legacy-broker-connector");
            }
            execute(managementClient, op, true);
            op = Operations.createAddOperation(getExternalTopicAddress());
            op.get("entries").add(TOPIC_LOOKUP);
            op.get("entries").add("/topic/myAwesomeClientTopic");
            execute(managementClient, op, true);
            op = Operations.createAddOperation(getExternalQueueAddress());
            op.get("entries").add(QUEUE_LOOKUP);
            op.get("entries").add("/queue/myAwesomeClientQueue");
            execute(managementClient, op, true);
            op = Operations.createWriteAttributeOperation(PathAddress.parseCLIStyleAddress("/subsystem=ejb3").toModelNode(), "default-resource-adapter-name", REMOTE_PCF);
            execute(managementClient, op, true);
            ServerReload.executeReloadAndWaitForCompletion(managementClient);
        }

        @Override
        protected void beforeRestore(final ManagementClient managementClient, final String containerId) throws Exception {
            final JMSOperations ops = JMSOperationsProvider.getInstance(managementClient.getControllerClient());
            ops.removeJmsQueue(QUEUE_NAME);
            ops.removeJmsTopic(TOPIC_NAME);
        }

        private ModelNode execute(final org.jboss.as.arquillian.container.ManagementClient managementClient, final ModelNode op, final boolean expectSuccess) throws IOException {
            ModelNode response = managementClient.getControllerClient().execute(op);
            final String outcome = response.get("outcome").asString();
            if (expectSuccess) {
                assertEquals(response.toString(), "success", outcome);
                return response.get("result");
            } else {
                assertEquals("failed", outcome);
                return response.get("failure-description");
            }
        }

        ModelNode getPooledConnectionFactoryAddress() {
            ModelNode address = new ModelNode();
            address.add("subsystem", "messaging-activemq");
            address.add("pooled-connection-factory", REMOTE_PCF);
            return address;
        }

        ModelNode getExternalTopicAddress() {
            ModelNode address = new ModelNode();
            address.add("subsystem", "messaging-activemq");
            address.add("external-jms-topic", TOPIC_NAME);
            return address;
        }

        ModelNode getExternalQueueAddress() {
            ModelNode address = new ModelNode();
            address.add("subsystem", "messaging-activemq");
            address.add("external-jms-queue", QUEUE_NAME);
            return address;
        }

        ModelNode getInitialPooledConnectionFactoryAddress(ModelNode serverAddress) {
            ModelNode address = serverAddress.clone();
            address.add("pooled-connection-factory", "activemq-ra");
            return address;
        }

        ModelNode addSocketBinding(String bindingName, int port) {
            ModelNode address = new ModelNode();
            address.add("socket-binding-group", "standard-sockets");
            address.add("socket-binding", bindingName);

            ModelNode socketBindingOp = new ModelNode();
            socketBindingOp.get(OP).set(ADD);
            socketBindingOp.get(OP_ADDR).set(address);
            socketBindingOp.get("port").set(port);
            return socketBindingOp;
        }

        ModelNode addExternalRemoteConnector(ModelNode subsystemAddress, String name, String socketBinding) {
            ModelNode address = subsystemAddress.clone();
            address.add("remote-connector", name);

            ModelNode socketBindingOp = new ModelNode();
            socketBindingOp.get(OP).set(ADD);
            socketBindingOp.get(OP_ADDR).set(address);
            socketBindingOp.get("socket-binding").set(socketBinding);
            return socketBindingOp;
        }

        ModelNode addRemoteAcceptor(ModelNode serverAddress, String name, String socketBinding) {
            ModelNode address = serverAddress.clone();
            address.add("remote-acceptor", name);

            ModelNode socketBindingOp = new ModelNode();
            socketBindingOp.get(OP).set(ADD);
            socketBindingOp.get(OP_ADDR).set(address);
            socketBindingOp.get("socket-binding").set(socketBinding);
            return socketBindingOp;
        }
    }

    @Deployment
    public static WebArchive createArchive() {
        return create(WebArchive.class, "ClientMessagingDeploymentTestCase.war")
                .addClasses(RemoteMessagingServlet.class, TimeoutUtil.class)
                .addClasses(DefaultResourceAdapterQueueMDB.class, TopicMDB.class)
                .addAsManifestResource(PermissionUtils.createPermissionsXmlAsset(
                        new SocketPermission("localhost", "resolve"),
                        new PropertyPermission(TimeoutUtil.FACTOR_SYS_PROP, "read")), "permissions.xml")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    @Test
    public void testSendMessageInClientQueue() throws Exception {
        sendAndReceiveMessage(true);
        checkCreatedPooledConnectionFactory();
    }

    @Test
    public void testSendMessageInClientTopic() throws Exception {
        sendAndReceiveMessage(false);
        checkCreatedPooledConnectionFactory();
    }

    private void sendAndReceiveMessage(boolean sendToQueue) throws Exception {
        String destination = sendToQueue ? "queue" : "topic";
        String text = UUID.randomUUID().toString();
        String serverUrl = this.url.toExternalForm();
        if (!serverUrl.endsWith("/")) {
            serverUrl = serverUrl + "/";
        }
        URL servletUrl = new URL(serverUrl + "ClientMessagingDeploymentTestCase?destination=" + destination + "&text=" + text);
        String reply = HttpRequest.get(servletUrl.toExternalForm(), TimeoutUtil.adjust(10), TimeUnit.SECONDS);

        assertNotNull(reply);
        assertEquals(text, reply);
    }

    private void checkCreatedPooledConnectionFactory() throws IOException {
        ModelNode op = Operations.createReadResourceOperation(
                PathAddress.pathAddress("deployment", "ClientMessagingDeploymentTestCase.war")
                        .append("subsystem", "messaging-activemq")
                        .append("pooled-connection-factory", "ClientMessagingDeploymentTestCase_ClientMessagingDeploymentTestCase_java_global/definedFactory")
                        .toModelNode());
        op.get("recursive").set(true);
        op.get("include-runtime").set(true);
        ModelNode response = managementClient.getControllerClient().execute(op);
        assertEquals(response.toString(), "success", response.get("outcome").asString());
    }
}
