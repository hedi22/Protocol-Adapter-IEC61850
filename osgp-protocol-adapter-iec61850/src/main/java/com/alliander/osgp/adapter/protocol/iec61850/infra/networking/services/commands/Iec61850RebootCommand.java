/**
 * Copyright 2014-2016 Smart Society Services B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.alliander.osgp.adapter.protocol.iec61850.infra.networking.services.commands;

import org.openmuc.openiec61850.BdaBoolean;
import org.openmuc.openiec61850.Fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alliander.osgp.adapter.protocol.iec61850.exceptions.ProtocolAdapterException;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.Iec61850Client;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.DataAttribute;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.DeviceConnection;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.Function;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.LogicalDevice;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.LogicalNode;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.NodeContainer;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.SubDataAttribute;

public class Iec61850RebootCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(Iec61850RebootCommand.class);

    public void rebootDevice(final Iec61850Client iec61850Client, final DeviceConnection deviceConnection)
            throws ProtocolAdapterException {
        final Function<Void> function = new Function<Void>() {

            @Override
            public Void apply() throws Exception {
                final NodeContainer rebootOperationNode = deviceConnection.getFcModelNode(LogicalDevice.LIGHTING,
                        LogicalNode.STREET_LIGHT_CONFIGURATION, DataAttribute.REBOOT_OPERATION, Fc.CO);
                iec61850Client.readNodeDataValues(deviceConnection.getConnection().getClientAssociation(),
                        rebootOperationNode.getFcmodelNode());
                LOGGER.info("device: {}, rebootOperationNode: {}", deviceConnection.getDeviceIdentification(),
                        rebootOperationNode);

                final NodeContainer oper = rebootOperationNode.getChild(SubDataAttribute.OPERATION);
                iec61850Client.readNodeDataValues(deviceConnection.getConnection().getClientAssociation(),
                        oper.getFcmodelNode());
                LOGGER.info("device: {}, oper: {}", deviceConnection.getDeviceIdentification(), oper);

                final BdaBoolean ctlVal = oper.getBoolean(SubDataAttribute.CONTROL_VALUE);
                LOGGER.info("device: {}, ctlVal: {}", deviceConnection.getDeviceIdentification(), ctlVal);

                ctlVal.setValue(true);
                LOGGER.info("device: {}, set ctlVal to true in order to reboot the device",
                        deviceConnection.getDeviceIdentification());
                oper.write();
                return null;
            }
        };

        iec61850Client.sendCommandWithRetry(function);
    }
}