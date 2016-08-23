package com.alliander.osgp.adapter.protocol.iec61850.infra.networking.reporting;

import java.util.ArrayList;
import java.util.List;

import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.DataAttribute;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.ReadOnlyNodeContainer;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.translation.Iec61850PhotovoltaicTranslator;
import com.alliander.osgp.dto.valueobjects.microgrids.MeasurementDto;
import com.alliander.osgp.dto.valueobjects.microgrids.MeasurementResultSystemIdentifierDto;

public class Iec61850PhotovoltaicReportHandler implements Iec61850RtuReportHandler {

    @Override
    public MeasurementResultSystemIdentifierDto createResult(final List<MeasurementDto> measurements) {
        final MeasurementResultSystemIdentifierDto systemResult = new MeasurementResultSystemIdentifierDto(1, "PV",
                measurements);
        final List<MeasurementResultSystemIdentifierDto> systems = new ArrayList<>();
        systems.add(systemResult);
        return systemResult;
    }

    @Override
    public MeasurementDto handleMember(final ReadOnlyNodeContainer member) {
        if (member.getFcmodelNode().getName().equals(DataAttribute.BEHAVIOR.getDescription())) {
            return Iec61850PhotovoltaicTranslator.translateBehavior(member);
        }

        if (member.getFcmodelNode().getName().equals(DataAttribute.GENERATOR_SPEED.getDescription())) {
            return Iec61850PhotovoltaicTranslator.translateGenerationSpeed(member);
        }

        if (member.getFcmodelNode().getName().equals(DataAttribute.HEALTH.getDescription())) {
            return Iec61850PhotovoltaicTranslator.translateHealth(member);
        }

        if (member.getFcmodelNode().getName().equals(DataAttribute.OPERATIONAL_HOURS.getDescription())) {
            return Iec61850PhotovoltaicTranslator.translateOperationalHours(member);
        }

        return null;
    }
}