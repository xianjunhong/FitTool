package com.fittool.service;

import com.fittool.bo.ComputeSamplesResultBO;
import com.fittool.bo.DistanceSeriesBO;
import com.fittool.bo.CoordinateBO;
import com.fittool.dto.PreviewDTO;
import com.fittool.vo.PreviewVO;
import com.fittool.bo.SampleBO;
import com.fittool.utils.FitUtils;
import com.garmin.fit.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;


@Service
public class FitService {

    private final ElevationService elevationService;

    public FitService(ElevationService elevationService) {
        this.elevationService = elevationService;
    }

    public PreviewVO preview(PreviewDTO request) {

//        得到所有点
        List<CoordinateBO> allCoordinateBOS = FitUtils.buildAllPoints(request.getCoordinateBOS(), request.getLapCount());
//        预览不需要展示高度信息了，节省一次调用
        List<Double> altitudes = null;
//        得到距离数组和总距离
        DistanceSeriesBO distanceSeriesBO = FitUtils.buildDistanceSeries(allCoordinateBOS);
//      卡路里
        Integer calories = FitUtils.estimateCalories(request.getWeightKg(), distanceSeriesBO.getTotalDist());

        ComputeSamplesResultBO computeSamplesResultBO = FitUtils.computeSamples(
                allCoordinateBOS,
                distanceSeriesBO.getDistances(),
                distanceSeriesBO.getTotalDist(),
                request.getPaceSecondsPerKm(),
                request.getHrRest(),
                request.getHrMax(),
                request.getWeightKg(),
                altitudes
        );

        PreviewVO previewVo = new PreviewVO();

        previewVo.setSamples(computeSamplesResultBO.getSamples());
        previewVo.setTotalDurationSec(computeSamplesResultBO.getTotalDurationSec());
        previewVo.setTotalDistanceMeters(distanceSeriesBO.getTotalDist());
        previewVo.setCalories(calories);

        return previewVo;
    }

    public byte[] generate(PreviewDTO request) throws IOException {

        List<CoordinateBO> allCoordinateBOS = FitUtils.buildAllPoints(request.getCoordinateBOS(), request.getLapCount());
        List<Double> altitudes = elevationService.fetchAltitudesOrNull(allCoordinateBOS);
        DistanceSeriesBO distanceSeriesBO = FitUtils.buildDistanceSeries(allCoordinateBOS);
        Integer calories = FitUtils.estimateCalories(request.getWeightKg(), distanceSeriesBO.getTotalDist());

        ComputeSamplesResultBO computeSamplesResultBO = FitUtils.computeSamples(
                allCoordinateBOS,
                distanceSeriesBO.getDistances(),
                distanceSeriesBO.getTotalDist(),
                request.getPaceSecondsPerKm(),
                request.getHrRest(),
                request.getHrMax(),
                request.getWeightKg(),
                altitudes
        );

        Path tempPath = Files.createTempFile("fittool-run-", ".fit");
        FileEncoder encoder = null;

        try {
            encoder = new FileEncoder(tempPath.toFile(), Fit.ProtocolVersion.V2_0);

            Date startDate = Date.from(request.getStartDate());
            DateTime fitStart = new DateTime(startDate);

            writeFileHeader(encoder, fitStart);
            writeRecordMessages(encoder, computeSamplesResultBO.getSamples(), startDate);

            DateTime sessionEnd = buildSessionEnd(startDate, computeSamplesResultBO.getTotalDurationSec());
            writeSessionMessage(encoder, fitStart, sessionEnd, distanceSeriesBO, computeSamplesResultBO, calories);
            writeActivityMessage(encoder, sessionEnd, computeSamplesResultBO);

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

    private void writeFileHeader(FileEncoder encoder, DateTime fitStart) {
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
    }

    private void writeRecordMessages(FileEncoder encoder, List<SampleBO> samples, Date startDate) {
        for (SampleBO s : samples) {
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
    }

    private DateTime buildSessionEnd(Date startDate, Double totalDurationSec) {
        return new DateTime(new Date((long) (startDate.getTime() + totalDurationSec * 1000)));
    }

    private void writeSessionMessage(
            FileEncoder encoder,
            DateTime fitStart,
            DateTime sessionEnd,
            DistanceSeriesBO distanceSeriesBO,
            ComputeSamplesResultBO computeSamplesResultBO,
            Integer calories
    ) {
        Double avgSpeed = distanceSeriesBO.getTotalDist() / computeSamplesResultBO.getTotalDurationSec();

        SessionMesg sessionMesg = new SessionMesg();
        sessionMesg.setTimestamp(sessionEnd);
        sessionMesg.setStartTime(fitStart);
        sessionMesg.setTotalElapsedTime(computeSamplesResultBO.getTotalDurationSec().floatValue());
        sessionMesg.setTotalDistance(distanceSeriesBO.getTotalDist().floatValue());
        sessionMesg.setTotalCalories(calories);
        sessionMesg.setSport(Sport.RUNNING);
        sessionMesg.setSubSport(SubSport.GENERIC);
        sessionMesg.setAvgSpeed(avgSpeed.floatValue());
        sessionMesg.setAvgRunningCadence((short) (computeSamplesResultBO.getAvgRunningCadence().shortValue() / 2));
        sessionMesg.setAvgPower(computeSamplesResultBO.getAvgPower());
        sessionMesg.setAvgStepLength(computeSamplesResultBO.getAvgStrideLength().floatValue() * 1000);
        sessionMesg.setTotalAscent(computeSamplesResultBO.getTotalAscent().intValue());
        sessionMesg.setTotalDescent(computeSamplesResultBO.getTotalDescent().intValue());
        encoder.onMesg(sessionMesg);
    }

    private void writeActivityMessage(
            FileEncoder encoder,
            DateTime sessionEnd,
            ComputeSamplesResultBO computeSamplesResultBO
    ) {
        ActivityMesg activityMesg = new ActivityMesg();
        activityMesg.setTimestamp(sessionEnd);
        activityMesg.setTotalTimerTime(computeSamplesResultBO.getTotalDurationSec().floatValue());
        activityMesg.setNumSessions(1);
        activityMesg.setType(Activity.MANUAL);
        encoder.onMesg(activityMesg);
    }
}
