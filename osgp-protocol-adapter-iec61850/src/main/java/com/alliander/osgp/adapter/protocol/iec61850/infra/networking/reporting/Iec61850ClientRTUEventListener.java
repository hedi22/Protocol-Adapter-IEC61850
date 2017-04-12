/**
 * Copyright 2016 Smart Society Services B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.alliander.osgp.adapter.protocol.iec61850.infra.networking.reporting;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.openmuc.openiec61850.BdaReasonForInclusion;
import org.openmuc.openiec61850.DataSet;
import org.openmuc.openiec61850.FcModelNode;
import org.openmuc.openiec61850.HexConverter;
import org.openmuc.openiec61850.Report;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alliander.osgp.adapter.protocol.iec61850.application.services.DeviceManagementService;
import com.alliander.osgp.adapter.protocol.iec61850.exceptions.ProtocolAdapterException;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.helper.ReadOnlyNodeContainer;
import com.alliander.osgp.adapter.protocol.iec61850.infra.networking.services.Iec61850BdaOptFldsHelper;
import com.alliander.osgp.dto.valueobjects.microgrids.GetDataResponseDto;
import com.alliander.osgp.dto.valueobjects.microgrids.GetDataSystemIdentifierDto;
import com.alliander.osgp.dto.valueobjects.microgrids.MeasurementDto;
import com.alliander.osgp.dto.valueobjects.microgrids.ReportDto;

public class Iec61850ClientRTUEventListener extends Iec61850ClientBaseEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(Iec61850ClientRTUEventListener.class);

    /**
     * The EntryTime from IEC61850 has timestamp values relative to 01-01-1984.
     * TimeStamp values and Java date time values have milliseconds since
     * 01-01-1970. The milliseconds between these representations are in the
     * following offset.
     */
    private static final long IEC61850_ENTRY_TIME_OFFSET = 441763200000L;

    private static final String NODE_NAMES = "(RTU|PV|BATTERY|ENGINE|LOAD|CHP|HEAT_BUFFER|GAS_FURNACE|HEAT_PUMP|BOILER)";
    private static final Pattern REPORT_PATTERN = Pattern
            .compile("\\A(.*)" + NODE_NAMES + "([1-9]\\d*+)/LLN0\\$(Status|Measurements)\\Z");

    private static final Map<String, Class<? extends Iec61850ReportHandler>> REPORT_HANDLERS_MAP = new HashMap<>();

    static {
        REPORT_HANDLERS_MAP.put("RTU", Iec61850RtuReportHandler.class);
        REPORT_HANDLERS_MAP.put("PV", Iec61850PvReportHandler.class);
        REPORT_HANDLERS_MAP.put("BATTERY", Iec61850BatteryReportHandler.class);
        REPORT_HANDLERS_MAP.put("ENGINE", Iec61850EngineReportHandler.class);
        REPORT_HANDLERS_MAP.put("LOAD", Iec61850LoadReportHandler.class);
        REPORT_HANDLERS_MAP.put("CHP", Iec61850ChpReportHandler.class);
        REPORT_HANDLERS_MAP.put("HEAT_BUFFER", Iec61850HeatBufferReportHandler.class);
        REPORT_HANDLERS_MAP.put("GAS_FURNACE", Iec61850GasFurnaceReportHandler.class);
        REPORT_HANDLERS_MAP.put("HEAT_PUMP", Iec61850HeatPumpReportHandler.class);
        REPORT_HANDLERS_MAP.put("BOILER", Iec61850BoilerReportHandler.class);
    }

    public Iec61850ClientRTUEventListener(final String deviceIdentification,
            final DeviceManagementService deviceManagementService) throws ProtocolAdapterException {
        super(deviceIdentification, deviceManagementService, Iec61850ClientRTUEventListener.class);
    }

    private Iec61850ReportHandler getReportHandler(final String dataSetRef) {

        final Matcher reportMatcher = REPORT_PATTERN.matcher(dataSetRef);
        if (reportMatcher.matches()) {
            final String node = reportMatcher.group(2);
            final int systemId = Integer.parseInt(reportMatcher.group(3));
            final Class<?> clazz = REPORT_HANDLERS_MAP.get(node);
            try {
                final Constructor<?> ctor = clazz.getConstructor(new Class<?>[] { int.class });
                return (Iec61850ReportHandler) ctor.newInstance(systemId);
            } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException | NoSuchMethodException ex) {
                LOGGER.error("Unable to instantiate Iec61850ReportHandler ", ex);
            }
        }
        return null;
    }

    @Override
    public void newReport(final Report report) {
        final DateTime timeOfEntry = report.getTimeOfEntry() == null ? null
                : new DateTime(report.getTimeOfEntry().getTimestampValue() + IEC61850_ENTRY_TIME_OFFSET);

        final String reportDescription = this.getReportDescription(report, timeOfEntry);

        this.logger.info("newReport for {}", reportDescription);

        if (report.isBufOvfl()) {
            this.logger.warn("Buffer Overflow reported for {} - entries within the buffer may have been lost.",
                    reportDescription);
        } else if (this.skipRecordBecauseOfOldSqNum(report)) {
            this.logger.warn("Skipping report because SqNum: {} is less than what should be the first new value: {}",
                    report.getSqNum(), this.firstNewSqNum);
            return;
        }

        final Iec61850ReportHandler reportHandler = this.getReportHandler(report.getDataSetRef());
        if (reportHandler == null) {
            this.logger.warn("Skipping report because dataset is not supported {}", report.getDataSetRef());
            return;
        }

        this.logReportDetails(report);
        try {
            this.processDataSet(report, reportDescription, reportHandler);
        } catch (final ProtocolAdapterException e) {
            this.logger.warn("Unable to process report, discarding report", e);
        }
    }

    private String getReportDescription(final Report report, final DateTime timeOfEntry) {
        return String.format("device: %s, reportId: %s, timeOfEntry: %s, sqNum: %s%s%s", this.deviceIdentification,
                report.getRptId(), timeOfEntry == null ? "-" : timeOfEntry, report.getSqNum(),
                        report.getSubSqNum() == null ? "" : " subSqNum: " + report.getSubSqNum(),
                                report.isMoreSegmentsFollow() ? " (more segments follow for this sqNum)" : "");
    }

    private boolean skipRecordBecauseOfOldSqNum(final Report report) {
        return (this.firstNewSqNum != null) && (report.getSqNum() != null) && (report.getSqNum() < this.firstNewSqNum);
    }

    private void processDataSet(final Report processReport, final String reportDescription,
            final Iec61850ReportHandler reportHandler) throws ProtocolAdapterException {
        if (processReport.getDataSet() == null) {
            this.logger.warn("No DataSet available for {}", reportDescription);
            return;
        }

        final List<FcModelNode> members = processReport.getDataSet().getMembers();
        if ((members == null) || members.isEmpty()) {
            this.logger.warn("No members in DataSet available for {}", reportDescription);
            return;
        }

        final List<MeasurementDto> measurements = new ArrayList<>();
        for (final FcModelNode member : members) {
            if (member == null) {
                this.logger.warn("Member == null in DataSet for {}", reportDescription);
                continue;
            }

            this.logger.info("Handle member {} for {}", member.getReference(), reportDescription);
            try {
                final MeasurementDto dto = reportHandler
                        .handleMember(new ReadOnlyNodeContainer(this.deviceIdentification, member));
                if (dto != null) {
                    measurements.add(dto);
                } else {
                    this.logger.warn("Unsupprted member {}, skipping", member.getName());
                }
            } catch (final Exception e) {
                this.logger.error("Error adding event notification for member {} from {}", member.getReference(),
                        reportDescription, e);
            }
        }

        final GetDataSystemIdentifierDto systemResult = reportHandler.createResult(measurements);
        final List<GetDataSystemIdentifierDto> systems = new ArrayList<>();
        systems.add(systemResult);

        final ReportDto reportDto = new ReportDto(processReport.getSqNum(),
                new DateTime(processReport.getTimeOfEntry().getTimestampValue() + IEC61850_ENTRY_TIME_OFFSET),
                processReport.getRptId());

        this.deviceManagementService.sendMeasurements(this.deviceIdentification, new GetDataResponseDto(systems, reportDto));
    }

    private void logReportDetails(final Report report) {
        final StringBuilder sb = new StringBuilder("Report details for device ").append(this.deviceIdentification)
                .append(System.lineSeparator());
        sb.append("\t             RptId:\t").append(report.getRptId()).append(System.lineSeparator());
        sb.append("\t        DataSetRef:\t").append(report.getDataSetRef()).append(System.lineSeparator());
        sb.append("\t           ConfRev:\t").append(report.getConfRev()).append(System.lineSeparator());
        sb.append("\t           BufOvfl:\t").append(report.isBufOvfl()).append(System.lineSeparator());
        sb.append("\t           EntryId:\t").append(report.getEntryId()).append(System.lineSeparator());
        sb.append("\tInclusionBitString:\t").append(Arrays.toString(report.getInclusionBitString()))
        .append(System.lineSeparator());
        sb.append("\tMoreSegmentsFollow:\t").append(report.isMoreSegmentsFollow()).append(System.lineSeparator());
        sb.append("\t             SqNum:\t").append(report.getSqNum()).append(System.lineSeparator());
        sb.append("\t          SubSqNum:\t").append(report.getSubSqNum()).append(System.lineSeparator());
        sb.append("\t       TimeOfEntry:\t").append(report.getTimeOfEntry()).append(System.lineSeparator());
        if (report.getTimeOfEntry() != null) {
            sb.append("\t                   \t(")
            .append(new DateTime(report.getTimeOfEntry().getTimestampValue() + IEC61850_ENTRY_TIME_OFFSET))
            .append(')').append(System.lineSeparator());
        }
        final List<BdaReasonForInclusion> reasonCodes = report.getReasonCodes();
        if ((reasonCodes != null) && !reasonCodes.isEmpty()) {
            sb.append("\t       ReasonCodes:").append(System.lineSeparator());
            for (final BdaReasonForInclusion reasonCode : reasonCodes) {
                sb.append("\t                   \t")
                .append(reasonCode.getReference() == null ? HexConverter.toHexString(reasonCode.getValue())
                        : reasonCode)
                .append("\t(").append(new Iec61850BdaReasonForInclusionHelper(reasonCode).getInfo()).append(')')
                .append(System.lineSeparator());
            }
        }
        sb.append("\t           optFlds:").append(report.getOptFlds()).append("\t(")
        .append(new Iec61850BdaOptFldsHelper(report.getOptFlds()).getInfo()).append(')')
        .append(System.lineSeparator());
        final DataSet dataSet = report.getDataSet();
        if (dataSet == null) {
            sb.append("\t           DataSet:\tnull").append(System.lineSeparator());
        } else {
            sb.append("\t           DataSet:\t").append(dataSet.getReferenceStr()).append(System.lineSeparator());
            final List<FcModelNode> members = dataSet.getMembers();
            if ((members != null) && !members.isEmpty()) {
                sb.append("\t   DataSet members:\t").append(members.size()).append(System.lineSeparator());
                for (final FcModelNode member : members) {
                    sb.append("\t            member:\t").append(member).append(System.lineSeparator());
                    sb.append("\t                   \t\t").append(member);
                }
            }
        }
        this.logger.info(sb.append(System.lineSeparator()).toString());
    }

    @Override
    public void associationClosed(final IOException e) {
        this.logger.info("associationClosed for device: {}, {}", this.deviceIdentification,
                e == null ? "no IOException" : "IOException: " + e.getMessage());
    }

}
