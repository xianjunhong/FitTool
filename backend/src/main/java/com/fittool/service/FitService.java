package com.fittool.service;

import com.fittool.domain.ComputeSamplesResult;
import com.fittool.domain.DistanceSeries;
import com.fittool.domain.Point;
import com.fittool.dto.request.PreviewRequestDto;
import com.fittool.dto.response.PreviewResponseDto;
import com.fittool.dto.response.SampleDto;
import com.garmin.fit.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;


@Service
public class FitService {

        public PreviewResponseDto preview(PreviewRequestDto request) {

//        得到所有点
        List<Point> allPoints = FitUtils.buildAllPoints(request.getPoints(), request.getLapCount());
//        得到距离数组和总距离
        DistanceSeries distanceSeries = FitUtils.buildDistanceSeries(allPoints);
//      卡路里
        Integer calories = FitUtils.estimateCalories(request.getWeightKg(), distanceSeries.getTotalDist());

        ComputeSamplesResult computeSamplesResult = FitUtils.computeSamples(
                allPoints,
                distanceSeries.getDistances(),
                distanceSeries.getTotalDist(),
                request.getPaceSecondsPerKm(),
                request.getHrRest(),
                request.getHrMax(),
                request.getWeightKg()
        );

        PreviewResponseDto previewResponseDto = new PreviewResponseDto();

        previewResponseDto.setSamples(computeSamplesResult.getSamples());
        previewResponseDto.setTotalDurationSec(computeSamplesResult.getTotalDurationSec());
        previewResponseDto.setTotalDistanceMeters(distanceSeries.getTotalDist());
        previewResponseDto.setCalories(calories);




                return previewResponseDto;
    }

        public byte[] generate(PreviewRequestDto request) throws IOException {

//        得到所有点
        List<Point> allPoints = FitUtils.buildAllPoints(request.getPoints(), request.getLapCount());
//        得到距离数组和总距离
        DistanceSeries distanceSeries = FitUtils.buildDistanceSeries(allPoints);
//      卡路里
        Integer calories = FitUtils.estimateCalories(request.getWeightKg(), distanceSeries.getTotalDist());

        ComputeSamplesResult computeSamplesResult = FitUtils.computeSamples(
                allPoints,
                distanceSeries.getDistances(),
                distanceSeries.getTotalDist(),
                request.getPaceSecondsPerKm(),
                request.getHrRest(),
                request.getHrMax(),
                request.getWeightKg()
        );

                Path tempPath = Files.createTempFile("fittool-run-", ".fit");
                FileEncoder encoder = null;

                try {
                        encoder = new FileEncoder(tempPath.toFile(), Fit.ProtocolVersion.V1_0);

                        Date startDate = Date.from(request.getStartDate());
                        DateTime fitStart = new DateTime(startDate);

                        FileIdMesg fileIdMesg = new FileIdMesg();
                        fileIdMesg.setType(File.ACTIVITY);
                        fileIdMesg.setManufacturer(Manufacturer.DEVELOPMENT);
                        fileIdMesg.setProduct(1);
                        fileIdMesg.setTimeCreated(fitStart);
                        encoder.onMesg(fileIdMesg);

                        DeviceInfoMesg deviceInfoMesg = new DeviceInfoMesg();
                        deviceInfoMesg.setTimestamp(fitStart);
                        deviceInfoMesg.setManufacturer(Manufacturer.DEVELOPMENT);
                        deviceInfoMesg.setProduct(1);
                        deviceInfoMesg.setSerialNumber(1L);
                        encoder.onMesg(deviceInfoMesg);

                        for (SampleDto s : computeSamplesResult.getSamples()) {
                                DateTime timestamp = new DateTime(new Date((long) (startDate.getTime() + s.getTimeSec() * 1000)));
                                RecordMesg recordMesg = new RecordMesg();
                                recordMesg.setTimestamp(timestamp);
                                recordMesg.setPositionLat(FitUtils.toSemicircles(s.getLat()));
                                recordMesg.setPositionLong(FitUtils.toSemicircles(s.getLng()));
                                recordMesg.setDistance(s.getDistance().floatValue());
                                recordMesg.setSpeed(s.getSpeed().floatValue());
                                recordMesg.setHeartRate(s.getHeartRate().shortValue());
                                recordMesg.setCadence((short) (s.getRunningCadence() / 2));
                                recordMesg.setStepLength(s.getStrideLength().floatValue());
                                recordMesg.setPower(s.getPower());
                                recordMesg.setAltitude(s.getAltitude().floatValue());
                                encoder.onMesg(recordMesg);
                        }

                        DateTime sessionEnd = new DateTime(new Date((long) (startDate.getTime() + computeSamplesResult.getTotalDurationSec() * 1000)));
                        Double avgSpeed = distanceSeries.getTotalDist() / computeSamplesResult.getTotalDurationSec();

                        SessionMesg sessionMesg = new SessionMesg();
                        sessionMesg.setTimestamp(sessionEnd);
                        sessionMesg.setStartTime(fitStart);
                        sessionMesg.setTotalElapsedTime(computeSamplesResult.getTotalDurationSec().floatValue());
                        sessionMesg.setTotalDistance(distanceSeries.getTotalDist().floatValue());
                        sessionMesg.setTotalCalories(calories);
                        sessionMesg.setSport(Sport.RUNNING);
                        sessionMesg.setSubSport(SubSport.GENERIC);
                        sessionMesg.setAvgSpeed(avgSpeed.floatValue());
//        sessionMesg.setAvgCadence((short) ((computeSamplesResult.getAvgRunningCadence().shortValue() ) / 2 ));
                        sessionMesg.setAvgRunningCadence((short) (computeSamplesResult.getAvgRunningCadence().shortValue() / 2));

                        sessionMesg.setAvgPower(computeSamplesResult.getAvgPower());
                        sessionMesg.setAvgStepLength((computeSamplesResult.getAvgStrideLength().floatValue()) * 1000);
                        sessionMesg.setTotalAscent(computeSamplesResult.getTotalAscent().intValue());
                        sessionMesg.setTotalDescent(computeSamplesResult.getTotalDescent().intValue());
//        sessionMesg.setTotalCycles((long) ((computeSamplesResult.getAvgRunningCadence().shortValue() ) * (computeSamplesResult.getTotalDurationSec() / 60.0))); // 必须补上总步�?
                        encoder.onMesg(sessionMesg);

                        ActivityMesg activityMesg = new ActivityMesg();
                        activityMesg.setTimestamp(sessionEnd);
                        activityMesg.setTotalTimerTime(computeSamplesResult.getTotalDurationSec().floatValue());
                        activityMesg.setNumSessions(1);
                        activityMesg.setType(Activity.MANUAL);
                        encoder.onMesg(activityMesg);

                        encoder.close();
                        encoder = null;

                        return Files.readAllBytes(tempPath);
                } finally {
                        if (encoder != null) {
                                encoder.close();
                        }
                        Files.deleteIfExists(tempPath);
                }

    }
}
