/**
 * Copyright 2017 Smart Society Services B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.alliander.osgp.adapter.protocol.iec61850.infra.messaging.processors;

import com.alliander.osgp.adapter.protocol.iec61850.device.da.rtu.DaDeviceRequest;
import com.alliander.osgp.adapter.protocol.iec61850.infra.messaging.DaRtuDeviceRequestMessageProcessor;
import com.alliander.osgp.adapter.protocol.iec61850.infra.messaging.DeviceRequestMessageType;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.Iec61850Client;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.DeviceConnection;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.Function;
import org.openmuc.openiec61850.BdaFloat32;
import org.openmuc.openiec61850.BdaQuality;
import org.openmuc.openiec61850.BdaTimestamp;
import org.openmuc.openiec61850.ConstructedDataAttribute;
import org.openmuc.openiec61850.Fc;
import org.openmuc.openiec61850.FcModelNode;
import org.openmuc.openiec61850.LogicalDevice;
import org.openmuc.openiec61850.LogicalNode;
import org.openmuc.openiec61850.ModelNode;
import org.openmuc.openiec61850.ServerModel;
import org.osgpfoundation.osgp.dto.da.iec61850.DataSampleDto;
import org.osgpfoundation.osgp.dto.da.iec61850.LogicalDeviceDto;
import org.osgpfoundation.osgp.dto.da.iec61850.LogicalNodeDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.osgpfoundation.osgp.dto.da.GetPQValuesResponseDto;

/**
 * Class for processing distribution automation get pq values request messages
 */
@Component("iec61850DistributionAutomationGetPQValuesRequestMessageProcessor")
public class DistributionAutomationGetPQValuesRequestMessageProcessor extends DaRtuDeviceRequestMessageProcessor {
    public DistributionAutomationGetPQValuesRequestMessageProcessor() {
        super(DeviceRequestMessageType.GET_POWER_QUALITY_VALUES);
    }

    @Override
    public Function<GetPQValuesResponseDto> getDataFunction(final Iec61850Client client, final DeviceConnection connection, final DaDeviceRequest deviceRequest) {
        return () -> {
            ServerModel serverModel = connection.getConnection().getServerModel();
            return new GetPQValuesResponseDto(processPQValuesLogicalDevice(serverModel));
        };
    }

    private synchronized List<LogicalDeviceDto> processPQValuesLogicalDevice(ServerModel model) {
        List<LogicalDeviceDto> logicalDevices = new ArrayList<>();
        for (ModelNode node : model.getChildren()) {
            if (node instanceof LogicalDevice) {
                List<LogicalNodeDto> logicalNodes = processPQValuesLogicalNodes((LogicalDevice) node);
                if (!logicalNodes.isEmpty()) {
                    logicalDevices.add(new LogicalDeviceDto(node.getName(), logicalNodes));
                }
            }
        }
        return logicalDevices;
    }

    private List<LogicalNodeDto> processPQValuesLogicalNodes(LogicalDevice node) {
        List<LogicalNodeDto> logicalNodes = new ArrayList<>();
        for (ModelNode subNode : node.getChildren()) {
            if (subNode instanceof LogicalNode) {
                List<DataSampleDto> data = processPQValueNodeChildren((LogicalNode) subNode);
                if (!data.isEmpty()) {
                    logicalNodes.add(new LogicalNodeDto(subNode.getName(), data));
                }
            }
        }
        return logicalNodes;
    }

    private List<DataSampleDto> processPQValueNodeChildren(LogicalNode node) {
        List<DataSampleDto> data = new ArrayList<>();
        Collection<ModelNode> children = node.getChildren();
        Map<String, Set<Fc>> childMap = new HashMap<>();
        for (ModelNode child : children) {
            if (!childMap.containsKey(child.getName())) {
                childMap.put(child.getName(), new HashSet<Fc>());
            }
            childMap.get(child.getName()).add(((FcModelNode) child).getFc());
        }
        for (Map.Entry<String, Set<Fc>> childEntry : childMap.entrySet()) {
            List<DataSampleDto> childData = processPQValuesFunctionalConstraintObject( node, childEntry.getKey(), childEntry.getValue());
            if (!childData.isEmpty()) {
                data.addAll(childData);
            }
        }
        return data;
    }

    private List<DataSampleDto> processPQValuesFunctionalConstraintObject(LogicalNode parentNode, String childName,
                                                           Set<Fc> childFcs) {
        List<DataSampleDto> data = new ArrayList<>();
        for (Fc constraint : childFcs) {
            List<DataSampleDto> childData = processPQValuesFunctionalChildConstraintObject(parentNode, childName, constraint);
            if (!childData.isEmpty()) {
                data.addAll(childData);
            }
        }
        return data;
    }

    private List<DataSampleDto> processPQValuesFunctionalChildConstraintObject(LogicalNode parentNode, String childName, Fc constraint) {
        List<DataSampleDto> data = new ArrayList<>();
        ModelNode node = parentNode.getChild(childName, constraint);
        if (Fc.MX == constraint && node.getChildren()!=null) {
            if (nodeHasBdaQualityChild(node)) {
                data.add(processPQValue(node));
            } else {
                for (ModelNode subNode : node.getChildren()) {
                    data.add(processPQValue(node, subNode));
                }
            }
        }
        return data;
    }

    private boolean nodeHasBdaQualityChild(ModelNode node) {
        for (ModelNode subNode : node.getChildren()) {
            if (subNode instanceof BdaQuality) {
                return true;
            }
        }
        return false;
    }

    private DataSampleDto processPQValue(ModelNode node) {
        Date ts = null;
        String type = null;
        BigDecimal value = null;
        if (node.getChildren() != null) {
            ts = findBdaTimestampNodeValue(node);
            ModelNode floatNode = findBdaFloat32NodeInConstructedDataAttribute(node);
            if (floatNode != null) {
                type = node.getName() + "." + floatNode.getParent().getName() + "." + floatNode.getName();
                value = new BigDecimal(((BdaFloat32) floatNode).getFloat(),
                        new MathContext(3, RoundingMode.HALF_EVEN));
            }
        }
        return new DataSampleDto(type, ts, value);
    }

    private Date findBdaTimestampNodeValue(ModelNode node) {
        Date timestamp = null;
        for (ModelNode subNode : node.getChildren()) {
            if (subNode instanceof BdaTimestamp) {
                timestamp = ((BdaTimestamp) subNode).getDate();
            }
        }
        return timestamp;
    }

    private ModelNode findBdaFloat32NodeInConstructedDataAttribute(ModelNode node) {
        ModelNode floatNode = null;
        for (ModelNode subNode : node.getChildren()) {
            if (subNode instanceof ConstructedDataAttribute && subNode.getChildren()!=null) {
                floatNode = findBdaFloat32Node(subNode);
            }
        }
        return floatNode;
    }

    private ModelNode findBdaFloat32Node(ModelNode node) {
        ModelNode floatNode = null;
        for (ModelNode subNode : node.getChildren()) {
            if (subNode instanceof BdaFloat32) {
                floatNode = subNode;
            }
        }
        return floatNode;
    }

    private DataSampleDto processPQValue(ModelNode parentNode, ModelNode node) {
        Date ts = null;
        String type = null;
        BigDecimal value = null;
        if (node.getChildren() != null) {
            ts = findBdaTimestampNodeValue(node);
            ModelNode floatNode = findDeeperBdaFloat32NodeInConstructedDataAttributeChildren(node);
            if (floatNode != null) {
                type = parentNode.getName() + "." + node.getName() + "." + floatNode.getParent().getParent().getName() + "." + floatNode.getParent().getName() + "." + floatNode.getName();
                value = getNodeBigDecimal(floatNode);
            }
        }
        return new DataSampleDto(type, ts, value);
    }

    private ModelNode findDeeperBdaFloat32NodeInConstructedDataAttributeChildren(ModelNode node) {
        ModelNode floatNode = null;
        for (ModelNode subNode : node.getChildren()) {
            if (subNode instanceof ConstructedDataAttribute && subNode.getChildren()!=null) {
                floatNode = findBdaFloat32NodeInConstructedDataAttribute(subNode);
            }
        }
        return floatNode;
    }

    private BigDecimal getNodeBigDecimal(ModelNode node) {
        return new BigDecimal(((BdaFloat32) node).getFloat(), new MathContext(3, RoundingMode.HALF_EVEN));
    }
}