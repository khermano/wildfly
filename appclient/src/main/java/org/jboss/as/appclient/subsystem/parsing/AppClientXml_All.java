/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.appclient.subsystem.parsing;

import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING_GROUP;
import static org.jboss.as.controller.parsing.ParseUtils.isNoNamespaceAttribute;
import static org.jboss.as.controller.parsing.ParseUtils.isXmlNamespaceAttribute;
import static org.jboss.as.controller.parsing.ParseUtils.missingRequired;
import static org.jboss.as.controller.parsing.ParseUtils.nextElement;
import static org.jboss.as.controller.parsing.ParseUtils.requireNamespace;
import static org.jboss.as.controller.parsing.ParseUtils.requireNoAttributes;
import static org.jboss.as.controller.parsing.ParseUtils.unexpectedAttribute;
import static org.jboss.as.controller.parsing.ParseUtils.unexpectedElement;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.appclient.logging.AppClientLogger;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.parsing.Attribute;
import org.jboss.as.controller.parsing.Element;
import org.jboss.as.controller.parsing.ExtensionXml;
import org.jboss.as.controller.parsing.ParseUtils;
import org.jboss.as.server.parsing.CommonXml;
import org.jboss.as.server.parsing.SocketBindingsXml;
import org.jboss.as.server.services.net.SocketBindingGroupResourceDefinition;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.modules.ModuleLoader;
import org.jboss.staxmapper.IntVersion;
import org.jboss.staxmapper.XMLExtendedStreamReader;
import org.jboss.staxmapper.XMLExtendedStreamWriter;

/**
 * A mapper between an AS server's configuration model and XML representations, particularly {@code appclient.xml}.
 *
 * @author Stuart Douglas
 */
public class AppClientXml_All extends CommonXml {

    private final IntVersion version;
    private final String namespaceUri;
    private final ExtensionXml extensionXml;

    AppClientXml_All(final ModuleLoader loader, final ExtensionRegistry extensionRegistry,
                            final IntVersion version, final String namespaceUri) {
        super(new AppClientSocketBindingsXml());
        extensionXml = new ExtensionXml(loader, null, extensionRegistry);
        this.version = version;
        this.namespaceUri = namespaceUri;
    }

    @Override
    public void readElement(XMLExtendedStreamReader reader, List<ModelNode> value) throws XMLStreamException {
        final ModelNode address = new ModelNode().setEmptyList();

        if (Element.forName(reader.getLocalName()) != Element.SERVER) {
            throw unexpectedElement(reader);
        }

        IntVersion version1_1 = new IntVersion(1,1);
        IntVersion version18 = new IntVersion(18);
        if (version.compareTo(version1_1) < 0) {
            readServerElement_1_0(reader, address, value);
        } else if (version.compareTo(version18) < 0) {
            readServerElement_1_1(reader, address, value);
        } else {
            readServerElement_18(reader, address, value);
        }
    }

    /**
     * Read the <server/> element based on version 1.0 of the schema.
     *
     * @param reader  the xml stream reader
     * @param address address of the parent resource of any resources this method will add
     * @param list the list of boot operations to which any new operations should be added
     * @throws XMLStreamException if a parsing error occurs
     */
    private void readServerElement_1_0(final XMLExtendedStreamReader reader, final ModelNode address,
                                        final List<ModelNode> list) throws XMLStreamException {

        parseNamespaces(reader, address, list);

        String serverName = null;

        // attributes
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if (isXmlNamespaceAttribute(reader, i)) {
                switch (Attribute.forName(reader.getAttributeLocalName(i))) {
                    case SCHEMA_LOCATION: {
                        parseSchemaLocations(reader, address, list, i);
                        break;
                    }
                    case NO_NAMESPACE_SCHEMA_LOCATION: {
                        // todo, jeez
                        break;
                    }
                    default: {
                        throw unexpectedAttribute(reader, i);
                    }
                }
            } else {
                switch (Attribute.forName(reader.getAttributeLocalName(i))) {
                    case NAME: {
                        serverName = reader.getAttributeValue(i);
                        break;
                    }
                    default:
                        throw unexpectedAttribute(reader, i);
                }
            }
        }

        setServerName(address, list, serverName);

        // elements - sequence

        Element element = nextElement(reader, namespaceUri);
        if (element == Element.EXTENSIONS) {
            extensionXml.parseExtensions(reader, address, namespaceUri, list);
            element = nextElement(reader, namespaceUri);
        }
        // System properties
        if (element == Element.SYSTEM_PROPERTIES) {
            parseSystemProperties(reader, address, namespaceUri, list, true);
            element = nextElement(reader, namespaceUri);
        }
        if (element == Element.PATHS) {
            parsePaths(reader, address, namespaceUri, list, true);
            element = nextElement(reader, namespaceUri);
        }

        // Single profile
        if (element == Element.PROFILE) {
            parseServerProfile(reader, address, list);
            element = nextElement(reader, namespaceUri);
        }

        // Interfaces
        final Set<String> interfaceNames = new HashSet<String>();
        if (element == Element.INTERFACES) {
            parseInterfaces(reader, interfaceNames, address, version, namespaceUri, list, true);
            element = nextElement(reader, namespaceUri);
        }
        // Single socket binding group
        if (element == Element.SOCKET_BINDING_GROUP) {
            parseSocketBindingGroup(reader, interfaceNames, address, list);
            element = nextElement(reader, namespaceUri);
        }
        if (element != null) {
            throw unexpectedElement(reader);
        }
    }

    /**
     * Read the <server/> element based on version 1.1 of the schema.
     *
     * @param reader  the xml stream reader
     * @param address address of the parent resource of any resources this method will add
     * @param list the list of boot operations to which any new operations should be added
     * @throws XMLStreamException if a parsing error occurs
     */
    private void readServerElement_1_1(final XMLExtendedStreamReader reader, final ModelNode address,
                                        final List<ModelNode> list)
            throws XMLStreamException {

        parseNamespaces(reader, address, list);

        String serverName = null;

        // attributes
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if (isXmlNamespaceAttribute(reader, i)) {
                switch (Attribute.forName(reader.getAttributeLocalName(i))) {
                    case SCHEMA_LOCATION: {
                        parseSchemaLocations(reader, address, list, i);
                        break;
                    }
                    case NO_NAMESPACE_SCHEMA_LOCATION: {
                        // todo, jeez
                        break;
                    }
                    default: {
                        throw unexpectedAttribute(reader, i);
                    }
                }
            } else {
                switch (Attribute.forName(reader.getAttributeLocalName(i))) {
                    case NAME: {
                        serverName = reader.getAttributeValue(i);
                        break;
                    }
                    default:
                        throw unexpectedAttribute(reader, i);
                }
            }
        }

        setServerName(address, list, serverName);

        // elements - sequence

        Element element = nextElement(reader, namespaceUri);
        if (element == Element.EXTENSIONS) {
            extensionXml.parseExtensions(reader, address, namespaceUri, list);
            element = nextElement(reader, namespaceUri);
        }
        // System properties
        if (element == Element.SYSTEM_PROPERTIES) {
            parseSystemProperties(reader, address, namespaceUri, list, true);
            element = nextElement(reader, namespaceUri);
        }
        if (element == Element.PATHS) {
            parsePaths(reader, address, namespaceUri, list, true);
            element = nextElement(reader, namespaceUri);
        }

        if (element == Element.VAULT) {
            parseVault(reader, address, namespaceUri, list);
            element = nextElement(reader, namespaceUri);
        }
        // Single profile
        if (element == Element.PROFILE) {
            parseServerProfile(reader, address, list);
            element = nextElement(reader, namespaceUri);
        }

        // Interfaces
        final Set<String> interfaceNames = new HashSet<String>();
        if (element == Element.INTERFACES) {
            parseInterfaces(reader, interfaceNames, address, version, namespaceUri, list, true);
            element = nextElement(reader, namespaceUri);
        }
        // Single socket binding group
        if (element == Element.SOCKET_BINDING_GROUP) {
            parseSocketBindingGroup(reader, interfaceNames, address, list);
            element = nextElement(reader, namespaceUri);
        }

        if (element != null) {
            throw unexpectedElement(reader);
        }
    }

    /**
     * Read the <server/> element based on version 18 of the schema.
     *
     * @param reader  the xml stream reader
     * @param address address of the parent resource of any resources this method will add
     * @param list the list of boot operations to which any new operations should be added
     * @throws XMLStreamException if a parsing error occurs
     */
    private void readServerElement_18(final XMLExtendedStreamReader reader, final ModelNode address,
                                        final List<ModelNode> list)
            throws XMLStreamException {

        parseNamespaces(reader, address, list);

        String serverName = null;

        // attributes
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if (isXmlNamespaceAttribute(reader, i)) {
                switch (Attribute.forName(reader.getAttributeLocalName(i))) {
                    case SCHEMA_LOCATION: {
                        parseSchemaLocations(reader, address, list, i);
                        break;
                    }
                    case NO_NAMESPACE_SCHEMA_LOCATION: {
                        // todo, jeez
                        break;
                    }
                    default: {
                        throw unexpectedAttribute(reader, i);
                    }
                }
            } else {
                switch (Attribute.forName(reader.getAttributeLocalName(i))) {
                    case NAME: {
                        serverName = reader.getAttributeValue(i);
                        break;
                    }
                    default:
                        throw unexpectedAttribute(reader, i);
                }
            }
        }

        setServerName(address, list, serverName);

        // elements - sequence

        Element element = nextElement(reader, namespaceUri);
        if (element == Element.EXTENSIONS) {
            extensionXml.parseExtensions(reader, address, namespaceUri, list);
            element = nextElement(reader, namespaceUri);
        }
        // System properties
        if (element == Element.SYSTEM_PROPERTIES) {
            parseSystemProperties(reader, address, namespaceUri, list, true);
            element = nextElement(reader, namespaceUri);
        }
        if (element == Element.PATHS) {
            parsePaths(reader, address, namespaceUri, list, true);
            element = nextElement(reader, namespaceUri);
        }

        // Single profile
        if (element == Element.PROFILE) {
            parseServerProfile(reader, address, list);
            element = nextElement(reader, namespaceUri);
        }

        // Interfaces
        final Set<String> interfaceNames = new HashSet<String>();
        if (element == Element.INTERFACES) {
            parseInterfaces(reader, interfaceNames, address, version, namespaceUri, list, true);
            element = nextElement(reader, namespaceUri);
        }
        // Single socket binding group
        if (element == Element.SOCKET_BINDING_GROUP) {
            parseSocketBindingGroup(reader, interfaceNames, address, list);
            element = nextElement(reader, namespaceUri);
        }

        if (element != null) {
            throw unexpectedElement(reader);
        }
    }

    private void parseSocketBindingGroup(final XMLExtendedStreamReader reader, final Set<String> interfaces,
            final ModelNode address, final List<ModelNode> updates) throws XMLStreamException {

        // unique names for both socket-binding and outbound-socket-binding(s)
        final Set<String> uniqueBindingNames = new HashSet<String>();

        ModelNode op = Util.getEmptyOperation(ADD, null);
        // Handle attributes
        String socketBindingGroupName = null;

        final EnumSet<Attribute> required = EnumSet.of(Attribute.NAME, Attribute.DEFAULT_INTERFACE);
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final String value = reader.getAttributeValue(i);
            if (!isNoNamespaceAttribute(reader, i)) {
                throw unexpectedAttribute(reader, i);
            }
            final Attribute attribute = Attribute.forName(reader.getAttributeLocalName(i));
            switch (attribute) {
                case NAME: {
                    socketBindingGroupName = value;
                    required.remove(attribute);
                    break;
                }
                case DEFAULT_INTERFACE: {
                    SocketBindingGroupResourceDefinition.DEFAULT_INTERFACE.parseAndSetParameter(value, op, reader);
                    required.remove(attribute);
                    break;
                }
                case PORT_OFFSET: {
                    SocketBindingGroupResourceDefinition.PORT_OFFSET.parseAndSetParameter(value, op, reader);
                    break;
                }
                default:
                    throw ParseUtils.unexpectedAttribute(reader, i);
            }
        }

        if (!required.isEmpty()) {
            throw missingRequired(reader, required);
        }


        ModelNode groupAddress = address.clone().add(SOCKET_BINDING_GROUP, socketBindingGroupName);
        op.get(OP_ADDR).set(groupAddress);

        updates.add(op);

        // Handle elements
        while (reader.nextTag() != END_ELEMENT) {
            requireNamespace(reader, namespaceUri);
            final Element element = Element.forName(reader.getLocalName());
            switch (element) {
                case SOCKET_BINDING: {
                    // FIXME JBAS-8825
                    final String bindingName = parseSocketBinding(reader, interfaces, groupAddress, updates);
                    if (!uniqueBindingNames.add(bindingName)) {
                        throw ControllerLogger.ROOT_LOGGER.alreadyDeclared(Element.SOCKET_BINDING.getLocalName(), Element.OUTBOUND_SOCKET_BINDING.getLocalName(), bindingName, Element.SOCKET_BINDING_GROUP.getLocalName(), socketBindingGroupName, reader.getLocation());
                    }
                    break;
                }
                case OUTBOUND_SOCKET_BINDING: {
                    final String bindingName = parseOutboundSocketBinding(reader, interfaces, groupAddress, updates);
                    if (!uniqueBindingNames.add(bindingName)) {
                        throw ControllerLogger.ROOT_LOGGER.alreadyDeclared(Element.SOCKET_BINDING.getLocalName(), Element.OUTBOUND_SOCKET_BINDING.getLocalName(), bindingName, Element.SOCKET_BINDING_GROUP.getLocalName(), socketBindingGroupName, reader.getLocation());
                    }
                    break;
                }
                default:
                    throw unexpectedElement(reader);
            }
        }
    }

    private void parseServerProfile(final XMLExtendedStreamReader reader, final ModelNode address, final List<ModelNode> list)
            throws XMLStreamException {
        // Attributes
        requireNoAttributes(reader);

        // Content
        final Set<String> configuredSubsystemTypes = new HashSet<String>();
        while (reader.hasNext() && reader.nextTag() != END_ELEMENT) {
            if (Element.forName(reader.getLocalName()) != Element.SUBSYSTEM) {
                throw unexpectedElement(reader);
            }
            if (!configuredSubsystemTypes.add(reader.getNamespaceURI())) {
                throw AppClientLogger.ROOT_LOGGER.duplicateSubsystemDeclaration(reader.getLocation());
            }
            // parse subsystem
            final List<ModelNode> subsystems = new ArrayList<ModelNode>();
            reader.handleAny(subsystems);

            // Process subsystems
            for (final ModelNode update : subsystems) {
                // Process relative subsystem path address
                final ModelNode subsystemAddress = address.clone();
                for (final Property path : update.get(OP_ADDR).asPropertyList()) {
                    subsystemAddress.add(path.getName(), path.getValue().asString());
                }
                update.get(OP_ADDR).set(subsystemAddress);
                list.add(update);
            }
        }
    }

    private void setServerName(final ModelNode address, final List<ModelNode> operationList, final String value) {
        if (value != null && value.length() > 0) {
            final ModelNode update = Util.getWriteAttributeOperation(address, NAME, value);
            operationList.add(update);
        }
    }

    static class AppClientSocketBindingsXml extends SocketBindingsXml {
        @Override
        protected void writeExtraAttributes(XMLExtendedStreamWriter writer, ModelNode bindingGroup) throws XMLStreamException {
            SocketBindingGroupResourceDefinition.PORT_OFFSET.marshallAsAttribute(bindingGroup, writer);
        }
    }

}
